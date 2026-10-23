package com.meshgames.bot.ai

import com.meshgames.game.GameEngine
import com.meshgames.game.MoveInput
import com.meshgames.game.Side
import com.meshgames.game.TttSnapshot
import kotlin.random.Random

/**
 * ИИ для Крестиков-ноликов с поддержкой уровней сложности:
 * - easy: 60% случайных ходов, даёт возможность победить новичку
 * - medium: тактический уровень (видит явные победы и блоки, но не строит сложные ловушки)
 * - hard: непобедимый полный перебор Minimax
 */
class TttAi(val difficulty: String = "hard") : BotAi {

    companion object : BotAi by TttAi("hard") {
        private val WIN_LINES = listOf(
            intArrayOf(0, 1, 2), intArrayOf(3, 4, 5), intArrayOf(6, 7, 8),
            intArrayOf(0, 3, 6), intArrayOf(1, 4, 7), intArrayOf(2, 5, 8),
            intArrayOf(0, 4, 8), intArrayOf(2, 4, 6),
        )
    }

    override fun computeMove(engine: GameEngine): MoveInput? {
        if (engine.finished || engine.turn != engine.mySide) return null
        val snap = engine.snapshot() as? TttSnapshot ?: return engine.botMove()
        val cells = snap.cells.copyOf()

        val myMark = if (engine.mySide == Side.A) 1 else 2
        val oppMark = if (engine.mySide == Side.A) 2 else 1

        val emptyCells = (0..8).filter { cells[it] == 0 }
        if (emptyCells.isEmpty()) return null

        when (difficulty.lowercase()) {
            "easy" -> {
                // В 60% случаев выбираем случайную пустую клетку
                if (Random.nextFloat() < 0.60f) {
                    return MoveInput.Cell(emptyCells.random())
                }
            }
            "medium" -> {
                // 1. Если можем выиграть за 1 ход — забираем победу
                for (cell in emptyCells) {
                    cells[cell] = myMark
                    val win = checkWinner(cells) == myMark
                    cells[cell] = 0
                    if (win) return MoveInput.Cell(cell)
                }
                // 2. В 75% случаев блокируем победу соперника за 1 ход
                if (Random.nextFloat() < 0.75f) {
                    for (cell in emptyCells) {
                        cells[cell] = oppMark
                        val oppWin = checkWinner(cells) == oppMark
                        cells[cell] = 0
                        if (oppWin) return MoveInput.Cell(cell)
                    }
                }
                // 3. Предпочитаем центр или угол
                if (cells[4] == 0 && Random.nextFloat() < 0.7f) return MoveInput.Cell(4)
                val corners = listOf(0, 2, 6, 8).filter { cells[it] == 0 }
                if (corners.isNotEmpty() && Random.nextFloat() < 0.6f) return MoveInput.Cell(corners.random())
                return MoveInput.Cell(emptyCells.random())
            }
        }

        // Hard (или 40% остатка easy): полный Minimax
        var bestScore = Int.MIN_VALUE
        var bestCell = -1

        if (cells.all { it == 0 }) return MoveInput.Cell(4)

        for (i in emptyCells) {
            cells[i] = myMark
            val score = minimax(cells, 0, false, myMark, oppMark)
            cells[i] = 0
            if (score > bestScore) {
                bestScore = score
                bestCell = i
            }
        }

        return if (bestCell != -1) MoveInput.Cell(bestCell) else MoveInput.Cell(emptyCells.random())
    }

    private fun minimax(cells: IntArray, depth: Int, isMaximizing: Boolean, myMark: Int, oppMark: Int): Int {
        val winner = checkWinner(cells)
        if (winner == myMark) return 10 - depth
        if (winner == oppMark) return depth - 10
        if (cells.none { it == 0 }) return 0

        if (isMaximizing) {
            var best = Int.MIN_VALUE
            for (i in 0..8) {
                if (cells[i] == 0) {
                    cells[i] = myMark
                    val score = minimax(cells, depth + 1, false, myMark, oppMark)
                    cells[i] = 0
                    best = maxOf(best, score)
                }
            }
            return best
        } else {
            var best = Int.MAX_VALUE
            for (i in 0..8) {
                if (cells[i] == 0) {
                    cells[i] = oppMark
                    val score = minimax(cells, depth + 1, true, myMark, oppMark)
                    cells[i] = 0
                    best = minOf(best, score)
                }
            }
            return best
        }
    }

    private fun checkWinner(cells: IntArray): Int {
        for (line in WIN_LINES) {
            val a = cells[line[0]]
            if (a != 0 && a == cells[line[1]] && a == cells[line[2]]) {
                return a
            }
        }
        return 0
    }
}
