package com.meshgames.net.bridge

import com.meshgames.game.GameType
import com.meshgames.game.MoveInput
import com.meshgames.game.Side
import com.meshgames.game.TttSnapshot
import com.meshgames.game.session.InMemorySessionStore
import com.meshgames.game.session.RecordingEvents
import com.meshgames.game.session.SessionManager
import com.meshgames.game.session.SessionStatus
import com.meshgames.game.session.SessionTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Сквозной тест: два телефона (два BridgeTransport) через фейковый TCP-мост,
 * два SessionManager'а. Полная партия в крестики-нолики + закрытие/возобновление,
 * удаление сессии и реконнект после обрыва соединения с мостом.
 */
class BridgeE2ETest {
    private lateinit var server: FakeBridgeServer
    private lateinit var scope: CoroutineScope
    private lateinit var bridgeA: BridgeTransport
    private lateinit var bridgeB: BridgeTransport
    private lateinit var managerA: SessionManager
    private lateinit var managerB: SessionManager
    private val eventsA = RecordingEvents()
    private val eventsB = RecordingEvents()

    @Before
    fun setUp() {
        server = FakeBridgeServer()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        bridgeA = newTransport(1001, "Алиса")
        bridgeB = newTransport(1002, "Боб")
        managerA = SessionManager(InMemorySessionStore(), transportOf(bridgeA), eventsA)
        managerB = SessionManager(InMemorySessionStore(), transportOf(bridgeB), eventsB)

        // маршрутизация входящих пакетов: транспорт → менеджер
        scope.launch {
            bridgeA.packets.collect { p ->
                if (p.to == 0 || p.to == bridgeA.myId) managerA.handleIncoming(p.from, p.payload)
            }
        }
        scope.launch {
            bridgeB.packets.collect { p ->
                if (p.to == 0 || p.to == bridgeB.myId) managerB.handleIncoming(p.from, p.payload)
            }
        }
        // тик (ретрансляции, таймауты удаления) — как в AppGraph
        scope.launch {
            while (true) {
                delay(500)
                managerA.tick()
                managerB.tick()
            }
        }

        bridgeA.start()
        bridgeB.start()
        waitUntil("оба транспорта на связи") { bridgeA.isConnected && bridgeB.isConnected }
    }

    @After
    fun tearDown() {
        runCatching { bridgeA.destroy() }
        runCatching { bridgeB.destroy() }
        scope.cancel()
        server.close()
    }

    private fun newTransport(id: Int, name: String): BridgeTransport = BridgeTransport(
        scope = scope,
        myId = id,
        host = "127.0.0.1",
        port = server.port,
        nameProvider = { name },
        busyProvider = { false },
    )

    private fun transportOf(bridge: BridgeTransport): SessionTransport = object : SessionTransport {
        override val myNodeNum: Int get() = bridge.myId
        override fun sendAsync(to: Int, payload: ByteArray) {
            scope.launch { runCatching { bridge.send(to, payload) } }
        }
    }

    private fun waitUntil(
        what: String,
        timeoutMs: Long = 15000,
        cond: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(25)
        }
        assertTrue("таймаут ожидания: $what", cond())
    }

    @Test
    fun helloFromBridgeExposesNodeInfoAndRefreshesStatus() {
        // Свежая установка ещё не знает свою ноду: до HELLO nodeInfo пуст.
        assertNull(bridgeA.nodeInfo.value)
        assertNull(bridgeB.nodeInfo.value)

        // Старый форк молчит → nodeInfo так и остаётся null, трафик идёт.
        val sid = managerA.invite(1002, GameType.TIC_TAC_TOE)
        waitUntil("B получил INVITE без HELLO") { managerB.session(sid) != null }

        // Мост прислал HELLO — оба транспорта видят номер и имя ноды.
        server.sendRawToAll(BridgeCodec.helloFrame(777, "Узел-Ло"))
        waitUntil("A видит HELLO") {
            bridgeA.nodeInfo.value?.let { it.nodeId == 777 && it.name == "Узел-Ло" } == true
        }
        waitUntil("B видит HELLO") {
            bridgeB.nodeInfo.value?.let { it.nodeId == 777 && it.name == "Узел-Ло" } == true
        }

        // Повторный идентичный HELLO не меняет публикуемое значение.
        server.sendRawToAll(BridgeCodec.helloFrame(777, "Узел-Ло"))
        Thread.sleep(100)
        assertEquals(777, bridgeA.nodeInfo.value?.nodeId)

        // Миграция идентичности (myId меняется) обновляет статус Ready.
        assertEquals(BridgeStatus.Ready(1001), bridgeA.status.value)
        bridgeA.myId = 777
        assertEquals(BridgeStatus.Ready(777), bridgeA.status.value)
    }

    @Test
    fun fullTicTacToeWithCloseResumeAndDelete() {
        // --- приглашение ---
        val sid = managerA.invite(1002, GameType.TIC_TAC_TOE)
        waitUntil("B получил INVITE") { managerB.session(sid) != null }
        managerB.respondInvite(sid, true)
        waitUntil("партия активна у обоих") {
            managerA.session(sid)?.status == SessionStatus.ACTIVE &&
                managerB.session(sid)?.status == SessionStatus.ACTIVE
        }

        // --- presence соперников подтверждён ---
        waitUntil("A видит presence B") { bridgeA.peers.value[1002]?.name == "Боб" }
        waitUntil("B видит presence A") { bridgeB.peers.value[1001]?.name == "Алиса" }

        // --- ход A ---
        assertTrue(managerA.submitMove(sid, MoveInput.Cell(4)))
        waitUntil("B видит ход A") {
            (managerB.snapshot(sid) as TttSnapshot).cells[4] == 1
        }
        // --- ход B ---
        assertTrue(managerB.submitMove(sid, MoveInput.Cell(0)))
        waitUntil("A видит ход B") {
            (managerA.snapshot(sid) as TttSnapshot).cells[0] == 2
        }
        // звук-событие хода соперника
        waitUntil("событие opponentAction у A") { eventsA.opponentActions.contains(sid) }

        // --- закрытие игры с сохранением ---
        managerA.closeGame(sid)
        waitUntil("B знает о закрытии") {
            managerB.session(sid)?.closed == true
        }
        assertEquals(SessionStatus.ACTIVE, managerB.session(sid)!!.status)
        // в закрытой игре ходы заблокированы
        assertFalse(managerA.submitMove(sid, MoveInput.Cell(1)))

        // --- возобновление ---
        managerA.resumeGame(sid)
        waitUntil("B видит возобновление") {
            managerB.session(sid)?.closed == false
        }
        assertTrue(managerA.submitMove(sid, MoveInput.Cell(1)))
        waitUntil("B видит ход A после resume") {
            (managerB.snapshot(sid) as TttSnapshot).cells[1] == 1
        }
        // --- добиваем партию до победы A (линия 1-4-7) ---
        assertTrue(managerB.submitMove(sid, MoveInput.Cell(8)))
        waitUntil("A видит ход B") {
            (managerA.snapshot(sid) as TttSnapshot).cells[8] == 2
        }
        assertTrue(managerA.submitMove(sid, MoveInput.Cell(7)))
        waitUntil("партия завершена у обоих") {
            managerA.session(sid)?.status == SessionStatus.FINISHED &&
                managerB.session(sid)?.status == SessionStatus.FINISHED
        }
        assertEquals(Side.A, eventsA.over[0].second.winner)
        assertEquals(Side.A, eventsB.over[0].second.winner)

        // --- удаление сессии: узнают обе стороны ---
        managerA.deleteSession(sid)
        waitUntil("B удалил сессию") { managerB.session(sid) == null }
        waitUntil("A удалил сессию") { managerA.session(sid) == null }
        assertEquals(1, eventsB.deletedByPeer.size)
        assertEquals(sid, eventsB.deletedByPeer[0].first)
        assertEquals(GameType.TIC_TAC_TOE, eventsB.deletedByPeer[0].second)
        assertEquals(1001, eventsB.deletedByPeer[0].third)
    }

    @Test
    fun inviteDeclinedOverBridge() {
        val sid = managerA.invite(1002, GameType.CHESS)
        waitUntil("B получил INVITE") { managerB.session(sid) != null }
        assertNotNull(eventsB.invites.let { if (it.isEmpty()) null else it.first() })
        managerB.respondInvite(sid, false)
        waitUntil("A узнал об отказе") {
            managerA.session(sid)?.status == SessionStatus.CANCELLED
        }
        assertEquals(listOf(sid), eventsA.declined)
        // сессии висят до dismiss, после — удалены
        managerA.dismiss(sid)
        managerB.dismiss(sid)
        assertNull(managerA.session(sid))
        assertNull(managerB.session(sid))
    }

    @Test
    fun sessionSurvivesBridgeReconnect() {
        val sid = managerA.invite(1002, GameType.TIC_TAC_TOE)
        waitUntil("B получил INVITE") { managerB.session(sid) != null }
        managerB.respondInvite(sid, true)
        waitUntil("партия активна у обоих") {
            managerA.session(sid)?.status == SessionStatus.ACTIVE &&
                managerB.session(sid)?.status == SessionStatus.ACTIVE
        }
        assertTrue(managerA.submitMove(sid, MoveInput.Cell(4)))
        waitUntil("B видит ход A") { (managerB.snapshot(sid) as TttSnapshot).cells[4] == 1 }

        // --- обрыв: мост разорвал оба соединения ---
        server.disconnectAll()
        waitUntil("A переподключился") { bridgeA.isConnected }
        waitUntil("B переподключился") { bridgeB.isConnected }

        // --- игра продолжается после реконнекта ---
        assertTrue(managerB.submitMove(sid, MoveInput.Cell(0)))
        waitUntil("A видит ход B после реконнекта") {
            (managerA.snapshot(sid) as TttSnapshot).cells[0] == 2
        }
    }
}
