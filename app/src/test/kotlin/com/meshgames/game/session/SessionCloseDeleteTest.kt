package com.meshgames.game.session

import com.meshgames.game.GameType
import com.meshgames.game.MoveInput
import com.meshgames.game.TttSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Жизненный цикл «закрыть игру с сохранением» (SESSION_CLOSE) и
 * «удалить сессию» (SESSION_DELETE) на двух связанных менеджерах.
 */
class SessionCloseDeleteTest {

    private fun startedPair(): PairNetwork {
        val net = PairNetwork()
        net.start()
        val sid = net.managerA.invite(net.nodeB, GameType.TIC_TAC_TOE)
        net.pump()
        net.managerB.respondInvite(sid, true)
        net.pump()
        return net
    }

    @Test
    fun closeGameNotifiesPeerAndKeepsSession() {
        val net = startedPair()
        val sid = net.managerA.sessions_.value.first().session.sessionId
        assertTrue(net.managerA.submitMove(sid, MoveInput.Cell(4)))
        net.pump()

        net.managerA.closeGame(sid)
        net.pump()

        // Обе стороны знают о закрытии, сессии целы (продолжаемые)
        assertTrue(net.managerA.session(sid)!!.closed)
        assertTrue(net.managerB.session(sid)!!.closed)
        assertEquals(SessionStatus.ACTIVE, net.managerA.session(sid)!!.status)
        assertEquals(SessionStatus.ACTIVE, net.managerB.session(sid)!!.status)
        assertEquals(listOf(sid to true), net.eventsB.closedEvents)
        // доска сохранена
        assertEquals(1, (net.managerA.snapshot(sid) as TttSnapshot).cells[4])
    }

    @Test
    fun closedGameBlocksLocalMoves() {
        val net = startedPair()
        val sid = net.managerA.sessions_.value.first().session.sessionId

        net.managerA.closeGame(sid)
        net.pump()

        assertFalse(net.managerA.submitMove(sid, MoveInput.Cell(4)))
        // после возобновления ходы снова возможны
        net.managerA.resumeGame(sid)
        net.pump()
        assertFalse(net.managerA.session(sid)!!.closed)
        assertTrue(net.managerA.submitMove(sid, MoveInput.Cell(4)))
    }

    @Test
    fun resumeClearsClosedOnPeer() {
        val net = startedPair()
        val sid = net.managerA.sessions_.value.first().session.sessionId
        net.managerA.closeGame(sid)
        net.pump()
        net.managerA.resumeGame(sid)
        net.pump()

        assertFalse(net.managerA.session(sid)!!.closed)
        assertFalse(net.managerB.session(sid)!!.closed)
        assertEquals(listOf(sid to true), net.eventsB.closedEvents.filter { it.second })
        assertEquals(listOf(sid to false), net.eventsB.closedEvents.filter { !it.second })
        // и снова играем
        assertTrue(net.managerA.submitMove(sid, MoveInput.Cell(4)))
        net.pump()
        assertEquals(1, (net.managerB.snapshot(sid) as TttSnapshot).cells[4])
    }

    @Test
    fun closeSurvivesRestart() {
        val storeA = InMemorySessionStore()
        val storeB = InMemorySessionStore()
        val net = PairNetwork()
        net.start(storeA, storeB)
        val sid = net.managerA.invite(net.nodeB, GameType.TIC_TAC_TOE)
        net.pump()
        net.managerB.respondInvite(sid, true)
        net.pump()
        net.managerA.closeGame(sid)
        net.pump()

        // «Рестарт» A: closed флаг восстановлен из хранилища
        val net2 = PairNetwork()
        net2.managerA = SessionManager(storeA, net2.transportRef(1), net2.eventsA, { net2.clock })
        assertTrue(net2.managerA.session(sid)!!.closed)
        // и ходы заблокированы до возобновления
        assertFalse(net2.managerA.submitMove(sid, MoveInput.Cell(4)))
    }

    @Test
    fun deleteSessionRemovesOnBothSides() {
        val net = startedPair()
        val sid = net.managerA.sessions_.value.first().session.sessionId
        assertNotNull(net.managerB.session(sid))

        net.managerA.deleteSession(sid)
        net.pump()
        // B удалил сразу и сообщил событием
        assertNull(net.managerB.session(sid))
        assertEquals(1, net.eventsB.deletedByPeer.size)
        assertEquals(sid, net.eventsB.deletedByPeer[0].first)
        assertEquals(GameType.TIC_TAC_TOE, net.eventsB.deletedByPeer[0].second)
        assertEquals(net.nodeA, net.eventsB.deletedByPeer[0].third)

        // A удалил локально сразу, ничего не дожидаясь (тик ничего не меняет)
        net.managerA.tick(net.clock)
        assertNull(net.managerA.session(sid))
    }

    @Test
    fun deleteRemovesLocallyWithoutNetwork() {
        val net = startedPair()
        val sid = net.managerA.sessions_.value.first().session.sessionId

        // Сессия удаляется локально сразу — подтверждение не требуется;
        // SESSION_DELETE ещё уходит в эфир по расписанию «сиротских» повторов.
        net.managerA.deleteSession(sid)
        assertNull(net.managerA.session(sid))
        net.managerA.tick(net.clock + 31_000)
        assertNull(net.managerA.session(sid))
    }

    @Test
    fun deleteOfUnknownSessionIgnored() {
        val net = PairNetwork()
        net.start()
        // SESSION_DELETE (type=15) для неизвестной сессии — игнор
        net.managerB.handleIncoming(1, byteArrayOf(0x47, 1, 15, 0, 7, 7, 0, 0))
        assertTrue(net.eventsB.deletedByPeer.isEmpty())
    }

    @Test
    fun opponentActionEventOnRemoteMove() {
        val net = startedPair()
        val sid = net.managerA.sessions_.value.first().session.sessionId
        assertTrue(net.managerA.submitMove(sid, MoveInput.Cell(4)))
        net.pump()
        assertEquals(listOf(sid), net.eventsB.opponentActions)
        // на ответный ход A тоже приходит событие
        assertTrue(net.managerB.submitMove(sid, MoveInput.Cell(0)))
        net.pump()
        assertTrue(net.eventsA.opponentActions.contains(sid))
    }
}
