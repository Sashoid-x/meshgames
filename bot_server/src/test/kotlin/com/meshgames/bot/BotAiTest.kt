package com.meshgames.bot

import com.meshgames.bot.ai.BaldaAi
import com.meshgames.bot.ai.CheckersAi
import com.meshgames.bot.ai.ChessAi
import com.meshgames.bot.ai.DurakAi
import com.meshgames.bot.ai.SeaBattleAi
import com.meshgames.bot.ai.TttAi
import com.meshgames.game.DurakPhase
import com.meshgames.game.MoveInput
import com.meshgames.game.SeaPhase
import com.meshgames.game.Side
import com.meshgames.game.TttSnapshot
import com.meshgames.game.engine.BaldaEngine
import com.meshgames.game.engine.CheckersEngine
import com.meshgames.game.engine.ChessEngine
import com.meshgames.game.engine.DurakEngine
import com.meshgames.game.engine.GameEngines
import com.meshgames.game.engine.SeaBattleEngine
import com.meshgames.game.engine.TttEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class BotAiTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun setupDictionary() {
            // Инициализируем словарь для Балды
            val words = listOf("балда", "арбуз", "баран", "банка", "кабан", "кулак", "лодка", "балка")
            GameEngines.wordChecker = BaldaDictionaryLoader.fromWords(words)
        }
    }

    @Test
    fun testTttAiWinsWhenPossible() {
        val engine = TttEngine(Side.A)
        // X . .
        // X O .
        // . . O
        // Ход X -> должен пойти в клетку 6 и победить!
        engine.localMove(MoveInput.Cell(0)) // X: 0
        engine.remoteMove(byteArrayOf(4))   // O: 4
        engine.localMove(MoveInput.Cell(3)) // X: 3
        engine.remoteMove(byteArrayOf(8))   // O: 8

        val move = TttAi.computeMove(engine)
        assertNotNull(move)
        assertTrue(move is MoveInput.Cell)
        assertEquals(6, (move as MoveInput.Cell).index)
    }

    @Test
    fun testTttAiBlocksOpponentWin() {
        val engine = TttEngine(Side.B)
        // X X .
        // O . .
        // . . .
        // Очередь O (Side.B) -> должен заблокировать X в клетку 2!
        engine.remoteMove(byteArrayOf(0)) // X: 0
        engine.localMove(MoveInput.Cell(3))  // O: 3
        engine.remoteMove(byteArrayOf(1)) // X: 1

        val move = TttAi.computeMove(engine)
        assertNotNull(move)
        assertTrue(move is MoveInput.Cell)
        assertEquals(2, (move as MoveInput.Cell).index)
    }

    @Test
    fun testTttSelfPlayEndsInDraw() {
        val engineA = TttEngine(Side.A)
        val engineB = TttEngine(Side.B)

        while (!engineA.finished && !engineB.finished) {
            val turn = engineA.turn ?: break
            if (turn == Side.A) {
                val move = TttAi.computeMove(engineA) as MoveInput.Cell
                val payload = engineA.localMove(move)!!
                engineB.remoteMove(payload)
            } else {
                val move = TttAi.computeMove(engineB) as MoveInput.Cell
                val payload = engineB.localMove(move)!!
                engineA.remoteMove(payload)
            }
        }

        assertTrue(engineA.finished)
        assertEquals(null, engineA.result?.winner) // ничья
    }

    @Test
    fun testChessAiComputesLegalMove() {
        val engine = ChessEngine(Side.A)
        val move = ChessAi(depth = 2).computeMove(engine)
        assertNotNull(move)
        assertTrue(move is MoveInput.FromTo)
        val payload = engine.localMove(move!!)
        assertNotNull(payload)
    }

    @Test
    fun testCheckersAiComputesMove() {
        val engine = CheckersEngine(Side.A)
        val move = CheckersAi(depth = 3).computeMove(engine)
        assertNotNull(move)
        assertTrue(move is MoveInput.Chain)
        val payload = engine.localMove(move!!)
        assertNotNull(payload)
    }

    @Test
    fun testSeaBattleAiTargetsWoundedShip() {
        val engine = SeaBattleEngine(Side.A)
        engine.confirmShips()
        engine.beginBattle()

        // Имитируем выстрел и попадание в клетку 44
        engine.localShot(44)
        engine.applyShotResult(byteArrayOf(44, 1)) // 1 = CELL_HIT

        // Теперь наш следующий выстрел должен быть в соседнюю клетку (34, 54, 43 или 45)
        val shot = SeaBattleAi.computeMove(engine)
        assertNotNull(shot)
        assertTrue(shot is MoveInput.Shoot)
        val target = (shot as MoveInput.Shoot).coord
        assertTrue(target in listOf(34, 54, 43, 45))
    }

    @Test
    fun testDurakAiPlays() {
        // Сид 12345
        val engine = DurakEngine(Side.A, sessionId = 12345)
        val snap = engine.snapshot()

        if (snap.phase == DurakPhase.ATTACK && engine.turn == Side.A) {
            val move = DurakAi.computeMove(engine)
            assertNotNull(move)
            val payload = engine.localMove(move!!)
            assertNotNull(payload)
        }
    }

    @Test
    fun testAiFactoryProducesDistinctDifficulties() {
        val easyTtt = com.meshgames.bot.ai.BotAiFactory.create(com.meshgames.game.GameType.TIC_TAC_TOE, "easy")
        val hardTtt = com.meshgames.bot.ai.BotAiFactory.create(com.meshgames.game.GameType.TIC_TAC_TOE, "hard")
        assertTrue(easyTtt is TttAi && easyTtt.difficulty == "easy")
        assertTrue(hardTtt is TttAi && hardTtt.difficulty == "hard")

        val easyBalda = com.meshgames.bot.ai.BotAiFactory.create(com.meshgames.game.GameType.BALDA, "easy")
        val hardBalda = com.meshgames.bot.ai.BotAiFactory.create(com.meshgames.game.GameType.BALDA, "hard")
        assertTrue(easyBalda is BaldaAi && easyBalda.difficulty == "easy")
        assertTrue(hardBalda is BaldaAi && hardBalda.difficulty == "hard")

        val easyDurak = com.meshgames.bot.ai.BotAiFactory.create(com.meshgames.game.GameType.DURAK, "easy")
        val hardDurak = com.meshgames.bot.ai.BotAiFactory.create(com.meshgames.game.GameType.DURAK, "hard")
        assertTrue(easyDurak is DurakAi && easyDurak.difficulty == "easy")
        assertTrue(hardDurak is DurakAi && hardDurak.difficulty == "hard")
    }
}
