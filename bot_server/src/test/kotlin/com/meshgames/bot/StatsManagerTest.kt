package com.meshgames.bot

import com.meshgames.bot.stats.StatsManager
import com.meshgames.net.bridge.BridgeCodec
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StatsManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun emptyStatsFormatsGracefully() {
        val file = File(tmp.root, "stats.json")
        val mgr = StatsManager(file)

        val brief = mgr.formatBriefTable()
        assertTrue(brief.contains("Игр пока нет"))
        assertTrue(brief.length <= 100)
        assertTrue(brief.toByteArray(Charsets.UTF_8).size <= 200)

        val full = mgr.formatFullTable()
        assertTrue(full.contains("Статистика пуста"))
    }

    @Test
    fun recordGamesAndLeaderboard() {
        val file = File(tmp.root, "stats.json")
        val mgr = StatsManager(file)

        mgr.recordPlayerName(100, "Alice")
        mgr.recordPlayerName(200, "Bob")
        mgr.recordPlayerName(300, "Charlie")

        // Game 1: Alice (A) vs Bob (B) -> Alice wins (winner=0)
        assertTrue(mgr.recordGame(BridgeCodec.GameStat(
            gameTypeId = 1, sessionId = 1, winner = 0, reason = 0, playerA = 100, playerB = 200
        )))

        // Duplicate of Game 1 -> should return false
        assertFalse(mgr.recordGame(BridgeCodec.GameStat(
            gameTypeId = 1, sessionId = 1, winner = 0, reason = 0, playerA = 100, playerB = 200
        )))

        // Game 2: Bob (A) vs Charlie (B) -> Charlie wins (winner=1)
        assertTrue(mgr.recordGame(BridgeCodec.GameStat(
            gameTypeId = 2, sessionId = 2, winner = 1, reason = 0, playerA = 200, playerB = 300
        )))

        // Game 3: Alice (A) vs Charlie (B) -> Draw (winner=2)
        assertTrue(mgr.recordGame(BridgeCodec.GameStat(
            gameTypeId = 3, sessionId = 3, winner = 2, reason = 0, playerA = 100, playerB = 300
        )))

        val leaderboard = mgr.getLeaderboard()
        assertEquals(3, leaderboard.size)

        // Alice: 2 games, 1 win, 0 loss, 1 draw
        val alice = leaderboard.find { it.nodeId == 100 }!!
        assertEquals(2, alice.games)
        assertEquals(1, alice.wins)
        assertEquals(0, alice.losses)
        assertEquals(1, alice.draws)

        // Charlie: 2 games, 1 win, 0 loss, 1 draw
        val charlie = leaderboard.find { it.nodeId == 300 }!!
        assertEquals(2, charlie.games)
        assertEquals(1, charlie.wins)

        // Bob: 2 games, 0 wins, 2 losses, 0 draws
        val bob = leaderboard.find { it.nodeId == 200 }!!
        assertEquals(2, bob.games)
        assertEquals(0, bob.wins)
        assertEquals(2, bob.losses)

        // Verify brief table strictly obeys length limit
        val brief = mgr.formatBriefTable()
        println("Brief table:\n$brief")
        assertTrue(brief.length <= 100)
        assertTrue(brief.toByteArray(Charsets.UTF_8).size <= 200)
        assertTrue(brief.contains("Alice"))

        val full = mgr.formatFullTable()
        println("Full table:\n$full")
        assertTrue(full.contains("Alice (0x64)"))
        assertTrue(full.contains("Всего проведенных игр: 3"))
    }

    @Test
    fun briefTableWithLongNamesStrictlyUnderLimits() {
        val file = File(tmp.root, "stats.json")
        val mgr = StatsManager(file)

        mgr.recordPlayerName(1, "ДлинноеИмяИгрока123")
        mgr.recordPlayerName(2, "ОченьДлинныйПсевдоним456")
        mgr.recordPlayerName(3, "СуперИгрокСетевойБот789")

        for (i in 1..10) {
            mgr.recordGame(BridgeCodec.GameStat(
                gameTypeId = 1, sessionId = i, winner = i % 3, reason = 0, playerA = 1, playerB = 2
            ))
        }

        val brief = mgr.formatBriefTable()
        println("Long names brief table:\n$brief")
        assertTrue("Brief length: ${brief.length}", brief.length <= 100)
        assertTrue("Brief bytes: ${brief.toByteArray(Charsets.UTF_8).size}", brief.toByteArray(Charsets.UTF_8).size <= 200)
    }

    @Test
    fun reloadFromFilePreservesStats() {
        val file = File(tmp.root, "stats.json")
        val mgr1 = StatsManager(file)
        mgr1.recordPlayerName(555, "TestBot")
        mgr1.recordGame(BridgeCodec.GameStat(
            gameTypeId = 4, sessionId = 999, winner = 0, reason = 1, playerA = 555, playerB = 666
        ))

        // Reload from same file
        val mgr2 = StatsManager(file)
        assertEquals("TestBot", mgr2.getPlayerName(555))
        assertEquals(2, mgr2.getLeaderboard().size)
        // Deduplication works across reloads
        assertFalse(mgr2.recordGame(BridgeCodec.GameStat(
            gameTypeId = 4, sessionId = 999, winner = 0, reason = 1, playerA = 555, playerB = 666
        )))
    }
}
