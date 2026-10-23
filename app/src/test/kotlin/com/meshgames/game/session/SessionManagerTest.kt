package com.meshgames.game.session

import com.meshgames.game.GameResult
import com.meshgames.game.GameType
import com.meshgames.game.MoveInput
import com.meshgames.game.ResultReason
import com.meshgames.game.SeaBattleSnapshot
import com.meshgames.game.Side
import com.meshgames.game.TttSnapshot
import com.meshgames.game.protocol.Envelope
import com.meshgames.game.protocol.MsgType
import java.util.ArrayDeque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** События сессий для проверки в тестах. */
class RecordingEvents : SessionEvents {
    val invites = ArrayList<Int>()
    val started = ArrayList<Int>()
    val over = ArrayList<Pair<Int, GameResult>>()
    val declined = ArrayList<Int>()
    val draws = ArrayList<Int>()
    val conflicts = ArrayList<Int>()
    val recovered = ArrayList<Pair<Int, Int>>()
    val closedEvents = ArrayList<Pair<Int, Boolean>>()
    val deletedByPeer = ArrayList<Triple<Int, GameType, Int>>()
    val opponentActions = ArrayList<Int>()

    override fun onInviteReceived(sessionId: Int, peerNode: Int, gameType: GameType) {
        invites.add(sessionId)
    }

    override fun onGameStarted(sessionId: Int) {
        started.add(sessionId)
    }

    override fun onGameOver(sessionId: Int, result: GameResult) {
        over.add(sessionId to result)
    }

    override fun onInviteDeclined(sessionId: Int) {
        declined.add(sessionId)
    }

    override fun onDrawOffered(sessionId: Int) {
        draws.add(sessionId)
    }

    override fun onSyncConflict(sessionId: Int) {
        conflicts.add(sessionId)
    }

    override fun onSessionRecovered(sessionId: Int, toEntry: Int) {
        recovered.add(sessionId to toEntry)
    }

    override fun onGameClosed(sessionId: Int, closed: Boolean) {
        closedEvents.add(sessionId to closed)
    }

    override fun onSessionDeletedByPeer(sessionId: Int, gameType: GameType, peerNode: Int) {
        deletedByPeer.add(Triple(sessionId, gameType, peerNode))
    }

    override fun onOpponentAction(sessionId: Int) {
        opponentActions.add(sessionId)
    }
}

/**
 * Пара менеджеров, связанных напрямую, с общими часами и управляемыми
 * потерями: каждая N-я отправка теряется.
 */
class PairNetwork(private val lossEveryNth: Int = 0) {
    val nodeA = 1
    val nodeB = 2

    private val outbox = ArrayDeque<Triple<Int, Int, ByteArray>>() // to, from, payload
    private var sendCounter = 0

    var clock = 100_000L

    /**
     * Всё, что летит К этому узлу, — в брак (эфир в сторону мёртв): пакет
     * пишется в sentLog, но в очередь не попадает. Моделирует полудуплексную
     * «дырку» в приёме из полевых логов.
     */
    var dropTo: Int? = null

    val eventsA = RecordingEvents()
    val eventsB = RecordingEvents()
    lateinit var managerA: SessionManager
    lateinit var managerB: SessionManager

    /** Все фреймы, ушедшие в сеть (для тестов дубликатов). */
    val sentLog = ArrayList<Triple<Int, Int, ByteArray>>()

    fun start(
        storeA: SessionStore = InMemorySessionStore(),
        storeB: SessionStore = InMemorySessionStore(),
    ) {
        managerA = SessionManager(storeA, transport(nodeA), eventsA, { clock })
        managerB = SessionManager(storeB, transport(nodeB), eventsB, { clock })
        pump()
    }

    private fun transport(node: Int) = object : SessionTransport {
        override val myNodeNum: Int = node
        override fun sendAsync(to: Int, payload: ByteArray) {
            sendCounter++
            val copy = payload.copyOf()
            sentLog.add(Triple(to, node, copy))
            if (lossEveryNth > 0 && sendCounter % lossEveryNth == 0) return
            if (dropTo == to) return
            outbox.add(Triple(to, node, copy))
        }
    }

    /** Выбросить всё накопленное в очереди (пакеты «утонули» в эфире). */
    fun dropOutbox() {
        outbox.clear()
    }

    /** Транспорт для «восстановленного» менеджера поверх той же сети. */
    fun transportRef(node: Int): SessionTransport = transport(node)

    /** Доставить всё, пока не опустеет (переотправки плодят новые сообщения). */
    fun pump() {
        var guard = 0
        while (outbox.isNotEmpty() && guard++ < 50000) {
            val item = outbox.poll() ?: break
            val (to, from, payload) = item
            if (to == 0) continue // широковещательные кадры (статистика, presence)
            (if (to == nodeA) managerA else managerB).handleIncoming(from, payload)
        }
    }

    /** Продвинуть часы, дёрнуть переотправки и доставить. */
    fun advanceTime(ms: Long) {
        clock += ms
        managerA.tick(clock)
        managerB.tick(clock)
        pump()
    }
}

class SessionManagerTest {
    private fun startedPair(
        game: GameType = GameType.TIC_TAC_TOE,
        net: PairNetwork = PairNetwork(),
    ): PairNetwork {
        net.start()
        val sid = net.managerA.invite(net.nodeB, game)
        net.pump()
        net.managerB.respondInvite(sid, true)
        net.pump()
        return net
    }

    @Test
    fun inviterSideIsCommunicatedInviteeGetsOpposite() {
        val net = PairNetwork()
        // Инициатор играет чёрными (сторона B) — стороны общаются в INVITE.
        net.managerA = SessionManager(
            InMemorySessionStore(), net.transportRef(net.nodeA), net.eventsA, { net.clock },
            sideDecider = { Side.B },
        )
        net.managerB = SessionManager(InMemorySessionStore(), net.transportRef(net.nodeB), net.eventsB, { net.clock })
        val sid = net.managerA.invite(net.nodeB, GameType.TIC_TAC_TOE)
        net.pump()
        assertEquals(Side.B, net.managerA.session(sid)!!.mySide)
        assertEquals(Side.A, net.managerB.session(sid)!!.mySide)
        net.managerB.respondInvite(sid, true)
        net.pump()
        assertEquals(SessionStatus.ACTIVE, net.managerA.session(sid)!!.status)
        // Первый ход за стороной A — то есть за приглашённым (игрок B):
        // его ход проходит, ход инициатора до этого отклонялся бы.
        assertTrue(net.managerB.submitMove(sid, MoveInput.Cell(0)))
        net.pump()
        assertTrue(net.managerA.submitMove(sid, MoveInput.Cell(4)))
        net.pump()
        // После ходов обоих (приглашённый — инициатор) очередь снова
        // приглашённого: сторона A за ним.
        assertEquals(Side.A, net.managerB.snapshot(sid)!!.turn)
    }

    @Test
    fun stuckInviteRetryKeepsInviterSide() {
        val net = PairNetwork()
        net.managerA = SessionManager(
            InMemorySessionStore(), net.transportRef(net.nodeA), net.eventsA, { net.clock },
            sideDecider = { Side.B },
        )
        net.managerB = SessionManager(InMemorySessionStore(), net.transportRef(net.nodeB), net.eventsB, { net.clock })
        val sid = net.managerA.invite(net.nodeB, GameType.TIC_TAC_TOE)
        // INVITE «утонул» в эфире — переотправки по расписанию несут
        // ту же сторону инициатора.
        net.advanceTime(7_000)
        val invited = net.managerB.session(sid)
        assertTrue(invited != null)
        assertEquals(Side.A, invited!!.mySide)
    }

    @Test
    fun inviteAcceptAndPlayTicTacToe() {
        val net = PairNetwork()
        net.start()
        val sid = net.managerA.invite(net.nodeB, GameType.TIC_TAC_TOE)
        net.pump()

        // B получил приглашение
        assertEquals(listOf(sid), net.eventsB.invites)
        assertEquals(SessionStatus.INVITING, net.managerA.session(sid)!!.status)
        val invited = net.managerB.session(sid)!!
        assertEquals(SessionStatus.INVITED, invited.status)
        assertEquals(Side.B, invited.mySide)

        net.managerB.respondInvite(sid, true)
        net.pump()
        assertEquals(SessionStatus.ACTIVE, net.managerA.session(sid)!!.status)
        assertEquals(SessionStatus.ACTIVE, net.managerB.session(sid)!!.status)
        assertTrue(net.eventsB.started.contains(sid))

        // A: центр (X), B: угол 0 (O)
        assertTrue(net.managerA.submitMove(sid, MoveInput.Cell(4)))
        net.pump()
        assertTrue(net.managerB.submitMove(sid, MoveInput.Cell(0)))
        net.pump()

        // доски идентичны
        val cellsA = (net.managerA.snapshot(sid) as TttSnapshot).cells
        val cellsB = (net.managerB.snapshot(sid) as TttSnapshot).cells
        assertTrue(cellsA.contentEquals(cellsB))
        assertEquals(1, cellsA[4])
        assertEquals(2, cellsA[0])

        // A добивает линию 1-4-7
        assertTrue(net.managerA.submitMove(sid, MoveInput.Cell(1)))
        net.pump()
        assertTrue(net.managerB.submitMove(sid, MoveInput.Cell(8)))
        net.pump()
        assertTrue(net.managerA.submitMove(sid, MoveInput.Cell(7)))
        net.pump()

        assertEquals(1, net.eventsA.over.size)
        assertEquals(1, net.eventsB.over.size)
        assertEquals(Side.A, net.eventsA.over[0].second.winner)
        assertEquals(Side.A, net.eventsB.over[0].second.winner)
        assertEquals(SessionStatus.FINISHED, net.managerA.session(sid)!!.status)
        assertEquals(SessionStatus.FINISHED, net.managerB.session(sid)!!.status)
    }

    @Test
    fun inviteEventFires() {
        val net = PairNetwork()
        net.start()
        val sid = net.managerA.invite(net.nodeB, GameType.CHESS)
        net.pump()
        assertTrue(net.eventsB.invites.contains(sid))
        assertEquals(GameType.CHESS, net.managerB.session(sid)!!.gameId)
    }

    @Test
    fun declineEndsEverything() {
        val net = PairNetwork()
        net.start()
        val sid = net.managerA.invite(net.nodeB, GameType.TIC_TAC_TOE)
        net.pump()
        net.managerB.respondInvite(sid, false)
        net.pump()
        assertEquals(SessionStatus.CANCELLED, net.managerA.session(sid)!!.status)
        assertEquals(listOf(sid), net.eventsA.declined)
    }

    @Test
    fun cancelInviteNotifiesPeer() {
        val net = PairNetwork()
        net.start()
        val sid = net.managerA.invite(net.nodeB, GameType.TIC_TAC_TOE)
        net.pump()
        net.managerA.cancelInvite(sid)
        net.pump()
        assertEquals(SessionStatus.CANCELLED, net.managerB.session(sid)!!.status)
    }

    @Test
    fun lostPacketsAreRetransmitted() {
        val net = PairNetwork(lossEveryNth = 2) // каждая вторая доставка теряется
        net.start()
        val sid = net.managerA.invite(net.nodeB, GameType.TIC_TAC_TOE)
        // INVITE мог упасть — переотправка по расписанию (INVITE терпеливый)
        net.advanceTime(ReliableChannel.PATIENT_RETRY_GAPS_MS[0] + 10)
        val b = net.managerB.session(sid)
        assertTrue("B должен получить INVITE", b != null)

        net.managerB.respondInvite(sid, true)
        net.advanceTime(ReliableChannel.PATIENT_RETRY_GAPS_MS[0] + 10)
        assertEquals(SessionStatus.ACTIVE, net.managerA.session(sid)!!.status)

        // играем, продвигая время для переотправок; чей ход — решает движок,
        // а не принадлежность менеджера
        var guard = 0
        while (guard++ < 200) {
            net.advanceTime(36_000)
            val sa = net.managerA.snapshot(sid) as TttSnapshot
            if (sa.finished) break
            val mover = if (sa.turn == Side.A) net.managerA else net.managerB
            val cells = (mover.snapshot(sid) as TttSnapshot).cells
            val free = cells.indices.firstOrNull { cells[it] == 0 } ?: break
            // ход может не пройти, пока пакет соперника в пути — крутим время
            mover.submitMove(sid, MoveInput.Cell(free))
        }
        assertEquals(SessionStatus.FINISHED, net.managerA.session(sid)!!.status)
        assertEquals(SessionStatus.FINISHED, net.managerB.session(sid)!!.status)
        assertEquals(net.eventsA.over[0].second.winner, net.eventsB.over[0].second.winner)
    }

    @Test
    fun resumeAfterRestartContinuesGame() {
        val storeA = InMemorySessionStore()
        val storeB = InMemorySessionStore()
        val net = PairNetwork()
        net.start(storeA, storeB)
        val sid = net.managerA.invite(net.nodeB, GameType.TIC_TAC_TOE)
        net.pump()
        net.managerB.respondInvite(sid, true)
        net.pump()
        assertTrue(net.managerA.submitMove(sid, MoveInput.Cell(4)))
        net.pump()

        // «Рестарт»: новые менеджеры поверх тех же хранилищ
        val net2 = PairNetwork()
        net2.managerA = SessionManager(storeA, net2.transportRef(1), net2.eventsA, { net2.clock })
        net2.managerB = SessionManager(storeB, net2.transportRef(2), net2.eventsB, { net2.clock })

        val ra = net2.managerA.session(sid)!!
        val rb = net2.managerB.session(sid)!!
        assertEquals(SessionStatus.ACTIVE, ra.status)
        assertEquals(Side.A, ra.mySide)
        assertEquals(Side.B, rb.mySide)
        val cells = (net2.managerA.snapshot(sid) as TttSnapshot).cells
        assertEquals(1, cells[4])
        // ход B проходит
        assertTrue(net2.managerB.submitMove(sid, MoveInput.Cell(0)))
    }

    @Test
    fun unackedFramesSurviveRestartAndResend() {
        val storeA = InMemorySessionStore()
        val storeB = InMemorySessionStore()
        val net = PairNetwork()
        net.start(storeA, storeB)
        val sid = net.managerA.invite(net.nodeB, GameType.TIC_TAC_TOE)
        net.pump()
        net.managerB.respondInvite(sid, true)
        net.pump()
        assertTrue(net.managerA.submitMove(sid, MoveInput.Cell(4)))
        // ход НЕ доставляем (сеть упала) — он должен быть в unacked
        assertTrue(storeA.data.first { it.sessionId == sid }.unacked.isNotEmpty())

        // рестарт A: ход уходит заново и B его применяет
        val net2 = PairNetwork()
        net2.managerA = SessionManager(storeA, net2.transportRef(1), net2.eventsA, { net2.clock })
        net2.managerB = SessionManager(storeB, net2.transportRef(2), net2.eventsB, { net2.clock })
        net2.advanceTime(ReliableChannel.RETRY_GAPS_MS[0] + 10)
        val cells = (net2.managerB.snapshot(sid) as TttSnapshot).cells
        assertEquals(1, cells[4])
    }

    @Test
    fun stuckInvitingHealsViaReinviteAfterRestart() {
        // INVITE ни разу не дошёл (сеть мертва): базовое patient-расписание
        // кончилось, но непОДТВЕРЖДЁННЫЙ кадр не сгорает — он в вечном режиме
        val storeA = InMemorySessionStore()
        val storeB = InMemorySessionStore()
        val net = PairNetwork(lossEveryNth = 1) // всё теряем
        net.start(storeA, storeB)
        val sid = net.managerA.invite(net.nodeB, GameType.TIC_TAC_TOE)
        net.advanceTime(200_000) // patient-расписание (6 отправок ≤135с) кончилось
        assertTrue(storeA.data.first { it.sessionId == sid }.unacked.isNotEmpty())
        assertEquals(SessionStatus.INVITING, net.managerA.session(sid)!!.status)
        assertNull(net.managerB.session(sid)) // пир приглашение так и не видел

        // «Рестарт»: restore восстанавливает INVITE в вечном режиме —
        // первый повтор уходит через гэп вечного расписания, seq=0
        val net2 = PairNetwork()
        net2.managerA = SessionManager(storeA, net2.transportRef(1), net2.eventsA, { net2.clock })
        net2.managerB = SessionManager(storeB, net2.transportRef(2), net2.eventsB, { net2.clock })
        net2.advanceTime(ReliableChannel.ETERNAL_GAP_MS + 1)
        assertEquals(listOf(sid), net2.eventsB.invites) // пир увидел приглашение
        assertEquals(
            0,
            Envelope.decode(net2.sentLog.first { it.first == net2.nodeB }.third)!!.seq,
        )

        // пир принимает — цепочка полностью восстановлена
        net2.managerB.respondInvite(sid, true)
        net2.pump()
        assertEquals(SessionStatus.ACTIVE, net2.managerA.session(sid)!!.status)
        assertEquals(SessionStatus.ACTIVE, net2.managerB.session(sid)!!.status)
        // cumAck из INVITE_RESP погасил расписание повторов повторного INVITE
        assertTrue(
            !net2.managerA.sessions_.value.first { it.session.sessionId == sid }.hasPending,
        )
    }

    @Test
    fun retryInviteIfStuckIsIdempotentWhileScheduleRuns() {
        val net = PairNetwork()
        net.start()
        val sid = net.managerA.invite(net.nodeB, GameType.TIC_TAC_TOE)
        val sent = net.sentLog.size
        // кадр ещё в расписании — повторный INVITE не шлём
        net.managerA.retryInviteIfStuck(sid)
        assertEquals(sent, net.sentLog.size)
        // неизвестная сессия — тоже не шлём
        net.managerA.retryInviteIfStuck(sid + 999)
        assertEquals(sent, net.sentLog.size)
    }

    @Test
    fun resendDeliversWithoutDoubleApply() {
        val net = PairNetwork()
        net.start()
        val sid = net.managerA.invite(net.nodeB, GameType.TIC_TAC_TOE)
        net.pump()
        net.managerB.respondInvite(sid, true)
        net.pump()
        assertTrue(net.managerA.submitMove(sid, MoveInput.Cell(4)))
        net.pump()
        val digestAfter = net.managerB.digest(sid)

        // A переотправляет неподтверждённое (piggyback не пришёл) — B не применяет дважды
        net.advanceTime(36_000)
        assertEquals(digestAfter, net.managerB.digest(sid))
        val cells = (net.managerB.snapshot(sid) as TttSnapshot).cells
        assertEquals(1, cells[4])
        // очередь B: всё ещё ход B
        assertEquals(Side.B, net.managerB.snapshot(sid)!!.turn)
    }

    @Test
    fun resignSendsGameOver() {
        val net = startedPair()
        val sid = net.managerA.sessions_.value.first().session.sessionId
        net.managerA.resign(sid)
        net.pump()
        assertEquals(ResultReason.RESIGN, net.eventsB.over[0].second.reason)
        assertEquals(Side.B, net.eventsB.over[0].second.winner)
        assertEquals(SessionStatus.FINISHED, net.managerB.session(sid)!!.status)
    }

    @Test
    fun drawOfferAndAccept() {
        val net = startedPair()
        val sid = net.managerA.sessions_.value.first().session.sessionId
        net.managerA.offerDraw(sid)
        net.pump()
        assertEquals(listOf(sid), net.eventsB.draws)
        net.managerB.respondDraw(sid, true)
        net.pump()
        assertEquals(ResultReason.DRAW_AGREEMENT, net.eventsA.over[0].second.reason)
        assertEquals(ResultReason.DRAW_AGREEMENT, net.eventsB.over[0].second.reason)
    }

    @Test
    fun drawDeclinedKeepsGame() {
        val net = startedPair()
        val sid = net.managerA.sessions_.value.first().session.sessionId
        net.managerA.offerDraw(sid)
        net.pump()
        net.managerB.respondDraw(sid, false)
        net.pump()
        assertTrue(net.eventsA.over.isEmpty())
        assertEquals(SessionStatus.ACTIVE, net.managerA.session(sid)!!.status)
        assertTrue(net.managerA.submitMove(sid, MoveInput.Cell(4)))
    }

    @Test
    fun unknownSessionIgnoresNonInvite() {
        val net = PairNetwork()
        net.start()
        // MOVE (type=3) для неизвестной сессии 9 — игнорируется
        net.managerB.handleIncoming(1, byteArrayOf(0x47, 1, 3, 0, 9, 9, 0, 0, 1))
        assertNull(net.managerB.session(9))
    }

    @Test
    fun packetFromOtherNodeIgnored() {
        val net = startedPair()
        val sid = net.managerA.sessions_.value.first().session.sessionId
        val digest = net.managerB.digest(sid)
        // чужой узел (3) пытается двигать сессию B
        net.managerB.handleIncoming(3, byteArrayOf(0x47, 1, 3, 0, sid.toByte(), 0, 5, 0, 8))
        assertEquals(digest, net.managerB.digest(sid))
    }

    @Test
    fun dismissRemovesFinished() {
        val net = PairNetwork()
        net.start()
        val sid = net.managerA.invite(net.nodeB, GameType.TIC_TAC_TOE)
        net.pump()
        net.managerB.respondInvite(sid, false)
        net.pump()
        assertNotNull(net.managerA.session(sid))
        net.managerA.dismiss(sid)
        assertNull(net.managerA.session(sid))
    }

    @Test
    fun illegalLocalMoveRejected() {
        val net = startedPair()
        val sid = net.managerA.sessions_.value.first().session.sessionId
        // ход B, когда очередь A
        assertFalse0(net.managerB.submitMove(sid, MoveInput.Cell(3)))
        assertTrue(net.managerA.submitMove(sid, MoveInput.Cell(4)))
        net.pump()
        // занята
        assertFalse0(net.managerB.submitMove(sid, MoveInput.Cell(4)))
        assertTrue(net.managerB.submitMove(sid, MoveInput.Cell(0)))
    }

    private fun assertFalse0(v: Boolean) = assertTrue(!v)

    @Test
    fun seaBattlePlacementAndBattle() {
        val net = startedPair(GameType.SEA_BATTLE)
        val sid = net.managerA.sessions_.value.first().session.sessionId
        assertEquals(SessionStatus.PLACEMENT, net.managerA.session(sid)!!.status)
        assertEquals(SessionStatus.PLACEMENT, net.managerB.session(sid)!!.status)

        assertTrue(net.managerA.reroll(sid))
        assertTrue(net.managerA.ready(sid))
        net.pump()
        // B ещё не подтвердил
        assertEquals(SessionStatus.PLACEMENT, net.managerA.session(sid)!!.status)
        assertTrue(net.managerB.ready(sid))
        net.pump()
        assertEquals(SessionStatus.ACTIVE, net.managerA.session(sid)!!.status)
        assertEquals(SessionStatus.ACTIVE, net.managerB.session(sid)!!.status)

        // A стреляет первым
        assertTrue(net.managerA.submitMove(sid, MoveInput.Shoot(0)))
        net.pump()
        val sa = net.managerA.snapshot(sid) as SeaBattleSnapshot
        assertTrue(sa.oppGrid[0] != 0) // результат известен
        val sb = net.managerB.snapshot(sid) as SeaBattleSnapshot
        assertTrue(sb.myGrid[0] != 0 && sb.myGrid[0] != 1)
    }

    @Test
    fun seaBattleGameOverWhenAllSunk() {
        val net = startedPair(GameType.SEA_BATTLE)
        val sid = net.managerA.sessions_.value.first().session.sessionId
        net.managerB.ready(sid)
        net.managerA.ready(sid)
        net.pump()

        // Обе стороны стреляют по очереди (промах передаёт ход), пока
        // чьи-либо корабли не будут уничтожены.
        val rnd = java.util.Random(7)
        var guard = 0
        while (guard++ < 1000) {
            val sa = net.managerA.snapshot(sid) as SeaBattleSnapshot
            if (sa.finished) break
            val byA = sa.turn == Side.A
            val shooter = if (byA) net.managerA else net.managerB
            val target = if (byA) {
                (net.managerA.snapshot(sid) as SeaBattleSnapshot).oppGrid
            } else {
                (net.managerB.snapshot(sid) as SeaBattleSnapshot).oppGrid
            }
            val free = target.indices.filter { target[it] == 0 }
            if (free.isEmpty()) break
            val coord = free[rnd.nextInt(free.size)]
            assertTrue("ход должен пройти", shooter.submitMove(sid, MoveInput.Shoot(coord)))
            net.pump()
        }
        val sa = net.managerA.snapshot(sid) as SeaBattleSnapshot
        assertTrue(sa.finished)
        assertNotNull(sa.result!!.winner)
        assertEquals(SessionStatus.FINISHED, net.managerB.session(sid)!!.status)
        // дайджесты сошлись в конце
        assertEquals(
            net.managerA.digest(sid),
            net.managerB.digest(sid),
        )
    }

    @Test
    fun syncReqBypassesSeqHoleAndIsNotRedelivered() {
        // Полевой провал sid=40290: дырка в rxNext держала SYNC в стэше
        // навсегда. Здесь SYNC_REQ доходит мимо дырки, отвечает SYNC_RES,
        // а повтор/закрытие дырки не дают второй доставки.
        val net = startedPair(GameType.TIC_TAC_TOE)
        val sid = net.managerA.sessions_.value.first().session.sessionId
        assertTrue(net.managerA.submitMove(sid, MoveInput.Cell(4)))
        net.pump()

        // B шлёт DRAW_OFFER — «тонет»: у A дырка в seq (rxNext=1, ждём seq 1)
        net.managerB.offerDraw(sid)
        val holeFrame = net.sentLog.last { it.first == net.nodeA }.third
        net.dropOutbox()
        assertEquals(1, net.managerA.session(sid)!!.rxNext)

        // B запрашивает синхронизацию: SYNC_REQ (seq 2) идёт ВЫШЕ дырки
        net.managerB.requestSync(sid)
        val syncReqFrame = net.sentLog.last { it.first == net.nodeA }.third
        assertEquals(MsgType.SYNC_REQ, Envelope.decode(syncReqFrame)!!.type)
        net.pump()

        // A ответил, несмотря на дырку (без ранней ветки SYNC_REQ осел бы
        // в стэше навсегда) — дайджесты совпали, конфликта нет
        val syncResFromA = net.sentLog.count {
            it.first == net.nodeB && Envelope.decode(it.third)?.type == MsgType.SYNC_RES
        }
        assertEquals(1, syncResFromA)
        assertTrue(net.eventsB.conflicts.isEmpty())
        // дырка не «закрылась» молча: rxNext не двинулся мимо потери
        assertEquals(1, net.managerA.session(sid)!!.rxNext)

        // повтор того же SYNC_REQ — дубликат: второй SYNC_RES не уходит
        net.managerA.handleIncoming(net.nodeB, syncReqFrame)
        assertEquals(
            1,
            net.sentLog.count {
                it.first == net.nodeB && Envelope.decode(it.third)?.type == MsgType.SYNC_RES
            },
        )

        // потеря догнали: DRAW_OFFER применяется, а рано отданный SYNC_REQ
        // «проглатывается» — rxNext догоняет сразу до 3 без повторной доставки
        net.managerA.handleIncoming(net.nodeB, holeFrame)
        assertEquals(3, net.managerA.session(sid)!!.rxNext)
        assertEquals(listOf(sid), net.eventsA.draws)
        assertEquals(
            1,
            net.sentLog.count {
                it.first == net.nodeB && Envelope.decode(it.third)?.type == MsgType.SYNC_RES
            },
        )
        // bitmap погасил SYNC_REQ у B сразу (бит раннего приёма), а
        // неполученный DRAW_OFFER честно остался в расписании
        assertEquals(1, net.managerB.pendingCount(sid))
    }

    @Test
    fun forcedSyncSendsNowAndThrottlesRepeatTap() {
        // Кнопка «⟳ Синхр» (все игры): SYNC_REQ уходит сразу же, зажатая
        // кнопка кулдауном не плодит дубли с полными расписаниями повторов.
        val net = startedPair(GameType.TIC_TAC_TOE)
        val sid = net.managerA.sessions_.value.first().session.sessionId
        fun reqCount() = net.sentLog.count {
            it.first == net.nodeB && Envelope.decode(it.third)?.type == MsgType.SYNC_REQ
        }
        fun resCount() = net.sentLog.count {
            it.first == net.nodeA && Envelope.decode(it.third)?.type == MsgType.SYNC_RES
        }

        assertTrue(net.managerA.requestSync(sid))
        assertEquals(1, reqCount())
        net.pump()
        assertEquals(1, resCount()) // пир ответил, конфликта нет
        assertTrue(net.eventsB.conflicts.isEmpty())

        // второй тап быстрее кулдауна — кадр не порождается
        assertFalse(net.managerA.requestSync(sid))
        assertEquals(1, reqCount())

        // время прошло — снова можно; состояния сходятся
        net.clock += SessionManager.FORCED_SYNC_COOLDOWN_MS + 1
        assertTrue(net.managerA.requestSync(sid))
        assertEquals(2, reqCount())
        net.pump()
        assertEquals(2, resCount())
        assertTrue(net.eventsA.conflicts.isEmpty())
        assertTrue(net.eventsB.conflicts.isEmpty())
        assertEquals(net.managerA.digest(sid), net.managerB.digest(sid))
    }

    @Test
    fun syncResIsNotServedWhileShotInFlight() {
        // Полевая гонка 17.10 (sid=13951): SHOT уходил 131 с, SYNC_REQ от
        // соперника пришёл раньше SHOT_RESULT. Ответ дайджестом «до
        // результата» заставил соперника (в покое) увидеть ложное расхождение
        // и завершить партию SYNC_CONFLICT. Теперь отвечающая сторона в
        // непокое молчит, а повторы SYNC_REQ по расписанию позже получают
        // честный дайджест, когда результат применился.
        val net = startedPair(GameType.SEA_BATTLE)
        val sid = net.managerA.sessions_.value.first().session.sessionId
        net.managerA.ready(sid)
        net.pump()
        net.managerB.ready(sid)
        net.pump()
        assertEquals(SessionStatus.ACTIVE, net.managerA.session(sid)!!.status)

        // A выстрелил; кадр SHOT доставляем вручную — эфир к B «жив»,
        // результат обратно тонет → A остаётся в непокое (pendingShot)
        net.dropTo = net.nodeA
        assertTrue(net.managerA.submitMove(sid, MoveInput.Shoot(0)))
        val shot = net.sentLog.last { it.first == net.nodeB }.third
        net.dropOutbox()
        net.managerB.handleIncoming(net.nodeA, shot)
        val result = net.sentLog.last { it.first == net.nodeA }.third
        net.dropOutbox()
        assertEquals(
            0,
            (net.managerA.snapshot(sid) as SeaBattleSnapshot).pendingShot,
        )

        // гонка: B запрашивает синхронизацию, пока у A выстрел в полёте
        net.dropTo = null
        net.managerB.requestSync(sid)
        net.pump()
        // A молчит — ни одного SYNC_RES и ни одного ложного конфликта
        assertEquals(
            0,
            net.sentLog.count {
                it.first == net.nodeB && Envelope.decode(it.third)?.type == MsgType.SYNC_RES
            },
        )
        assertTrue(net.eventsA.conflicts.isEmpty())
        assertTrue(net.eventsB.conflicts.isEmpty())
        assertEquals(SessionStatus.ACTIVE, net.managerA.session(sid)!!.status)
        assertEquals(SessionStatus.ACTIVE, net.managerB.session(sid)!!.status)

        // эфир ожил: результат доходит к A — состояния сходятся
        net.managerA.handleIncoming(net.nodeB, result)
        assertEquals(-1, (net.managerA.snapshot(sid) as SeaBattleSnapshot).pendingShot)

        // повторный запрос синхронизации уже получает честный ответ
        // (вне кулдауна принудительного запроса — 5 с между нажатиями прошли)
        net.clock += SessionManager.FORCED_SYNC_COOLDOWN_MS + 1
        net.managerB.requestSync(sid)
        net.pump()
        assertEquals(
            1,
            net.sentLog.count {
                it.first == net.nodeB && Envelope.decode(it.third)?.type == MsgType.SYNC_RES
            },
        )
        assertTrue(net.eventsA.conflicts.isEmpty())
        assertTrue(net.eventsB.conflicts.isEmpty())
        assertEquals(SessionStatus.ACTIVE, net.managerA.session(sid)!!.status)
        assertEquals(SessionStatus.ACTIVE, net.managerB.session(sid)!!.status)
        // партия продолжается: ходит сторона по итогу выстрела
        // (промах → ход соперника, попадание → снова стрелявший)
        // партия продолжается: ходит сторона по итогу выстрела
        // (промах → ход соперника, попадание → снова стрелявший).
        // Клетку берём дальнюю: убитый корабль помечает соседние клетки
        // (у нуля — 1, 10 и 11) промахом, и выстрел в них нелегален.
        val turnSide = (net.managerA.snapshot(sid) as SeaBattleSnapshot).turn
        val shooter = if (turnSide == Side.A) net.managerA else net.managerB
        assertTrue(shooter.submitMove(sid, MoveInput.Shoot(99)))
    }

    @Test
    fun stuckShotResultHealsViaJournalRescueInsteadOfConflict() {
        // Полевой сценарий sid=40290: SHOT дошёл до B, SHOT_RESULT потерян
        // безвозвратно (оба расписания исчерпаны). По таймауту A сбрасывает
        // pendingShot и шлёт SYNC_REQ с хвостом журнала — B видит, что
        // результат «передержан», и досылает его по исходному seq (дырка в
        // rxNext A закрывается), A восстанавливает состояние на момент
        // выстрела и применяет поздний результат. Раньше здесь была ничья
        // SYNC_CONFLICT — теперь партия продолжается.
        val net = PairNetwork()
        net.start()
        val sid = net.managerA.invite(net.nodeB, GameType.SEA_BATTLE)
        net.pump()
        net.managerB.respondInvite(sid, true)
        net.pump()
        net.managerA.ready(sid)
        net.pump()
        net.managerB.ready(sid)
        net.pump()
        assertEquals(SessionStatus.ACTIVE, net.managerA.session(sid)!!.status)

        // эфир в сторону A мёртв; SHOT доставляем B вручную
        net.dropTo = net.nodeA
        assertTrue(net.managerA.submitMove(sid, MoveInput.Shoot(0)))
        val shot = net.sentLog.last { it.first == net.nodeB }.third
        net.dropOutbox()
        net.managerB.handleIncoming(net.nodeA, shot) // B применил, SHOT_RESULT тонет

        // выстрел в полёте на обеих сторонах; расписание SHOT_RESULT
        // исчерпано — все повторы ушли в мёртвый эфир
        assertTrue((net.managerA.snapshot(sid) as SeaBattleSnapshot).pendingShot != -1)
        net.clock += ReliableChannel.RETRY_GAPS_MS.sum() + 10_000
        net.managerA.tick(net.clock)
        net.managerB.tick(net.clock)
        net.dropOutbox()
        assertTrue(net.eventsA.conflicts.isEmpty())

        // до таймаута не лечим — окно ещё идёт
        net.clock = 100_000L + SessionManager.PENDING_SHOT_TIMEOUT_MS - 1_000
        net.managerA.tick(net.clock)
        assertTrue((net.managerA.snapshot(sid) as SeaBattleSnapshot).pendingShot != -1)
        assertTrue(net.eventsA.conflicts.isEmpty())
        assertEquals(SessionStatus.ACTIVE, net.managerA.session(sid)!!.status)

        // таймаут: pending сброшен, SYNC_REQ ушёл; эфир ожил
        net.clock += 2_000
        net.managerA.tick(net.clock)
        assertEquals(-1, (net.managerA.snapshot(sid) as SeaBattleSnapshot).pendingShot)
        net.dropTo = null
        net.pump() // SYNC_REQ → SYNC_RES с хвостом → досылка SHOT_RESULT → rescue

        // Конфликта нет: сверка журналов дослала результат, партия идёт.
        assertTrue(net.eventsA.conflicts.isEmpty())
        assertTrue(net.eventsB.conflicts.isEmpty())
        assertTrue(net.eventsA.over.isEmpty())
        assertTrue(net.eventsB.over.isEmpty())
        assertEquals(SessionStatus.ACTIVE, net.managerA.session(sid)!!.status)
        assertEquals(SessionStatus.ACTIVE, net.managerB.session(sid)!!.status)
        assertEquals(net.managerA.digest(sid), net.managerB.digest(sid))
        // Поздний результат применён: выстрел закрыт, ход перешёл по правилам.
        assertEquals(-1, (net.managerA.snapshot(sid) as SeaBattleSnapshot).pendingShot)
        // дырка в rxNext закрыта досланным кадром (SHOT_RESULT применился)
        assertEquals(4, net.managerA.session(sid)!!.rxNext)
    }

    @Test
    fun stuckShotHealContinuesGameWhenShotNeverArrived() {
        // Второй исход исцеления: SHOT до B не дошёл вовсе → после отказа
        // от pendingShot дайджесты СОШЛИСЬ → партия продолжается без конфликта.
        val storeA = InMemorySessionStore()
        val net = PairNetwork()
        net.start(storeA, InMemorySessionStore())
        val sid = net.managerA.invite(net.nodeB, GameType.SEA_BATTLE)
        net.pump()
        net.managerB.respondInvite(sid, true)
        net.pump()
        net.managerA.ready(sid)
        net.pump()
        net.managerB.ready(sid)
        net.pump()

        // эфир в сторону B мёртв: SHOT тонет, B ничего не видел
        net.dropTo = net.nodeB
        assertTrue(net.managerA.submitMove(sid, MoveInput.Shoot(0)))
        assertTrue((net.managerA.snapshot(sid) as SeaBattleSnapshot).pendingShot != -1)
        // SHOT в расписании повторов (непОДТВЕРЖДЁННЫЙ — вечный режим)
        assertTrue(
            storeA.data.first { it.sessionId == sid }.unacked
                .any { it.type == MsgType.SHOT.id },
        )

        net.clock += ReliableChannel.RETRY_GAPS_MS.sum() + 10_000
        net.managerA.tick(net.clock)
        net.managerB.tick(net.clock)
        net.dropOutbox()

        // до таймаута — выстрел всё ещё «в полёте»
        net.clock = 100_000L + SessionManager.PENDING_SHOT_TIMEOUT_MS - 1_000
        net.managerA.tick(net.clock)
        assertTrue((net.managerA.snapshot(sid) as SeaBattleSnapshot).pendingShot != -1)

        // таймаут + эфир ожил: SYNC проходит, дайджесты сошлись — играем дальше
        net.dropTo = null
        net.clock += 2_000
        net.managerA.tick(net.clock)
        assertEquals(-1, (net.managerA.snapshot(sid) as SeaBattleSnapshot).pendingShot)
        net.pump()
        assertTrue(net.eventsA.conflicts.isEmpty())
        assertTrue(net.eventsA.over.isEmpty())
        assertEquals(SessionStatus.ACTIVE, net.managerA.session(sid)!!.status)
        assertEquals(SessionStatus.ACTIVE, net.managerB.session(sid)!!.status)
        // Отброшенный ход забыт в расписании (forgetGameFrames): вечный
        // повтор не «применит» его у пира, когда эфир оживёт
        assertFalse(
            storeA.data.first { it.sessionId == sid }.unacked
                .any { it.type == MsgType.SHOT.id },
        )
        // ход остался за A (turn не менялся) и клетка 1 свободна
        assertTrue(net.managerA.submitMove(sid, MoveInput.Shoot(1)))
    }

    @Test
    fun stuckShotHealsAfterRestart() {
        // pendingShot переживает рестарт в движке, но таймер живёт в Runtime
        // (память) — окно лечения стартует заново от первого тика.
        val storeA = InMemorySessionStore()
        val storeB = InMemorySessionStore()
        val net = PairNetwork()
        net.start(storeA, storeB)
        val sid = net.managerA.invite(net.nodeB, GameType.SEA_BATTLE)
        net.pump()
        net.managerB.respondInvite(sid, true)
        net.pump()
        net.managerA.ready(sid)
        net.pump()
        net.managerB.ready(sid)
        net.pump()

        net.dropTo = net.nodeA
        assertTrue(net.managerA.submitMove(sid, MoveInput.Shoot(0)))
        val shot = net.sentLog.last { it.first == net.nodeB }.third
        net.dropOutbox()
        net.managerB.handleIncoming(net.nodeA, shot)
        net.clock += ReliableChannel.RETRY_GAPS_MS.sum() + 10_000
        net.managerA.tick(net.clock)
        net.managerB.tick(net.clock)
        net.dropOutbox()

        // «Рестарт» A: pendingShot восстановился из движка
        net.managerA = SessionManager(storeA, net.transportRef(net.nodeA), net.eventsA, { net.clock })
        assertEquals(0, (net.managerA.snapshot(sid) as SeaBattleSnapshot).pendingShot)

        // первый тик открывает окно таймера заново — до его конца не лечим
        net.managerA.tick(net.clock)
        assertTrue((net.managerA.snapshot(sid) as SeaBattleSnapshot).pendingShot != -1)

        // полное окно от старта — исцеление через сверку с журналом пира:
        // журнал A утрачен рестартом (Noop-стор), но B видит «передержанный»
        // результат и досылает его по исходному seq; A восстанавливает
        // состояние на момент выстрела (базовая точка несла pendingShot)
        // и применяет результат — конфликта нет, партия продолжается.
        net.clock += SessionManager.PENDING_SHOT_TIMEOUT_MS + 1_000
        net.managerA.tick(net.clock)
        assertEquals(-1, (net.managerA.snapshot(sid) as SeaBattleSnapshot).pendingShot)
        net.dropTo = null
        net.pump()
        assertTrue(net.eventsA.conflicts.isEmpty())
        assertTrue(net.eventsB.conflicts.isEmpty())
        assertEquals(SessionStatus.ACTIVE, net.managerA.session(sid)!!.status)
        assertEquals(SessionStatus.ACTIVE, net.managerB.session(sid)!!.status)
        assertEquals(net.managerA.digest(sid), net.managerB.digest(sid))
        assertEquals(-1, (net.managerA.snapshot(sid) as SeaBattleSnapshot).pendingShot)
    }

    /** Индикатор латентности: piggyback-cumAck пира даёт замер для UI. */
    @Test
    fun rttFlowMeasuresAfterPeerAckAndIsNullForUnknownSession() {
        val net = PairNetwork()
        net.start()
        val sid = net.managerA.invite(net.nodeB, GameType.TIC_TAC_TOE)
        // INVITE ушёл — подтверждения ещё не было.
        assertNull(net.managerA.rttFlow(sid).value)
        net.pump()
        net.managerB.respondInvite(sid, true)
        // INVITE_RESP погасил INVITE cumAck'ом — замер появился.
        net.pump()
        val rtt = net.managerA.rttFlow(sid)
        assertNotNull(rtt.value)
        // Часы общие и без потерь: замер от первой отправки до ответа = 0 мс.
        assertEquals(0L, rtt.value!!)

        // Несуществующая сессия — читается как «неизвестно», без падений.
        assertNull(net.managerA.rttFlow(0x7777).value)
    }

    /**
     * Подтверждённая расстановка (морской бой) переживает рестарт: myReady в
     * сессии и myConfirmed в engineState блокируют перестановку и повторный
     * «Готово» — кнопки после рестарта не дают сбросить чужие корабли.
     */
    @Test
    fun confirmedPlacementSurvivesRestartAndBlocksReroll() {
        val net = PairNetwork()
        val storeA = InMemorySessionStore()
        net.start(storeA)
        val sid = net.managerA.invite(net.nodeB, GameType.SEA_BATTLE)
        net.pump()
        net.managerB.respondInvite(sid, true)
        net.pump()
        assertEquals(SessionStatus.PLACEMENT, net.managerA.session(sid)!!.status)
        assertTrue(net.managerA.reroll(sid)) // до подтверждения перестановка работает
        assertTrue(net.managerA.ready(sid))
        assertTrue(net.managerA.session(sid)!!.myReady)
        assertFalse(net.managerA.reroll(sid)) // уже подтверждено

        // Рестарт поверх того же стора: myReady и engineState на месте.
        net.managerA = SessionManager(
            storeA, net.transportRef(net.nodeA), net.eventsA, { net.clock },
        )
        val restored = net.managerA.session(sid)!!
        assertTrue(restored.myReady)
        assertEquals(SessionStatus.PLACEMENT, restored.status)
        assertFalse(net.managerA.reroll(sid)) // перестановка блокирована
        assertFalse(net.managerA.ready(sid)) // повторный «Готово» не шлём
    }
}
