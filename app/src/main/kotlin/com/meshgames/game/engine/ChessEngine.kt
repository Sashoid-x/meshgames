package com.meshgames.game.engine

import com.meshgames.game.*

/**
 * Шахматный движок с классическими правилами.
 *
 * Индексация клеток: 0..63, index = row*8 + col; row 0 — верх экрана (8-я горизонталь),
 * col 0 — файл a. То есть 0 = a8, 7 = h8, 56 = a1, 63 = h1.
 *
 * Коды фигур: 0 — пусто; положительные — белые (1 пешка, 2 конь, 3 слон, 4 ладья,
 * 5 ферзь, 6 король); отрицательные — чёрные (-1..-6).
 * Сторона A = белые, сторона B = чёрные.
 *
 * Протокол MOVE: payload ровно 3 байта [from, to, promotion].
 * promotion: 0 — не нужна, 1 — ферзь, 2 — ладья, 3 — слон, 4 — конь.
 */
class ChessEngine(
    override val mySide: Side,
    state: ByteArray? = null, // null = начальная позиция
) : GameEngine {

    override val type: GameType = GameType.CHESS

    private val board = IntArray(64)
    private var turnSide: Side = Side.A
    private var castlingRights: Int = 0
    private var epSquare: Int = -1
    private var halfmoveClock: Int = 0
    private var fullmoveNumber: Int = 1
    private var isFinished: Boolean = false
    private var gameResult: GameResult? = null
    private val logEntries = mutableListOf<String>()
    private val positionHistory = mutableListOf<Int>()

    override val turn: Side? get() = if (isFinished) null else turnSide
    override val finished: Boolean get() = isFinished
    override val result: GameResult? get() = gameResult
    override val log: List<String> get() = logEntries

    init {
        if (state != null) {
            require(decodeState(state)) { "corrupt chess state" }
        } else {
            setupInitialPosition()
            positionHistory.add(digest())
        }
    }

    // ---------------------------------------------------------------- позиция

    private fun setupInitialPosition() {
        val back = intArrayOf(4, 2, 3, 5, 6, 3, 2, 4)
        for (c in 0..7) {
            board[c] = -back[c]      // row 0: чёрные тяжёлые
            board[8 + c] = -1        // row 1: чёрные пешки
            board[48 + c] = 1        // row 6: белые пешки
            board[56 + c] = back[c]  // row 7: белые тяжёлые
        }
        turnSide = Side.A
        castlingRights = 15
        epSquare = -1
        halfmoveClock = 0
        fullmoveNumber = 1
        isFinished = false
        gameResult = null
        logEntries.clear()
        positionHistory.clear()
    }

    private fun isWhite(code: Int) = code > 0
    private fun pieceType(code: Int) = if (code > 0) code else -code

    override fun snapshot(): ChessSnapshot = ChessSnapshot(
        board = board.copyOf(),
        turn = turn,
        checked = if (isFinished) null else if (inCheck(turnSide)) turnSide else null,
        castling = castlingRights,
        epSquare = epSquare,
        halfmove = halfmoveClock,
        fullmove = fullmoveNumber,
        finished = isFinished,
        result = gameResult,
        log = logEntries.toList(),
    )

    // ------------------------------------------------------- генерация ходов

    private class MoveCand(
        val to: Int,
        val needsPromotion: Boolean,
        val isEnPassant: Boolean,
        val rookFrom: Int, // -1 — не рокировка
        val captureSquare: Int,
    )

    private fun genMoves(b: IntArray, from: Int): List<MoveCand> {
        val res = ArrayList<MoveCand>(4)
        val piece = b[from]
        if (piece == 0) return res
        val white = isWhite(piece)
        val type = pieceType(piece)
        val row = from / 8
        val col = from % 8
        when (type) {
            1 -> genPawnMoves(b, from, row, col, white, res)
            2 -> genStepMoves(b, row, col, white, KNIGHT_OFFSETS, res)
            3 -> genSlidingMoves(b, row, col, white, DIAGONALS, res)
            4 -> genSlidingMoves(b, row, col, white, STRAIGHTS, res)
            5 -> genSlidingMoves(b, row, col, white, ALL_DIRS, res)
            6 -> {
                genStepMoves(b, row, col, white, ALL_DIRS, res)
                genCastlingMoves(b, from, white, res)
            }
        }
        return res
    }

    private fun genStepMoves(
        b: IntArray,
        row: Int,
        col: Int,
        white: Boolean,
        dirs: Array<IntArray>,
        res: MutableList<MoveCand>,
    ) {
        for (d in dirs) {
            val r = row + d[0]
            val c = col + d[1]
            if (r !in 0..7 || c !in 0..7) continue
            val to = r * 8 + c
            val t = b[to]
            if (t == 0 || isWhite(t) != white) res.add(MoveCand(to, false, false, -1, to))
        }
    }

    private fun genSlidingMoves(
        b: IntArray,
        row: Int,
        col: Int,
        white: Boolean,
        dirs: Array<IntArray>,
        res: MutableList<MoveCand>,
    ) {
        for (d in dirs) {
            var r = row + d[0]
            var c = col + d[1]
            while (r in 0..7 && c in 0..7) {
                val to = r * 8 + c
                val t = b[to]
                if (t == 0) {
                    res.add(MoveCand(to, false, false, -1, to))
                } else {
                    if (isWhite(t) != white) res.add(MoveCand(to, false, false, -1, to))
                    break
                }
                r += d[0]
                c += d[1]
            }
        }
    }

    private fun genPawnMoves(
        b: IntArray,
        from: Int,
        row: Int,
        col: Int,
        white: Boolean,
        res: MutableList<MoveCand>,
    ) {
        val dir = if (white) -1 else 1
        val startRow = if (white) 6 else 1
        val lastRow = if (white) 0 else 7
        val r1 = row + dir
        if (r1 !in 0..7) return
        // шаг вперёд (и два шага с начальной горизонтали)
        val forward = r1 * 8 + col
        if (b[forward] == 0) {
            if (r1 == lastRow) {
                for (p in 1..4) res.add(MoveCand(forward, true, false, -1, forward))
            } else {
                res.add(MoveCand(forward, false, false, -1, forward))
                if (row == startRow && b[(row + 2 * dir) * 8 + col] == 0) {
                    val two = (row + 2 * dir) * 8 + col
                    res.add(MoveCand(two, false, false, -1, two))
                }
            }
        }
        // взятия по диагонали и на проходе
        for (dc in intArrayOf(-1, 1)) {
            val c = col + dc
            if (c !in 0..7) continue
            val to = r1 * 8 + c
            val target = b[to]
            if (target != 0 && isWhite(target) != white) {
                if (r1 == lastRow) {
                    for (p in 1..4) res.add(MoveCand(to, true, false, -1, to))
                } else {
                    res.add(MoveCand(to, false, false, -1, to))
                }
            } else if (target == 0 && to == epSquare) {
                val victim = row * 8 + c
                val vp = b[victim]
                if (vp != 0 && pieceType(vp) == 1 && isWhite(vp) != white) {
                    res.add(MoveCand(to, false, true, -1, victim))
                }
            }
        }
    }

    private fun genCastlingMoves(b: IntArray, from: Int, white: Boolean, res: MutableList<MoveCand>) {
        val home = if (white) 60 else 4
        if (from != home) return
        val rookCode = if (white) 4 else -4
        val enemy = !white
        val kingRight = if (white) 1 else 4
        val queenRight = if (white) 2 else 8
        // короткая: король home -> home+2, ладья home+3 -> home+1
        if ((castlingRights and kingRight) != 0 &&
            b[home + 3] == rookCode &&
            b[home + 1] == 0 && b[home + 2] == 0 &&
            !attacked(b, home, enemy) &&
            !attacked(b, home + 1, enemy) &&
            !attacked(b, home + 2, enemy)
        ) {
            res.add(MoveCand(home + 2, false, false, home + 3, home + 2))
        }
        // длинная: король home -> home-2, ладья home-4 -> home-1
        if ((castlingRights and queenRight) != 0 &&
            b[home - 4] == rookCode &&
            b[home - 1] == 0 && b[home - 2] == 0 && b[home - 3] == 0 &&
            !attacked(b, home, enemy) &&
            !attacked(b, home - 1, enemy) &&
            !attacked(b, home - 2, enemy)
        ) {
            res.add(MoveCand(home - 2, false, false, home - 4, home - 2))
        }
    }

    /** Атакует ли сторона [byWhite] клетку [square] на доске [b]. */
    private fun attacked(b: IntArray, square: Int, byWhite: Boolean): Boolean {
        val row = square / 8
        val col = square % 8
        // пешки
        val pawnRow = if (byWhite) row + 1 else row - 1
        if (pawnRow in 0..7) {
            val pawn = if (byWhite) 1 else -1
            for (dc in intArrayOf(-1, 1)) {
                val c = col + dc
                if (c in 0..7 && b[pawnRow * 8 + c] == pawn) return true
            }
        }
        // кони
        val knight = if (byWhite) 2 else -2
        for (d in KNIGHT_OFFSETS) {
            val r = row + d[0]
            val c = col + d[1]
            if (r in 0..7 && c in 0..7 && b[r * 8 + c] == knight) return true
        }
        // короли
        val king = if (byWhite) 6 else -6
        for (d in ALL_DIRS) {
            val r = row + d[0]
            val c = col + d[1]
            if (r in 0..7 && c in 0..7 && b[r * 8 + c] == king) return true
        }
        // слоны и ферзи по диагоналям
        val bishop = if (byWhite) 3 else -3
        val queen = if (byWhite) 5 else -5
        for (d in DIAGONALS) {
            var r = row + d[0]
            var c = col + d[1]
            while (r in 0..7 && c in 0..7) {
                val p = b[r * 8 + c]
                if (p != 0) {
                    if (p == bishop || p == queen) return true
                    break
                }
                r += d[0]
                c += d[1]
            }
        }
        // ладьи и ферзи по прямым
        val rook = if (byWhite) 4 else -4
        for (d in STRAIGHTS) {
            var r = row + d[0]
            var c = col + d[1]
            while (r in 0..7 && c in 0..7) {
                val p = b[r * 8 + c]
                if (p != 0) {
                    if (p == rook || p == queen) return true
                    break
                }
                r += d[0]
                c += d[1]
            }
        }
        return false
    }

    // ------------------------------------------------------------ применение

    /** Симулирует ход и проверяет, не остаётся ли король стороны под шахом. */
    private fun moveKeepsKingSafe(from: Int, cand: MoveCand, promotion: Int): Boolean {
        val nb = board.copyOf()
        val piece = nb[from]
        val white = isWhite(piece)
        nb[cand.captureSquare] = 0
        nb[from] = 0
        var placed = piece
        if (cand.needsPromotion) {
            val t = when (promotion) {
                1 -> 5
                2 -> 4
                3 -> 3
                else -> 2
            }
            placed = if (white) t else -t
        }
        nb[cand.to] = placed
        if (cand.rookFrom >= 0) {
            // короткая (ладья справа) -> to-1, длинная (ладья слева) -> to+1
            val rookTo = if (cand.rookFrom > cand.to) cand.to - 1 else cand.to + 1
            nb[rookTo] = nb[cand.rookFrom]
            nb[cand.rookFrom] = 0
        }
        val kingCode = if (white) 6 else -6
        var kingAt = -1
        for (i in 0..63) if (nb[i] == kingCode) {
            kingAt = i
            break
        }
        if (kingAt < 0) return false
        return !attacked(nb, kingAt, !white)
    }

    private fun hasAnyLegalMove(): Boolean {
        val whiteTurn = turnSide == Side.A
        for (from in 0..63) {
            val p = board[from]
            if (p == 0 || isWhite(p) != whiteTurn) continue
            for (c in genMoves(board, from)) {
                if (moveKeepsKingSafe(from, c, if (c.needsPromotion) 1 else 0)) return true
            }
        }
        return false
    }

    private fun kingSquare(white: Boolean): Int {
        val code = if (white) 6 else -6
        for (i in 0..63) if (board[i] == code) return i
        return -1
    }

    private fun inCheck(side: Side): Boolean {
        val k = kingSquare(side == Side.A)
        if (k < 0) return false
        return attacked(board, k, side.other == Side.A)
    }

    /**
     * Валидирует ход и применяет его атомарно: либо всё, либо ничего.
     * Ход должен быть легален для стороны, чья очередь.
     */
    private fun tryMove(from: Int, to: Int, promotion: Int): Boolean {
        if (isFinished) return false
        if (from !in 0..63 || to !in 0..63 || from == to) return false
        if (promotion !in 0..4) return false
        val piece = board[from]
        if (piece == 0 || isWhite(piece) != (turnSide == Side.A)) return false
        var chosen: MoveCand? = null
        for (c in genMoves(board, from)) {
            if (c.to == to) {
                chosen = c
                break
            }
        }
        val cand = chosen ?: return false
        if (cand.needsPromotion) {
            if (promotion == 0) return false
        } else if (promotion != 0) {
            return false
        }
        if (!moveKeepsKingSafe(from, cand, promotion)) return false

        // ---- ход валиден: применяем ----
        val white = isWhite(piece)
        val type = pieceType(piece)
        val isCapture = board[cand.captureSquare] != 0

        board[cand.captureSquare] = 0
        board[from] = 0
        var placed = piece
        if (cand.needsPromotion) {
            val t = when (promotion) {
                1 -> 5
                2 -> 4
                3 -> 3
                else -> 2
            }
            placed = if (white) t else -t
        }
        board[cand.to] = placed
        if (cand.rookFrom >= 0) {
            val rookTo = if (cand.rookFrom > cand.to) cand.to - 1 else cand.to + 1
            board[rookTo] = board[cand.rookFrom]
            board[cand.rookFrom] = 0
        }

        // права рокировки: король/ладья двигаются — снимаются; взятие ладьи — снимается
        if (type == 6) {
            castlingRights = if (white) castlingRights and 12 else castlingRights and 3
        }
        if (type == 4) {
            castlingRights = when (from) {
                63 -> castlingRights and 14
                56 -> castlingRights and 13
                7 -> castlingRights and 11
                0 -> castlingRights and 7
                else -> castlingRights
            }
        }
        if (isCapture) {
            castlingRights = when (cand.captureSquare) {
                63 -> castlingRights and 14
                56 -> castlingRights and 13
                7 -> castlingRights and 11
                0 -> castlingRights and 7
                else -> castlingRights
            }
        }

        // поле en passant — только после двойного шага пешки
        epSquare = if (type == 1 && (to - from == 16 || from - to == 16)) (from + to) / 2 else -1

        halfmoveClock = if (type == 1 || isCapture) 0 else halfmoveClock + 1
        if (!white) fullmoveNumber++
        turnSide = turnSide.other

        if (positionHistory.size >= 64) positionHistory.removeAt(0)
        positionHistory.add(digest())

        // завершение партии
        val opponentHasMoves = hasAnyLegalMove()
        val check = inCheck(turnSide)
        val suffix = when {
            !opponentHasMoves && check -> "#"
            check -> "+"
            else -> ""
        }
        when {
            !opponentHasMoves && check -> {
                gameResult = GameResult(turnSide.other, ResultReason.CHECKMATE)
                isFinished = true
            }
            !opponentHasMoves -> {
                gameResult = GameResult(null, ResultReason.STALEMATE)
                isFinished = true
            }
            insufficientMaterial() -> {
                gameResult = GameResult(null, ResultReason.INSUFFICIENT_MATERIAL)
                isFinished = true
            }
            halfmoveClock >= 100 -> {
                gameResult = GameResult(null, ResultReason.FIFTY_MOVES)
                isFinished = true
            }
            else -> {
                val d = digest()
                var count = 0
                for (h in positionHistory) if (h == d) count++
                if (count >= 3) {
                    gameResult = GameResult(null, ResultReason.THREEFOLD)
                    isFinished = true
                }
            }
        }

        logEntries.add(notate(from, to, piece, cand, promotion, isCapture, suffix))
        return true
    }

    private fun insufficientMaterial(): Boolean {
        var count = 0
        var first = 0
        var firstSq = -1
        var second = 0
        var secondSq = -1
        for (i in 0..63) {
            val p = board[i]
            if (p == 0 || pieceType(p) == 6) continue
            count++
            if (count == 1) {
                first = p
                firstSq = i
            } else if (count == 2) {
                second = p
                secondSq = i
            } else {
                return false
            }
        }
        if (count == 0) return true // K vs K
        if (count == 1) {
            val t = pieceType(first)
            return t == 2 || t == 3 // K+N vs K, K+B vs K
        }
        // K+B vs K+B одинакового цвета полей
        if (pieceType(first) == 3 && pieceType(second) == 3 && isWhite(first) != isWhite(second)) {
            val ca = (firstSq / 8 + firstSq % 8) and 1
            val cb = (secondSq / 8 + secondSq % 8) and 1
            return ca == cb
        }
        return false
    }

    // ------------------------------------------------------------- нотация

    private fun squareName(sq: Int): String = "${('a' + (sq % 8))}${8 - sq / 8}"

    private fun notate(
        from: Int,
        to: Int,
        piece: Int,
        cand: MoveCand,
        promotion: Int,
        isCapture: Boolean,
        suffix: String,
    ): String {
        val type = pieceType(piece)
        val sb = StringBuilder()
        when {
            type == 6 && cand.rookFrom >= 0 -> {
                sb.append(if (to > from) "O-O" else "O-O-O")
            }
            type == 1 -> {
                if (isCapture) {
                    sb.append('a' + (from % 8))
                    sb.append('x')
                    sb.append(squareName(to))
                } else {
                    sb.append(squareName(from))
                    sb.append('-')
                    sb.append(squareName(to))
                }
                if (cand.needsPromotion) {
                    sb.append('=')
                    sb.append(PROMO_LETTERS[promotion])
                }
            }
            else -> {
                sb.append(PIECE_LETTERS[type])
                if (isCapture) {
                    sb.append('x')
                    sb.append(squareName(to))
                } else {
                    sb.append(squareName(from))
                    sb.append('-')
                    sb.append(squareName(to))
                }
            }
        }
        sb.append(suffix)
        return sb.toString()
    }

    // --------------------------------------------------------- интерфейс

    override fun legalMoves(from: Int): Set<Int> {
        if (isFinished || from !in 0..63) return emptySet()
        val p = board[from]
        if (p == 0 || isWhite(p) != (turnSide == Side.A)) return emptySet()
        val res = LinkedHashSet<Int>()
        for (c in genMoves(board, from)) {
            if (moveKeepsKingSafe(from, c, if (c.needsPromotion) 1 else 0)) res.add(c.to)
        }
        return res
    }

    override fun localMove(input: MoveInput): ByteArray? {
        val m = input as? MoveInput.FromTo ?: return null
        if (isFinished) return null
        if (turnSide != mySide) return null
        if (!tryMove(m.from, m.to, m.promotion)) return null
        return byteArrayOf(m.from.toByte(), m.to.toByte(), m.promotion.toByte())
    }

    override fun remoteMove(payload: ByteArray): Boolean {
        if (isFinished || payload.size < 3) return false
        val from = payload[0].toInt() and 0xFF
        val to = payload[1].toInt() and 0xFF
        val promo = payload[2].toInt() and 0xFF
        if (from > 63 || to > 63 || promo > 4) return false
        return tryMove(from, to, promo)
    }

    /**
     * Тестовый бот: первое взятие, которое не оставляет нашего короля под
     * ударом, иначе первый такой спокойный ход. Обход доски 0..63 и порядок
     * кандидатов детерминированы — обе стороны ходят предсказуемо.
     */
    override fun botMove(): MoveInput? {
        if (isFinished || turnSide != mySide) return null
        val white = mySide == Side.A
        var quiet: MoveInput.FromTo? = null
        for (from in 0..63) {
            val p = board[from]
            if (p == 0 || isWhite(p) != white) continue
            for (c in genMoves(board, from)) {
                val promo = if (c.needsPromotion) 1 else 0
                if (!moveKeepsKingSafe(from, c, promo)) continue
                val mv = MoveInput.FromTo(from, c.to, promo)
                // Взятие берём сразу (простая эвристика — сложность не нужна).
                if (board[c.captureSquare] != 0) return mv
                if (quiet == null) quiet = mv
            }
        }
        return quiet
    }

    override fun digest(): Int {
        // FNV-1a 32-bit по каноническим байтам: board[64] + turn + castling + ep
        var h = -2128831035 // 0x811C9DC5
        for (v in board) h = (h xor (v and 0xFF)) * 16777619
        h = (h xor (if (turnSide == Side.A) 0 else 1)) * 16777619
        h = (h xor (castlingRights and 0xFF)) * 16777619
        h = (h xor (if (epSquare < 0) 255 else epSquare)) * 16777619
        h = h xor (h ushr 16)
        return h and 0xFFFF
    }

    // ------------------------------------------------------- сериализация

    override fun encodeState(): ByteArray {
        val history = if (positionHistory.size > 64) positionHistory.takeLast(64) else positionHistory
        val logs = logEntries.takeLast(100)
        val logBytes = ArrayList<ByteArray>(logs.size)
        var size = 72 + history.size * 2 + 1
        for (s in logs) {
            var bytes = s.encodeToByteArray()
            if (bytes.size > 100) bytes = bytes.copyOf(100)
            logBytes.add(bytes)
            size += 1 + bytes.size
        }
        val out = ByteArray(size)
        var i = 0
        out[i++] = STATE_VERSION.toByte()
        for (v in board) out[i++] = v.toByte()
        out[i++] = (if (turnSide == Side.A) 0 else 1).toByte()
        out[i++] = castlingRights.toByte()
        out[i++] = if (epSquare < 0) 255.toByte() else epSquare.toByte()
        out[i++] = halfmoveClock.coerceAtMost(255).toByte()
        val fm = fullmoveNumber.coerceIn(0, 65535)
        out[i++] = (fm and 0xFF).toByte()
        out[i++] = ((fm shr 8) and 0xFF).toByte()
        out[i++] = history.size.toByte()
        for (h in history) {
            out[i++] = (h and 0xFF).toByte()
            out[i++] = ((h shr 8) and 0xFF).toByte()
        }
        out[i++] = logBytes.size.toByte()
        for (bytes in logBytes) {
            out[i++] = bytes.size.toByte()
            for (x in bytes) out[i++] = x
        }
        return out
    }

    private fun decodeState(data: ByteArray): Boolean {
        var i = 0
        if (data.size < 72) return false
        if ((data[0].toInt() and 0xFF) != STATE_VERSION) return false
        i = 1
        val nb = IntArray(64)
        for (k in 0..63) {
            val v = data[i].toInt() and 0xFF
            i++
            nb[k] = when {
                v == 0 -> 0
                v in 1..6 -> v
                v >= 250 -> v - 256
                else -> return false
            }
        }
        val t = data[i].toInt() and 0xFF
        i++
        val newTurn = when (t) {
            0 -> Side.A
            1 -> Side.B
            else -> return false
        }
        val c = data[i].toInt() and 0xFF
        i++
        if (c > 15) return false
        val epB = data[i].toInt() and 0xFF
        i++
        val newEp = when {
            epB == 255 -> -1
            epB < 64 -> epB
            else -> return false
        }
        if (newEp >= 0 && newEp / 8 != 2 && newEp / 8 != 5) return false
        val hm = data[i].toInt() and 0xFF
        i++
        val fm = (data[i].toInt() and 0xFF) or ((data[i + 1].toInt() and 0xFF) shl 8)
        i += 2

        var whiteKings = 0
        var blackKings = 0
        for (k in 0..63) {
            when (nb[k]) {
                6 -> whiteKings++
                -6 -> blackKings++
            }
        }
        if (whiteKings != 1 || blackKings != 1) return false

        if (i >= data.size) return false
        val n = data[i].toInt() and 0xFF
        i++
        if (n > 64) return false
        if (data.size < i + n * 2 + 1) return false
        val newHistory = ArrayList<Int>(n)
        for (k in 0 until n) {
            val h = (data[i].toInt() and 0xFF) or ((data[i + 1].toInt() and 0xFF) shl 8)
            i += 2
            newHistory.add(h)
        }
        val m = data[i].toInt() and 0xFF
        i++
        if (m > 100) return false
        val newLog = ArrayList<String>(m)
        for (k in 0 until m) {
            if (i >= data.size) return false
            val len = data[i].toInt() and 0xFF
            i++
            if (len > 100) return false
            if (data.size < i + len) return false
            newLog.add(data.copyOfRange(i, i + len).decodeToString())
            i += len
        }
        if (i != data.size) return false

        nb.copyInto(board)
        turnSide = newTurn
        castlingRights = c
        epSquare = newEp
        halfmoveClock = hm
        fullmoveNumber = fm
        if (newHistory.isNotEmpty() && newHistory.last() != digest()) return false
        positionHistory.clear()
        positionHistory.addAll(newHistory)
        logEntries.clear()
        logEntries.addAll(newLog)
        isFinished = false
        gameResult = null
        // восстановление выводимых условий (мат/пат/ничьи по правилам)
        refreshEndState()
        return true
    }

    private fun refreshEndState() {
        if (isFinished) return
        val hasMoves = hasAnyLegalMove()
        val check = inCheck(turnSide)
        when {
            !hasMoves && check -> {
                gameResult = GameResult(turnSide.other, ResultReason.CHECKMATE)
                isFinished = true
            }
            !hasMoves -> {
                gameResult = GameResult(null, ResultReason.STALEMATE)
                isFinished = true
            }
            insufficientMaterial() -> {
                gameResult = GameResult(null, ResultReason.INSUFFICIENT_MATERIAL)
                isFinished = true
            }
            halfmoveClock >= 100 -> {
                gameResult = GameResult(null, ResultReason.FIFTY_MOVES)
                isFinished = true
            }
            else -> {
                val d = digest()
                var count = 0
                for (h in positionHistory) if (h == d) count++
                if (count >= 3) {
                    gameResult = GameResult(null, ResultReason.THREEFOLD)
                    isFinished = true
                }
            }
        }
    }

    private companion object {
        const val STATE_VERSION = 1

        val KNIGHT_OFFSETS = arrayOf(
            intArrayOf(-2, -1), intArrayOf(-2, 1),
            intArrayOf(-1, -2), intArrayOf(-1, 2),
            intArrayOf(1, -2), intArrayOf(1, 2),
            intArrayOf(2, -1), intArrayOf(2, 1),
        )
        val DIAGONALS = arrayOf(
            intArrayOf(-1, -1), intArrayOf(-1, 1),
            intArrayOf(1, -1), intArrayOf(1, 1),
        )
        val STRAIGHTS = arrayOf(
            intArrayOf(-1, 0), intArrayOf(1, 0),
            intArrayOf(0, -1), intArrayOf(0, 1),
        )
        val ALL_DIRS = arrayOf(
            intArrayOf(-1, -1), intArrayOf(-1, 0), intArrayOf(-1, 1),
            intArrayOf(0, -1), intArrayOf(0, 1),
            intArrayOf(1, -1), intArrayOf(1, 0), intArrayOf(1, 1),
        )

        // индекс = тип фигуры (2..6); индекс = код превращения (1..4)
        val PIECE_LETTERS = charArrayOf(' ', ' ', 'N', 'B', 'R', 'Q', 'K')
        val PROMO_LETTERS = charArrayOf(' ', 'Q', 'R', 'B', 'N')
    }
}
