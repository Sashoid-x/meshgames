package com.meshgames.game.engine

import com.meshgames.game.DurakPhase
import com.meshgames.game.DurakSnapshot
import com.meshgames.game.GameResult
import com.meshgames.game.MoveInput
import com.meshgames.game.ResultReason
import com.meshgames.game.Side
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Дурак подкидной: детерминизм раздачи от sid, легальность боя (та же
 * масть старше либо козырь), накопление стола (пары «атака/бой» до 6),
 * подкидывание по номиналу любой карты захода с лимитами, взятие ВСЕХ
 * карт захода (атакующий остаётся), авто-бито на 6-й атаке, «бито» со
 * сменой ролей, конец по пустой колоде (дурак/ничья), restore (формат
 * v4, v3 декодируется, старое состояние отклоняется). Состояния крафтятся вручную —
 * правила проверяются без PRNG.
 */
class DurakEngineTest {

    /**
     * Состояние в формате encodeState: STATE_VERSION, phase (0 ATTACK,
     * 1 DEFEND, 2 FINISHED), attacker, winner (2 = нет), reason, trump,
     * nAttacks, карты атаки, бой параллельно (+1, 0 = не забита),
     * taking (только v4), колода (верхняя первая), руки [n, карты…].
     */
    private fun state(
        phase: Int,
        attacker: Side,
        handA: List<Int>,
        handB: List<Int>,
        deck: List<Int> = emptyList(),
        attacks: List<Int> = emptyList(),
        beats: List<Int> = List(attacks.size) { -1 },
        trump: Int = 2, // козырь ♥ если не задан иначе
        winner: Int = 2,
        reason: Int = 0,
        taking: Boolean = false,
        version: Int = DurakEngine.STATE_VERSION,
    ): ByteArray {
        var out = byteArrayOf(
            version.toByte(),
            phase.toByte(), attacker.ordinal.toByte(), winner.toByte(),
            reason.toByte(), trump.toByte(), attacks.size.toByte(),
        )
        for (c in attacks) out += c.toByte()
        for (b in beats) out += (b + 1).toByte()
        if (version >= 4) {
            out += (if (taking) 1 else 0).toByte()
        }
        out += deck.size.toByte()
        for (c in deck) out += c.toByte()
        for (h in listOf(handA, handB)) {
            out += h.size.toByte()
            for (c in h) out += c.toByte()
        }
        return out
    }

    private fun snap(e: DurakEngine) = e.snapshot() as DurakSnapshot

    // 1. Раздача ---------------------------------------------------------

    @Test
    fun dealIdenticalFromSessionId() {
        val a = DurakEngine(Side.A, 777)
        val b = DurakEngine(Side.B, 777)
        val sa = snap(a)
        val sb = snap(b)
        // По 6 карт, колода 24, козырь — нижняя карта колоды (виден обеим).
        assertEquals(6, sa.myHand.size)
        assertEquals(6, sb.myHand.size)
        assertEquals(24, sa.deckCount)
        assertEquals(sa.trump, sb.trump)
        assertEquals(sa.deckCount, sb.deckCount)
        // Начальный атакующий и фаза — одинаковы у обеих сторон.
        assertEquals(sa.attacker, sb.attacker)
        assertEquals(DurakPhase.ATTACK, sa.phase)
        assertEquals(sa.attacker, sa.turn)
        assertEquals(a.digest(), b.digest())
        // Руки отсортированы для каноничности дайджеста.
        assertEquals(sa.myHand.toList(), sa.myHand.sorted().toList())
        // Другой sid — другая раздача (фиксированные сиды, детерминизм).
        val c = DurakEngine(Side.A, 778)
        assertNotEquals(a.digest(), c.digest())
    }

    // 2. Атака, подкидывание, защита --------------------------------------

    @Test
    fun attackPutsCardOnTableAndSwitchesToDefend() {
        // Козырь 2 = 6♥. Атакующий A играет 5 = 7♣.
        val e = DurakEngine(Side.A, 0, state(0, Side.A, listOf(5, 7), listOf(13)))
        val p = e.localMove(MoveInput.DurakAttack(5))
        assertArrayEquals(byteArrayOf(0x01, 5), p)
        val s = snap(e)
        assertEquals(DurakPhase.DEFEND, s.phase)
        assertArrayEquals(intArrayOf(5), s.attacks)
        assertArrayEquals(intArrayOf(-1), s.tableBeats) // ещё не забита
        assertEquals(Side.B, s.turn)
        assertEquals(listOf(7), s.myHand.toList())
    }

    @Test
    fun throwInMatchesRankOfAnyTableCard() {
        // Стол захода: две забитые пары (7♣/9♣ и В♣/Д♣) — фаза атаки.
        val setup = state(0, Side.A, listOf(4), listOf(30),
            attacks = listOf(5, 21), beats = listOf(13, 25))
        // 4 = 7♦: номинал 7 есть на столе (7♣ атакой) — подкидываем.
        val e = DurakEngine(Side.A, 0, setup)
        assertArrayEquals(intArrayOf(4), snap(e).legalThrows)
        val p = e.localMove(MoveInput.DurakAttack(4))!!
        assertArrayEquals(byteArrayOf(0x01, 4), p)
        val s = snap(e)
        assertArrayEquals(intArrayOf(5, 21, 4), s.attacks)   // стол накопился
        assertArrayEquals(intArrayOf(13, 25, -1), s.tableBeats)
        assertEquals(DurakPhase.DEFEND, s.phase)
        // Номинал бой-карты тоже подкидывает: 14 = 9♥ (9♣ = 13 на столе).
        val e2 = DurakEngine(Side.A, 0, state(0, Side.A, listOf(14), listOf(30),
            attacks = listOf(5, 21), beats = listOf(13, 25)))
        assertArrayEquals(intArrayOf(14), snap(e2).legalThrows)
        assertNotNull(e2.localMove(MoveInput.DurakAttack(14)))
        // Номинала 6 на столе нет — 0 = 6♦ не подкидывается.
        val e3 = DurakEngine(Side.A, 0, state(0, Side.A, listOf(0), listOf(30),
            attacks = listOf(5, 21), beats = listOf(13, 25)))
        assertArrayEquals(intArrayOf(), snap(e3).legalThrows)
        assertNull(e3.localMove(MoveInput.DurakAttack(0)))
    }

    @Test
    fun throwInBlockedWhenDefenderHasNoCards() {
        // Защитник отбился последней картой (13 лежит на столе): рук нет —
        // подкидывать нельзя, только «хватит». Колода пуста — после биты
        // защитник выходит, атакующий остаётся дураком.
        val setup = state(0, Side.A, listOf(4), emptyList(),
            attacks = listOf(5), beats = listOf(13))
        val e = DurakEngine(Side.A, 0, setup)
        assertArrayEquals(intArrayOf(), snap(e).legalThrows)
        assertNull(e.localMove(MoveInput.DurakAttack(4)))
        assertNotNull(e.localMove(MoveInput.DurakEnough))
        // B отбился последней картой — вышел; у A осталась карта — дурак.
        assertTrue(e.finished)
        assertEquals(GameResult(Side.B, ResultReason.LAST_WITH_CARDS), e.result)
    }

    @Test
    fun beatSameSuitHigherAcceptedWrongSuitRejected() {
        // 13 = 9♣ бьёт 7♣; 0 = 6♦ другой масти и не козырь — нет.
        val setup = state(1, Side.A, listOf(5), listOf(0, 13), attacks = listOf(5))
        val a = DurakEngine(Side.A, 0, setup)
        val b = DurakEngine(Side.B, 0, setup)
        val s = snap(b)
        // Подсветка: бьёт только 9♣.
        assertArrayEquals(intArrayOf(13), s.legalBeats)
        // Нелегальный бой отклоняется локально (до отправки) и по сети.
        assertNull(b.localMove(MoveInput.DurakBeat(0)))
        assertFalse(a.remoteMove(byteArrayOf(0x02, 0)))
        // Легальный: применяем локально у B и сетевым кадром у A.
        val p = b.localMove(MoveInput.DurakBeat(13))!!
        assertArrayEquals(byteArrayOf(0x02, 13), p)
        assertTrue(a.remoteMove(p))
        assertEquals(a.digest(), b.digest())
        assertEquals(DurakPhase.ATTACK, snap(a).phase)
        assertArrayEquals(intArrayOf(13), snap(a).tableBeats)
    }

    @Test
    fun trumpBeatsNonTrumpAndHigherTrumpBeatsLower() {
        // Козырь 3 = 6♠. Козырь 11 = 8♠ бьёт некозырь 5 = 7♣.
        val s1 = state(1, Side.A, listOf(5), listOf(11), attacks = listOf(5), trump = 3)
        val b1 = DurakEngine(Side.B, 0, s1)
        assertArrayEquals(intArrayOf(11), snap(b1).legalBeats)
        assertNotNull(b1.localMove(MoveInput.DurakBeat(11)))
        // Козырь против козыря — старший: 15 = 9♠ бьёт 8♠, 7♠ (7) не бьёт.
        val s2 = state(1, Side.A, listOf(11), listOf(7, 15), attacks = listOf(11), trump = 3)
        val b2 = DurakEngine(Side.B, 0, s2)
        assertArrayEquals(intArrayOf(15), snap(b2).legalBeats)
        // Некозырь не бьёт козырь: 5 = 7♣ против 11 = 8♠ — нет.
        val s3 = state(1, Side.A, listOf(11), listOf(5), attacks = listOf(11), trump = 3)
        val b3 = DurakEngine(Side.B, 0, s3)
        assertArrayEquals(intArrayOf(), snap(b3).legalBeats)
        assertEquals(MoveInput.DurakTake, b3.botMove())
    }

    @Test
    fun attackRejectedWhenNotYourTurnOrWrongPhase() {
        val e = DurakEngine(Side.A, 0, state(1, Side.A, listOf(5), listOf(13), attacks = listOf(5)))
        // Фаза защиты: атака невозможна; атака соперника (защитника) тоже.
        assertNull(e.localMove(MoveInput.DurakAttack(5)))
        assertFalse(e.remoteMove(byteArrayOf(0x01, 5)))
        // Карта не в руке.
        val e2 = DurakEngine(Side.A, 0, state(0, Side.A, listOf(5), listOf(13)))
        assertNull(e2.localMove(MoveInput.DurakAttack(6)))
    }

    @Test
    fun autoBitoAfterSixthBeat() {
        // Заход на 6 карт: бой 6-й атаки закрывает заход сам, без «хватит».
        val setup = state(1, Side.A, listOf(21), listOf(13, 20), deck = listOf(30),
            attacks = listOf(0, 1, 2, 3, 4, 5), beats = listOf(8, 9, 10, 11, 12, -1))
        val a = DurakEngine(Side.A, 0, setup)
        val b = DurakEngine(Side.B, 0, setup)
        val p = b.localMove(MoveInput.DurakBeat(13))!! // 9♣ бьёт 7♣
        assertArrayEquals(byteArrayOf(0x02, 13), p)
        assertTrue(a.remoteMove(p))
        assertEquals(a.digest(), b.digest())
        val s = snap(b)
        // Авто-бито: весь заход в сбросе, роли поменялись — атакует B.
        assertEquals(0, s.attacks.size)
        assertEquals(Side.B, s.attacker)
        assertEquals(DurakPhase.ATTACK, s.phase)
    }

    // 3. Взятие и добор --------------------------------------------------

    @Test
    fun takeGrantsAllTableCardsAndAttackerStays() {
        // Стол: пара 5(7♣)+13(9♣) забита, 19(10♣) не забита. B берёт ВСЕ
        // три карты захода. Колода [30,31,32]: добирает первый атакующий —
        // A (3 → 6 карт), у B добора не остаётся.
        val setup = state(1, Side.A, listOf(1, 2, 3), listOf(20), deck = listOf(30, 31, 32),
            attacks = listOf(5, 19), beats = listOf(13, -1))
        val a = DurakEngine(Side.A, 0, setup)
        val b = DurakEngine(Side.B, 0, setup)
        val p = b.localMove(MoveInput.DurakTake)!!
        assertArrayEquals(byteArrayOf(0x03), p)
        assertTrue(a.remoteMove(p))
        assertEquals(a.digest(), b.digest())
        assertTrue(snap(a).taking)
        assertTrue(snap(b).taking)
        assertEquals(Side.A, snap(a).turn) // ход у атакующего для подкидывания вдогонку

        // Атакующий A подтверждает окончание подкидывания («Хватит»)
        val pEnough = a.localMove(MoveInput.DurakEnough)!!
        assertArrayEquals(byteArrayOf(0x04), pEnough)
        assertTrue(b.remoteMove(pEnough))
        assertEquals(a.digest(), b.digest())

        val s = snap(b)
        // Атакующий остаётся (защитник теряет ход).
        assertEquals(Side.A, s.attacker)
        assertEquals(DurakPhase.ATTACK, s.phase)
        assertFalse(s.taking)
        // В руку легли и атаки, и забитый бой.
        assertTrue(5 in s.myHand && 13 in s.myHand && 19 in s.myHand)
        assertEquals(6, snap(a).myHand.size)   // A добрал до 6
        assertEquals(4, s.myHand.size)         // B: своя + три взятые
        assertEquals(0, s.deckCount)
        assertEquals(0, s.attacks.size)        // стол очищен
    }

    @Test
    fun takeAllowsAttackerToThrowInPursuitCards() {
        // Ситуация из запроса пользователя: на столе 5 (7♣). У атакующего A есть 6 (7♦) и 7 (7♠) -
        // карты того же ранга 7. Защитник B нажимает «Беру».
        // Теперь атакующий A может выложить 7♦ и 7♠ вдогонку, и только потом нажать «Хватит».
        // У защитника B на руках 3 карты (20, 21, 22), так что лимит рук позволяет принять обе карты.
        val setup = state(1, Side.A, listOf(6, 7), listOf(20, 21, 22), deck = listOf(30, 31, 32), attacks = listOf(5))
        val a = DurakEngine(Side.A, 0, setup)
        val b = DurakEngine(Side.B, 0, setup)

        // B объявляет «беру»
        val pTake = b.localMove(MoveInput.DurakTake)!!
        assertTrue(a.remoteMove(pTake))
        assertTrue(snap(a).taking)
        assertEquals(Side.A, snap(a).turn)

        // A подкидывает первую 7 (6 = 7♦)
        val pThrow1 = a.localMove(MoveInput.DurakAttack(6))!!
        assertArrayEquals(byteArrayOf(0x01, 6), pThrow1)
        assertTrue(b.remoteMove(pThrow1))
        assertEquals(a.digest(), b.digest())
        assertTrue(snap(a).taking)
        assertEquals(Side.A, snap(a).turn) // ход остаётся у A для дальнейших подкидываний!

        // A подкидывает вторую 7 (7 = 7♠)
        val pThrow2 = a.localMove(MoveInput.DurakAttack(7))!!
        assertArrayEquals(byteArrayOf(0x01, 7), pThrow2)
        assertTrue(b.remoteMove(pThrow2))
        assertEquals(a.digest(), b.digest())

        // A нажимает «Хватит»
        val pEnough = a.localMove(MoveInput.DurakEnough)!!
        assertTrue(b.remoteMove(pEnough))
        assertEquals(a.digest(), b.digest())

        val sb = snap(b)
        assertEquals(0, sb.attacks.size)
        assertFalse(sb.taking)
        assertEquals(Side.A, sb.attacker) // A остаётся атакующим
        assertEquals(DurakPhase.ATTACK, sb.phase)
        // B забрал все 3 семёрки (5, 6, 7) + 3 свои карты = 6 карт
        assertEquals(6, sb.myHand.size)
        assertTrue(5 in sb.myHand && 6 in sb.myHand && 7 in sb.myHand)
        // A добрал 3 карты из колоды
        assertEquals(3, snap(a).myHand.size)
    }

    @Test
    fun throwInPursuitLimitedByDefenderHandSize() {
        // У защитника B всего 1 карта на руках (20). На столе уже лежит 1 незабитая атака 5.
        // Защитник B говорит «Беру».
        // Больше подкинуть вдогонку нельзя (unbeatCount == defenderHand.size == 1).
        val setup = state(1, Side.A, listOf(6, 7), listOf(20), attacks = listOf(5))
        val a = DurakEngine(Side.A, 0, setup)
        val b = DurakEngine(Side.B, 0, setup)

        val pTake = b.localMove(MoveInput.DurakTake)!!
        assertTrue(a.remoteMove(pTake))
        assertTrue(snap(a).taking)
        // Лимит исчерпан: legalThrows пуст, попытка подкинуть отвергается
        assertArrayEquals(intArrayOf(), snap(a).legalThrows)
        assertNull(a.localMove(MoveInput.DurakAttack(6)))

        // Единственное разрешённое действие атакующего — «Хватит»
        val pEnough = a.localMove(MoveInput.DurakEnough)!!
        assertTrue(b.remoteMove(pEnough))
        assertEquals(a.digest(), b.digest())
        assertEquals(2, snap(b).myHand.size) // 20 + 5
    }

    @Test
    fun v3StateDecodesCorrectly() {
        // Обратная совместимость с v3: без байта taking
        val setupV3 = state(1, Side.A, listOf(5), listOf(13), attacks = listOf(5), version = 3)
        val a = DurakEngine(Side.A, 0, setupV3)
        assertFalse(snap(a).taking)
        assertEquals(DurakPhase.DEFEND, snap(a).phase)
        assertEquals(Side.B, snap(a).turn)
    }

    // 4. «Хватит» — бито --------------------------------------------------

    @Test
    fun enoughAfterBeatDiscardsAndRotatesRoles() {
        val setup = state(0, Side.A, listOf(10, 11), listOf(12, 15), deck = listOf(30, 31),
            attacks = listOf(5), beats = listOf(13))
        val a = DurakEngine(Side.A, 0, setup)
        val b = DurakEngine(Side.B, 0, setup)
        val p = a.localMove(MoveInput.DurakEnough)!!
        assertArrayEquals(byteArrayOf(0x04), p)
        assertTrue(b.remoteMove(p))
        assertEquals(a.digest(), b.digest())
        val s = snap(a)
        assertEquals(Side.B, s.attacker)   // отбившийся атакует следующим
        assertEquals(DurakPhase.ATTACK, s.phase)
        assertEquals(0, s.attacks.size)    // весь заход в сбросе
        assertEquals(4, s.myHand.size)     // A добрал обе карты колоды (2→4)
        assertEquals(2, snap(b).myHand.size)
        assertEquals(0, s.deckCount)
    }

    @Test
    fun enoughIllegalWithoutBeatenAttack() {
        // Свежий заход — надо атаковать; небитая атака — рано.
        val e = DurakEngine(Side.A, 0, state(1, Side.A, listOf(10), listOf(11), attacks = listOf(5)))
        assertNull(e.localMove(MoveInput.DurakEnough))
        val e2 = DurakEngine(Side.A, 0, state(0, Side.A, listOf(10), listOf(11)))
        assertNull(e2.localMove(MoveInput.DurakEnough))
        // Защитник «хватит» не говорит.
        assertFalse(e2.remoteMove(byteArrayOf(0x04)))
    }

    // 5. Конец партии ----------------------------------------------------

    @Test
    fun deckEmptyLastWithCardsLoses() {
        // Колода пуста: A вышел (руна пуста), у B карты — после «биты»
        // B остаётся дураком.
        val setup = state(0, Side.A, emptyList(), listOf(12),
            attacks = listOf(5), beats = listOf(13))
        val a = DurakEngine(Side.A, 0, setup)
        val b = DurakEngine(Side.B, 0, setup)
        assertTrue(a.localMove(MoveInput.DurakEnough) != null)
        assertTrue(b.remoteMove(byteArrayOf(0x04)))
        assertEquals(a.digest(), b.digest())
        assertTrue(a.finished && b.finished)
        assertEquals(GameResult(Side.A, ResultReason.LAST_WITH_CARDS), a.result)
        assertEquals(a.result, b.result)
        // После конца ходов нет.
        assertNull(a.localMove(MoveInput.DurakEnough))
        assertNull(a.botMove())
    }

    @Test
    fun bothOutIsDraw() {
        val setup = state(0, Side.A, emptyList(), emptyList(),
            attacks = listOf(5), beats = listOf(13))
        val a = DurakEngine(Side.A, 0, setup)
        val b = DurakEngine(Side.B, 0, setup)
        assertNotNull(a.localMove(MoveInput.DurakEnough))
        assertTrue(b.remoteMove(byteArrayOf(0x04)))
        assertTrue(a.finished)
        assertEquals(GameResult(null, ResultReason.BOTH_OUT), a.result)
        assertEquals(a.digest(), b.digest())
    }

    // 6. Формат кадров ---------------------------------------------------

    @Test
    fun remoteRejectsMalformedPayloads() {
        val e = DurakEngine(Side.B, 0, state(1, Side.A, listOf(5), listOf(13), attacks = listOf(5)))
        assertFalse(e.remoteMove(byteArrayOf()))          // пусто
        assertFalse(e.remoteMove(byteArrayOf(0x01)))      // атака без карты
        assertFalse(e.remoteMove(byteArrayOf(0x01, 5, 9))) // лишние байты
        assertFalse(e.remoteMove(byteArrayOf(0x02)))      // бой без карты
        assertFalse(e.remoteMove(byteArrayOf(0x03, 5)))   // беру с мусором
        assertFalse(e.remoteMove(byteArrayOf(0x77)))      // неизвестный kind
        // Атака возможна только в фазе атаки — из фазы защиты не принимается.
        assertFalse(e.remoteMove(byteArrayOf(0x01, 13)))
        // Действие не той стороны: в атаке B защитник атаковать не может.
        val e2 = DurakEngine(Side.B, 0, state(0, Side.B, listOf(13), listOf(5)))
        assertFalse(e2.remoteMove(byteArrayOf(0x01, 13)))
    }

    // 7. Restore ---------------------------------------------------------

    @Test
    fun restoreContinuesMidGame() {
        val setup = state(1, Side.A, listOf(7, 10), listOf(13, 20), deck = listOf(30),
            attacks = listOf(5))
        val a = DurakEngine(Side.A, 0, setup)
        val b = DurakEngine(Side.B, 0, setup)
        val p = b.localMove(MoveInput.DurakBeat(13))!!
        assertTrue(a.remoteMove(p))
        // Оба рестартуют с состояния — дайджесты и партия продолжаются.
        val a2 = DurakEngine(Side.A, 0, a.encodeState())
        val b2 = DurakEngine(Side.B, 0, b.encodeState())
        assertEquals(a.digest(), a2.digest())
        assertEquals(b.digest(), b2.digest())
        assertEquals(DurakPhase.ATTACK, snap(a2).phase)
        // A подкидывает после рестарта: 7 = 7♠ того же номинала, что 7♣.
        assertArrayEquals(intArrayOf(7), snap(a2).legalThrows)
        val p2 = a2.localMove(MoveInput.DurakAttack(7))!!
        assertTrue(b2.remoteMove(p2))
        assertEquals(a2.digest(), b2.digest())
    }

    @Test
    fun legacyStateIsRejected() {
        // Состояние прежних правил (до подкидывания): байт 0 = фаза 0..2 —
        // движок обязан его отклонить (создаётся заново из sid).
        val legacy = byteArrayOf(0, 0, 2, 0, 0, 0, 2, 0, 6, 6, 1, 5)
        assertThrows { DurakEngine(Side.A, 0, legacy) }
    }

    @Test
    fun finishedStateSurvivesRestore() {
        val setup = state(2, Side.A, emptyList(), listOf(12),
            attacks = listOf(5), beats = listOf(13),
            winner = 0, reason = ResultReason.LAST_WITH_CARDS.ordinal)
        val a = DurakEngine(Side.A, 0, setup)
        assertTrue(a.finished)
        val a2 = DurakEngine(Side.A, 0, a.encodeState())
        assertTrue(a2.finished)
        assertEquals(GameResult(Side.A, ResultReason.LAST_WITH_CARDS), a2.result)
        assertEquals(a.digest(), a2.digest())
    }

    // 8. Бот -------------------------------------------------------------

    @Test
    fun botVsBotFinishesGame() {
        val a = DurakEngine(Side.A, 4242)
        val b = DurakEngine(Side.B, 4242)
        var made = 0
        while (!a.finished && made < 600) {
            // Ходит сторона, чей turn (атакующий может атаковать и
            // подкидывать несколько раз подряд — поэтому по turn).
            val mover = if (a.turn == Side.A) a else b
            val other = if (a.turn == Side.A) b else a
            val input = mover.botMove() ?: break
            val payload = mover.localMove(input)
                ?: error("бот предложил нелегальный ход: $input")
            made++
            assertTrue("ход $made отклонён соперником", other.remoteMove(payload))
            assertEquals("дайджесты разошлись после хода $made", a.digest(), b.digest())
        }
        assertTrue("партия не завершилась за $made ходов", a.finished)
        assertNotNull("нет итога", a.result)
        assertEquals(a.finished, b.finished)
        assertEquals(a.digest(), b.digest())
    }

    @Test
    fun botBeatsCheapestOrTakes() {
        // Бот-защитник: бьёт минимальной легальной картой, иначе берёт.
        val setup = state(1, Side.A, listOf(5), listOf(13, 14, 21), attacks = listOf(5))
        val b = DurakEngine(Side.B, 0, setup)
        assertEquals(MoveInput.DurakBeat(13), b.botMove())
        val setup2 = state(1, Side.A, listOf(11), listOf(0, 1), attacks = listOf(11), trump = 3)
        assertEquals(MoveInput.DurakTake, DurakEngine(Side.B, 0, setup2).botMove())
    }

    @Test
    fun botEnoughWhenHandEmptyAndTableBeaten() {
        // Регресс: атакующий выложил всю руну, заход забит — говорит
        // «хватит» (а не зависает с null). Колода непуста — партия живёт.
        val setup = state(0, Side.A, emptyList(), listOf(30),
            attacks = listOf(5), beats = listOf(13), deck = listOf(34, 35))
        val a = DurakEngine(Side.A, 0, setup)
        assertEquals(MoveInput.DurakEnough, a.botMove())
        assertNotNull(a.localMove(MoveInput.DurakEnough))
        assertFalse(a.finished)
        // A добрал обе карты колоды, атаку перехватил защитившийся B.
        assertEquals(2, snap(a).myHand.size)
        assertEquals(Side.B, snap(a).attacker)
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
            throw AssertionError("ожидалось исключение")
        } catch (_: IllegalStateException) {
            // createRuntime перехватывает битое состояние
        } catch (_: IllegalArgumentException) {
            // прямое создание из теста бросает именно его
        }
    }
}
