package com.meshgames.bot.ai

import com.meshgames.game.ChessSnapshot
import com.meshgames.game.GameEngine
import com.meshgames.game.MoveInput
import com.meshgames.game.Side
import kotlin.math.abs

/**
 * Интеллектуальный шахматный движок для bot_server.
 * Использует Alpha-Beta отсечение, Piece-Square Tables (PST),
 * упорядочивание ходов (взятия первыми) и оценку мобильности/безопасности короля.
 */
class ChessAi(private val depth: Int = 4, val difficulty: String = "hard") : BotAi {

    data class Move(val from: Int, val to: Int, val promo: Int = 0, val score: Int = 0)

    override fun computeMove(engine: GameEngine): MoveInput? {
        if (engine.finished || engine.turn != engine.mySide) return null
        val snap = engine.snapshot() as? ChessSnapshot ?: return engine.botMove()

        val isWhite = engine.mySide == Side.A
        val legalMoves = generateAllLegalMoves(engine, isWhite)
        if (legalMoves.isEmpty()) return null
        if (legalMoves.size == 1) return MoveInput.FromTo(legalMoves[0].from, legalMoves[0].to, legalMoves[0].promo)

        var bestMove: Move? = null
        var bestScore = Int.MIN_VALUE
        val alpha = Int.MIN_VALUE + 1
        val beta = Int.MAX_VALUE - 1

        // Сортировка ходов: сначала взятия более ценных фигур
        val sortedMoves = orderMoves(legalMoves, snap.board)

        for (m in sortedMoves) {
            val nextBoard = simulateMove(snap.board, m.from, m.to, m.promo, isWhite)
            val score = -alphaBeta(nextBoard, depth - 1, -beta, -alpha, !isWhite)
            if (score > bestScore) {
                bestScore = score
                bestMove = m
            }
        }

        val chosen = if (difficulty.lowercase() == "easy" && sortedMoves.size > 1 && kotlin.random.Random.nextFloat() < 0.35f) {
            // На легком уровне с вероятностью 35% выбираем 2-й или 3-й ход
            val candidates = sortedMoves.take(minOf(3, sortedMoves.size))
            candidates.random()
        } else {
            bestMove ?: sortedMoves.first()
        }
        return MoveInput.FromTo(chosen.from, chosen.to, chosen.promo)
    }

    private fun alphaBeta(board: IntArray, d: Int, alphaParam: Int, betaParam: Int, isWhiteTurn: Boolean): Int {
        var alpha = alphaParam
        val baseScore = evaluateBoard(board, isWhiteTurn)

        if (d <= 0) {
            // Quiescence-like: возвращаем статическую оценку
            return baseScore
        }

        val moves = generatePseudoLegalMoves(board, isWhiteTurn)
        if (moves.isEmpty()) {
            return -10000 - d // Мат или пат
        }

        val sortedMoves = orderMoves(moves, board)

        for (m in sortedMoves) {
            val nextBoard = simulateMove(board, m.from, m.to, m.promo, isWhiteTurn)
            val score = -alphaBeta(nextBoard, d - 1, -betaParam, -alpha, !isWhiteTurn)
            if (score >= betaParam) return betaParam
            if (score > alpha) alpha = score
        }

        return alpha
    }

    private fun generateAllLegalMoves(engine: GameEngine, isWhite: Boolean): List<Move> {
        val snap = engine.snapshot() as ChessSnapshot
        val moves = ArrayList<Move>()
        for (from in 0..63) {
            val p = snap.board[from]
            if (p != 0 && (p > 0) == isWhite) {
                val targets = engine.legalMoves(from)
                for (to in targets) {
                    val isPawn = abs(p) == 1
                    val toRow = to / 8
                    if (isPawn && (toRow == 0 || toRow == 7)) {
                        // Превращение в ферзя (1)
                        moves.add(Move(from, to, 1))
                    } else {
                        moves.add(Move(from, to, 0))
                    }
                }
            }
        }
        return moves
    }

    private fun generatePseudoLegalMoves(board: IntArray, isWhite: Boolean): List<Move> {
        val moves = ArrayList<Move>()
        for (from in 0..63) {
            val p = board[from]
            if (p == 0 || (p > 0) != isWhite) continue
            val type = abs(p)
            val r = from / 8
            val c = from % 8

            when (type) {
                1 -> { // Пешка
                    val dir = if (isWhite) -1 else 1
                    val startRow = if (isWhite) 6 else 1
                    val promoRow = if (isWhite) 0 else 7

                    val fwd = (r + dir) * 8 + c
                    if (fwd in 0..63 && board[fwd] == 0) {
                        val promo = if (r + dir == promoRow) 1 else 0
                        moves.add(Move(from, fwd, promo))
                        if (r == startRow) {
                            val fwd2 = (r + 2 * dir) * 8 + c
                            if (fwd2 in 0..63 && board[fwd2] == 0) moves.add(Move(from, fwd2, 0))
                        }
                    }
                    // Взятия
                    for (dc in listOf(-1, 1)) {
                        val tc = c + dc
                        if (tc in 0..7) {
                            val target = (r + dir) * 8 + tc
                            if (target in 0..63) {
                                val tp = board[target]
                                if (tp != 0 && (tp > 0) != isWhite) {
                                    val promo = if (r + dir == promoRow) 1 else 0
                                    moves.add(Move(from, target, promo))
                                }
                            }
                        }
                    }
                }
                2 -> { // Конь
                    val offsets = intArrayOf(-17, -15, -10, -6, 6, 10, 15, 17)
                    for (off in offsets) {
                        val to = from + off
                        if (to in 0..63 && abs((to % 8) - c) <= 2 && abs((to / 8) - r) <= 2) {
                            val tp = board[to]
                            if (tp == 0 || (tp > 0) != isWhite) moves.add(Move(from, to))
                        }
                    }
                }
                3 -> addRayMoves(board, from, r, c, isWhite, moves, intArrayOf(-1, -1, 1, 1), intArrayOf(-1, 1, -1, 1)) // Слон
                4 -> addRayMoves(board, from, r, c, isWhite, moves, intArrayOf(-1, 1, 0, 0), intArrayOf(0, 0, -1, 1))  // Ладья
                5 -> { // Ферзь
                    addRayMoves(board, from, r, c, isWhite, moves, intArrayOf(-1, -1, 1, 1), intArrayOf(-1, 1, -1, 1))
                    addRayMoves(board, from, r, c, isWhite, moves, intArrayOf(-1, 1, 0, 0), intArrayOf(0, 0, -1, 1))
                }
                6 -> { // Король
                    for (dr in -1..1) for (dc in -1..1) {
                        if (dr == 0 && dc == 0) continue
                        val tr = r + dr
                        val tc = c + dc
                        if (tr in 0..7 && tc in 0..7) {
                            val to = tr * 8 + tc
                            val tp = board[to]
                            if (tp == 0 || (tp > 0) != isWhite) moves.add(Move(from, to))
                        }
                    }
                }
            }
        }
        return moves
    }

    private fun addRayMoves(
        board: IntArray, from: Int, r: Int, c: Int, isWhite: Boolean,
        out: MutableList<Move>, dr: IntArray, dc: IntArray
    ) {
        for (i in dr.indices) {
            var step = 1
            while (true) {
                val tr = r + dr[i] * step
                val tc = c + dc[i] * step
                if (tr !in 0..7 || tc !in 0..7) break
                val to = tr * 8 + tc
                val tp = board[to]
                if (tp == 0) {
                    out.add(Move(from, to))
                } else {
                    if ((tp > 0) != isWhite) out.add(Move(from, to))
                    break
                }
                step++
            }
        }
    }

    private fun simulateMove(board: IntArray, from: Int, to: Int, promo: Int, isWhite: Boolean): IntArray {
        val next = board.copyOf()
        var piece = next[from]
        if (promo > 0 && abs(piece) == 1) {
            piece = if (isWhite) 5 else -5 // Ферзь
        }
        next[from] = 0
        next[to] = piece
        return next
    }

    private fun orderMoves(moves: List<Move>, board: IntArray): List<Move> {
        return moves.sortedByDescending { m ->
            val victim = abs(board[m.to])
            val attacker = abs(board[m.from])
            if (victim > 0) {
                1000 + victim * 10 - attacker
            } else {
                0
            }
        }
    }

    private fun evaluateBoard(board: IntArray, forWhite: Boolean): Int {
        var score = 0
        for (i in 0..63) {
            val p = board[i]
            if (p == 0) continue
            val isW = p > 0
            val type = abs(p)
            val valBase = PIECE_VALUES[type]
            val pst = piecePst(type, i, isW)
            val total = valBase + pst
            score += if (isW) total else -total
        }
        return if (forWhite) score else -score
    }

    private fun piecePst(type: Int, sq: Int, isWhite: Boolean): Int {
        val idx = if (isWhite) sq else (7 - sq / 8) * 8 + (sq % 8)
        return when (type) {
            1 -> PST_PAWN[idx]
            2 -> PST_KNIGHT[idx]
            3 -> PST_BISHOP[idx]
            4 -> PST_ROOK[idx]
            5 -> PST_QUEEN[idx]
            6 -> PST_KING_MID[idx]
            else -> 0
        }
    }

    companion object : BotAi by ChessAi(4, "hard") {
        private val PIECE_VALUES = intArrayOf(0, 100, 320, 330, 500, 900, 20000)

        private val PST_PAWN = intArrayOf(
            0,  0,  0,  0,  0,  0,  0,  0,
            50, 50, 50, 50, 50, 50, 50, 50,
            10, 10, 20, 30, 30, 20, 10, 10,
            5,  5, 10, 25, 25, 10,  5,  5,
            0,  0,  0, 20, 20,  0,  0,  0,
            5, -5,-10,  0,  0,-10, -5,  5,
            5, 10, 10,-20,-20, 10, 10,  5,
            0,  0,  0,  0,  0,  0,  0,  0
        )

        private val PST_KNIGHT = intArrayOf(
            -50,-40,-30,-30,-30,-30,-40,-50,
            -40,-20,  0,  0,  0,  0,-20,-40,
            -30,  0, 10, 15, 15, 10,  0,-30,
            -30,  5, 15, 20, 20, 15,  5,-30,
            -30,  0, 15, 20, 20, 15,  0,-30,
            -30,  5, 10, 15, 15, 10,  5,-30,
            -40,-20,  0,  5,  5,  0,-20,-40,
            -50,-40,-30,-30,-30,-30,-40,-50
        )

        private val PST_BISHOP = intArrayOf(
            -20,-10,-10,-10,-10,-10,-10,-20,
            -10,  0,  0,  0,  0,  0,  0,-10,
            -10,  0,  5, 10, 10,  5,  0,-10,
            -10,  5,  5, 10, 10,  5,  5,-10,
            -10,  0, 10, 10, 10, 10,  0,-10,
            -10, 10, 10, 10, 10, 10, 10,-10,
            -10,  5,  0,  0,  0,  0,  5,-10,
            -20,-10,-10,-10,-10,-10,-10,-20
        )

        private val PST_ROOK = intArrayOf(
            0,  0,  0,  0,  0,  0,  0,  0,
            5, 10, 10, 10, 10, 10, 10,  5,
            -5,  0,  0,  0,  0,  0,  0, -5,
            -5,  0,  0,  0,  0,  0,  0, -5,
            -5,  0,  0,  0,  0,  0,  0, -5,
            -5,  0,  0,  0,  0,  0,  0, -5,
            -5,  0,  0,  0,  0,  0,  0, -5,
            0,  0,  0,  5,  5,  0,  0,  0
        )

        private val PST_QUEEN = intArrayOf(
            -20,-10,-10, -5, -5,-10,-10,-20,
            -10,  0,  0,  0,  0,  0,  0,-10,
            -10,  0,  5,  5,  5,  5,  0,-10,
            -5,  0,  5,  5,  5,  5,  0, -5,
            0,  0,  5,  5,  5,  5,  0, -5,
            -10,  5,  5,  5,  5,  5,  0,-10,
            -10,  0,  5,  0,  0,  0,  0,-10,
            -20,-10,-10, -5, -5,-10,-10,-20
        )

        private val PST_KING_MID = intArrayOf(
            -30,-40,-40,-50,-50,-40,-40,-30,
            -30,-40,-40,-50,-50,-40,-40,-30,
            -30,-40,-40,-50,-50,-40,-40,-30,
            -30,-40,-40,-50,-50,-40,-40,-30,
            -20,-30,-30,-40,-40,-30,-30,-20,
            -10,-20,-20,-20,-20,-20,-20,-10,
            20, 20,  0,  0,  0,  0, 20, 20,
            20, 30, 10,  0,  0, 10, 30, 20
        )
    }
}
