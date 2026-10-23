package com.meshgames.game.session

import com.meshgames.game.GameType
import com.meshgames.game.MoveInput
import com.meshgames.game.ResultReason
import com.meshgames.game.Side
import com.meshgames.game.protocol.Envelope
import com.meshgames.game.protocol.JournalTailPayload
import com.meshgames.game.protocol.MsgType
import com.meshgames.game.protocol.SyncResPayload
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Восстановление партии после расхождения состояний: сверка хвостов
 * канонического журнала — досылка «передержанных» записей, проверенный
 * откат вместо ничьей SYNC_CONFLICT.
 */
class SyncRecoveryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** Начатая пара менеджеров + идентификатор сессии. */
    private class Started(val net: PairNetwork, val sid: Int)

    private fun startedPair(
        game: GameType = GameType.TIC_TAC_TOE,
        net: PairNetwork = PairNetwork(),
    ): Started {
        net.start()
        val sid = net.managerA.invite(net.nodeB, game)
        net.pump()
        net.managerB.respondInvite(sid, true)
        net.pump()
        return Started(net, sid)
    }

    /** Потерянный MOVE: сверка досылает запись из журнала вместо ничьей. */
    @Test
    fun lostMoveIsResentFromJournalInsteadOfConflict() {
        val started = startedPair()
        val net = started.net
        val sid = started.sid

        // A делает ход — кадр «тонет» в эфире (ретраи канала ещё в расписании).
        assertTrue(net.managerA.submitMove(sid, MoveInput.Cell(4)))
        net.dropOutbox()

        // Сверка: A несёт хвост с записью, B отстаёт и ждёт досылку;
        // A видит себя впереди и досылает запись из журнала.
        net.managerA.requestSync(sid)
        net.pump()

        assertEquals(net.managerA.digest(sid), net.managerB.digest(sid))
        assertTrue(net.eventsA.conflicts.isEmpty())
        assertTrue(net.eventsB.conflicts.isEmpty())
        // Откат не понадобился — только досылка.
        assertTrue(net.eventsA.recovered.isEmpty())
        assertTrue(net.eventsB.recovered.isEmpty())
        assertEquals(Side.B, net.managerA.snapshot(sid)!!.turn)
        assertEquals(1, net.managerA.journalEvents(sid))
        assertEquals(1, net.managerB.journalEvents(sid))
    }

    /**
     * Полевой сценарий: результат выстрела потерялся, по таймауту A сбросил
     * pendingShot — раньше это вело к ничьей SYNC_CONFLICT. Теперь сверка
     * досылает результат из журнала пира, а поздний результат применяется
     * после восстановления состояния на момент выстрела.
     */
    @Test
    fun lostShotResultHealsViaJournalRescueInsteadOfConflict() {
        val started = startedPair(GameType.SEA_BATTLE)
        val net = started.net
        val sid = started.sid
        assertTrue(net.managerA.ready(sid))
        assertTrue(net.managerB.ready(sid))
        net.pump()
        assertEquals(SessionStatus.ACTIVE, net.managerA.session(sid)!!.status)

        // Выстрел A достаётся B, а SHOT_RESULT обратно — тонет.
        net.dropTo = net.nodeA
        assertTrue(net.managerA.submitMove(sid, MoveInput.Shoot(0)))
        net.pump()
        net.dropTo = null

        // Лечение по таймауту: сброс pendingShot + SYNC_REQ с хвостом журнала.
        net.advanceTime(300_000)

        // Досланный пиром результат применён (через восстановление покоя) —
        // состояния сошлись, конфликта нет, партия идёт.
        assertEquals(net.managerA.digest(sid), net.managerB.digest(sid))
        assertTrue(net.eventsA.conflicts.isEmpty())
        assertTrue(net.eventsB.conflicts.isEmpty())
        assertEquals(SessionStatus.ACTIVE, net.managerA.session(sid)!!.status)
        assertEquals(2, net.managerA.journalEvents(sid))
        assertEquals(2, net.managerB.journalEvents(sid))
    }

    /** Старый пир без хвоста журнала: поведение прежнее — конфликт = ничья. */
    @Test
    fun syncResWithoutTailFromLegacyPeerEndsInConflict() {
        val started = startedPair()
        val net = started.net
        val sid = started.sid

        // Подлинный SYNC_RES от «старого APK»: ядро 6 байт, хвоста нет,
        // дайджест чужой.
        val env = Envelope(
            MsgType.SYNC_RES, sid, 909,
            SyncResPayload(
                cumAck = 0, turn = 0,
                status = SessionStatus.ACTIVE.ordinal,
                digest = net.managerA.digest(sid) xor 0xFFFF,
            ).encode(),
        )
        net.managerA.handleIncoming(net.nodeB, env.encode())

        assertEquals(listOf(sid), net.eventsA.conflicts)
        assertTrue(net.eventsA.recovered.isEmpty())
        assertEquals(SessionStatus.FINISHED, net.managerA.session(sid)!!.status)
        assertEquals(ResultReason.SYNC_CONFLICT, net.eventsA.over.last().second.reason)
    }

    /**
     * Настоящий раскол (цепи в общем окне разошлись) → проверенный откат к
     * последней записи, чей снимок подтверждается дайджестом пира, без
     * завершения партии. Раскол моделируется поддельным SYNC_RES: запись #1
     * пира «чужая», а дайджест базовой точки подлинный.
     */
    @Test
    fun divergentChainsRollBackToVerifiedCommonPrefix() {
        val started = startedPair()
        val net = started.net
        val sid = started.sid
        val baselineDigest = net.managerA.digest(sid) // исходная позиция ещё не тронута

        assertTrue(net.managerA.submitMove(sid, MoveInput.Cell(4)))
        net.dropOutbox()
        // Корреляция ответов синхронизации (реальный диалог ничего не меняет).
        net.managerA.requestSync(sid)
        net.pump()

        val tail = JournalTailPayload(
            digest = baselineDigest xor 0xFFFF, // текущий дайджест «пира» чужой
            entries = listOf(
                JournalTailPayload.TailEntry(0, 0, baselineDigest),
                JournalTailPayload.TailEntry(1, 0x7EADBEEF, baselineDigest),
            ),
        )
        val env = Envelope(
            MsgType.SYNC_RES, sid, 911,
            SyncResPayload(
                cumAck = 0, turn = 0,
                status = SessionStatus.ACTIVE.ordinal,
                digest = net.managerA.digest(sid) xor 0xFFFF,
            ).encode() + tail.encode(),
        )
        net.managerA.handleIncoming(net.nodeB, env.encode())

        // Откат к базовой точке: ход #1 утрачен, партия не завершена.
        assertEquals(listOf(sid to 0), net.eventsA.recovered)
        assertTrue(net.eventsA.conflicts.isEmpty())
        assertEquals(SessionStatus.ACTIVE, net.managerA.session(sid)!!.status)
        assertEquals(0, net.managerA.journalEvents(sid))
        assertEquals(baselineDigest, net.managerA.digest(sid))
    }

    /** При удалении сессии её журнал (включая файл на диске) удаляется. */
    @Test
    fun deletingSessionRemovesItsJournal() {
        val net = PairNetwork()
        val journalsA = InMemoryJournalStore()
        net.managerA = SessionManager(
            InMemorySessionStore(), net.transportRef(net.nodeA), net.eventsA, { net.clock },
            journals = journalsA,
        )
        net.managerB = SessionManager(
            InMemorySessionStore(), net.transportRef(net.nodeB), net.eventsB, { net.clock },
        )
        val sid = net.managerA.invite(net.nodeB, GameType.TIC_TAC_TOE)
        net.pump()
        net.managerB.respondInvite(sid, true)
        net.pump()

        // Журнал существует (базовая точка уже записана на диск).
        assertEquals(setOf(sid), journalsA.data.keys.toSet())

        net.managerA.deleteSession(sid)
        assertTrue(sid !in journalsA.data)
        assertTrue(journalsA.list().isEmpty())
    }

    /** Журнал переживает рестарт: сверка и досылка работают после восстановления. */
    @Test
    fun journalSurvivesRestartAndKeepsRepairing() {
        val net = PairNetwork()
        val appDir = tmp.newFolder("app")
        val storeA = JsonSessionStore(File(appDir, "sessions.json"))
        val journalsA = JsonJournalStore(File(appDir, "journals"))
        net.managerA = SessionManager(
            storeA, net.transportRef(net.nodeA), net.eventsA, { net.clock },
            journals = journalsA,
        )
        net.managerB = SessionManager(
            InMemorySessionStore(), net.transportRef(net.nodeB), net.eventsB, { net.clock },
        )
        val sid = net.managerA.invite(net.nodeB, GameType.TIC_TAC_TOE)
        net.pump()
        net.managerB.respondInvite(sid, true)
        net.pump()

        // Ход A утонул; сессия и журнал уходят на диск.
        assertTrue(net.managerA.submitMove(sid, MoveInput.Cell(4)))
        net.dropOutbox()
        assertTrue(sid in journalsA.list())

        // Рестарт A: менеджер пересобран поверх тех же сторов.
        net.managerA = SessionManager(
            storeA, net.transportRef(net.nodeA), net.eventsA, { net.clock },
            journals = journalsA,
        )

        // Журнал восстановлен (событие #1 на месте) и сверка досылает ход.
        assertEquals(1, net.managerA.journalEvents(sid))
        net.managerA.requestSync(sid)
        net.pump()
        assertEquals(net.managerA.digest(sid), net.managerB.digest(sid))
        assertTrue(net.eventsA.conflicts.isEmpty())

        // Удаление сессии убирает файл журнала.
        net.managerA.deleteSession(sid)
        assertTrue(sid !in journalsA.list())
    }
}
