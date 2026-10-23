package com.meshgames.ui

import com.meshgames.game.GameResult
import com.meshgames.game.ResultReason
import com.meshgames.game.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Итог партии всегда читается глазами [mySide] — иначе игрок второй стороны
 * видит инвертированный вердикт (полевой отчёт 20.10: «выиграл ноликами,
 * на доске написано поражение»: доска показывала итог жёстко от Side.A).
 */
class ResultTextTest {

    @Test
    fun verdictIsReadFromPlayersSideOnly() {
        for (winner in listOf(Side.A, Side.B)) {
            val result = GameResult(winner, ResultReason.CHECKMATE)
            assertEquals("победа (CHECKMATE)", resultText(result, winner))
            assertEquals("поражение (CHECKMATE)", resultText(result, winner.other))
        }
    }

    @Test
    fun drawAndMissingResultAreSideIndependent() {
        for (side in listOf(Side.A, Side.B)) {
            assertEquals("ничья (STALEMATE)", resultText(GameResult(null, ResultReason.STALEMATE), side))
            assertEquals("ничья (DRAW_AGREEMENT)", resultText(GameResult(null, ResultReason.DRAW_AGREEMENT), side))
            assertEquals("завершено", resultText(null, side))
        }
    }

    @Test
    fun everyReasonIsNamedInTheVerdict() {
        for (reason in ResultReason.entries) {
            val text = resultText(GameResult(Side.B, reason), Side.B)
            assertTrue("нет причины в «$text»", text.contains(reason.name))
        }
    }
}
