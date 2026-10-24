package com.meshgames.game.session

import com.meshgames.game.BoardSnapshot
import com.meshgames.game.GameResult
import com.meshgames.game.GameType
import com.meshgames.game.MoveInput
import com.meshgames.game.ResultReason
import com.meshgames.game.Side
import com.meshgames.game.engine.GameEngines
import com.meshgames.game.protocol.DrawRespPayload
import com.meshgames.game.protocol.Envelope
import com.meshgames.game.protocol.GameOverPayload
import com.meshgames.game.protocol.InvitePayload
import com.meshgames.game.protocol.InviteRespPayload
import com.meshgames.game.protocol.JournalTailPayload
import com.meshgames.game.protocol.MsgType
import com.meshgames.game.protocol.SyncResPayload
import com.meshgames.debug.DebugLog
import com.meshgames.net.bridge.BridgeCodec
import java.security.SecureRandom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** События сессий для уведомлений / UI. Все методы с пустой реализацией по умолчанию. */
interface SessionEvents {
    /** Пришло приглашение в игру. */
    fun onInviteReceived(sessionId: Int, peerNode: Int, gameType: GameType) {}

    /** Пришло приглашение в игру с указанием сложности (0=дефолт, 1=лёгкий, 2=средний, 3=сложный). */
    fun onInviteReceived(sessionId: Int, peerNode: Int, gameType: GameType, difficulty: Int) {
        onInviteReceived(sessionId, peerNode, gameType)
    }

    /** Приглашение отклонено / отменено. */
    fun onInviteDeclined(sessionId: Int) {}

    /** Партия началась. */
    fun onGameStarted(sessionId: Int) {}

    /** Партия завершена. */
    fun onGameOver(sessionId: Int, result: GameResult) {}

    /** Соперник предложил ничью. */
    fun onDrawOffered(sessionId: Int) {}

    /** Расхождение состояний (ошибка синхронизации). */
    fun onSyncConflict(sessionId: Int) {}

    /**
     * Расхождение состояний восстановлено без завершения партии: состояние
     * откатилось к записи журнала [toEntry] (0 — начало), игроки уведомлены.
     */
    fun onSessionRecovered(sessionId: Int, toEntry: Int) {}

    /** Соперник закрыл (closed=true) или возобновил (closed=false) игру с сохранением. */
    fun onGameClosed(sessionId: Int, closed: Boolean) {}

    /** Соперник удалил сохранённую сессию — предложить начать новую игру. */
    fun onSessionDeletedByPeer(sessionId: Int, gameType: GameType, peerNode: Int) {}

    /** Соперник сделал ход/выстрел (для звука). */
    fun onOpponentAction(sessionId: Int) {}
}

/**
 * Менеджер игровых сессий: приглашения, надёжная доставка (через
 * [ReliableChannel]), валидация ходов движками, возобновление после рестарта.
 *
 * Весь ввод — от AppGraph (`handleIncoming` из моста) и от UI;
 * состояние — единый источник правды для UI ([sessions]) и диска ([SessionStore]).
 *
 * Потокобезопасность: ввод приходит из разных корутин (тик и приём пакетов —
 * Default, кнопки UI — Main), поэтому все публичные методы синхронизированы
 * на мониторе экземпляра (`@Synchronized`): карты сессий/повторов и состояния
 * движков не трогаются параллельно. Вложенные вызвы внутри — reentrant,
 * события ([SessionEvents]) публикуются неблокирующими `tryEmit`.
 */
class SessionManager(
    private val store: SessionStore,
    private val transport: SessionTransport,
    private val events: SessionEvents = object : SessionEvents {},
    private val now: () -> Long = { System.currentTimeMillis() },
    /**
     * Сторона инициатора приглашения (цвет в шахматах/шашках, кто ходит
     * первым). Приложение подставляет случайную; тесты — детерминированную.
     * Приглашённый всегда занимает противоположную сторону.
     */
    private val sideDecider: () -> Side = { Side.A },
    /**
     * Персист журналов ходов (файл на сессию; удаляется вместе с сессией).
     * По умолчанию без диска — журнал и так живёт в памяти и участвует в
     * сверке/откате; стор нужен лишь для переживания рестарта.
     */
    private val journals: JournalStore = NoopJournalStore,
) {
    private class Runtime(
        var session: GameSession,
        val channel: ReliableChannel,
        var engine: com.meshgames.game.GameEngine?,
    ) {
        /** Счётчик локальных изменений движка (корреляция ответов синхронизации). */
        var localActivity: Int = 0

        /** Значение [localActivity] на момент отправки последнего SYNC_REQ. */
        var syncAtActivity: Int = 0

        /**
         * Когда завис выстрел (мс по часам менеджера; 0 — ничего не в полёте).
         * Ставится в [submitMove] на Shoot, при ленивом старте в [tick]
         * (restore-случай) и сбрасывается, когда движок возвращается в покой.
         * По таймауту [PENDING_SHOT_TIMEOUT_MS] результат считается потерянным
         * безвозвратно — см. [healStuckPendingShot].
         */
        var pendingShotSinceMs: Long = 0

        /**
         * Канонический журнал ходов: записи событий + снимки состояний —
         * сверка «последних ходов» и откат при расхождении (см. [reconcile]).
         */
        val journal = MoveJournal()

        /**
         * Индекс верхней записи, которую я досылал пиру как «впереди идущую»
         * ([resendJournalTail]). Повторное отвершение той же записи пиром =
         * настоящий раскол → проверенный откат, а не бесконечная досылка.
         */
        var resentTopEntry: Int = -1

        /**
         * Я уже ждал досылку от пира в этой «эпопее» (пир впереди). Второе
         * ожидание подряд без прогресса — досылка невозможна (например,
         * журнал рестарта устарел), честный конфликт вместо вечного ожидания.
         */
        var repairWaited: Boolean = false
    }

    private val sessions = LinkedHashMap<Int, Runtime>()
    private val random = SecureRandom()

    /** Момент последней принудительной синхронизации (кнопка ⟳, антифлуд). */
    private var lastForcedSyncAtMs = 0L

    /**
     * «Сиротские» повторы: кадры, которые должны дойти уже ПОСЛЕ удаления
     * сессии (SESSION_DELETE, финальный GAME_OVER из [dismiss]). Живут вне
     * сессии и шлются по расписанию [ReliableChannel.gapsFor] из [tick] —
     * приёмник идемпотентен, повторы для него безопасны.
     */
    private class Repeat(
        val to: Int,
        val frame: ByteArray,
        val gaps: LongArray,
        var nextDueMs: Long,
        var idx: Int = 0,
    )

    private val repeats = ArrayList<Repeat>()

    /** Cooldown отправки SESSION_DELETE для неизвестных сессий (антифлуд). */
    private val unknownSessionDeleteCooldown = HashMap<Int, Long>()
    private val UNKNOWN_SESSION_DELETE_COOLDOWN_MS = 30_000L

    private val _views = MutableStateFlow<List<SessionView>>(emptyList())
    val sessions_: StateFlow<List<SessionView>> = _views.asStateFlow()

    /** Поток «RTT неизвестен» для несуществующих сессий (общий, read-only). */
    private val unknownRtt: StateFlow<Long?> = MutableStateFlow<Long?>(null)

    init {
        restore()
    }

    // ---------------------------------------------------------------- API --

    /** Отправить приглашение. Возвращает sessionId. */
    @Synchronized
    fun invite(toNode: Int, gameType: GameType, difficulty: Int = 0): Int {
        val sessionId = newSessionId()
        // Цвета/кто первым ходит — случайно: инициатор занимает выбранную
        // сторону, приглашённый получает противоположную (байт в INVITE).
        val mySide = sideDecider()
        val engine = GameEngines.create(gameType, mySide, sessionId)
        val gs = GameSession(
            sessionId = sessionId, gameId = gameType, mySide = mySide, peerNode = toNode,
            status = SessionStatus.INVITING, updatedAtMs = now(), difficulty = difficulty,
        )
        val rt = Runtime(gs, ReliableChannel(sessionId), engine)
        sessions[sessionId] = rt
        DebugLog.d("S", "исходящее приглашение sid=$sessionId → узел $toNode (${gameType.title}, моя сторона=$mySide, diff=$difficulty)")
        sendMsg(rt, MsgType.INVITE, InvitePayload(gameType.id, mySide.ordinal, difficulty).encode())
        refresh()
        return sessionId
    }

    /** Отменить исходящее приглашение. */
    @Synchronized
    fun cancelInvite(sessionId: Int) {
        val rt = sessions[sessionId] ?: return
        if (rt.session.status != SessionStatus.INVITING) return
        // INVITE больше не нужен — в расписании оставляем только
        // INVITE_CANCEL (иначе оба кадра крутились бы в эфире параллельно).
        rt.channel.clearUnacked()
        sendMsg(rt, MsgType.INVITE_CANCEL, ByteArray(0))
        DebugLog.d("S", "отмена приглашения sid=$sessionId")
        rt.session = rt.session.copy(status = SessionStatus.CANCELLED, updatedAtMs = now())
        refresh()
    }

    /**
     * «Оживить» зависшее приглашение: сессия в INVITING, но в канале нет
     * ни одного кадра в ожидании (старое сохранение — INVITE «сгорел» до
     * перехода на вечный режим повторов, — либо ответ соперника потерялся).
     * Идемпотентно: пока кадры идут по расписанию — ничего не шлём.
     *
     * INVITE переиспользует seq=0 (см. ReliableChannel.send forceSeq) —
     * счётчики обеих сторон остаются согласованными, а cumAck из ответа
     * гасит расписание повторов сразу после доставки. На стороне пира
     * повторный INVITE обрабатывается до дедупа и повторяет свой
     * INVITE_RESP (см. handleIncoming), так что цепочка самолечится.
     */
    @Synchronized
    fun retryInviteIfStuck(sessionId: Int) {
        val rt = sessions[sessionId] ?: return
        if (rt.session.status != SessionStatus.INVITING) return
        if (rt.channel.pendingCount() > 0) return
        DebugLog.d("S", "sid=$sessionId завис в INVITING без кадров — повторный INVITE (seq=0)")
        val frame = rt.channel.send(
            MsgType.INVITE, sessionId,
            // Сторона та же, что и в исходном INVITE (повтор не меняет цвета).
            InvitePayload(rt.session.gameId.id, rt.session.mySide.ordinal, rt.session.difficulty).encode(),
            now(), forceSeq = 0,
        )
        transport.sendAsync(rt.session.peerNode, frame.frame)
    }

    /** Ответить на входящее приглашение. */
    @Synchronized
    fun respondInvite(sessionId: Int, accept: Boolean) {
        val rt = sessions[sessionId] ?: return
        if (rt.session.status != SessionStatus.INVITED) return
        DebugLog.d("S", "ответ на приглашение sid=$sessionId: принято=$accept")
        sendMsg(rt, MsgType.INVITE_RESP, InviteRespPayload(accept).encode())
        if (accept) {
            val engine = GameEngines.create(rt.session.gameId, rt.session.mySide, sessionId)
            rt.engine = engine
            val placement = rt.session.gameId == GameType.SEA_BATTLE
            rt.session = rt.session.copy(
                status = if (placement) SessionStatus.PLACEMENT else SessionStatus.ACTIVE,
                inviteAnswered = true, inviteAccepted = true, updatedAtMs = now(),
            )
            events.onGameStarted(sessionId)
        } else {
            // Отказ доносим одним ненадёжным кадром: инициатор узнаёт об
            // отказе с первого же пакета, в эфире — ноль повторов. Если кадр
            // потеряется, инициатор продолжит переотправлять INVITE, и
            // handleIncoming ответит на каждый такой повтор (см. дуп-INVITE).
            sendFragile(rt, MsgType.INVITE_RESP, InviteRespPayload(accept).encode())
            rt.session = rt.session.copy(
                status = SessionStatus.CANCELLED,
                inviteAnswered = true, inviteAccepted = false, updatedAtMs = now(),
            )
        }
        refresh()
    }

    /** Переставить корабли (морской бой). */
    @Synchronized
    fun reroll(sessionId: Int): Boolean {
        val rt = sessions[sessionId] ?: return false
        if (rt.session.status != SessionStatus.PLACEMENT || rt.session.closed) return false
        // Подтверждённую расстановку не переставляем: myReady переживает
        // рестарт (персист в GameSession), кнопка в UI гаснет по нему же —
        // здесь третья, «движковая» страховка (myConfirmed в engineState).
        if (rt.session.myReady) return false
        val ok = rt.engine?.rerollShips() == true
        if (ok) refresh()
        return ok
    }

    /** Подтвердить расстановку (морской бой). */
    @Synchronized
    fun ready(sessionId: Int): Boolean {
        val rt = sessions[sessionId] ?: return false
        val s = rt.session
        if (s.status != SessionStatus.PLACEMENT || s.myReady || s.closed) return false
        if (rt.engine?.confirmShips() != true) return false
        sendMsg(rt, MsgType.READY, ByteArray(0))
        rt.localActivity++
        val ns = rt.session.copy(myReady = true, updatedAtMs = now())
        rt.session = if (ns.peerReady) {
            ns.copy(status = SessionStatus.ACTIVE).also { rt.engine?.beginBattle() }
        } else ns
        if (rt.session.status == SessionStatus.ACTIVE) events.onGameStarted(sessionId)
        refresh()
        return true
    }

    /**
     * Локальный ход/выстрел. Валидируется движком ДО отправки:
     * false — ход нелегален, ничего не отправлено.
     */
    @Synchronized
    fun submitMove(sessionId: Int, input: MoveInput): Boolean {
        val rt = sessions[sessionId] ?: return false
        val s = rt.session
        if (s.status != SessionStatus.ACTIVE || s.closed) return false
        val engine = rt.engine ?: return false
        if (engine.finished || engine.turn != s.mySide) return false

        val payload = if (input is MoveInput.Shoot) {
            engine.localShot(input.coord)
        } else {
            engine.localMove(input)
        } ?: return false

        val type = if (input is MoveInput.Shoot) MsgType.SHOT else MsgType.MOVE
        // Журнал ходов: событие уже применено движком; фиксируем seq кадра —
        // досылка при сверке пойдёт по исходному seq (закроет дырку у пира).
        val seq = sendMsg(rt, type, payload)
        journalRecord(
            rt, if (input is MoveInput.Shoot) JOURNAL_SHOT else JOURNAL_MOVE,
            s.mySide, payload, seq,
        )
        if (input is MoveInput.Shoot) rt.pendingShotSinceMs = now()
        rt.localActivity++
        refresh()
        return true
    }

    /**
     * Автоход тестового бота (настройка «Тестовый бот»): движок подбирает
     * первый легальный ход ([com.meshgames.game.GameEngine.botMove]), дальше —
     * штатный путь [submitMove]. false — ходить нечего (чужой ход, партия
     * завершена, фаза расстановки либо спор балды).
     *
     * Сложность не нужна: бот нужен, чтобы партия жила сама, пока человек
     * проверяет связь с расстояния.
     */
    @Synchronized
    fun submitBotMove(sessionId: Int): Boolean {
        val rt = sessions[sessionId] ?: return false
        val s = rt.session
        if (s.status != SessionStatus.ACTIVE || s.closed) return false
        val engine = rt.engine ?: return false
        if (engine.finished || engine.turn != s.mySide) return false
        val input = engine.botMove() ?: return false
        DebugLog.d("S", "бот sid=$sessionId ходит: $input")
        return submitMove(sessionId, input)
    }

    /**
     * Есть ли спорное слово балды, ждущее моего вердикта. Отдельный геттер
     * для бота: тот принимает спор молча, без диалога в UI.
     */
    @Synchronized
    fun pendingVerdict(sessionId: Int): Boolean =
        sessions[sessionId]?.engine?.pendingVerdict == true

    /** Сдаться. */
    @Synchronized
    fun resign(sessionId: Int) {
        val rt = sessions[sessionId] ?: return
        if (rt.session.status != SessionStatus.ACTIVE) return
        finish(rt, GameResult(rt.session.mySide.other, ResultReason.RESIGN), sendOver = true)
    }

    /**
     * Закрыть игру с сохранением: сессия остаётся (можно продолжить позже),
     * соперник получает SESSION_CLOSE и видит уведомление о закрытии.
     */
    @Synchronized
    fun closeGame(sessionId: Int) {
        val rt = sessions[sessionId] ?: return
        val s = rt.session
        if (s.status != SessionStatus.ACTIVE && s.status != SessionStatus.PLACEMENT) return
        if (s.closed) return
        sendMsg(rt, MsgType.SESSION_CLOSE, byteArrayOf(1))
        rt.session = s.copy(closed = true, updatedAtMs = now())
        refresh()
    }

    /** Возобновить закрытую игру: уведомляем соперника и запрашиваем синхронизацию. */
    @Synchronized
    fun resumeGame(sessionId: Int) {
        val rt = sessions[sessionId] ?: return
        val s = rt.session
        if (!s.closed) return
        val outClose = rt.channel.send(MsgType.SESSION_CLOSE, rt.session.sessionId, byteArrayOf(0), now())
        val outSync = rt.channel.send(MsgType.SYNC_REQ, rt.session.sessionId, journalTail(rt).encode(), now())
        val batch = com.meshgames.game.protocol.BatchFrame.encode(rt.session.sessionId, listOf(outClose.frame, outSync.frame))
        if (batch != null) {
            transport.sendAsync(rt.session.peerNode, batch)
        } else {
            sendFrames(rt, listOf(outClose, outSync))
        }
        rt.session = s.copy(closed = false, updatedAtMs = now())
        refresh()
    }

    /**
     * Удалить сохранённую сессию. Сессия удаляется локально сразу (пользователь
     * ничего не ждёт — никаких «жду подтверждения»), а SESSION_DELETE ещё
     * несколько раз уходит в эфир из «сиротской» очереди ([fireAndRepeat]):
     * приёмник обрабатывает его мимо канала (см. [handleIncoming]) и удаляет
     * сессию с первого же пакета, повторы для него идемпотентны.
     */
    @Synchronized
    fun deleteSession(sessionId: Int) {
        val rt = sessions[sessionId] ?: return
        DebugLog.d("S", "удаление сессии sid=$sessionId (локально сразу + повторы)")
        repeats.removeAll { Envelope.decode(it.frame)?.sessionId == sessionId }
        fireAndRepeat(
            rt.session.peerNode,
            Envelope(MsgType.SESSION_DELETE, sessionId, 0, ByteArray(0)).encode(),
        )
        sessions.remove(sessionId)
        // Данные сессии на диске — вместе с журналом ходов.
        journals.delete(sessionId)
        refresh()
    }

    /** Предложить ничью. */
    @Synchronized
    fun offerDraw(sessionId: Int) {
        val rt = sessions[sessionId] ?: return
        val s = rt.session
        if (s.status != SessionStatus.ACTIVE || s.drawOfferByMe) return
        sendMsg(rt, MsgType.DRAW_OFFER, ByteArray(0))
        rt.session = s.copy(drawOfferByMe = true, updatedAtMs = now())
        refresh()
    }

    /** Принять/отклонить ничью. */
    @Synchronized
    fun respondDraw(sessionId: Int, accept: Boolean) {
        val rt = sessions[sessionId] ?: return
        val s = rt.session
        if (s.status != SessionStatus.ACTIVE || !s.drawOfferByPeer) return
        sendMsg(rt, MsgType.DRAW_RESP, DrawRespPayload(accept).encode())
        if (accept) {
            finish(rt, GameResult(null, ResultReason.DRAW_AGREEMENT), sendOver = true)
        } else {
            rt.session = s.copy(drawOfferByPeer = false, updatedAtMs = now())
            refresh()
        }
    }

    /**
     * Принудительная синхронизация (кнопка «⟳ Синхр» в UI, все игры):
     * SYNC_REQ с наивысшим приоритетом — уходит в обход очереди обычных
     * отправок ([SessionTransport.sendNow]), а повторы расписания канала
     * позже позаботятся о доставке. Ничего не блокирует и ничего не ждёт.
     * false — сессии нет, партия завершена/закрыта либо повторный вызов
     * быстрее [FORCED_SYNC_COOLDOWN_MS] (защита от зажатой кнопки).
     */
    @Synchronized
    fun requestSync(sessionId: Int): Boolean {
        val rt = sessions[sessionId] ?: return false
        if (rt.session.closed || rt.engine?.finished == true) return false
        val nowMs = now()
        if (nowMs - lastForcedSyncAtMs < FORCED_SYNC_COOLDOWN_MS) {
            DebugLog.d("S", "принудительная синхронизация sid=$sessionId — повтор быстрее 5 с, пропуск")
            return false
        }
        lastForcedSyncAtMs = nowMs
        DebugLog.d("S", "принудительная синхронизация sid=$sessionId (кнопка, приоритет)")
        sendSyncRequest(rt, priority = true)
        refresh()
        return true
    }

    /**
     * Отправить SYNC_REQ с отметкой «состояние не менялось с момента запроса».
     * Поздний ответ (когда я успел сходить) не должен давать ложный конфликт.
     * Payload — хвост журнала ходов: пир по нему видит свои «дырки» и
     * досылает недостающие записи (старый пир payload не читает — безвредно).
     * [priority] — уйти немедленно, минуя очередь обычных отправок.
     */
    private fun sendSyncRequest(rt: Runtime, priority: Boolean = false) {
        rt.syncAtActivity = rt.localActivity
        sendMsg(rt, MsgType.SYNC_REQ, journalTail(rt).encode(), priority = priority)
    }

    /** Убрать завершённую/отменённую сессию из списка. */
    @Synchronized
    fun dismiss(sessionId: Int) {
        val rt = sessions[sessionId] ?: return
        when (rt.session.status) {
            SessionStatus.FINISHED, SessionStatus.CANCELLED, SessionStatus.DECLINED -> Unit
            else -> return
        }
        // Сессия удаляется сразу, но её неподтверждённое (например, финальный
        // GAME_OVER или INVITE_CANCEL) ещё несколько раз уходит в эфир из
        // «сиротской» очереди [fireAndRepeat]: приёмник применит кадр ровно
        // один раз (дедуп по seq), здесь важно лишь донести отправку.
        val to = rt.session.peerNode
        val farewell = rt.channel.resendAll()
        DebugLog.d("S", "dismiss sid=$sessionId status=${rt.session.status}, доотправка кадров=${farewell.size}")
        for (f in farewell) fireAndRepeat(to, f.frame)
        sessions.remove(sessionId)
        // Данные сессии на диске — вместе с журналом ходов.
        journals.delete(sessionId)
        refresh()
    }

    /** Периодический тик: повторы кадров по расписанию + «сиротские» повторы. */
    @Synchronized
    fun tick(nowMs: Long = now()) {
        var changed = false
        for (rt in sessions.values) {
            val frames = rt.channel.tick(nowMs)
            if (frames.isNotEmpty()) {
                changed = true
                for (f in frames) {
                    DebugLog.d("TX*", "повтор ${Envelope.decode(f.frame)?.type} sid=${rt.session.sessionId}")
                }
                sendFrames(rt, frames)
            }
            // Лечение зависшего выстрела (морской бой): см. healStuckPendingShot.
            if (healStuckPendingShot(rt, nowMs)) changed = true
        }
        // «Сиротские» повторы кадров, переживших удаление сессии.
        if (repeats.isNotEmpty()) {
            val it = repeats.iterator()
            while (it.hasNext()) {
                val r = it.next()
                if (nowMs >= r.nextDueMs) {
                    DebugLog.d("TX*", "сиротский повтор ${Envelope.decode(r.frame)?.type} → ${r.to}")
                    transport.sendAsync(r.to, r.frame)
                    r.idx++
                    if (r.idx >= r.gaps.size) it.remove() else r.nextDueMs = nowMs + r.gaps[r.idx]
                }
            }
        }
        if (changed) refresh()
    }

    /**
     * Лечение зависшего выстрела (морской бой): SHOT_RESULT теряется
     * (повторы не доходят), движок висит вне покоя
     * ([com.meshgames.game.GameEngine.quiescent]), и синхронизация
     * заблокирована: сравнение дайджестов работает только в покое (полевой
     * провал sid=40290 — взаимный deadlock pendingShot обеих сторон).
     *
     * По таймауту [PENDING_SHOT_TIMEOUT_MS] сбрасываем pendingShot (он не
     * входит в дайджест — состояния от сброса не расходятся) и запрашиваем
     * синхронизацию. Дальше два честных исхода: дайджесты разойдутся (пир
     * результат уже применил, а у меня клетка не помечена) — SYNC_CONFLICT
     * завершает партию; сошлись (SHOT до пира так и не дошёл) — партия
     * продолжается. Поздний SHOT_RESULT после сброса тоже безопасен:
     * applyShotResult вернёт false → новый SYNC_REQ → конфликт/продолжение.
     *
     * Возвращает true, если состояние движка изменилось (нужен refresh).
     */
    private fun healStuckPendingShot(rt: Runtime, nowMs: Long): Boolean {
        val engine = rt.engine ?: return false
        // Лечим только «видимую» активную партию: закрытую/неактивную в фоне
        // не трогаем (трафик не шлём), но окно таймера продолжает идти —
        // сразу после открытия сессии лечение сработает по-честному.
        if (rt.session.status != SessionStatus.ACTIVE || rt.session.closed) return false
        if (engine.quiescent) {
            rt.pendingShotSinceMs = 0 // в покое — следующий выстрел начнёт окно заново
            return false
        }
        if (rt.pendingShotSinceMs == 0L) {
            // Restore-случай: Runtime живёт только в памяти — после рестарта
            // окно таймера идёт заново от первого тика (а не от снимка).
            rt.pendingShotSinceMs = nowMs
            return false
        }
        if (nowMs - rt.pendingShotSinceMs < PENDING_SHOT_TIMEOUT_MS) return false
        if (!engine.abandonPendingShot()) return false
        DebugLog.d(
            "S",
            "результат выстрела потерян sid=${rt.session.sessionId} " +
                "(${nowMs - rt.pendingShotSinceMs} мс) — сброс pendingShot + SYNC_REQ",
        )
        rt.pendingShotSinceMs = 0
        // Отброшенный ход (и прочие MOVE/SHOT/SHOT_RESULT) не должны больше
        // повторяться в эфире: непОДТВЕРЖДЁННЫЕ кадры теперь живут вечно
        // до cumAck, а запоздалый повтор откачанного SHOT снова развёл бы
        // состояния с пиром (он мог его успеть применить).
        rt.channel.forgetGameFrames()
        // localActivity не трогаем: сброс pendingShot в дайджест не входит,
        // а ответ SYNC_RES обязан пройти корреляцию (syncAtActivity == localActivity).
        sendSyncRequest(rt)
        return true
    }

    // ------------------------------------------------------- incoming -----

    @Synchronized
    fun handleIncoming(from: Int, payload: ByteArray) {
        if (payload.isEmpty()) return
        val first = payload[0].toInt() and 0xFF
        if (first == BridgeCodec.PRESENCE_MAGIC || first == BridgeCodec.STAT_MAGIC || first == BridgeCodec.HELLO_MAGIC) {
            return
        }
        val env = Envelope.decode(payload) ?: run {
            DebugLog.d("RX", "битый кадр от $from (${payload.size}B)")
            return
        }
        // Составной пакет BATCH распаковывается и обрабатывается покадрово
        if (env.type == MsgType.BATCH) {
            val subFrames = com.meshgames.game.protocol.BatchFrame.decode(env.payload) ?: return
            DebugLog.d("RX", "BATCH (${subFrames.size} подкадров) от $from")
            for (sub in subFrames) {
                handleIncoming(from, sub)
            }
            return
        }
        DebugLog.d("RX", "${env.type} sid=${env.sessionId} seq=${env.seq} от $from")
        var rt = sessions[env.sessionId]
        if (rt == null) {
            // Новая сессия возможна только через INVITE.
            if (env.type != MsgType.INVITE) {
                DebugLog.d("RX", "→ нет сессии sid=${env.sessionId}, пропуск")
                // Если соперник шлёт кадры для уже удалённой/несуществующей у нас сессии (например,
                // повторы GAME_OVER после того, как мы нажали «Закрыть» или удалили партию),
                // отправляем SESSION_DELETE, чтобы пир не мусорил в эфир вечными повторами.
                if (env.type != MsgType.SESSION_DELETE && env.type != MsgType.ACK_ONLY) {
                    val nowMs = now()
                    val lastSent = unknownSessionDeleteCooldown[env.sessionId] ?: 0L
                    if (nowMs - lastSent > UNKNOWN_SESSION_DELETE_COOLDOWN_MS) {
                        unknownSessionDeleteCooldown[env.sessionId] = nowMs
                        if (unknownSessionDeleteCooldown.size > 50) {
                            unknownSessionDeleteCooldown.entries.removeIf { nowMs - it.value > UNKNOWN_SESSION_DELETE_COOLDOWN_MS * 2 }
                        }
                        DebugLog.d("S", "отправляем SESSION_DELETE для отсутствующей sid=${env.sessionId} узлу $from")
                        transport.sendAsync(from, Envelope(MsgType.SESSION_DELETE, env.sessionId, 0, ByteArray(0)).encode())
                    }
                }
                return
            }
            val invite = InvitePayload.decode(env.payload) ?: run {
                DebugLog.d("RX", "битый INVITE sid=${env.sessionId} (${env.payload.size}B) — пропуск")
                return
            }
            val gameType = GameType.fromId(invite.gameTypeId) ?: return
            // Инициатор занял сторону из payload — занимаем противоположную.
            val inviterSide = if (invite.inviterSide == 1) Side.B else Side.A
            rt = createRuntime(
                GameSession(
                    sessionId = env.sessionId, gameId = gameType,
                    mySide = inviterSide.other,
                    peerNode = from, status = SessionStatus.INVITED, updatedAtMs = now(),
                    difficulty = invite.difficulty,
                ),
            )
            DebugLog.d("S", "входящее приглашение sid=${env.sessionId} от $from: ${gameType.title} (diff=${invite.difficulty})")
            events.onInviteReceived(env.sessionId, from, gameType, invite.difficulty)
        }
        // Сессия привязана к конкретному узлу.
        if (rt.session.peerNode != from) {
            DebugLog.d("RX", "→ чужой узел: сессид ждёт ${rt.session.peerNode}, пришло от $from")
            return
        }

        // ACK_ONLY — легковесный кадр без payload: обновляет piggyback-cumAck в канале и завершается
        if (env.type == MsgType.ACK_ONLY) {
            DebugLog.d("RX", "ACK_ONLY sid=${env.sessionId} от $from (cumAck=${env.cumAck})")
            rt.channel.receive(payload, now())
            refresh()
            return
        }

        // SESSION_DELETE обрабатываем мимо канала: удаление должно сработать,
        // даже если в канале есть «дырка» (потерян соседний кадр) или после
        // рестарта счётчики разошлись. Повторы идемпотентны: сессия уже
        // удалена → rt==null → тихий игнор, событие не дублируется.
        if (env.type == MsgType.SESSION_DELETE) {
            DebugLog.d("S", "SESSION_DELETE sid=${env.sessionId} от $from → удаляем сессию")
            sessions.remove(env.sessionId)
            // Данные сессии на диске — вместе с журналом ходов.
            journals.delete(env.sessionId)
            repeats.removeAll { Envelope.decode(it.frame)?.sessionId == env.sessionId }
            events.onSessionDeletedByPeer(env.sessionId, rt.session.gameId, from)
            refresh()
            return
        }

        // SYNC_REQ/SYNC_RES — тоже мимо порядка: дырка в seq не должна
        // блокировать исцеление (полевой провал sid=40290: потеря
        // SHOT/SHOT_RESULT оставила дыру в rxNext → SYNC_RES осел в стэше
        // навсегда → взаимный deadlock pendingShot обеих сторон). Канал
        // доставляет контрольный кадр сразу; дедуп повторов и piggyback-ACK
        // сохраняются, seq мимо порядка помечается как отданный — вторую
        // доставку не дадут ни повтор кадра, ни закрытие дырки.
        if (env.type == MsgType.SYNC_REQ || env.type == MsgType.SYNC_RES) {
            val result = rt.channel.receiveOutOfBand(payload, now())
            if (result.deliveries.isEmpty()) {
                DebugLog.d("RX", "→ дубликат ${env.type} seq=${env.seq} (rxNext=${rt.channel.rxNextSeq()})")
            }
            for (env2 in result.deliveries) dispatch(rt, env2)
            refresh()
            return
        }

        // Повторный INVITE (наш ответ потерялся) — отвечаем ещё раз тем же
        // ненадёжным кадром. Дедуп канала не выдаёт дубль в dispatch, поэтому
        // обрабатываем здесь: инициатор переотправляет INVITE по расписанию,
        // и на каждый такой повтор уходит новый ответ, пока один не дойдёт.
        if (env.type == MsgType.INVITE && rt.session.inviteAnswered) {
            DebugLog.d("S", "повторный INVITE sid=${env.sessionId} → повторяем ответ (принято=${rt.session.inviteAccepted})")
            sendFragile(rt, MsgType.INVITE_RESP, InviteRespPayload(rt.session.inviteAccepted).encode())
        }

        val result = rt.channel.receive(payload, now())
        if (result.deliveries.isEmpty()) {
            DebugLog.d("RX", "→ дубликат/стэш seq=${env.seq} (rxNext=${rt.channel.rxNextSeq()})")
            if (env.type == MsgType.GAME_OVER) {
                DebugLog.d("S", "дубликат GAME_OVER sid=${env.sessionId} — повторно шлём ACK_ONLY")
                sendAckOnly(rt)
            }
        }
        sendFrames(rt, result.toSend)
        for (env2 in result.deliveries) dispatch(rt, env2)
        refresh()
    }

    private fun dispatch(rt: Runtime, env: Envelope) {
        when (env.type) {
            MsgType.ACK_ONLY, MsgType.BATCH -> Unit
            MsgType.INVITE -> {
                // Повторные INVITE обрабатываются в handleIncoming до дедупа
                // (дубликат сюда не доезжает — канал его отбрасывает).
            }
            MsgType.INVITE_RESP -> {
                val resp = InviteRespPayload.decode(env.payload) ?: return
                val s = rt.session
                if (s.status != SessionStatus.INVITING) return
                if (resp.accepted) {
                    val placement = s.gameId == GameType.SEA_BATTLE
                    DebugLog.d("S", "приглашение принято sid=${s.sessionId} → ${if (placement) "PLACEMENT" else "ACTIVE"}")
                    rt.session = s.copy(
                        status = if (placement) SessionStatus.PLACEMENT else SessionStatus.ACTIVE,
                        updatedAtMs = now(),
                    )
                    events.onGameStarted(s.sessionId)
                } else {
                    // Отказ получен: повторы INVITE бессмысленны — бросаем.
                    DebugLog.d("S", "приглашение отклонено sid=${s.sessionId}")
                    rt.channel.clearUnacked()
                    rt.session = s.copy(status = SessionStatus.CANCELLED, updatedAtMs = now())
                    events.onInviteDeclined(s.sessionId)
                }
            }
            MsgType.INVITE_CANCEL -> {
                if (rt.session.status == SessionStatus.INVITED) {
                    DebugLog.d("S", "соперник отменил приглашение sid=${rt.session.sessionId}")
                    rt.session = rt.session.copy(
                        status = SessionStatus.CANCELLED, updatedAtMs = now(),
                    )
                }
            }
            MsgType.MOVE -> {
                val engine = rt.engine ?: return
                if (!engine.remoteMove(env.payload)) {
                    // Ход отклонён движком — запрашиваем синхронизацию.
                    DebugLog.d("S", "ход соперника отклонён движком sid=${rt.session.sessionId} → SYNC_REQ")
                    sendSyncRequest(rt)
                    return
                }
                journalRecord(rt, JOURNAL_MOVE, rt.session.mySide.other, env.payload)
                events.onOpponentAction(rt.session.sessionId)
                checkFinished(rt)
            }
            MsgType.SHOT -> {
                val engine = rt.engine ?: return
                val reply = engine.remoteShot(env.payload) ?: run {
                    DebugLog.d("S", "выстрел соперника не разобрался sid=${rt.session.sessionId} → SYNC_REQ")
                    sendSyncRequest(rt)
                    return
                }
                journalRecord(rt, JOURNAL_SHOT, rt.session.mySide.other, env.payload)
                val replySeq = sendMsg(rt, MsgType.SHOT_RESULT, reply)
                journalRecord(rt, JOURNAL_SHOT_RESULT, rt.session.mySide, reply, replySeq)
                events.onOpponentAction(rt.session.sessionId)
                checkFinished(rt)
            }
            MsgType.SHOT_RESULT -> {
                val engine = rt.engine ?: return
                if (!engine.applyShotResult(env.payload)) {
                    // Поздний результат после сброса pendingShot (таймаут):
                    // журнал знает мой выстрел — восстанавливаем состояние
                    // на момент выстрела и применяем результат по-честному.
                    if (rescueLateShotResult(rt, env.payload)) {
                        checkFinished(rt)
                        return
                    }
                    DebugLog.d("S", "результат выстрела не применился sid=${rt.session.sessionId} → SYNC_REQ")
                    sendSyncRequest(rt)
                    return
                }
                journalRecord(rt, JOURNAL_SHOT_RESULT, rt.session.mySide.other, env.payload)
                checkFinished(rt)
            }
            MsgType.READY -> {
                val s = rt.session
                if (s.status != SessionStatus.PLACEMENT || s.peerReady) return
                DebugLog.d("S", "расстановка соперника подтверждена sid=${s.sessionId}")
                val ns = s.copy(peerReady = true, updatedAtMs = now())
                rt.session = if (ns.myReady) {
                    ns.copy(status = SessionStatus.ACTIVE).also { rt.engine?.beginBattle() }
                } else ns
                if (rt.session.status == SessionStatus.ACTIVE) events.onGameStarted(s.sessionId)
            }
            MsgType.GAME_OVER -> {
                val over = GameOverPayload.decode(env.payload) ?: return
                // Кадр уже завершённой сессии (повторы GAME_OVER после
                // рестарта) — обрабатывать нечего: иначе два аппарата
                // крутят GAME_OVER вечно. Гасим повторы кадра отправкой ACK_ONLY.
                if (rt.session.status == SessionStatus.FINISHED) {
                    DebugLog.d("S", "GAME_OVER для уже завершённой sid=${rt.session.sessionId} — пропуск, гасим повтор ACK_ONLY")
                    sendAckOnly(rt)
                    return
                }
                val winner = when (over.winner) {
                    0 -> Side.A
                    1 -> Side.B
                    else -> null
                }
                val reason = ResultReason.entries.getOrElse(over.reason) { ResultReason.OPPONENT_LEFT }
                DebugLog.d("S", "итог от соперника sid=${rt.session.sessionId}: победитель=$winner причина=$reason")
                finish(rt, GameResult(winner, reason), sendOver = false)
                sendAckOnly(rt)
            }
            MsgType.DRAW_OFFER -> {
                val s = rt.session
                if (s.status != SessionStatus.ACTIVE) return
                DebugLog.d("S", "соперник предлагает ничью sid=${s.sessionId}")
                rt.session = s.copy(drawOfferByPeer = true, updatedAtMs = now())
                events.onDrawOffered(s.sessionId)
            }
            MsgType.DRAW_RESP -> {
                val resp = DrawRespPayload.decode(env.payload) ?: return
                val s = rt.session
                if (resp.accepted && s.status == SessionStatus.ACTIVE) {
                    DebugLog.d("S", "ничья принята sid=${s.sessionId}")
                    finish(rt, GameResult(null, ResultReason.DRAW_AGREEMENT), sendOver = false)
                } else {
                    rt.session = s.copy(drawOfferByMe = false, updatedAtMs = now())
                }
            }
            MsgType.SYNC_REQ -> {
                val engine = rt.engine
                // Отвечаем только из покоя: дайджест «в полёте» (pendingShot) —
                // состояние до применения результата, а соперник в покое сравнил
                // бы его со своим и объявил ложный SYNC_CONFLICT (полевая гонка
                // 17.10 sid=13951: SHOT шёл 131 с, SYNC_REQ пришёл раньше
                // SHOT_RESULT — конфликт объявил тот, кто запросил). Молчим:
                // повторы SYNC_REQ по расписанию позже попадают на покой и
                // получают честный дайджест; настоящему расхождению молчание
                // не мешает — исцеление/повторы приведут к сравнению в покое.
                if (engine != null && !engine.quiescent) {
                    DebugLog.d(
                        "S",
                        "SYNC_RES не отвечен: выстрел в полёте sid=${rt.session.sessionId}",
                    )
                    return
                }
                val myTail = journalTail(rt)
                val sync = SyncResPayload(
                    cumAck = rt.channel.rxNextSeq() - 1 and 0xFFFF,
                    turn = engine?.turn?.ordinal ?: 255,
                    status = rt.session.status.ordinal,
                    digest = engine?.digest() ?: 0,
                )
                // В ответ вшиваем хвост журнала; если запрос нес хвост пира и
                // дайджесты совпали — шлём пустой хвост (экономия ~90 байт в эфире).
                // Если дайджесты разошлись — шлём полный хвост и пробуем восстановление (досылка/откат).
                val reqTail = JournalTailPayload.decode(env.payload)
                val tailToSend = if (reqTail != null && engine != null && reqTail.digest == engine.digest()) {
                    JournalTailPayload(engine.digest(), emptyList())
                } else {
                    myTail
                }
                sendMsg(rt, MsgType.SYNC_RES, sync.encode() + tailToSend.encode())
                if (reqTail != null && engine != null &&
                    rt.session.status == SessionStatus.ACTIVE &&
                    reqTail.digest != engine.digest()
                ) {
                    reconcile(rt, myTail, reqTail)
                }
            }
            MsgType.SYNC_RES -> {
                val sync = SyncResPayload.decode(env.payload) ?: return
                rt.channel.acknowledgeUpTo(sync.cumAck, 0, now())
                val engine = rt.engine
                // Сравниваем дайджесты только в покое: пока мой выстрел в
                // полёте, состояния legitimately расходятся. Ответ, пришедший
                // после МОЕГО нового хода (пересёкся в пути), игнорируем —
                // он посчитан по состоянию соперника до моего хода.
                if (engine != null && engine.quiescent &&
                    rt.session.status == SessionStatus.ACTIVE &&
                    rt.syncAtActivity == rt.localActivity &&
                    sync.digest != engine.digest()
                ) {
                    // Хвост журнала пира (опциональная часть payload): сверка
                    // «последних ходов» — досылка или откат вместо ничьей.
                    val peerTail = JournalTailPayload.decode(
                        env.payload.copyOfRange(
                            JournalTailPayload.SYNC_RES_TAIL_OFFSET, env.payload.size,
                        ),
                    )
                    if (peerTail == null || !reconcile(rt, journalTail(rt), peerTail)) {
                        DebugLog.d(
                            "S",
                            "КОНФЛИКТ СИНХРОНИЗАЦИИ sid=${rt.session.sessionId} (digest ${sync.digest}≠${engine.digest()})",
                        )
                        events.onSyncConflict(rt.session.sessionId)
                        finish(rt, GameResult(null, ResultReason.SYNC_CONFLICT), sendOver = true)
                    }
                }
            }
            MsgType.SESSION_CLOSE -> {
                val closed = (env.payload.firstOrNull() ?: 0).toInt() != 0
                val s = rt.session
                if (s.closed != closed) {
                    rt.session = s.copy(closed = closed, updatedAtMs = now())
                    events.onGameClosed(s.sessionId, closed)
                }
            }
            // SESSION_DELETE обрабатывается мимо канала в handleIncoming —
            // сюда он никогда не доезжает (ранний выход до dispatch).
            MsgType.SESSION_DELETE -> Unit
        }
    }

    // ------------------------------------------------------- helpers ------

    private fun createRuntime(gs: GameSession): Runtime {
        val channel = ReliableChannel(gs.sessionId)
        // Движок есть у всех сессий, кроме непринятого приглашения (он
        // создаётся в respondInvite): инициатор — при любой стороне.
        val engine = if (gs.inviteAccepted || gs.status != SessionStatus.INVITED) {
            try {
                GameEngines.restore(gs.gameId, gs.mySide, gs.engineState, gs.sessionId)
            } catch (_: Exception) {
                // Состояние записано прежней версией формата (например, шашки
                // со старой расстановкой) — нечитаемо. Партия начинается
                // заново с начальной позиции: у обеих сторон, обновивших APK,
                // это происходит одинаково — синхронность сохраняется.
                DebugLog.d("S", "состояние sid=${gs.sessionId} нечитаемо — движок создан заново")
                GameEngines.create(gs.gameId, gs.mySide, gs.sessionId)
            }
        } else null
        val rt = Runtime(gs, channel, engine)
        // Журнал ходов с диска. Истина локального состояния — engineState
        // (пишется в refresh синхронно с журналом); при расхождении верим
        // снимку движка и логируем — журнал продолжает сверяться по хвостам.
        val loaded = journals.load(gs.sessionId)
        if (!loaded.isNullOrEmpty()) {
            rt.journal.restore(loaded)
            if (engine != null && loaded.last().digest != engine.digest()) {
                DebugLog.d("S", "журнал sid=${gs.sessionId} расходится с движком — верим engineState")
            }
        }
        sessions[gs.sessionId] = rt
        return rt
    }

    private fun restore() {
        for (gs in store.load()) {
            if (gs.status == SessionStatus.CANCELLED || gs.status == SessionStatus.DECLINED) {
                continue // мусор не восстанавливаем
            }
            val rt = createRuntime(gs)
            // Расписание повторов возобновляется через гэп после рестарта
            // (без взрывной пересылки сразу всего); кадры с исчерпанным
            // базовым расписанием восстанавливаются в вечном режиме —
            // рестарт не «сжигает» непОДТВЕРЖДЁННЫЕ (иначе у пира навсегда
            // остаётся дырка в rxNext — полевой инцидент 18.10).
            rt.channel.restore(gs.nextSeq, gs.rxNext, gs.unacked, now())
            DebugLog.d("S", "восстановление sid=${gs.sessionId} status=${gs.status} unacked=${gs.unacked.size}")
            // Приглашение без ответа (в ожидании ни одного кадра — старое
            // сохранение) — переотправляем сразу: иначе сессия навсегда
            // зависнет в «ждём ответа соперника» (такое случалось в поле).
            // Если транспорт ещё не подключён — кадр уже в расписании
            // канала и уйдёт первым же повтором после подключения.
            retryInviteIfStuck(gs.sessionId)
        }
        // Журналы осиротевших сессий (удалены/отменены — не восстановлены)
        // убираем с диска: данные сессии живут ровно до её удаления.
        for (sid in journals.list()) {
            if (!sessions.containsKey(sid)) journals.delete(sid)
        }
        refresh()
    }

    // ------------------------------------- журнал и сверка приёма -------

    /**
     * Каноническая запись журнала: событие зафиксировано после применения
     * движком (и после отправки кадра, чтобы знать его seq для досылки).
     * Снимок/дайджест — локальные, каноническая часть (kind/by/payload) —
     * одинакова на обеих сторонах.
     */
    private fun journalRecord(
        rt: Runtime,
        kind: Int,
        by: Side,
        payload: ByteArray,
        seq: Int = -1,
    ) {
        val engine = rt.engine ?: return
        val idx = rt.journal.record(
            kind, by.ordinal, payload, engine.encodeState(), engine.digest(), seq,
        )
        // Новая запись = прогресс: бюджет ожидания досылки от пира сброшен.
        rt.repairWaited = false
        DebugLog.d("S", "журнал sid=${rt.session.sessionId}: #$idx kind=$kind от $by")
    }

    /** Хвост журнала для сверки: последние записи + текущий дайджест. */
    private fun journalTail(rt: Runtime): JournalTailPayload {
        val entries = rt.journal.tail(JournalTailPayload.MAX_ENTRIES)
            .map { JournalTailPayload.TailEntry(it.index, it.chain, it.digest) }
        return JournalTailPayload(rt.engine?.digest() ?: 0, entries)
    }

    /**
     * Сверка журналов после SYNC_REQ/SYNC_RES. Обе стороны выполняют один и
     * тот же детерминированный алгоритм на одинаковых данных — хвостах,
     * снятых ДО начала обмена (SYNC_RES уходит в ответ на запрос; расчёт на
     * стороне запросившего — по своему дозапросному хвосту).
     *
     * 1. Пир впереди (его записи сверх моего префикса) — он сам досылает их;
     *    мне достаточно вернуть состояние к вершине своего журнала (снимает,
     *    например, локальный сброс pendingShot по таймауту) и ждать.
     * 2. Я впереди — досылаю свои записи («дырка» в принятом у пира);
     *    повторное отвершение той же записи = настоящий раскол → откат.
     * 3. Записи в общем окне разошлись — проверенный откат к последней
     *    записи, чей снимок подтверждается дайджестом пира ([verifiedRollback]).
     * 4. Проверить не удалось — false: конфликт завершает партию по-старому.
     */
    private fun reconcile(
        rt: Runtime,
        myTail: JournalTailPayload,
        peerTail: JournalTailPayload,
    ): Boolean {
        if (rt.session.status != SessionStatus.ACTIVE || rt.engine == null) return true
        val nA = myTail.entries.lastOrNull()?.index ?: 0
        val nB = peerTail.entries.lastOrNull()?.index ?: 0
        val m = minOf(nA, nB)
        // Общий префикс: первая (ближайшая к m) цепная сумма, совпавшая у
        // обеих сторон; записи выше m у одной стороны заведомо нет.
        var common = -1
        for (i in m downTo maxOf(0, m - JournalTailPayload.MAX_ENTRIES + 1)) {
            val a = myTail.entries.lastOrNull { it.index == i } ?: continue
            val b = peerTail.entries.lastOrNull { it.index == i } ?: continue
            if (a.chain == b.chain) {
                common = i
                break
            }
        }
        val sid = rt.session.sessionId
        return when {
            nB > nA && common == nA -> {
                if (rt.repairWaited) {
                    // Уже ждал досылку без прогресса (например, журнал был
                    // утрачен рестартом) — дальше только честный конфликт.
                    DebugLog.d("S", "sid=$sid: ждал досылку без прогресса — сверка не помогает")
                    false
                } else {
                    rt.repairWaited = true
                    restoreJournalTip(rt)
                    DebugLog.d("S", "sid=$sid: журнал пира впереди ($nB>$nA) — ждём досылку")
                    true
                }
            }
            nA > nB && common == nB -> {
                if (rt.resentTopEntry != nA) {
                    resendJournalTail(rt)
                    rt.resentTopEntry = nA
                    DebugLog.d("S", "sid=$sid: я впереди ($nA>$nB) — досылаю журнал")
                    true
                } else {
                    // Мою запись пир отверг повторно — настоящий раскол.
                    verifiedRollback(rt, peerTail, common).also { ok ->
                        if (!ok) DebugLog.d("S", "sid=$sid: запись #$nA отвергнута пиром, откат не подтвердился")
                    }
                }
            }
            common >= 0 -> {
                verifiedRollback(rt, peerTail, common).also { ok ->
                    if (ok) DebugLog.d("S", "sid=$sid: расхождение журналов — откат к #$common")
                }
            }
            else -> false // общего префикса в окне нет — сверять нечем
        }
    }

    /**
     * Проверенный откат: иду вниз от [startK] и беру первую запись, чей снимок
     * воспроизводит дайджест, подтверждённый пиром. Промежуточные «внутри
     * транзакции» точки (выстрел без результата в морском бое — дайджесты
     * сторон там несравнимы) дайджестом не подтверждаются и пропускаются.
     */
    private fun verifiedRollback(rt: Runtime, peerTail: JournalTailPayload, startK: Int): Boolean {
        val sid = rt.session.sessionId
        for (k in startK downTo 0) {
            val mine = rt.journal.entryAt(k) ?: return false
            val peerDigest = peerTail.entries.lastOrNull { it.index == k }?.digest ?: continue
            val restored = try {
                GameEngines.restore(rt.session.gameId, rt.session.mySide, mine.state, sid)
            } catch (_: Exception) {
                return false
            }
            if (restored.digest() != peerDigest) continue
            rt.engine = restored
            rt.pendingShotSinceMs = if (restored.quiescent) 0 else now()
            rt.localActivity++
            rt.syncAtActivity = rt.localActivity
            if (k < rt.journal.eventCount) {
                rt.journal.truncateTo(k)
                // Повторы отброшенных ходов убираем: старый ход вернулся бы
                // повтором и снова развёл бы партию.
                rt.channel.forgetGameFrames()
                rt.resentTopEntry = -1
                events.onSessionRecovered(sid, k)
                DebugLog.d("S", "sid=$sid: ОТКАТ к записи #$k, дайджест пира подтверждён")
            } else {
                // Вершина журнала: состояние вернулось к записи (снимает, напр.,
                // локальный сброс pendingShot) — уведомление не нужно.
                DebugLog.d("S", "sid=$sid: состояние возвращено к вершине журнала #$k")
            }
            return true
        }
        return false
    }

    /** Вернуть состояние движка к вершине своего журнала (без урезания). */
    private fun restoreJournalTip(rt: Runtime): Boolean {
        val tip = rt.journal.entryAt(rt.journal.eventCount) ?: return false
        val engine = try {
            GameEngines.restore(rt.session.gameId, rt.session.mySide, tip.state, rt.session.sessionId)
        } catch (_: Exception) {
            return false
        }
        rt.engine = engine
        rt.pendingShotSinceMs = if (engine.quiescent) 0 else now()
        return true
    }

    /** Дослать пиру свои записи из хвоста (MOVE/SHOT/SHOT_RESULT моей стороны). */
    private fun resendJournalTail(rt: Runtime) {
        val mine = rt.session.mySide.ordinal
        for (e in rt.journal.tail(JournalTailPayload.MAX_ENTRIES)) {
            if (e.index == 0 || e.by != mine) continue
            val type = when (e.kind) {
                JOURNAL_MOVE -> MsgType.MOVE
                JOURNAL_SHOT -> MsgType.SHOT
                JOURNAL_SHOT_RESULT -> MsgType.SHOT_RESULT
                else -> continue
            }
            // Досылка по ИСХОДНОМУ seq: кадр закрывает дырку в rxNext пира —
            // иначе повтор с новым seq застрял бы в его стэше в ожидании
            // потерянного оригинала (дырка-то и есть причина досылки).
            sendMsg(rt, type, e.payload, forceSeq = if (e.seq >= 0) e.seq else null)
        }
    }

    /**
     * Поздний SHOT_RESULT после сброса pendingShot по таймауту: результат
     * «нашлёпался» после лечения (досылка из журнала пира). Журнал знает
     * координату моего выстрела — восстанавливаем состояние на момент выстрела
     * (pendingShot снова в полёте) и повторяем применение.
     */
    private fun rescueLateShotResult(rt: Runtime, payload: ByteArray): Boolean {
        if (payload.isEmpty()) return false
        val tip = rt.journal.entryAt(rt.journal.eventCount) ?: return false
        if (tip.kind != JOURNAL_SHOT || tip.by != rt.session.mySide.ordinal) return false
        if (tip.payload.isEmpty() || tip.payload[0] != payload[0]) return false
        val restored = try {
            GameEngines.restore(rt.session.gameId, rt.session.mySide, tip.state, rt.session.sessionId)
        } catch (_: Exception) {
            return false
        }
        if (!restored.applyShotResult(payload)) return false
        rt.engine = restored
        rt.pendingShotSinceMs = 0
        journalRecord(rt, JOURNAL_SHOT_RESULT, rt.session.mySide.other, payload)
        DebugLog.d("S", "sid=${rt.session.sessionId}: поздний результат выстрела применён")
        return true
    }

    private fun checkFinished(rt: Runtime) {
        val engine = rt.engine ?: return
        if (engine.finished && engine.result != null) {
            finish(rt, engine.result!!, sendOver = true)
        }
    }

    private fun finish(rt: Runtime, result: GameResult, sendOver: Boolean) {
        if (rt.session.status == SessionStatus.FINISHED) return
        DebugLog.d("S", "партия завершена sid=${rt.session.sessionId}: $result (GAME_OVER отправляем=$sendOver)")
        if (sendOver) {
            val winner = when (result.winner) {
                Side.A -> 0
                Side.B -> 1
                null -> 2
            }
            sendMsg(rt, MsgType.GAME_OVER, GameOverPayload(winner, result.reason.ordinal).encode())
        }
        rt.session = rt.session.copy(
            status = SessionStatus.FINISHED, result = result, updatedAtMs = now(),
        )
        // Широковещательное оповещение о результате игры в канал для сбора статистики сервером
        val winnerSide = when (result.winner) {
            Side.A -> 0
            Side.B -> 1
            null -> 2
        }
        val (playerA, playerB) = if (rt.session.mySide == Side.A) {
            Pair(transport.myNodeNum, rt.session.peerNode)
        } else {
            Pair(rt.session.peerNode, transport.myNodeNum)
        }
        val statPayload = BridgeCodec.encodeStat(
            gameTypeId = rt.session.gameId.id,
            sessionId = rt.session.sessionId,
            winner = winnerSide,
            reason = result.reason.ordinal,
            playerA = playerA,
            playerB = playerB,
        )
        transport.sendAsync(0, statPayload)

        events.onGameOver(rt.session.sessionId, result)
    }

    /**
     * Кодирует и отправляет сообщение через надёжный канал сессии и транспорт.
     * Единственная точка отправки — так фрейм гарантированно уходит в сеть
     * (раньше кадры терялись: channel.send возвращал фрейм, который никто не
     * передавал в transport).
     */
    private fun sendMsg(
        rt: Runtime,
        type: MsgType,
        payload: ByteArray,
        forceSeq: Int? = null,
        priority: Boolean = false,
    ): Int {
        val frame = rt.channel.send(type, rt.session.sessionId, payload, now(), forceSeq)
        DebugLog.d("TX", "$type sid=${rt.session.sessionId} ${frame.frame.size}B → узел ${rt.session.peerNode}" + if (priority) " (приоритет)" else "")
        if (priority) {
            transport.sendNow(rt.session.peerNode, frame.frame)
        } else {
            transport.sendAsync(rt.session.peerNode, frame.frame)
        }
        return frame.seq
    }

    /**
     * Отправить ненадёжный кадр (fire-and-forget): вне надёжного канала,
     * без seq и без ретрансмитов — для быстрых одноразовых ответов, где
     * важна минимальная нагрузка эфира (отказ в приглашении: при потере
     * его дошлёт переотправленный INVITE — см. дуп-обработку в
     * [handleIncoming]).
     */
    private fun sendFragile(rt: Runtime, type: MsgType, payload: ByteArray) {
        val env = Envelope(type, rt.session.sessionId, 0, payload)
        DebugLog.d("TX", "$type (ненадёжный) sid=${rt.session.sessionId} → узел ${rt.session.peerNode}")
        transport.sendAsync(rt.session.peerNode, env.encode())
    }

    /**
     * Отправить легковесный ACK_ONLY (12 байт, seq=0) для быстрого гашения повторов пира.
     */
    private fun sendAckOnly(rt: Runtime) {
        val ackFrame = rt.channel.makeAckOnly(force = true) ?: return
        DebugLog.d("TX", "ACK_ONLY sid=${rt.session.sessionId} (cumAck=${(rt.channel.rxNextSeq() - 1) and 0xFFFF})")
        transport.sendAsync(rt.session.peerNode, ackFrame)
    }

    private fun sendFrames(rt: Runtime, frames: List<ReliableChannel.Outgoing>) {
        val to = rt.session.peerNode
        for (f in frames) {
            transport.sendAsync(to, f.frame)
        }
    }

    /**
     * Отправить сразу и поставить в очередь «сиротских» повторов ([repeats]):
     * кадр продолжает переотправляться по расписанию уже без сессии
     * (SESSION_DELETE, прощальный GAME_OVER из [dismiss]).
     */
    private fun fireAndRepeat(to: Int, frame: ByteArray) {
        transport.sendAsync(to, frame)
        val type = Envelope.decode(frame)?.type ?: return
        val gaps = ReliableChannel.gapsFor(type)
        repeats.add(Repeat(to, frame, gaps, now() + gaps[0]))
    }

    private fun newSessionId(): Int {
        var id: Int
        do {
            id = random.nextInt(0x10000)
        } while (sessions.containsKey(id))
        return id
    }

    private fun refresh() {
        val nowMs = now()
        for (rt in sessions.values) {
            rt.session = rt.session.copy(
                engineState = rt.engine?.encodeState() ?: rt.session.engineState,
                nextSeq = rt.channel.currentSeq(),
                rxNext = rt.channel.rxNextSeq(),
                unacked = rt.channel.snapshotUnacked(),
                updatedAtMs = nowMs,
            )
            // Базовая точка журнала (индекс 0) следует за состоянием, пока
            // партия не началась (расстановка/подтверждение в морском бое)
            // и не записано ни одного хода. С активной партией база заморожена
            // даже без ходов: лечение зависшего выстрела (сброс pendingShot)
            // не должно двигать базу — иначе restoreJournalTip вернул бы
            // состояние уже после отказа и поздний SHOT_RESULT был бы
            // отвергнут (дырка после рестарта без журнала). Дальше — откат
            // по снимкам записей.
            val engine = rt.engine
            if (engine != null && (
                rt.journal.isEmpty ||
                    (rt.journal.eventCount == 0 && rt.session.status != SessionStatus.ACTIVE)
                )
            ) {
                rt.journal.seedBaseline(engine.encodeState(), engine.digest())
            }
            if (rt.journal.dirty) {
                journals.save(rt.session.sessionId, rt.journal.list)
                rt.journal.clearDirty()
            }
        }
        store.save(sessions.values.map { it.session })
        _views.value = sessions.values
            .sortedByDescending { it.session.updatedAtMs }
            .map { rt ->
                SessionView(rt.session, rt.engine?.snapshot(), rt.channel.pendingCount() > 0)
            }
    }

    /** Снимок доски для UI (или null). */
    @Synchronized
    fun snapshot(sessionId: Int): BoardSnapshot? = sessions[sessionId]?.engine?.snapshot()

    /** Движок сессии (для интеллектуальных агентов и ботов). */
    @Synchronized
    fun engine(sessionId: Int): com.meshgames.game.GameEngine? = sessions[sessionId]?.engine

    /** Легальные клетки-цели из клетки [from] (для подсветки в UI). */
    @Synchronized
    fun legalMoves(sessionId: Int, from: Int): Set<Int> =
        sessions[sessionId]?.engine?.legalMoves(from) ?: emptySet()

    // --- Шашки: выбор хода «фигура → конечные клетки → маршрут» (делегируется движку) ---

    /** Начать выбор фигуры в шашках. false — нельзя (не мой ход/нет ходов). */
    @Synchronized
    fun checkersSelect(sessionId: Int, square: Int): Boolean {
        val e = sessions[sessionId]?.engine as? com.meshgames.game.engine.CheckersEngine ?: return false
        val ok = e.select(square)
        if (ok) refresh()
        return ok
    }

    /**
     * Маршруты хода выбранной фигуры, завершающегося в [square] (пусто — такого
     * хода нет). Несколько маршрутов бывают, когда разные цепочки взятий ведут
     * в одну клетку — тогда игрок выбирает, какой применить.
     */
    @Synchronized
    fun checkersRoutes(sessionId: Int, square: Int): List<IntArray> =
        (sessions[sessionId]?.engine as? com.meshgames.game.engine.CheckersEngine)
            ?.routes(square).orEmpty()

    /**
     * Применить ход выбранной фигуры, завершающийся в [square], и отправить MOVE.
     * [routeIndex] выбирает ветвь, когда в [square] ведёт несколько маршрутов.
     */
    @Synchronized
    fun checkersCommit(sessionId: Int, square: Int, routeIndex: Int = 0): Boolean {
        val rt = sessions[sessionId] ?: return false
        if (rt.session.closed) return false
        val e = rt.engine as? com.meshgames.game.engine.CheckersEngine ?: return false
        val payload = e.commitAt(square, routeIndex) ?: return false
        val seq = sendMsg(rt, MsgType.MOVE, payload)
        journalRecord(rt, JOURNAL_MOVE, rt.session.mySide, payload, seq)
        rt.localActivity++
        refresh()
        return true
    }

    /** Сбросить выбор фигуры без отправки. */
    @Synchronized
    fun checkersCancel(sessionId: Int) {
        (sessions[sessionId]?.engine as? com.meshgames.game.engine.CheckersEngine)?.cancelSelect()
        refresh()
    }

    // --- Балда: спорное слово — вердикт (делегируется движку) ---

    /**
     * Мой вердикт спорному слову соперника: кадр MOVE вне очереди хода
     * (precedент SHOT_RESULT в морском бое). false — нет спора, ждущего
     * моего решения. Принятие может заполнить поле — проверяем завершение.
     */
    @Synchronized
    fun submitVerdict(sessionId: Int, accept: Boolean): Boolean {
        val rt = sessions[sessionId] ?: return false
        val s = rt.session
        if (s.status != SessionStatus.ACTIVE || s.closed) return false
        val engine = rt.engine ?: return false
        if (engine.finished) return false
        val payload = engine.localVerdict(accept) ?: return false
        val seq = sendMsg(rt, MsgType.MOVE, payload)
        journalRecord(rt, JOURNAL_MOVE, s.mySide, payload, seq)
        rt.localActivity++
        checkFinished(rt)
        refresh()
        return true
    }

    /**
     * Балда: слово найдено в словаре движка (мягкая проверка с парными
     * буквами) — UI предупреждает игрока перед отправкой спорного слова
     * (диалог «Отмена/Отправить»).
     */
    fun wordInDictionary(sessionId: Int, word: String): Boolean =
        (sessions[sessionId]?.engine as? com.meshgames.game.engine.BaldaEngine)
            ?.wordInDictionary(word) == true

    /** Текущий выбор в шашках для подсветки: (selected, конечные клетки легальных ходов). */
    @Synchronized
    fun checkersSelection(sessionId: Int): Pair<Int, Set<Int>>? {
        val e = sessions[sessionId]?.engine as? com.meshgames.game.engine.CheckersEngine ?: return null
        return e.selected to e.destinations.toSet()
    }

    /** Дайджест состояния движка (для проверки синхронизации/тестов). */
    @Synchronized
    fun digest(sessionId: Int): Int = sessions[sessionId]?.engine?.digest() ?: 0

    /** Текущее состояние сессии. */
    @Synchronized
    fun session(sessionId: Int): GameSession? = sessions[sessionId]?.session

    /** Отладочный доступ: сколько кадров ещё в расписании повторов. */
    @Synchronized
    fun pendingCount(sessionId: Int): Int = sessions[sessionId]?.channel?.pendingCount() ?: 0

    /** Число записей журнала ходов (события; базовая точка не считается). */
    @Synchronized
    fun journalEvents(sessionId: Int): Int = sessions[sessionId]?.journal?.eventCount ?: -1

    /**
     * Оценка латентности канала с пиру (мс): от первой отправки кадра до
     * прихода piggyback-cumAck пира, его погасившего (см. [ReliableChannel.rttMs]).
     * null — подтверждений ещё не было. Индикатор в UI: чисто информационный,
     * ничем не управляет и ничего не ждёт.
     */
    @Synchronized
    fun rttFlow(sessionId: Int): StateFlow<Long?> =
        sessions[sessionId]?.channel?.rttMs ?: unknownRtt

    companion object {
        /**
         * Таймаут лечения зависшего выстрела (мс): 5 минут. Расписание
         * SHOT/SHOT_RESULT сейчас до 125 с на сторону; типичный худший
         * случай — последняя попытка SHOT (~125 с) плюс латентность канала
         * (в поле наблюдалось до ~141 с) ≈ 266 с, сверх этого результат
         * теряется безвозвратно и ждать больше нечего. Редкий двойной
         * экстремум латентностей (~407 с) даст ложное срабатывание — но это
         * всё равно честный SYNC_CONFLICT, а не вечное ожидание.
         */
        const val PENDING_SHOT_TIMEOUT_MS = 300_000L

        /**
         * Пауза между принудительными синхронизациями (мс): повторное
         * нажатие кнопки быстрее — не плодит дубли SYNC_REQ в эфире
         * (каждый ушёл бы со своим seq и полным расписанием повторов).
         */
        const val FORCED_SYNC_COOLDOWN_MS = 5_000L
    }
}
