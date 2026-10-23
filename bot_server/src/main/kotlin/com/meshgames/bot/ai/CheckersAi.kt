package com.meshgames.bot.ai

import com.meshgames.game.CheckersSnapshot
import com.meshgames.game.GameEngine
import com.meshgames.game.MoveInput
import com.meshgames.game.Side
import com.meshgames.game.engine.CheckersEngine

/**
 * Интеллектуальный движок русских шашек для bot_server.
 * Использует Alpha-Beta перебор, оценку дамок, продвижение шашек,
 * контроль центра и обязательные цепочки взятий.
 */
class CheckersAi(private val depth: Int = 6, val difficulty: String = "hard") : BotAi {

    companion object : BotAi by CheckersAi(6, "hard")

    override fun computeMove(engine: GameEngine): MoveInput? {
        if (engine.finished || engine.turn != engine.mySide) return null
        val checkers = engine as? CheckersEngine ?: return engine.botMove()
        val snap = checkers.snapshot() as? CheckersSnapshot ?: return engine.botMove()

        val isWhite = engine.mySide == Side.A

        // Собираем все легальные ходы
        val allChains = ArrayList<IntArray>()
        for (sq in 0..63) {
            val p = snap.board[sq]
            if (p == 0) continue
            val isMyPiece = if (isWhite) (p == 1 || p == 2) else (p == 3 || p == 4)
            if (isMyPiece) {
                val chains = checkers.completeMovesFrom(sq)
                allChains.addAll(chains)
            }
        }

        if (allChains.isEmpty()) return null
        if (allChains.size == 1) return MoveInput.Chain(allChains[0])

        var bestChain: IntArray? = null
        var bestScore = Int.MIN_VALUE
        val alpha = Int.MIN_VALUE + 1
        val beta = Int.MAX_VALUE - 1

        for (chain in allChains) {
            val nextBoard = simulateChain(snap.board, chain, isWhite)
            val score = -alphaBeta(nextBoard, depth - 1, -beta, -alpha, !isWhite)
            if (score > bestScore) {
                bestScore = score
                bestChain = chain
            }
        }

        val chosen = if (difficulty.lowercase() == "easy" && allChains.size > 1 && kotlin.random.Random.nextFloat() < 0.30f) {
            allChains.random()
        } else {
            bestChain ?: allChains.first()
        }
        return MoveInput.Chain(chosen)
    }

    private fun alphaBeta(board: IntArray, d: Int, alphaParam: Int, betaParam: Int, isWhiteTurn: Boolean): Int {
        var alpha = alphaParam
        if (d <= 0) {
            return evaluateBoard(board, isWhiteTurn)
        }

        val moves = generateMovesSimple(board, isWhiteTurn)
        if (moves.isEmpty()) {
            return -10000 - d // Нет ходов — проигрыш
        }

        for (chain in moves) {
            val nextBoard = simulateChain(board, chain, isWhiteTurn)
            val score = -alphaBeta(nextBoard, d - 1, -betaParam, -alpha, !isWhiteTurn)
            if (score >= betaParam) return betaParam
            if (score > alpha) alpha = score
        }

        return alpha
    }

    private fun generateMovesSimple(board: IntArray, isWhite: Boolean): List<IntArray> {
        val captures = ArrayList<IntArray>()
        val quiets = ArrayList<IntArray>()

        val myMan = if (isWhite) 1 else 3
        val myKing = if (isWhite) 2 else 4
        val oppMan = if (isWhite) 3 else 1
        val oppKing = if (isWhite) 4 else 2

        for (from in 0..63) {
            val p = board[from]
            if (p != myMan && p != myKing) continue
            val r = from / 8
            val c = from % 8
            val isKing = p == myKing

            // 1. Поиск взятий
            for (dr in listOf(-1, 1)) for (dc in listOf(-1, 1)) {
                if (isKing) {
                    var dist = 1
                    while (true) {
                        val midR = r + dr * dist
                        val midC = c + dc * dist
                        if (midR !in 0..7 || midC !in 0..7) break
                        val midPiece = board[midR * 8 + midC]
                        if (midPiece == myMan || midPiece == myKing) break
                        if (midPiece == oppMan || midPiece == oppKing) {
                            // Ищем свободное поле за ним
                            val landR = midR + dr
                            val landC = midC + dc
                            if (landR in 0..7 && landC in 0..7 && board[landR * 8 + landC] == 0) {
                                captures.add(intArrayOf(from, landR * 8 + landC))
                            }
                            break
                        }
                        dist++
                    }
                } else {
                    val midR = r + dr
                    val midC = c + dc
                    val landR = r + 2 * dr
                    val landC = c + 2 * dc
                    if (landR in 0..7 && landC in 0..7) {
                        val midPiece = board[midR * 8 + midC]
                        if ((midPiece == oppMan || midPiece == oppKing) && board[landR * 8 + landC] == 0) {
                            captures.add(intArrayOf(from, landR * 8 + landC))
                        }
                    }
                }
            }

            // 2. Тихие ходы (если нет взятий)
            if (captures.isEmpty()) {
                val fwdDir = if (isWhite) -1 else 1
                val directions = if (isKing) listOf(-1, 1) else listOf(fwdDir)
                for (dr in directions) for (dc in listOf(-1, 1)) {
                    val tr = r + dr
                    val tc = c + dc
                    if (tr in 0..7 && tc in 0..7 && board[tr * 8 + tc] == 0) {
                        quiets.add(intArrayOf(from, tr * 8 + tc))
                    }
                }
            }
        }

        // Обязательность боя
        return if (captures.isNotEmpty()) captures else quiets
    }

    private fun simulateChain(board: IntArray, chain: IntArray, isWhite: Boolean): IntArray {
        val next = board.copyOf()
        val from = chain[0]
        val to = chain.last()
        var piece = next[from]
        next[from] = 0

        // Проверяем превращение в дамку
        val toRow = to / 8
        if (piece == 1 && toRow == 0) piece = 2
        if (piece == 3 && toRow == 7) piece = 4

        // Удаляем побитые шашки вдоль цепочки
        for (i in 0 until chain.size - 1) {
            val a = chain[i]
            val b = chain[i + 1]
            val ar = a / 8
            val ac = a % 8
            val br = b / 8
            val bc = b % 8
            val dr = if (br > ar) 1 else -1
            val dc = if (bc > ac) 1 else -1
            var cr = ar + dr
            var cc = ac + dc
            while (cr != br && cc != bc) {
                next[cr * 8 + cc] = 0
                cr += dr
                cc += dc
            }
        }

        next[to] = piece
        return next
    }

    private fun evaluateBoard(board: IntArray, forWhite: Boolean): Int {
        var score = 0
        for (sq in 0..63) {
            val p = board[sq]
            if (p == 0) continue
            val r = sq / 8
            val c = sq % 8

            var valPiece = 0
            when (p) {
                1 -> { // Белая простая
                    valPiece = 100 + (7 - r) * 10 // продвижение вперед
                    if (c in 2..5 && r in 2..5) valPiece += 15 // центр
                    score += valPiece
                }
                2 -> { // Белая дамка
                    valPiece = 320
                    if (c in 2..5 && r in 2..5) valPiece += 20
                    score += valPiece
                }
                3 -> { // Черная простая
                    valPiece = 100 + r * 10 // продвижение вперед
                    if (c in 2..5 && r in 2..5) valPiece += 15
                    score -= valPiece
                }
                4 -> { // Черная дамка
                    valPiece = 320
                    if (c in 2..5 && r in 2..5) valPiece += 20
                    score -= valPiece
                }
            }
        }
        return if (forWhite) score else -score
    }
}
