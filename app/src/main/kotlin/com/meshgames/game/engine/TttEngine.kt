package com.meshgames.game.engine

import com.meshgames.game.GameEngine
import com.meshgames.game.GameResult
import com.meshgames.game.GameType
import com.meshgames.game.MoveInput
import com.meshgames.game.ResultReason
import com.meshgames.game.Side
import com.meshgames.game.TttSnapshot

/**
 * Крестики-нолики 3×3. Сторона A — крестики (ходит первой).
 *
 * MOVE payload: 1 байт — индекс клетки 0..8.
 * State: [0] turn (0=A,1=B), [1] finished, [2] winner (0=A,1=B,2=нет),
 *        [3] reason ordinal, [4..12] cells.
 */
class TttEngine(
    override val mySide: Side,
    state: ByteArray? = null,
) : GameEngine {
    override val type: GameType = GameType.TIC_TAC_TOE

    /** 0 пусто, 1 X (A), 2 O (B). */
    private val cells = IntArray(9)
    private var turnSide: Side? = Side.A
    private var finishedFlag = false
    private var resultFlag: GameResult? = null
    private val logList = ArrayList<String>()

    init {
        if (state != null && state.size >= 13) decodeState(state)
    }

    override val turn: Side? get() = turnSide
    override val finished: Boolean get() = finishedFlag
    override val result: GameResult? get() = resultFlag
    override val log: List<String> get() = logList

    override fun snapshot() = TttSnapshot(
        cells = cells.copyOf(),
        turn = turnSide,
        finished = finishedFlag,
        result = resultFlag,
        log = logList.toList(),
    )

    override fun legalMoves(from: Int): Set<Int> =
        if (finishedFlag || turnSide != mySide) emptySet()
        else cells.indices.filter { cells[it] == 0 }.toSet()

    override fun localMove(input: MoveInput): ByteArray? {
        val cell = (input as? MoveInput.Cell)?.index ?: return null
        if (!applyMove(cell, mySide)) return null
        return byteArrayOf(cell.toByte())
    }

    override fun remoteMove(payload: ByteArray): Boolean {
        if (payload.isEmpty()) return false
        val cell = payload[0].toInt() and 0xFF
        val remote = mySide.other
        if (turnSide != remote) return false
        return applyMove(cell, remote)
    }

    /**
     * Тестовый бот: выиграть, иначе заблокировать выигрыш соперника, иначе
     * центр, иначе первая свободная клетка (детерминированный порядок 0..8).
     */
    override fun botMove(): MoveInput? {
        if (finishedFlag || turnSide != mySide) return null
        val me = if (mySide == Side.A) 1 else 2
        val opp = if (mySide == Side.A) 2 else 1
        for (cell in cells.indices) {
            if (winsWith(cell, me)) return MoveInput.Cell(cell)
        }
        for (cell in cells.indices) {
            if (winsWith(cell, opp)) return MoveInput.Cell(cell)
        }
        if (cells[4] == 0) return MoveInput.Cell(4)
        return MoveInput.Cell(cells.indices.first { cells[it] == 0 })
    }

    /** Побеждает ли сторона [mark], поставив свою клетку в [cell] (без записи в поле). */
    private fun winsWith(cell: Int, mark: Int): Boolean {
        if (cell !in cells.indices || cells[cell] != 0) return false
        return LINES.any { line ->
            cell in line && line.all { it == cell || cells[it] == mark }
        }
    }

    private fun applyMove(cell: Int, side: Side): Boolean {
        if (finishedFlag || turnSide != side) return false
        if (cell !in 0..8 || cells[cell] != 0) return false
        cells[cell] = if (side == Side.A) 1 else 2
        logList.add("${if (side == Side.A) 'X' else 'O'}:$cell")
        val winLine = winningLine()
        if (winLine != null) {
            finishedFlag = true
            resultFlag = GameResult(side, ResultReason.CHECKMATE)
        } else if (cells.all { it != 0 }) {
            finishedFlag = true
            resultFlag = GameResult(null, ResultReason.STALEMATE)
        } else {
            turnSide = side.other
        }
        return true
    }

    private fun winningLine(): List<Int>? {
        for (line in LINES) {
            val a = cells[line[0]]
            if (a != 0 && a == cells[line[1]] && a == cells[line[2]]) {
                return listOf(line[0], line[1], line[2])
            }
        }
        return null
    }

    override fun digest(): Int {
        // 3^9 < 2^16 — кодируем поле в u16.
        var code = 0
        for (c in cells) code = code * 3 + c
        var h = 0x9E37
        h = h * 31 + code
        h = h * 31 + (turnSide?.ordinal ?: 3)
        h = h * 31 + if (finishedFlag) 1 else 0
        return h and 0xFFFF
    }

    override fun encodeState(): ByteArray {
        val out = ByteArray(13)
        out[0] = (turnSide?.ordinal ?: 3).toByte()
        out[1] = if (finishedFlag) 1 else 0
        out[2] = when (resultFlag?.winner) {
            Side.A -> 0
            Side.B -> 1
            null -> 2
        }.toByte()
        out[3] = (resultFlag?.reason?.ordinal ?: 0).toByte()
        for (i in 0..8) out[4 + i] = cells[i].toByte()
        return out
    }

    private fun decodeState(s: ByteArray) {
        turnSide = when (s[0].toInt()) {
            0 -> Side.A
            1 -> Side.B
            else -> null
        }
        finishedFlag = s[1].toInt() != 0
        resultFlag = if (finishedFlag) {
            val winner = when (s[2].toInt()) {
                0 -> Side.A
                1 -> Side.B
                else -> null
            }
            GameResult(winner, ResultReason.entries.getOrElse(s[3].toInt()) { ResultReason.STALEMATE })
        } else null
        for (i in 0..8) cells[i] = s[4 + i].toInt()
    }

    companion object {
        private val LINES = listOf(
            intArrayOf(0, 1, 2), intArrayOf(3, 4, 5), intArrayOf(6, 7, 8),
            intArrayOf(0, 3, 6), intArrayOf(1, 4, 7), intArrayOf(2, 5, 8),
            intArrayOf(0, 4, 8), intArrayOf(2, 4, 6),
        )
    }
}
