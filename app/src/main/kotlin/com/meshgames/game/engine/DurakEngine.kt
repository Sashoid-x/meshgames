package com.meshgames.game.engine

import com.meshgames.game.DurakPhase
import com.meshgames.game.DurakSnapshot
import com.meshgames.game.GameEngine
import com.meshgames.game.GameResult
import com.meshgames.game.GameType
import com.meshgames.game.MoveInput
import com.meshgames.game.ResultReason
import com.meshgames.game.Side

/**
 * Дурак на двоих, колода 36, ПОДКИДНОЙ (канон правил — ru/en Wikipedia:
 * «Дурак»/«Durak»):
 *
 *  - раздача детерминирована от sessionId (xorshift32 — как стартовое
 *    слово балды): обе стороны считают одну и ту же раздачу, скрытость —
 *    только уровень UI (чужая руна не рисуется);
 *  - по 6 карт, козырь — нижняя карта колоды (виден обеим сторонам);
 *  - начальный атакующий — у кого младший козырь (равенство — младшая
 *    карта вообще);
 *  - стол захода **накапливает пары** «атака/бой»: свежий заход — любая
 *    карта, подкидывание — по номиналу ЛЮБОЙ карты захода (и атаки, и
 *    боя), только от атакующего, пока attacks < 6 и защитнику есть чем
 *    ответить (его рука не пуста);
 *  - защита — той же масти старше либо козырь (козырный туз не бьётся —
 *    старшая карта и так непобедима); защитник бьёт последнюю (небитую)
 *    карту или берёт ВСЕ карты захода (атаку + бой);
 *  - бой 6-й атаки закрывает заход сам (авто-бито, без «хватит»);
 *  - «хватит» → «бито» (только когда все забиты): весь заход в сброс,
 *    оба добирают до 6 (первым атакующий), роли меняются местами —
 *    атакующий следующим становится защитник;
 *  - взятие: берущий ЗАБИРАЕТ ВСЕ карты захода, атакующий остаётся
 *    (en-wiki: «defender loses their turn», атака — игрок слева от
 *    защитника = атакующий); добор: первым атакующий, последним берущий;
 *  - колода кончилась: в точке бито/взятия игрок без карт вышел; последний
 *    с картами — «дурак» (LAST_WITH_CARDS), оба вышли сразу — ничья
 *    (BOTH_OUT).
 *
 * Payload (кадры MOVE): атака/подкидывание `[0x01][card]`, бить `[0x02][card]`,
 * беру `[0x03]`, хватит `[0x04]`; card = rank*4 + suit (0..35).
 *
 * State v4 (`STATE_VERSION = 4`; байт 0 у старого формата = фаза 0..2,
 * поэтому версия стартует с 3 — прежнее состояние распознаётся и
 * отклоняется; v3 декодируется с `taking=false`):
 * [0] STATE_VERSION, [1] phase (0=ATTACK,1=DEFEND,2=FINISHED),
 * [2] attacker (0/1), [3] winner (0,1,2=нет), [4] reason, [5] trump,
 * [6] nAttacks, карты атаки (n), бой параллельно (+1, 0 = не забита),
 * [taking] (только v4), [deckCount], колода (верхняя первая),
 * руки: [n, карты…] A, затем B. Лог в состояние не входит (как у балды).
 */
class DurakEngine(
    override val mySide: Side,
    sessionId: Int = 0,
    state: ByteArray? = null,
) : GameEngine {

    override val type: GameType = GameType.DURAK

    // ------------------------------------------------------ состояние ---

    private var phase = DurakPhase.ATTACK
    private var attacker = Side.A
    private var taking = false
    private var resultFlag: GameResult? = null
    /** Карты атаки захода по порядку (лежат на столе до конца захода). */
    private val attacks = ArrayList<Int>()
    /** Бой параллельно [attacks]: -1 — ещё не забита. */
    private val beats = ArrayList<Int>()
    private var trumpCard = 0
    private val deck = ArrayList<Int>()
    private val handA = ArrayList<Int>()
    private val handB = ArrayList<Int>()
    private val logList = ArrayList<String>()
    private var rng = 0

    private fun hand(side: Side) = if (side == Side.A) handA else handB

    init {
        if (state != null && state.size >= MIN_STATE) {
            decodeState(state)
        } else {
            deal(sessionId)
        }
    }

    override val turn: Side?
        get() = when {
            phase == DurakPhase.FINISHED -> null
            taking -> attacker
            phase == DurakPhase.ATTACK -> attacker
            phase == DurakPhase.DEFEND -> attacker.other
            else -> null
        }

    override val finished: Boolean get() = phase == DurakPhase.FINISHED
    override val result: GameResult? get() = resultFlag
    override val log: List<String> get() = logList

    override fun snapshot() = DurakSnapshot(
        myHand = hand(mySide).toIntArray(),
        peerHandCount = hand(mySide.other).size,
        deckCount = deck.size,
        trump = trumpCard,
        attacks = attacks.toIntArray(),
        tableBeats = beats.toIntArray(),
        attacker = attacker,
        phase = phase,
        legalBeats = if (myDefending()) legalBeats() else intArrayOf(),
        legalThrows = if (myAttacking()) legalThrowsFor(mySide) else intArrayOf(),
        turn = turn,
        finished = finished,
        result = resultFlag,
        log = logList.toList(),
        taking = taking,
    )

    // ------------------------------------------------------------ ходы ---

    override fun localMove(input: MoveInput): ByteArray? = when (input) {
        is MoveInput.DurakAttack -> applyAction(mySide, KIND_ATTACK, input.card)
        is MoveInput.DurakBeat -> applyAction(mySide, KIND_BEAT, input.card)
        MoveInput.DurakTake -> applyAction(mySide, KIND_TAKE, -1)
        MoveInput.DurakEnough -> applyAction(mySide, KIND_ENOUGH, -1)
        else -> null
    }

    override fun remoteMove(payload: ByteArray): Boolean {
        if (payload.isEmpty()) return false
        val side = mySide.other
        return when (val kind = payload[0].toInt() and 0xFF) {
            KIND_ATTACK, KIND_BEAT -> {
                if (payload.size != 2) return false
                applyAction(side, kind, payload[1].toInt() and 0xFF) != null
            }
            KIND_TAKE, KIND_ENOUGH -> {
                if (payload.size != 1) return false
                applyAction(side, kind, -1) != null
            }
            else -> false
        }
    }

    /**
     * Применение действия [side]: валидация фазы/актора/карт одинакова на
     * обеих сторонах; null — нелегально (локальный не уходит в эфир,
     * полученный отклоняется).
     */
    private fun applyAction(side: Side, kind: Int, card: Int): ByteArray? {
        if (finished) return null
        val hand = hand(side)
        when (kind) {
            KIND_ATTACK -> {
                if (attacker != side) return null
                if (attacks.size >= MAX_ATTACKS) return null
                val defenderHand = hand(attacker.other)
                if (defenderHand.isEmpty()) return null

                val unbeatCount = beats.count { it == -1 }

                val isAllowed = when {
                    attacks.isEmpty() -> {
                        // Свежий заход: любая карта из руки
                        phase == DurakPhase.ATTACK
                    }
                    taking -> {
                        // Защитник берёт: подкидывание вдогонку
                        // Номинал любой карты на столе (атаки и боя)
                        // Лимит: защитнику нельзя подкинуть больше карт, чем у него в руке
                        if (unbeatCount >= defenderHand.size) return null
                        val ranks = (attacks + beats).filter { it >= 0 }.map { it / 4 }.toSet()
                        card / 4 in ranks
                    }
                    beats.all { it >= 0 } -> {
                        // Все предыдущие атаки забиты: подкидывание нового номинала со стола
                        if (phase != DurakPhase.ATTACK) return null
                        if (unbeatCount >= defenderHand.size) return null
                        val ranks = (attacks + beats).filter { it >= 0 }.map { it / 4 }.toSet()
                        card / 4 in ranks
                    }
                    else -> {
                        // На столе есть незабитые карты: можно подкинуть карту того же номинала,
                        // что и начальная атака (атака веером нескольких карт одного ранга)
                        if (unbeatCount >= defenderHand.size) return null
                        val initialRank = attacks.first() / 4
                        card / 4 == initialRank
                    }
                }

                if (!isAllowed) return null
                if (!hand.remove(card)) return null

                attacks.add(card)
                beats.add(-1)
                if (!taking) {
                    phase = DurakPhase.DEFEND
                }
                logList.add(
                    if (attacks.size == 1) "атака $side: ${cardName(card)}"
                    else if (taking) "вдогонку $side: ${cardName(card)}"
                    else "подкидываю $side: ${cardName(card)}"
                )
                return byteArrayOf(KIND_ATTACK.toByte(), card.toByte())
            }
            KIND_BEAT -> {
                if (attacker == side) return null
                if (taking) return null // нельзя бить, если уже объявил «беру»
                if (attacks.isEmpty()) return null

                val targetIndex = findTargetAttack(card)
                if (targetIndex < 0) return null
                if (!hand.remove(card)) return null

                beats[targetIndex] = card
                logList.add("бой $side: ${cardName(card)} бьёт ${cardName(attacks[targetIndex])}")

                val allBeaten = beats.none { it == -1 }
                if (allBeaten) {
                    if (attacks.size >= MAX_ATTACKS) {
                        // Бой 6-й атаки закрывает заход автоматически — без «хватит»
                        resolveBito()
                        return byteArrayOf(KIND_BEAT.toByte(), card.toByte())
                    }
                    // Все карты забиты — ход возвращается атакующему для подкидывания или «хватит»
                    phase = DurakPhase.ATTACK
                } else {
                    // Ещё остались незабитые карты на столе — защитник продолжает отбиваться
                    phase = DurakPhase.DEFEND
                }
                return byteArrayOf(KIND_BEAT.toByte(), card.toByte())
            }
            KIND_TAKE -> {
                if (attacker == side) return null
                if (taking) return null // уже берёт
                if (attacks.isEmpty()) return null
                if (beats.all { it >= 0 }) return null // все карты уже забиты

                taking = true
                phase = DurakPhase.ATTACK
                logList.add("беру $side: соперник может подкинуть вдогонку")
                return byteArrayOf(KIND_TAKE.toByte())
            }
            KIND_ENOUGH -> {
                if (attacker != side) return null
                if (attacks.isEmpty()) return null

                if (taking) {
                    // Защитник брал — атакующий закончил подкидывать вдогонку
                    resolveTake()
                    return byteArrayOf(KIND_ENOUGH.toByte())
                } else {
                    // Все карты захода забиты — «бито»
                    if (beats.any { it == -1 }) return null // есть незабитые карты
                    resolveBito()
                    return byteArrayOf(KIND_ENOUGH.toByte())
                }
            }
        }
        return null
    }

    /** Взятие: защитник забирает все карты стола, добор до 6, атакующий остаётся. */
    private fun resolveTake() {
        val defender = attacker.other
        val defHand = hand(defender)
        val taken = ArrayList<Int>()
        for (i in attacks.indices) {
            taken.add(attacks[i])
            if (beats[i] >= 0) taken.add(beats[i])
        }
        defHand.addAll(taken)
        defHand.sort()
        logList.add("взял $defender: +${taken.size} карт со стола")
        attacks.clear()
        beats.clear()
        taking = false
        drawToSix(attacker)       // первым атакующий
        drawToSix(defender)       // затем берущий (защитник)
        if (checkEnd()) return

        // en-wiki: защитник теряет ход — атакующий остаётся
        phase = DurakPhase.ATTACK
    }

    /** Бито: весь заход в сброс, добор (первым атакующий), роли меняются. */
    private fun resolveBito() {
        logList.add("бито: ${attacks.size} карт в сброс")
        attacks.clear()
        beats.clear()
        taking = false
        drawToSix(attacker)
        drawToSix(attacker.other)
        if (checkEnd()) return
        attacker = attacker.other
        phase = DurakPhase.ATTACK
    }

    override fun botMove(): MoveInput? {
        if (finished) return null
        return when {
            attacker == mySide && taking -> {
                // Защитник берёт: подкидываем некозыри вдогонку, иначе «хватит»
                val ts = trumpSuit()
                val throws = legalThrowsFor(mySide)
                val nonTrumpThrows = throws.filter { suitOf(it) != ts }
                if (nonTrumpThrows.isNotEmpty()) {
                    MoveInput.DurakAttack(nonTrumpThrows.min())
                } else {
                    MoveInput.DurakEnough
                }
            }
            attacker == mySide && phase == DurakPhase.ATTACK -> {
                if (attacks.isEmpty()) {
                    val hand = hand(mySide)
                    if (hand.isEmpty()) return null
                    val ts = trumpSuit()
                    val card = hand.filter { suitOf(it) != ts }.minOrNull() ?: hand.min()
                    MoveInput.DurakAttack(card)
                } else {
                    val ts = trumpSuit()
                    val throws = legalThrowsFor(mySide)
                    val nonTrumpThrows = throws.filter { suitOf(it) != ts }
                    if (nonTrumpThrows.isNotEmpty()) {
                        MoveInput.DurakAttack(nonTrumpThrows.min())
                    } else {
                        MoveInput.DurakEnough
                    }
                }
            }
            attacker != mySide && !taking && phase == DurakPhase.DEFEND -> {
                val beats = legalBeats()
                if (beats.isNotEmpty()) MoveInput.DurakBeat(beats.first()) else MoveInput.DurakTake
            }
            else -> null
        }
    }

    // --------------------------------------------------------- утилиты ---

    private fun trumpSuit(): Int = trumpCard % 4
    private fun suitOf(card: Int): Int = card % 4
    private fun rankOf(card: Int): Int = card / 4

    /**
     * Индекс незабитой атаки на столе, которую бьёт [beatCard].
     * Приоритет:
     * 1. Незабитая атака той же масти, которую [beatCard] бьёт по достоинству.
     * 2. Если [beatCard] — козырь, незабитая козырная атака младше.
     * 3. Если [beatCard] — козырь, первая незабитая некозырная атака.
     */
    private fun findTargetAttack(beatCard: Int): Int {
        val ts = trumpSuit()
        val bs = suitOf(beatCard)
        val br = rankOf(beatCard)

        // 1. Ищем незабитую карту той же масти
        for (i in attacks.indices) {
            if (beats[i] == -1 && suitOf(attacks[i]) == bs && br > rankOf(attacks[i])) {
                return i
            }
        }
        // 2. Если козырь — бьём младший козырь
        if (bs == ts) {
            for (i in attacks.indices) {
                if (beats[i] == -1 && suitOf(attacks[i]) == ts && br > rankOf(attacks[i])) {
                    return i
                }
            }
            // 3. Если козырь — бьём первую некозырную
            for (i in attacks.indices) {
                if (beats[i] == -1 && suitOf(attacks[i]) != ts) {
                    return i
                }
            }
        }
        return -1
    }

    /** Бьёт ли [beat] карту атаки [attack]: та же масть старше либо козырь. */
    private fun beats(beat: Int, attack: Int): Boolean = when {
        suitOf(beat) == suitOf(attack) -> rankOf(beat) > rankOf(attack)
        suitOf(beat) == trumpSuit() -> true
        else -> false
    }

    private fun myDefending(): Boolean =
        !taking && phase == DurakPhase.DEFEND && attacker != mySide

    private fun myAttacking(): Boolean =
        (taking || phase == DurakPhase.ATTACK) && attacker == mySide

    private fun legalBeats(): IntArray {
        if (taking || attacks.isEmpty()) return intArrayOf()
        return hand(mySide).filter { findTargetAttack(it) >= 0 }.sorted().toIntArray()
    }

    /**
     * Что можно подкиднуть [side]:
     * - при taking == true: любые карты достоинства со стола (атаки и боя), пока unbeatCount < defenderHand.size и attacks < 6;
     * - при beats.all { it >= 0 }: аналогично, любые карты достоинства со стола;
     * - при наличии незабитых атак: карты ТОГО ЖЕ достоинства, что и начальная атака (атака несколькими картами одного ранга),
     *   пока unbeatCount < defenderHand.size и attacks < 6.
     */
    private fun legalThrowsFor(side: Side): IntArray {
        if (attacker != side) return intArrayOf()
        if (attacks.isEmpty()) return intArrayOf()
        if (attacks.size >= MAX_ATTACKS) return intArrayOf()
        val defenderHand = hand(attacker.other)
        if (defenderHand.isEmpty()) return intArrayOf()

        val unbeatCount = beats.count { it == -1 }
        if (unbeatCount >= defenderHand.size) return intArrayOf()

        val ranks = when {
            taking || beats.all { it >= 0 } -> {
                (attacks + beats).filter { it >= 0 }.map { it / 4 }.toSet()
            }
            else -> {
                setOf(attacks.first() / 4)
            }
        }
        return hand(side).filter { it / 4 in ranks }.sorted().toIntArray()
    }

    /** Добор до 6: первым атакующий, рука остаётся отсортированной. */
    private fun drawToSix(side: Side) {
        val h = hand(side)
        var drew = 0
        while (h.size < 6 && deck.isNotEmpty()) {
            h.add(deck.removeAt(0))
            drew++
        }
        if (drew > 0) logList.add("добор $side: $drew")
        h.sort()
    }

    /** Конец партии в точке бито/взятия, когда колода пуста. */
    private fun checkEnd(): Boolean {
        if (deck.isNotEmpty()) return false
        return when {
            handA.isEmpty() && handB.isEmpty() ->
                finish(null, ResultReason.BOTH_OUT)
            handA.isEmpty() -> finish(Side.A, ResultReason.LAST_WITH_CARDS)
            handB.isEmpty() -> finish(Side.B, ResultReason.LAST_WITH_CARDS)
            else -> false
        }
    }

    private fun finish(winner: Side?, reason: ResultReason): Boolean {
        resultFlag = GameResult(winner, reason)
        phase = DurakPhase.FINISHED
        logList.add(
            if (winner == null) "конец: оба вышли — ничья"
            else "конец: дурак — ${winner.other}",
        )
        return true
    }

    // --------------------------------------------------------- дайджест ---

    override fun digest(): Int {
        var h = 0x47B1
        h = h * 31 + phase.ordinal
        h = h * 31 + attacker.ordinal
        h = h * 31 + (if (taking) 1 else 0)
        for (c in attacks) h = h * 31 + c
        for (c in beats) h = h * 31 + c
        h = h * 31 + trumpCard
        for (c in deck) h = h * 31 + c
        for (c in handA) h = h * 31 + c
        for (c in handB) h = h * 31 + c
        resultFlag?.let { r ->
            h = h * 31 + (r.winner?.ordinal ?: 2)
            h = h * 31 + r.reason.ordinal
        }
        return h and 0xFFFF
    }

    // ------------------------------------------------- encode/decode -----

    override fun encodeState(): ByteArray {
        var out = byteArrayOf(
            STATE_VERSION.toByte(),
            phase.ordinal.toByte(),
            attacker.ordinal.toByte(),
            (resultFlag?.winner?.ordinal ?: 2).toByte(),
            (resultFlag?.reason?.ordinal ?: 0).toByte(),
            trumpCard.toByte(),
            attacks.size.toByte(),
        )
        for (c in attacks) out += c.toByte()
        for (b in beats) out += (b + 1).toByte() // -1 → 0, карта → 1..36
        out += (if (taking) 1 else 0).toByte()
        out += deck.size.toByte()
        for (c in deck) out += c.toByte()
        for (h in listOf(handA, handB)) {
            out += h.size.toByte()
            for (c in h) out += c.toByte()
        }
        return out
    }

    private fun decodeState(b: ByteArray) {
        if (b.size < MIN_STATE) throw IllegalArgumentException("durak state too short")
        val ver = b[0].toInt()
        // Старый формат (до подкидывания): байт 0 — фаза 0..2.
        if (ver in 0..2) throw IllegalArgumentException("durak state legacy")
        if (ver != 3 && ver != STATE_VERSION) throw IllegalArgumentException("durak state version")
        val ph = b[1].toInt()
        val win = b[3].toInt()
        val reason = b[4].toInt()
        val trump = b[5].toInt()
        val n = b[6].toInt()
        if (ph !in 0..2 || win !in 0..2 || trump !in 0 until DECK_SIZE ||
            reason !in ResultReason.entries.indices || n !in 0..MAX_ATTACKS
        ) {
            throw IllegalArgumentException("durak state corrupt")
        }
        var i = 7
        if (i + 2 * n > b.size) throw IllegalArgumentException("durak table truncated")
        val at = IntArray(n) { b[i + it].toInt() and 0xFF }
        i += n
        val bt = IntArray(n) { (b[i + it].toInt() and 0xFF) - 1 }
        i += n
        if (at.any { it !in 0 until DECK_SIZE } || bt.any { it !in -1 until DECK_SIZE }) {
            throw IllegalArgumentException("durak table corrupt")
        }
        val isTaking = if (ver >= 4) {
            if (i >= b.size) throw IllegalArgumentException("durak taking truncated")
            b[i++].toInt() != 0
        } else {
            false
        }
        val unbeat = bt.count { it == -1 }
        when (ph) {
            0 -> if (!isTaking && unbeat != 0) throw IllegalArgumentException("durak table phase")
            1 -> if (unbeat == 0) throw IllegalArgumentException("durak table phase")
        }
        phase = DurakPhase.entries[ph]
        taking = isTaking
        attacker = if (b[2].toInt() == 0) Side.A else Side.B
        resultFlag = if (ph == 2) {
            GameResult(if (win == 2) null else if (win == 0) Side.A else Side.B,
                ResultReason.entries[reason])
        } else {
            null
        }
        attacks.addAll(at.asList())
        beats.addAll(bt.asList())
        trumpCard = trump
        val deckCount = b[i++].toInt() and 0xFF
        if (deckCount > DECK_SIZE) throw IllegalArgumentException("durak deck corrupt")
        deck.addAll(IntArray(deckCount) { b[i + it].toInt() and 0xFF }.asList())
        i += deckCount
        for (h in listOf(handA, handB)) {
            if (i >= b.size) throw IllegalArgumentException("durak hands truncated")
            val hn = b[i++].toInt() and 0xFF
            if (i + hn > b.size) throw IllegalArgumentException("durak hands truncated")
            h.addAll(IntArray(hn) { b[i + it].toInt() and 0xFF }.asList())
            i += hn
        }
    }

    // ----------------------------------------------------- раздача ------

    private fun deal(sessionId: Int) {
        rng = if (sessionId != 0) sessionId else 0x1A2B3C4D
        val codes = (0 until DECK_SIZE).toMutableList()
        for (i in codes.size - 1 downTo 1) {
            val j = nextInt(i + 1)
            val t = codes[i]
            codes[i] = codes[j]
            codes[j] = t
        }
        handA.addAll(codes.subList(0, 6))
        handB.addAll(codes.subList(6, 12))
        handA.sort()
        handB.sort()
        deck.addAll(codes.subList(12, DECK_SIZE))
        trumpCard = deck.last()
        attacker = initialAttacker()
        phase = DurakPhase.ATTACK
        taking = false
        logList.add("раздача: козырь ${SUIT_NAMES[trumpSuit()]}, атакует $attacker")
    }

    /** xorshift32 от сида партии: одна и та же раздача у обеих сторон. */
    private fun nextInt(bound: Int): Int {
        var x = rng
        x = x xor (x shl 13)
        x = x xor (x ushr 17)
        x = x xor (x shl 5)
        rng = x
        return (x ushr 1) % bound
    }

    /** Начальный атакующий: младший козырь, равенство — младшая карта вообще. */
    private fun initialAttacker(): Side {
        val ts = trumpSuit()
        fun lowestTrump(h: List<Int>) =
            h.filter { suitOf(it) == ts }.minOrNull() ?: Int.MAX_VALUE
        val ta = lowestTrump(handA)
        val tb = lowestTrump(handB)
        if (ta != tb) return if (ta < tb) Side.A else Side.B
        return if (handA.first() <= handB.first()) Side.A else Side.B
    }

    companion object {
        const val KIND_ATTACK = 0x01
        const val KIND_BEAT = 0x02
        const val KIND_TAKE = 0x03
        const val KIND_ENOUGH = 0x04

        const val DECK_SIZE = 36
        /** Максимум атак в заходе: бой 6-й закрывает заход автоматически. */
        const val MAX_ATTACKS = 6
        /** Версия формата состояния (0..2 заняты фазами формата v1). */
        const val STATE_VERSION = 4
        const val MIN_STATE = 10

        val RANK_NAMES = arrayOf("6", "7", "8", "9", "10", "В", "Д", "К", "Т")
        val SUIT_NAMES = arrayOf("♦", "♣", "♥", "♠")

        /** Код карты: rank*4 + suit (rank 0..8 = 6..Т, suit 0..3). */
        fun cardName(card: Int): String =
            if (card !in 0 until DECK_SIZE) "?" else RANK_NAMES[card / 4] + SUIT_NAMES[card % 4]
    }
}
