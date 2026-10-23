package com.meshgames.game.engine

import com.meshgames.game.GameEngine
import com.meshgames.game.MoveInput
import com.meshgames.game.Side
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Автоход тестового бота ([GameEngine.botMove]) в каждом движке: ход
 * детерминирован, легален (прогоняется через штатный localMove), не меняет
 * состояние движка до применения и всегда оставляет дайджесты синхронными.
 */
class BotMoveTest {

    private class FakeWords(words: Collection<String>) : WordChecker {
        private val set = words.map { BaldaEngine.normalize(it) }.toSet()
        override fun contains(word: String) = word in set
        override fun startWords() = listOf("балда")
    }

    /** «ба» намеренно НЕТ — бот не должен выдавать спорные слова. */
    private val dict = FakeWords(listOf("балда", "бы"))

    @Before
    fun installDict() {
        GameEngines.wordChecker = dict
    }

    @After
    fun restoreDict() {
        GameEngines.wordChecker = EmptyWordChecker
    }

    /**
     * Оба движка играют бот против бота до конца или лимита ходов:
     * ход A применяется к B и наоборот, дайджесты обязаны совпадать.
     */
    private fun playBoth(a: GameEngine, b: GameEngine, steps: Int): Int {
        var made = 0
        repeat(steps) {
            if (a.finished || b.finished) return made
            val payloadA = a.localMove(a.botMove() ?: return made)
                ?: error("бот A предложил нелегальный ход")
            made++
            assertTrue("A: ход отклонён соперником", b.remoteMove(payloadA))
            assertEquals("дайджесты разошлись после хода A ($made)", a.digest(), b.digest())
            if (a.finished || b.finished) return made
            val payloadB = b.localMove(b.botMove() ?: return made)
                ?: error("бот B предложил нелегальный ход")
            made++
            assertTrue("B: ход отклонён соперником", a.remoteMove(payloadB))
            assertEquals("дайджесты разошлись после хода B ($made)", a.digest(), b.digest())
        }
        return made
    }

    // ------------------------------------------------------------ крестики

    @Test
    fun tttBotPrefersCenterWhenNothingAtStake() {
        assertEquals(MoveInput.Cell(4), TttEngine(Side.A).botMove())
    }

    @Test
    fun tttBotBlocksOpponentThreat() {
        val e = TttEngine(Side.A)
        // A: 4, 8 — B: 0, 1. У A выигрыша нет, B угрожает 0-1-2.
        assertNotNull(e.localMove(MoveInput.Cell(4)))
        assertTrue(e.remoteMove(byteArrayOf(0)))
        assertNotNull(e.localMove(MoveInput.Cell(8)))
        assertTrue(e.remoteMove(byteArrayOf(1)))
        assertEquals(MoveInput.Cell(2), e.botMove())
    }

    @Test
    fun tttBotTakesWinBeforeBlock() {
        val e = TttEngine(Side.A)
        // A: 3, 4 — B: 0, 1. A выигрывает по 3-4-5, блокировать 0-1-2 не нужно.
        assertNotNull(e.localMove(MoveInput.Cell(3)))
        assertTrue(e.remoteMove(byteArrayOf(0)))
        assertNotNull(e.localMove(MoveInput.Cell(4)))
        assertTrue(e.remoteMove(byteArrayOf(1)))
        assertEquals(MoveInput.Cell(5), e.botMove())
    }

    @Test
    fun tttBotDoesNotMutateBeforeMove() {
        val e = TttEngine(Side.A)
        val before = e.digest()
        val move = e.botMove()
        assertEquals("бот изменил состояние до хода", before, e.digest())
        assertNotNull(move)
        assertNotNull(e.localMove(move!!))
    }

    @Test
    fun tttBotIsQuietOnOpponentTurnAndAfterFinish() {
        assertNull(TttEngine(Side.B).botMove())
        val done = TttEngine(Side.A)
        // A: 0,1,2 — выигрыш.
        assertNotNull(done.localMove(MoveInput.Cell(0)))
        assertTrue(done.remoteMove(byteArrayOf(3)))
        assertNotNull(done.localMove(MoveInput.Cell(1)))
        assertTrue(done.remoteMove(byteArrayOf(4)))
        assertNotNull(done.localMove(MoveInput.Cell(2)))
        assertTrue(done.finished)
        assertNull(done.botMove())
    }

    @Test
    fun tttBotVsBotFinishes() {
        val a = TttEngine(Side.A)
        val b = TttEngine(Side.B)
        playBoth(a, b, 20)
        assertTrue("боты не довели партию до конца", a.finished && b.finished)
    }

    // -------------------------------------------------------------- шахматы

    @Test
    fun chessBotOpensWithDeterministicQuietPawnMove() {
        val e = ChessEngine(Side.A)
        val move = e.botMove()
        assertNotNull(move)
        val m = move as MoveInput.FromTo
        // Первая белая пешка на доске (48 = a2) идёт на 40 (a3): ни одного
        // взятия в позиции, обход 0..63 детерминирован.
        assertEquals(48, m.from)
        assertEquals(40, m.to)
        assertEquals(0, m.promotion)
        assertNotNull(e.localMove(move))
        assertEquals(Side.B, e.turn)
        assertFalse(e.finished)
    }

    @Test
    fun chessBotTakesCaptureWhenAvailable() {
        val e = ChessEngine(Side.A)
        // 1. e4 (52→36), 1... d5 (11→27) — пешка соперника на диагонали.
        assertNotNull(e.localMove(MoveInput.FromTo(52, 36)))
        assertTrue(e.remoteMove(byteArrayOf(11, 27, 0)))
        val m = e.botMove() as MoveInput.FromTo
        assertEquals(36, m.from)
        assertEquals(27, m.to)
        assertNotNull(e.localMove(m))
        // Взятие принято: на 27 теперь белая пешка, очередь за чёрными.
        assertEquals(Side.B, e.turn)
    }

    @Test
    fun chessBotVsBotKeepsDigestsEqual() {
        val a = ChessEngine(Side.A)
        val b = ChessEngine(Side.B)
        val made = playBoth(a, b, 40)
        assertTrue("боты не сделали ни одного хода", made > 0)
        assertEquals(a.digest(), b.digest())
        assertEquals(a.finished, b.finished)
    }

    // --------------------------------------------------------------- шашки

    @Test
    fun checkersBotMoveIsLegalAndFlipsTurn() {
        val e = CheckersEngine(Side.A)
        val move = e.botMove()
        assertNotNull(move)
        assertNotNull(e.localMove(move!!))
        assertEquals(Side.B, e.turn)
        assertFalse(e.finished)
    }

    @Test
    fun checkersBotVsBotKeepsDigestsEqual() {
        val a = CheckersEngine(Side.A)
        val b = CheckersEngine(Side.B)
        val made = playBoth(a, b, 60)
        assertTrue("боты не сделали ни одного хода", made > 0)
        assertEquals(a.digest(), b.digest())
        assertFalse(a.finished != b.finished)
    }

    // ---------------------------------------------------------- морской бой

    @Test
    fun seaBattleBotShootsFirstCellAndWaitsForResult() {
        val e = SeaBattleEngine(Side.A)
        assertTrue(e.confirmShips())
        assertTrue(e.beginBattle())
        // Первый выстрел — первая необстрелянная клетка (обход 0..99).
        assertEquals(MoveInput.Shoot(0), e.botMove())
        // Выстрел ушёл в полёте — повторный бот не шлёт.
        assertNotNull(e.localMove(MoveInput.Shoot(0)))
        assertNull(e.botMove())
    }

    @Test
    fun seaBattleBotIsQuietOnOpponentTurnAndDuringPlacement() {
        assertNull(SeaBattleEngine(Side.B).botMove()) // расстановка
        val e = SeaBattleEngine(Side.B)
        assertTrue(e.confirmShips())
        assertTrue(e.beginBattle())
        assertNull(e.botMove()) // первый стрелок — A
    }

    @Test
    fun seaBattleBotVsBotFinishesGame() {
        val a = SeaBattleEngine(Side.A)
        val b = SeaBattleEngine(Side.B)
        assertTrue(a.confirmShips() && a.beginBattle())
        assertTrue(b.confirmShips() && b.beginBattle())
        var guard = 0
        while (!a.finished && !b.finished && guard++ < 400) {
            // Кто сейчас стреляет (попадание даёт ещё выстрел).
            val snap = a.snapshot()
            val shooter = if (snap.turn == Side.A) a else b
            val target = if (snap.turn == Side.A) b else a
            val shot = shooter.botMove() as? MoveInput.Shoot ?: error("нет выстрела: $snap")
            val payload = shooter.localShot(shot.coord) ?: error("нелегальный выстрел $shot")
            val result = target.remoteShot(payload) ?: error("выстрел $shot отклонён")
            assertTrue("результат выстрела не применён", shooter.applyShotResult(result))
        }
        assertTrue("бой не завершился за отведённые выстрелы", a.finished || b.finished)
        // Дайджест симметричен по сторонам — состояние обязано совпасть.
        assertEquals(a.digest(), b.digest())
        assertEquals(-1, a.snapshot().pendingShot)
        assertEquals(-1, b.snapshot().pendingShot)
    }

    // --------------------------------------------------------------- балда

    @Test
    fun baldaBotFindsDictionaryWordWithoutDispute() {
        val e = BaldaEngine(Side.A, sessionId = 0, checker = dict)
        val move = e.botMove()
        assertNotNull("бот не нашёл слово", move)
        val m = move as MoveInput.LetterAdd
        // Первое слово по порядку перебора: клетка 5 (над «б»), буква «ы»,
        // путь [10, 5] → «бы».
        assertEquals(5, m.cell)
        assertEquals(29, m.letter) // «ы»
        org.junit.Assert.assertArrayEquals(intArrayOf(10, 5), m.path)
        val payload = e.localMove(m)
        assertNotNull(payload)
        // Ход принят как словарное слово, а не как спор.
        assertEquals(BaldaEngine.KIND_WORD, payload!![0].toInt() and 0xFF)
    }

    @Test
    fun baldaBotDoesNotMutateBeforeMove() {
        val e = BaldaEngine(Side.A, sessionId = 0, checker = dict)
        val before = e.digest()
        e.botMove()
        assertEquals("бот изменил состояние до хода", before, e.digest())
    }

    @Test
    fun baldaBotIsQuietOnOpponentTurnDuringDisputeAndAfterFinish() {
        // Ход соперника.
        assertNull(BaldaEngine(Side.B, 0, dict).botMove())

        // Спор: слово вне словаря ложится условно, очередь хода не уходит.
        val e = BaldaEngine(Side.A, 0, dict)
        val disputed = e.localMove(MoveInput.LetterAdd(5, 1, intArrayOf(10, 5))) // «ба»
        assertEquals(BaldaEngine.KIND_WORD_DISPUTED, disputed!![0].toInt() and 0xFF)
        assertTrue(e.pendingVerdict.not()) // спор мой — вердикт не жду
        assertNull("бот должен молчать во время спора", e.botMove())

        // Вердикт соперника (принять) — спор закрыт, очередь хода у соперника.
        assertTrue(e.remoteMove(byteArrayOf(BaldaEngine.KIND_VERDICT.toByte(), 1)))
        assertEquals(Side.B, e.turn)
    }

    @Test
    fun baldaBotNeverRepeatsWordsAndSkipsWhenNothingFound() {
        val a = BaldaEngine(Side.A, 0, dict)
        val b = BaldaEngine(Side.B, 0, dict)
        val played = HashSet<String>()
        var steps = 0
        while (!a.finished && !b.finished && steps++ < 12) {
            // Ход A → кадр → применение у B (как по сети).
            val moveA = a.botMove()
            assertNotNull("A не знает, что делать", moveA)
            if (moveA is MoveInput.LetterAdd) {
                val word = wordOf(a, moveA)
                assertTrue("повтор слова «$word»", played.add(BaldaEngine.normalize(word)))
            }
            val payloadA = a.localMove(moveA!!) ?: error("ход A нелегален: $moveA")
            assertTrue("B отклонил ход A", b.remoteMove(payloadA))
            assertEquals("дайджесты разошлись", a.digest(), b.digest())
            if (a.finished || b.finished) break

            val moveB = b.botMove()
            assertNotNull("B не знает, что делать", moveB)
            if (moveB is MoveInput.LetterAdd) {
                val word = wordOf(b, moveB)
                assertTrue("повтор слова «$word»", played.add(BaldaEngine.normalize(word)))
            }
            val payloadB = b.localMove(moveB!!) ?: error("ход B нелегален: $moveB")
            assertTrue("A отклонил ход B", a.remoteMove(payloadB))
            assertEquals("дайджесты разошлись", a.digest(), b.digest())
        }
        // Словарь крошечный («балда» — стартовое, «бы» — единственное):
        // партия обязана была закончиться тремя пропусками.
        assertTrue("балда не завершилась", a.finished && b.finished)
    }

    /** Слово, которое бот собирается сыграть (для проверки повторов). */
    private fun wordOf(engine: BaldaEngine, move: MoveInput.LetterAdd): String {
        val cells = engine.snapshot().cells
        return buildString {
            for (idx in move.path) {
                append(
                    if (idx == move.cell) BaldaEngine.codeLetter(move.letter)
                    else BaldaEngine.codeLetter(cells[idx]),
                )
            }
        }
    }
}
