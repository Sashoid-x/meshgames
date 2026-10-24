package com.meshgames.game.session

import com.meshgames.game.protocol.ACK_NONE
import com.meshgames.game.protocol.AckPayload
import com.meshgames.game.protocol.Envelope
import com.meshgames.game.protocol.MsgType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Per-session delivery layer: «повторяй и не жди» (repeat-and-forget).
 *
 * Схема намеренно НЕ ждёт подтверждений. На реальном LoRa-пути ACK
 * возвращался ненадёжно: обе стороны зависали во взаимном ожидании
 * («кадры ждут подтверждения»), а эфир забивался ретрансмитами. Правила:
 *
 * - каждый кадр несёт u16 seq; приёмник применяет его ровно один раз
 *   (rxNext + стэш out-of-order) — повтор благодаря дедупу безопасен;
 * - отправитель шлёт кадр по фиксированному расписанию повторов:
 *   обычные игровые кадры — [RETRY_GAPS_MS] (отправки 0/6/16/32/62/122 с),
 *   handshake/критичные — [PATIENT_RETRY_GAPS_MS] (0/8/22/47/92/152 с),
 *   см. [gapsFor]; после базового расписания кадр НЕ забывается, а
 *   переходит в вечный режим ([ETERNAL_GAP_MS]): пока пир не подтвердил
 *   его своим piggyback-cumAck, он повторяется редко (1 пакет/2 мин).
 *   Полевой инцидент 18.10 (sid=17753): «сгоревшие» до возвращения пира
 *   в эфир SESSION_CLOSE/SYNC_REQ оставили ему вечную дырку в rxNext —
 *   весь дальнейший поток (SHOT_RESULT в том числе) оседал в стэше,
 *   партия висла с откатами каждые 5 мин. Цена вечного режима — по
 *   одному пакету на неподтверждённый кадр (обычно их 0–3), альтернатива
 *   — вечная дырка и мёртвая партия;
 * - отдельных standalone ACK в протоколе нет вовсе (MsgType.ACK удалён):
 *   получатель ничего не «должен». Подтверждение — только piggyback
 *   в заголовке ([AckPayload]) — бесплатная оптимизация: если пир и так
 *   отвечает (ход, INVITE_RESP, SHOT_RESULT), его cumAck гасит повторы
 *   раньше расписания; если не отвечает — расписание работает само и
 *   никто ничего не ждёт;
 * - повторы несут свежий piggyback (подтверждаем принятое за это время);
 * - контрольные кадры SYNC_REQ/SYNC_RES принимаются мимо порядка
 *   ([receiveOutOfBand]): «дырка» в rxNext не должна блокировать
 *   исцеление (полевой deadlock sid=40290) — кадр отдаётся диспетчеру
 *   сразу, seq помечается как отданный, а rxNext при закрытии дырки
 *   «проглатывает» его без повторной доставки;
 * - если тики пропускались (сон приложения), [tick] догоняет все
 *   просроченные повторы расписания за один вызов.
 *
 * Ничего в протоколе не блокируется в ожидании подтверждения: ход,
 * приглашение или удаление применяются приёмником с первого же
 * дошедшего пакета.
 *
 * All timing is passed in explicitly (nowMs) for deterministic tests.
 */
class ReliableChannel(val sessionId: Int) {

    /** Frames produced by the channel that must be sent to the peer. */
    data class Outgoing(val frame: ByteArray, val seq: Int = -1) {
        override fun equals(other: Any?): Boolean =
            other is Outgoing && frame.contentEquals(other.frame)
        override fun hashCode(): Int = frame.contentHashCode()
    }

    data class ReceiveResult(val deliveries: List<Envelope>, val toSend: List<Outgoing>)

    /**
     * Кадр в расписании повторов: первая отправка уже сделана в [send],
     * далее — повторы по [gapsFor]-расписанию ([retriesDone] — сколько
     * повторов уже отправлено, [nextDueMs] — срок следующего).
     */
    private data class Pending(
        val envelope: Envelope,
        var retriesDone: Int,
        var nextDueMs: Long,
        /** Когда кадр ушёл в эфир ПЕРВЫЙ раз (мс; для оценки латентности). */
        val sentAtMs: Long,
        /** Флаг применения отсрочки повтора при живом пире (piggyback silence). */
        var bonusApplied: Boolean = false,
    )

    /** Unacked frames keyed by seq (insertion ordered for stable iteration). */
    private val unacked = LinkedHashMap<Int, Pending>()

    /** Next seq to assign to an outgoing frame. */
    private var nextSeq = 0

    /** Next seq expected from the peer. */
    private var rxNext = 0

    /** Out-of-order frames above rxNext. */
    private val stash = HashMap<Int, Envelope>()

    /** Время последнего приёма любого пакета от пира (мс). */
    private var lastRxFromPeerMs = -1L

    /** Последний подтверждённый cumAck, для которого уже отправлен ACK_ONLY. */
    private var lastAckOnlyCumAck = -1

    /**
     * Seq'ы, отданные диспетчеру мимо порядка ([receiveOutOfBand]): кадр уже
     * применён, но rxNext до него ещё не дошёл (ниже — «дырка»). Продвижение
     * rxNext «проглатывает» такие seq'ы без повторной доставки, а их же
     * повторы отсекает дедуп — дырка не блокирует исцеление и не порождает
     * двойных доставок. Порядок вставки сохраняется (вытеснение — самый
     * старый элемент), набор ограничен [MAX_STASH].
     */
    private val deliveredEarly = LinkedHashSet<Int>()

    /**
     * Последняя измеренная латентность канала (мс): от первой отправки кадра
     * до прихода подтверждения его piggyback'ом (cumAck пира). Оценка для
     * UI-индикатора «~N с»: ничего не ждёт и ничем не управляет — схема
     * «повторяй и не жди» от неё не меняется. После [restore]/[clear] —
     * «неизвестно» (старые замеры рестарт обнуляет честно).
     */
    private val _rttMs = MutableStateFlow<Long?>(null)
    val rttMs: StateFlow<Long?> = _rttMs.asStateFlow()

    /** Кадры ещё в расписании повторов (для индикатора «идёт отправка»). */
    fun pendingCount(): Int = unacked.size

    fun currentSeq(): Int = nextSeq

    /** Sequence number the peer reports as received (for SYNC_RES). */
    fun rxNextSeq(): Int = rxNext

    /**
     * Encode and register a frame for repeat delivery.
     *
     * [forceSeq] — переиспользовать конкретный номер кадра (нужно для
     * переотправки INVITE в «зависшей» сессии, см.
     * SessionManager.retryInviteIfStuck): seq=0 согласован с обеими
     * возможными ситуациями у пира — если он уже получил исходный INVITE,
     * кадр идёт в дедуп (а дуп-INVITE-ветка повторит ответ), если не получил
     * ни одного кадра, seq=0 для него первый. Ответ несёт cumAck и гасит
     * расписание повторов сразу после доставки.
     */
    fun send(
        type: MsgType,
        sessionId: Int,
        payload: ByteArray,
        nowMs: Long,
        forceSeq: Int? = null,
    ): Outgoing {
        val seq: Int
        if (forceSeq != null) {
            seq = forceSeq and 0xFFFF
            // Указатель не откатываем: если он уже за (seq) ушёл дальше —
            // оставляем как есть, иначе двигаем ровно на использованный seq.
            if (!u16Before(seq, nextSeq)) nextSeq = (seq + 1) and 0xFFFF
        } else {
            seq = nextSeq
            nextSeq = (nextSeq + 1) and 0xFFFF
        }
        // Piggyback: подтверждаем свои приходы прямо в заголовке кадра —
        // если пир отвечает данными, его cumAck остановит наши повторы.
        val ack = ackSnapshot()
        val env = Envelope(type, sessionId, seq, payload, ack.cumAck, ack.bitmap)
        val gaps = gapsFor(type)
        val baseGap = gaps[0]
        val adaptiveFirstGap = rttMs.value?.let { rtt ->
            (rtt * 2).coerceIn(MIN_FIRST_GAP_MS, baseGap)
        } ?: baseGap
        unacked[seq] = Pending(env, retriesDone = 0, nextDueMs = nowMs + adaptiveFirstGap, sentAtMs = nowMs)
        return Outgoing(env.encode(), seq)
    }

    /** Apply a cumAck reported by the peer (e.g. from SYNC_RES). */
    fun acknowledgeUpTo(cumAck: Int, bitmap: Int, nowMs: Long) {
        applyAck(if (cumAck == ACK_NONE) null else cumAck, bitmap, nowMs)
    }

    /**
     * Снять подтверждённые кадры. [cumAck] = null — «ничего не подтверждено»
     * ([ACK_NONE] в wire): кумулятивная часть молчит, действуют только биты
     * [bitmap], отсчёт которых начинается с seq 0.
     */
    private fun applyAck(cumAck: Int?, bitmap: Int, nowMs: Long) {
        // Латентность — по самому свежему из погашенных кадров: старые могли
        // пережить потерю повтора и ждать подтверждения неопределённо долго.
        var newestSentAt = -1L
        if (cumAck != null) {
            val it = unacked.keys.iterator()
            while (it.hasNext()) {
                val s = it.next()
                if (u16BeforeOrEqual(s, cumAck)) {
                    newestSentAt = maxOf(newestSentAt, unacked.getValue(s).sentAtMs)
                    it.remove()
                }
            }
        }
        // bitmap bits are relative to cumAck+1 (при null — от seq 0)
        for (i in 0 until 16) {
            if ((bitmap shr i) and 1 == 1) {
                unacked.remove(((cumAck ?: -1) + 1 + i) and 0xFFFF)?.let { p ->
                    newestSentAt = maxOf(newestSentAt, p.sentAtMs)
                }
            }
        }
        if (newestSentAt >= 0) _rttMs.value = (nowMs - newestSentAt).coerceAtLeast(0)
    }

    /**
     * Feed a raw payload received from the mesh. Returns deliveries.
     */
    fun receive(bytes: ByteArray, nowMs: Long): ReceiveResult {
        lastRxFromPeerMs = nowMs
        val env = Envelope.decode(bytes) ?: return ReceiveResult(emptyList(), emptyList())

        // Piggyback-подтверждение из заголовка: гасит наши повторы,
        // если пир и так шлёт данные. Работает и на дубликатах.
        applyAck(env.cumAck, env.bitmap, nowMs)

        // ACK_ONLY кадр предназначен только для передачи piggyback-ACK
        if (env.type == MsgType.ACK_ONLY) {
            return ReceiveResult(emptyList(), emptyList())
        }

        val seq = env.seq
        if (seq == rxNext) return deliverInOrder(env)

        if (u16Before(rxNext, seq)) {
            // Future frame: stash (bounded). Дырку закроет повтор потерянного.
            // Seq, отданный мимо порядка, не стэшим — иначе после закрытия
            // дырки он доставился бы диспетчеру второй раз.
            if (!deliveredEarly.contains(seq) && stash.size < MAX_STASH &&
                !stash.containsKey(seq)
            ) {
                stash[seq] = env
            }
        }
        // Иначе это дубликат уже применённого кадра — игнорируем
        // (доставка ровно один раз, повторы безопасны).
        return ReceiveResult(emptyList(), emptyList())
    }

    /**
     * Приём контрольного кадра (SYNC_REQ/SYNC_RES) мимо стэша: дырка в seq
     * не должна блокировать исцеление. Полевой sid=40290: потерянные
     * SHOT/SHOT_RESULT оставили дыру в rxNext, и SYNC_RES осел в стэше
     * навсегда — взаимный deadlock pendingShot обеих сторон. Кадр отдается
     * диспетчеру сразу; его seq помечается в [deliveredEarly], чтобы повторы
     * не доставлялись дважды, а rxNext при закрытии дырки «проглотил» seq
     * без второй доставки. Piggyback-cumAck применяется как в [receive].
     */
    fun receiveOutOfBand(bytes: ByteArray, nowMs: Long): ReceiveResult {
        lastRxFromPeerMs = nowMs
        val env = Envelope.decode(bytes) ?: return ReceiveResult(emptyList(), emptyList())
        applyAck(env.cumAck, env.bitmap, nowMs)

        val seq = env.seq
        if (seq == rxNext) return deliverInOrder(env)

        if (u16Before(rxNext, seq)) {
            if (deliveredEarly.contains(seq)) {
                return ReceiveResult(emptyList(), emptyList()) // повтор уже отданным
            }
            // Страховка от двойной доставки при дренаже стэша (SYNC всегда
            // идут мимо стэша, но след за состоянием — дёшево).
            stash.remove(seq)
            if (deliveredEarly.size >= MAX_STASH) {
                deliveredEarly.remove(deliveredEarly.first()) // вытесняем самый старый
            }
            deliveredEarly.add(seq)
            return ReceiveResult(listOf(env), emptyList())
        }
        // Дубликат уже применённого по порядку кадра — игнорируем.
        return ReceiveResult(emptyList(), emptyList())
    }

    /** Отдать кадр по порядку и продвинуть rxNext сквозь отданные мимо порядка. */
    private fun deliverInOrder(env: Envelope): ReceiveResult {
        val deliveries = ArrayList<Envelope>(4)
        deliveries.add(env)
        rxNext = (rxNext + 1) and 0xFFFF
        skipDeliveredEarly()
        // drain contiguous stashed frames
        while (true) {
            val next = stash.remove(rxNext) ?: break
            deliveries.add(next)
            rxNext = (rxNext + 1) and 0xFFFF
            skipDeliveredEarly()
        }
        return ReceiveResult(deliveries, emptyList())
    }

    /** rxNext «проглатывает» seq, отданные мимо порядка, — дырки между ними нет. */
    private fun skipDeliveredEarly() {
        while (deliveredEarly.remove(rxNext)) rxNext = (rxNext + 1) and 0xFFFF
    }

    /** Текущее кумулятивное подтверждение (для piggyback в заголовке). */
    private fun ackSnapshot(): AckPayload {
        val cumAck = (rxNext - 1) and 0xFFFF
        var bitmap = 0
        for (i in 0 until 16) {
            val s = (rxNext + i) and 0xFFFF
            // Получено = либо лежит в стэше, либо уже отдано мимо порядка —
            // гасим повторы пира и для контрольных кадров тоже.
            if (stash.containsKey(s) || deliveredEarly.contains(s)) {
                bitmap = bitmap or (1 shl i)
            }
        }
        return AckPayload(cumAck, bitmap)
    }

    /**
     * Повторы по расписанию. Если тики пропускались (сон приложения),
     * догоняем все просроченные повторы за один вызов: расписание абсолютное
     * ([Pending.nextDueMs] сдвигается на следующий гэп), поэтому кадр не
     * «растягивается» из-за пропущенных тиков.
     *
     * После исчерпания базового расписания непОДТВЕРЖДЁННЫЙ кадр остаётся
     * в вечном режиме: один повтор за тик с гэпом [ETERNAL_GAP_MS] (догон
     * сна НЕ плодит взрыв повторов — nextDue сдвигается от «сейчас»).
     * Кадр живёт до подтверждения (cumAck/бит в ответе пира) либо до
     * очистки канала — см. шапку класса.
     */
    fun tick(nowMs: Long): List<Outgoing> {
        val out = ArrayList<Outgoing>(2)
        val it = unacked.values.iterator()
        while (it.hasNext()) {
            val p = it.next()
            val gaps = gapsFor(p.envelope.type)
            // Piggyback-triggered silence: если пир присылал кадр ПОСЛЕ отправки нашего кадра,
            // даём ему дополнительное время на ответ перед первым повтором.
            if (p.retriesDone == 0 && !p.bonusApplied && lastRxFromPeerMs > p.sentAtMs &&
                (nowMs - lastRxFromPeerMs) < PEER_ALIVE_THRESHOLD_MS
            ) {
                p.nextDueMs += PEER_ALIVE_BONUS_MS
                p.bonusApplied = true
            }
            while (nowMs >= p.nextDueMs) {
                // Текущий дуэ отправляем (свежий piggyback вместо застаревшего).
                val ack = ackSnapshot()
                out.add(Outgoing(p.envelope.copy(cumAck = ack.cumAck, bitmap = ack.bitmap).encode()))
                p.retriesDone++
                if (p.retriesDone >= gaps.size) {
                    if (isTerminalType(p.envelope.type)) {
                        // Терминальный кадр (GAME_OVER, SESSION_DELETE, INVITE_CANCEL):
                        // попытки базового расписания исчерпаны — удаляем из очереди,
                        // чтобы не мусорить в эфир вечными повторами.
                        it.remove()
                    } else {
                        // Базовое расписание исчерпано, подтверждения нет —
                        // вечный режим: редкий повтор, ровно один за этот тик.
                        p.nextDueMs = nowMs + ETERNAL_GAP_MS
                    }
                    break
                }
                p.nextDueMs += gaps[p.retriesDone]
            }
        }
        return out
    }

    /**
     * Все кадры в расписании — сейчас, с свежим piggyback (не трогает
     * расписание): прощальная отправка перед удалением сессии (dismiss).
     */
    fun resendAll(): List<Outgoing> {
        val ack = ackSnapshot()
        return unacked.values.map {
            Outgoing(it.envelope.copy(cumAck = ack.cumAck, bitmap = ack.bitmap).encode())
        }
    }

    /** Бросить все неподтверждённые повторы (направление больше не нужно). */
    fun clearUnacked() {
        unacked.clear()
    }

    /**
     * Забыть игровые кадры (MOVE/SHOT/SHOT_RESULT) в расписании повторов —
     * используется при откате партии: повтор отброшенного хода снова развёл
     * бы состояния. Прочие типы (READY/GAME_OVER/SESSION_*) не трогаются.
     */
    fun forgetGameFrames() {
        val it = unacked.entries.iterator()
        while (it.hasNext()) {
            when (it.next().value.envelope.type) {
                MsgType.MOVE, MsgType.SHOT, MsgType.SHOT_RESULT -> it.remove()
                else -> Unit
            }
        }
    }

    /**
     * Сформировать легковесный ненадёжный кадр подтверждения ACK_ONLY (12 байт, seq=0).
     * Отправляется однократно при получении входящего кадра, если у нас нет
     * запланированных исходящих пакетов, чтобы немедленно погасить повторы пира.
     * Возвращает null, если подтверждать нечего (ACK_NONE) или для этого cumAck
     * ACK_ONLY уже отправлялся.
     */
    fun makeAckOnly(force: Boolean = false): ByteArray? {
        val ack = ackSnapshot()
        if (ack.cumAck == ACK_NONE) return null
        if (!force && ack.cumAck == lastAckOnlyCumAck) return null
        lastAckOnlyCumAck = ack.cumAck
        return Envelope(
            type = MsgType.ACK_ONLY,
            sessionId = sessionId,
            seq = 0,
            payload = ByteArray(0),
            cumAck = ack.cumAck,
            bitmap = ack.bitmap,
        ).encode()
    }

    /** Drop all state (session closed). */
    fun clear() {
        unacked.clear()
        stash.clear()
        deliveredEarly.clear()
        _rttMs.value = null
        lastRxFromPeerMs = -1L
        lastAckOnlyCumAck = -1
    }

    /**
     * Восстановить состояние канала из персистентного снимка.
     * Первый повтор уходит через гэп после рестарта (без взрывной
     * пересылки всего сразу). Кадры с исчерпанным базовым расписанием
     * восстанавливаются в вечном режиме ([ETERNAL_GAP_MS]) — рестарт не
     * «сжигает» непОДТВЕРЖДЁННЫЕ, иначе у пира навсегда остаётся дырка
     * в rxNext (полевой инцидент 18.10: два рестарта подряд, и партия
     * умерла с откатами до самой пересоздания).
     */
    fun restore(nextSeq: Int, rxNext: Int, unackedFrames: List<PendingFrame>, nowMs: Long) {
        this.nextSeq = nextSeq and 0xFFFF
        this.rxNext = rxNext and 0xFFFF
        unacked.clear()
        stash.clear()
        deliveredEarly.clear()
        // Прошлые замеры латентности к рестарту не относятся — заново.
        _rttMs.value = null
        lastRxFromPeerMs = -1L
        lastAckOnlyCumAck = -1
        for (f in unackedFrames) {
            val type = MsgType.fromId(f.type) ?: continue
            val gaps = gapsFor(type)
            if (f.attempts >= gaps.size) {
                if (isTerminalType(type)) {
                    // Терминальные кадры с исчерпанным базовым расписанием не воскрешаем в вечном режиме
                    continue
                }
                // Базовое расписание кончилось ещё до рестарта — кадр живёт
                // вечно до подтверждения, первый вечный повтор через гэп.
                unacked[f.seq and 0xFFFF] = Pending(
                    Envelope(type, sessionId, f.seq and 0xFFFF, f.payload),
                    retriesDone = f.attempts,
                    nextDueMs = nowMs + ETERNAL_GAP_MS,
                    sentAtMs = nowMs,
                )
                continue
            }
            unacked[f.seq and 0xFFFF] = Pending(
                Envelope(type, sessionId, f.seq and 0xFFFF, f.payload),
                retriesDone = f.attempts,
                nextDueMs = nowMs + gaps[f.attempts],
                sentAtMs = nowMs,
            )
        }
    }

    /** Снимок неподтверждённых кадров для персистентности. */
    fun snapshotUnacked(): List<PendingFrame> = unacked.values.map {
        PendingFrame(it.envelope.type.id, it.envelope.seq, it.envelope.payload, it.retriesDone)
    }

    companion object {
        /**
         * Расписание повторов обычных игровых кадров (гэпы после каждой
         * отправки): отправки на 0/6/16/32/62/122 с — 6 отправок,
         * спан ~2 мин. Хвост +30/+60 с покрывает наблюдаемые в поле
         * «дырки» в приёме. Первый гэп 6с выбран с учетом реального RTT LoRa
         * (время передачи в эфире ~2-3с + ответ оппонента), что предотвращает
         * преждевременные дублирующие переотправки и взаимное полудуплексное глушение.
         */
        @JvmField
        val RETRY_GAPS_MS = longArrayOf(6_000, 10_000, 16_000, 30_000, 60_000)

        /**
         * Handshake- и критичные кадры (INVITE/ответ, отмена, READY,
         * финальный GAME_OVER, удаление сессии): дольше живут в эфире —
         * отправки 0/8/22/47/92/152 с, 6 отправок суммарно. Первый гэп 8с
         * дает пользователю время прочитать уведомление и нажать кнопку.
         */
        @JvmField
        val PATIENT_RETRY_GAPS_MS = longArrayOf(8_000, 14_000, 25_000, 45_000, 60_000)

        /**
         * Вечный гэп для непОДТВЕРЖДЁННЫХ после базового расписания.
         * По одному пакету каждые 2 мин на кадр (обычно их 0–3 — piggyback
         * cumAck гасит подтверждённые сразу). Прекращение только по
         * подтверждению пира либо очистке канала (удаление сессии,
         * dismiss, откат) — см. шапку класса.
         */
        @JvmField
        val ETERNAL_GAP_MS = 120_000L

        const val MAX_STASH = 32

        /** Минимальный адаптивный первый гэп (мс) даже при идеальном RTT. */
        const val MIN_FIRST_GAP_MS = 3_000L

        /** Порог недавней активности пира (мс) для бонуса задержки повтора. */
        const val PEER_ALIVE_THRESHOLD_MS = 3_000L

        /** Дополнительная отсрочка первого повтора, если пир недавно активен в эфире. */
        const val PEER_ALIVE_BONUS_MS = 6_000L

        /** Расписание повторов для типа кадра. */
        fun gapsFor(type: MsgType): LongArray = when (type) {
            MsgType.INVITE,
            MsgType.INVITE_RESP,
            MsgType.INVITE_CANCEL,
            MsgType.READY,
            MsgType.GAME_OVER,
            MsgType.SESSION_DELETE,
            -> PATIENT_RETRY_GAPS_MS

            else -> RETRY_GAPS_MS
        }

        /**
         * Терминальные кадры, завершающие сессию или приглашение.
         * Не должны крутиться в вечном режиме после исчерпания базового расписания.
         */
        fun isTerminalType(type: MsgType): Boolean = when (type) {
            MsgType.GAME_OVER,
            MsgType.SESSION_DELETE,
            MsgType.INVITE_CANCEL,
            -> true

            else -> false
        }

        /** a precedes b in u16 wrap-around serial arithmetic. */
        fun u16Before(a: Int, b: Int): Boolean = ((b - a) and 0xFFFF) in 1..0x7FFF

        fun u16BeforeOrEqual(a: Int, b: Int): Boolean = a == b || u16Before(a, b)
    }
}
