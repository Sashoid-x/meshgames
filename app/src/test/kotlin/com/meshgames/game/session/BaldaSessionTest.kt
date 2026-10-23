package com.meshgames.game.session

import com.meshgames.game.BaldaSnapshot
import com.meshgames.game.GameResult
import com.meshgames.game.GameType
import com.meshgames.game.MoveInput
import com.meshgames.game.ResultReason
import com.meshgames.game.Side
import com.meshgames.game.engine.BaldaEngine
import com.meshgames.game.engine.EmptyWordChecker
import com.meshgames.game.engine.GameEngines
import com.meshgames.game.engine.WordChecker
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Балда на PairNetwork: приглашение, ходы по сети, спор (вердикт кадром
 * MOVE), пропуски до поражения, восстановление спора после рестарта.
 */
class BaldaSessionTest {

    private class FakeWords(words: Collection<String>) : WordChecker {
        private val set = words.map { BaldaEngine.normalize(it) }.toSet()

        override fun contains(word: String) = word in set
        override fun startWords() = listOf("балда")
    }

    /** «ка» намеренно НЕТ — для спора; «ра», «ма» — для обычных ходов. */
    private val dict = FakeWords(listOf("балда", "ра", "ма"))

    @Before
    fun installDict() {
        GameEngines.wordChecker = dict
    }

    @After
    fun restoreDict() {
        GameEngines.wordChecker = EmptyWordChecker
    }

    private fun startedBalda(): Pair<PairNetwork, Int> {
        val net = PairNetwork()
        net.start()
        val sid = net.managerA.invite(net.nodeB, GameType.BALDA)
        net.pump()
        net.managerB.respondInvite(sid, true)
        net.pump()
        return net to sid
    }

    /** В sentLog лежат целиком конверты — вырезаем payload после заголовка. */
    private fun payloadOf(frame: ByteArray): ByteArray {
        val header = if (frame[1].toInt() == 1) 8 else 12
        return frame.copyOfRange(header, frame.size)
    }

    @Test
    fun inviteStartsBaldaWithSameStartWord() {
        val (net, sid) = startedBalda()
        assertEquals(SessionStatus.ACTIVE, net.managerA.session(sid)!!.status)
        assertEquals(SessionStatus.ACTIVE, net.managerB.session(sid)!!.status)
        val sa = net.managerA.snapshot(sid) as BaldaSnapshot
        val sb = net.managerB.snapshot(sid) as BaldaSnapshot
        assertTrue(sa.cells.contentEquals(sb.cells))
        assertEquals("балда", (10..14).joinToString("") { BaldaEngine.codeLetter(sa.cells[it]).toString() })
        assertEquals(Side.A, sa.turn) // A (инициатор) ходит первым
        assertEquals(net.managerA.digest(sid), net.managerB.digest(sid))
    }

    @Test
    fun moveTravelsOverNetworkWithWordPayload() {
        val (net, sid) = startedBalda()
        assertTrue(
            net.managerA.submitMove(
                sid,
                MoveInput.LetterAdd(6, BaldaEngine.letterCode('р'), intArrayOf(6, 11)),
            ),
        )
        net.pump()
        // Формат: [0x01][cell][letter][pathLen][path…]
        val payload = payloadOf(net.sentLog.last { it.first == net.nodeB }.third)
        assertEquals(0x01, payload[0].toInt() and 0xFF)
        assertEquals(6, payload[1].toInt())
        assertEquals(BaldaEngine.letterCode('р'), payload[2].toInt())
        assertEquals(2, payload[3].toInt())
        assertEquals(6, payload[4].toInt())
        assertEquals(11, payload[5].toInt())

        val sa = net.managerA.snapshot(sid) as BaldaSnapshot
        assertEquals(2, sa.myScore)
        assertEquals(Side.B, sa.turn)
        assertEquals(net.managerA.digest(sid), net.managerB.digest(sid))
        // очередь у B — её ход тоже проходит
        assertTrue(
            net.managerB.submitMove(
                sid,
                MoveInput.LetterAdd(16, BaldaEngine.letterCode('м'), intArrayOf(16, 11)),
            ),
        )
        net.pump()
        assertEquals(net.managerA.digest(sid), net.managerB.digest(sid))
    }

    @Test
    fun disputedWordAwaitingPeerVerdictAcceptWorks() {
        val (net, sid) = startedBalda()
        // «ка» в словаре нет — ход уходит как 0x81
        assertTrue(
            net.managerA.submitMove(
                sid,
                MoveInput.LetterAdd(9, BaldaEngine.letterCode('к'), intArrayOf(9, 14)),
            ),
        )
        net.pump()
        val payload = payloadOf(net.sentLog.last { it.first == net.nodeB }.third)
        assertEquals(0x81, payload[0].toInt() and 0xFF)

        val sb = net.managerB.snapshot(sid) as BaldaSnapshot
        assertNotNull(sb.dispute)
        assertTrue(sb.dispute!!.awaitingMyVerdict)
        assertEquals("ка", sb.dispute!!.word)
        // у инициатора спор виден как ожидающий ответа
        val sa0 = net.managerA.snapshot(sid) as BaldaSnapshot
        assertFalse(sa0.dispute!!.awaitingMyVerdict)

        // B принимает → очки A, очередь — B
        assertTrue(net.managerB.submitVerdict(sid, true))
        net.pump()
        val sa = net.managerA.snapshot(sid) as BaldaSnapshot
        assertNull(sa.dispute)
        assertEquals(2, sa.myScore)
        assertEquals(Side.B, sa.turn)
        assertEquals(net.managerA.digest(sid), net.managerB.digest(sid))
        // ход B после принятия — проходит
        assertTrue(
            net.managerB.submitMove(
                sid,
                MoveInput.LetterAdd(16, BaldaEngine.letterCode('м'), intArrayOf(16, 11)),
            ),
        )
        net.pump()
        assertEquals(net.managerA.digest(sid), net.managerB.digest(sid))
    }

    @Test
    fun disputedWordRejectRevertsAndKeepsTurn() {
        val (net, sid) = startedBalda()
        assertTrue(
            net.managerA.submitMove(
                sid,
                MoveInput.LetterAdd(9, BaldaEngine.letterCode('к'), intArrayOf(9, 14)),
            ),
        )
        net.pump()
        assertTrue(net.managerB.submitVerdict(sid, false))
        net.pump()
        val sa = net.managerA.snapshot(sid) as BaldaSnapshot
        assertNull(sa.dispute)
        assertEquals(0, sa.cells[9])
        assertEquals(0, sa.myScore)
        assertEquals(Side.A, sa.turn) // очередь у инициатора: ход не состоялся
        assertEquals(net.managerA.digest(sid), net.managerB.digest(sid))
        // A может сделать ход заново
        assertTrue(
            net.managerA.submitMove(
                sid,
                MoveInput.LetterAdd(6, BaldaEngine.letterCode('р'), intArrayOf(6, 11)),
            ),
        )
        net.pump()
        assertEquals(2, (net.managerA.snapshot(sid) as BaldaSnapshot).myScore)
    }

    @Test
    fun verdictWithoutDisputeIsRejected() {
        val (net, sid) = startedBalda()
        assertFalse(net.managerB.submitVerdict(sid, true)) // спора нет
        assertFalse(net.managerA.submitVerdict(sid, true)) // инициатор не судит
    }

    @Test
    fun threeConsecutivePassesFinishGameOnBothSides() {
        val (net, sid) = startedBalda()
        fun pass(byA: Boolean) {
            val ok = if (byA) {
                net.managerA.submitMove(sid, MoveInput.Skip())
            } else {
                net.managerB.submitMove(sid, MoveInput.Skip())
            }
            assertTrue(ok)
            net.pump()
        }
        pass(true)   // A:1
        pass(false)  // B:1
        pass(true)   // A:2
        pass(false)  // B:2
        pass(true)   // A:3 → A проигрывает

        assertEquals(SessionStatus.FINISHED, net.managerA.session(sid)!!.status)
        assertEquals(SessionStatus.FINISHED, net.managerB.session(sid)!!.status)
        val ra = net.managerA.session(sid)!!.result!!
        val rb = net.managerB.session(sid)!!.result!!
        assertEquals(GameResult(Side.B, ResultReason.THREE_PASSES), ra)
        assertEquals(ra, rb)
        assertTrue(net.eventsA.over.isNotEmpty())
        assertTrue(net.eventsB.over.isNotEmpty())
        // после конца ходы не принимаются
        assertFalse(net.managerA.submitMove(sid, MoveInput.Skip()))
        assertFalse(net.managerB.submitMove(sid, MoveInput.Skip()))
    }

    @Test
    fun pendingVerdictSurvivesRestart() {
        val storeA = InMemorySessionStore()
        val storeB = InMemorySessionStore()
        val net = PairNetwork()
        net.start(storeA, storeB)
        val sid = net.managerA.invite(net.nodeB, GameType.BALDA)
        net.pump()
        net.managerB.respondInvite(sid, true)
        net.pump()
        assertTrue(
            net.managerA.submitMove(
                sid,
                MoveInput.LetterAdd(9, BaldaEngine.letterCode('к'), intArrayOf(9, 14)),
            ),
        )
        net.pump()

        // «Рестарт»: новые менеджеры поверх тех же хранилищ
        val net2 = PairNetwork()
        net2.managerA = SessionManager(storeA, net2.transportRef(1), net2.eventsA, { net2.clock })
        net2.managerB = SessionManager(storeB, net2.transportRef(2), net2.eventsB, { net2.clock })

        val sa = net2.managerA.snapshot(sid) as BaldaSnapshot
        val sb = net2.managerB.snapshot(sid) as BaldaSnapshot
        assertNotNull(sa.dispute)
        assertTrue(sb.dispute!!.awaitingMyVerdict)
        // спор дорешивается после рестарта
        assertTrue(net2.managerB.submitVerdict(sid, true))
        net2.pump()
        val sa2 = net2.managerA.snapshot(sid) as BaldaSnapshot
        assertNull(sa2.dispute)
        assertEquals(2, sa2.myScore)
        assertEquals(Side.B, sa2.turn)
        assertEquals(net2.managerA.digest(sid), net2.managerB.digest(sid))
    }
}
