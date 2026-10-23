package com.meshgames.game.engine

import com.meshgames.game.*

/**
 * Движок русских шашек (классические правила) для сетевой игры.
 *
 * Индексация клеток: 0..63, index = row*8 + col; row 0 — верх (8-я горизонталь),
 * col 0 — файл a. Игровые (тёмные) клетки: (row + col) % 2 != 0
 * (a1 = 56 и h8 = 7 — тёмные, на них стоят шашки) — как на настоящей доске:
 * ближний левый угол игрока тёмный, a8 светлая.
 *
 * Коды board: 0 пусто; 1 белая шашка; 2 белая дамка; 3 чёрная шашка; 4 чёрная дамка.
 * Сторона A = белые (ряды 5..7, ходят «вверх», к row 0),
 * сторона B = чёрные (ряды 0..2, ходят «вниз», к row 7).
 *
 * MOVE payload: [n, sq0, ..., sq(n-1)] — ровно n+1 байт, n квадратов хода.
 *  - простой ход: ровно 2 квадрата (откуда, куда);
 *  - взятие: нечётное число квадратов 3, 5, ... в формате
 *    «откуда, площадка, площадка, ...» — все приземления по порядку;
 *    квадраты побитых шашек принимающая сторона вычисляет по доске
 *    (детерминированно: первая фигура на диагонали — побитая).
 */
class CheckersEngine(
    override val mySide: Side,
    state: ByteArray? = null,
) : GameEngine {

    override val type: GameType = GameType.CHECKERS

    private val board = IntArray(64)
    private var toMove: Side = Side.A
    private var halfmove: Int = 0
    private val moveLog = ArrayList<String>()
    private var isFinished = false
    private var gameResult: GameResult? = null

    // --- транзиентное состояние выбора хода (не сериализуется) ---
    private var selFrom: Int = -1
    private var selMoves: List<IntArray> = emptyList()

    init {
        if (state == null) {
            setupInitial()
        } else {
            require(decodeState(state)) { "CheckersEngine: повреждённое состояние" }
        }
        // Сторона, чья очередь, без легальных ходов — сразу проигрывает.
        if (!isFinished && !hasAnyMove(toMove)) finishNoMoves()
    }

    // ------------------------------------------------------------------
    // GameEngine
    // ------------------------------------------------------------------

    override val turn: Side? get() = if (isFinished) null else toMove
    override val finished: Boolean get() = isFinished
    override val result: GameResult? get() = gameResult
    override val log: List<String> get() = moveLog

    override fun snapshot(): CheckersSnapshot = CheckersSnapshot(
        board.copyOf(),
        turn,
        anyCapture(board, toMove),
        selFrom,
        destinations,
        isFinished,
        gameResult,
        moveLog.toList(),
    )

    /** Первые шаги для фигуры с клетки [from] (при обязательном взятии — только площадки взятий). */
    override fun legalMoves(from: Int): Set<Int> {
        if (isFinished || from < 0 || from > 63) return emptySet()
        val piece = board[from]
        if (piece == 0 || sideOf(piece) != toMove) return emptySet()
        val targets =
            if (anyCapture(board, toMove)) captureTargets(board, from)
            else simpleTargets(board, from)
        return targets.toSet()
    }

    override fun localMove(input: MoveInput): ByteArray? {
        if (isFinished || toMove != mySide) return null
        if (input !is MoveInput.Chain) return null
        val squares = input.squares
        val fmt = validateChain(squares, mySide, board)
        if (fmt < 0) return null
        applyChain(squares, fmt)
        return payloadOf(squares)
    }

    override fun remoteMove(payload: ByteArray): Boolean {
        if (isFinished || toMove == mySide) return false
        val squares = parsePayload(payload) ?: return false
        val fmt = validateChain(squares, toMove, board)
        if (fmt < 0) return false
        applyChain(squares, fmt)
        return true
    }

    /**
     * Тестовый бот: первая цепочка первой своей шашки (обход доски 0..63
     * детерминирован). [completeMovesFrom] уже учитывает обязательность
     * взятия и валидирует цепочку — ход из движка никогда не отвергается.
     */
    override fun botMove(): MoveInput? {
        if (isFinished || toMove != mySide) return null
        for (sq in 0..63) {
            val piece = board[sq]
            if (piece == 0 || sideOf(piece) != mySide) continue
            val chain = completeMovesFrom(sq).firstOrNull() ?: continue
            return MoveInput.Chain(chain)
        }
        return null
    }

    override fun digest(): Int {
        // FNV-1a 32-bit по board[64] + байту очереди хода, затем смешивание в u16.
        var h = -2128831035 // 0x811C9DC5
        for (i in 0..63) h = (h xor board[i]) * 16777619
        h = (h xor (if (toMove == Side.A) 0 else 1)) * 16777619
        return (h xor (h ushr 16)) and 0xFFFF
    }

    override fun encodeState(): ByteArray {
        val m = minOf(moveLog.size, 100)
        val entries = ArrayList<ByteArray>(m)
        for (i in 0 until m) {
            var utf8 = moveLog[i].encodeToByteArray()
            if (utf8.size > 100) utf8 = utf8.copyOf(100)
            entries.add(utf8)
        }
        var size = 69 // байты 0..67 — заголовок, 68 — счётчик записей лога
        for (e in entries) size += 1 + e.size
        val out = ByteArray(size)
        out[0] = STATE_VERSION.toByte()
        for (i in 0..63) out[1 + i] = board[i].toByte()
        out[65] = (if (toMove == Side.A) 0 else 1).toByte()
        out[66] = 0 // зарезервировано
        out[67] = halfmove.toByte()
        out[68] = m.toByte()
        var pos = 69
        for (e in entries) {
            out[pos++] = e.size.toByte()
            for (b in e) out[pos++] = b
        }
        return out
    }

    // ------------------------------------------------------------------
    // API выбора хода для UI
    //
    // Модель «фигура → конечные клетки → маршрут → подтверждение»: игрок
    // выбирает шашку, видит подсвеченными конечные клетки всех легальных
    // ходов, выбирает точку завершения (маршрут рисуется линией) и
    // подтверждает ход повторным нажатием по ней. Цепочки взятий
    // перечисляются целиком, поэтому промежуточные площадки не являются
    // целями, а ход применяется атомарно.
    // ------------------------------------------------------------------

    /** Выбранная клетка; -1 — выбора нет. */
    val selected: Int get() = selFrom

    /** Конечные клетки легальных ходов выбранной фигуры (пусто — выбора нет). */
    val destinations: IntArray
        get() {
            val res = LinkedHashSet<Int>()
            for (m in selMoves) res.add(m[m.size - 1])
            return res.toIntArray()
        }

    /**
     * Все легальные цепочки выбранной фигуры, завершающиеся в [dest]
     * (пусто — такого хода нет). Несколько цепочек бывают, когда разные
     * последовательности взятий приводят в одну клетку — например, шашка
     * пробивается через «глухую» площадку и дотягивается дамкой.
     * Порядок детерминированный (порядок перебора ветвей).
     */
    fun routes(dest: Int): List<IntArray> {
        if (selFrom < 0 || dest < 0 || dest > 63) return emptyList()
        return selMoves.filter { it[it.size - 1] == dest }
    }

    /**
     * Маршрут хода (квадраты площадок, начиная с исходного), завершающегося
     * в [dest]; null — такого хода у выбранной фигуры нет. При нескольких
     * ветвях возвращает первую детерминированную.
     */
    fun route(dest: Int): IntArray? = routes(dest).firstOrNull()

    /**
     * Начать выбор своей фигуры (только когда очередь = mySide; при обязательном
     * взятии — только фигуры с взятием, у которых есть легальная цепочка).
     * false → ничего не меняется.
     */
    fun select(sq: Int): Boolean {
        if (isFinished || toMove != mySide) return false
        if (sq < 0 || sq > 63) return false
        val piece = board[sq]
        if (piece == 0 || sideOf(piece) != mySide) return false
        val moves = completeMovesFrom(sq)
        if (moves.isEmpty()) return false
        selFrom = sq
        selMoves = moves
        return true
    }

    /**
     * Применить ход, завершающийся в [dest], вернуть payload для MOVE;
     * сброс выбора. [routeIndex] выбирает ветвь, когда в [dest] ведёт
     * несколько цепочек. null → нелегален (состояние не меняется).
     */
    fun commitAt(dest: Int, routeIndex: Int = 0): ByteArray? {
        val squares = routes(dest).getOrNull(routeIndex) ?: return null
        if (isFinished || toMove != mySide) return null
        val fmt = validateChain(squares, mySide, board)
        if (fmt < 0) return null
        applyChain(squares, fmt)
        return payloadOf(squares)
    }

    /** Сбросить выбор без отправки. */
    fun cancelSelect() {
        resetSelection()
    }

    // ------------------------------------------------------------------
    // Начальная расстановка
    // ------------------------------------------------------------------

    private fun setupInitial() {
        for (i in 0..63) {
            val r = i shr 3
            val c = i and 7
            // Игровые (тёмные) клетки: (r + c) нечётно — на них и стоят шашки.
            if (((r + c) and 1) == 0) continue // светлая клетка
            when {
                r <= 2 -> board[i] = B_MAN
                r >= 5 -> board[i] = W_MAN
            }
        }
    }

    // ------------------------------------------------------------------
    // Правила
    // ------------------------------------------------------------------

    private fun sideOf(piece: Int): Side = if (piece == B_MAN || piece == B_KING) Side.B else Side.A

    private fun isKing(piece: Int): Boolean = piece == W_KING || piece == B_KING

    /** Превращение в дамку при остановке на последней горизонтали. */
    private fun promoted(piece: Int, sq: Int): Int = when {
        piece == W_MAN && (sq shr 3) == 0 -> W_KING
        piece == B_MAN && (sq shr 3) == 7 -> B_KING
        else -> piece
    }

    private fun iabs(x: Int): Int = if (x < 0) -x else x

    private fun anyCapture(b: IntArray, side: Side): Boolean {
        for (i in 0..63) {
            val p = b[i]
            if (p != 0 && sideOf(p) == side && captureTargets(b, i).isNotEmpty()) return true
        }
        return false
    }

    private fun anySimple(b: IntArray, side: Side): Boolean {
        for (i in 0..63) {
            val p = b[i]
            if (p != 0 && sideOf(p) == side && simpleTargets(b, i).isNotEmpty()) return true
        }
        return false
    }

    private fun hasAnyMove(side: Side): Boolean = anyCapture(board, side) || anySimple(board, side)

    /**
     * Все завершённые легальные ходы фигуры с клетки [from] в формате
     * «квадраты площадок» (первый — исходный). При обязательном взятии —
     * только завершённые цепочки (продолжение боя обязательно, уже
     * превратившаяся дамка бьёт дальше как дамка). Каждая цепочка проходит
     * ту же [validateChain], что и сетевой кадр, — UI не может предложить
     * ход, который движок отверг бы при отправке.
     */
    fun completeMovesFrom(from: Int): List<IntArray> {
        val piece = board[from]
        if (piece == 0) return emptyList()
        val res = ArrayList<IntArray>()
        if (anyCapture(board, toMove)) {
            enumerateCaptures(board, from, piece, ArrayList<Int>().apply { add(from) }, res)
        } else {
            for (t in simpleTargets(board, from)) res.add(intArrayOf(from, t))
        }
        return res.filter { validateChain(it, toMove, board) >= 0 }
    }

    /** DFS завершённых цепочек взятий с клетки [from] на доске [b]. */
    private fun enumerateCaptures(b: IntArray, from: Int, piece: Int, path: ArrayList<Int>, out: ArrayList<IntArray>) {
        for (to in captureTargets(b, from)) {
            val mid = captureStep(b, from, to, piece) ?: continue
            val w = b.copyOf()
            w[from] = 0
            w[mid] = 0
            w[to] = promoted(piece, to)
            path.add(to)
            if (captureTargets(w, to).isNotEmpty()) {
                enumerateCaptures(w, to, w[to], path, out)
            } else {
                out.add(path.toIntArray())
            }
            path.removeAt(path.size - 1)
        }
    }

    /** Площадки взятия фигуры с клетки [sq] на доске [b] (с учётом её цвета/рода). */
    private fun captureTargets(b: IntArray, sq: Int): IntArray {
        val piece = b[sq]
        if (piece == 0) return NONE
        val res = ArrayList<Int>()
        if (isKing(piece)) {
            for (d in 0..3) {
                val sr = DR[d]
                val sc = DC[d]
                var r = (sq shr 3) + sr
                var c = (sq and 7) + sc
                var mid = -1
                while (r in 0..7 && c in 0..7) {
                    val v = b[r * 8 + c]
                    if (v != 0) {
                        if (sideOf(v) == sideOf(piece)) break // своя мешает
                        mid = r * 8 + c
                        break
                    }
                    r += sr
                    c += sc
                }
                if (mid < 0) continue
                // Любая свободная клетка той же диагонали за побитой — площадка.
                r += sr
                c += sc
                while (r in 0..7 && c in 0..7 && b[r * 8 + c] == 0) {
                    res.add(r * 8 + c)
                    r += sr
                    c += sc
                }
            }
        } else {
            // Русские шашки: простая шашка бьёт и вперёд, и назад — прыжок
            // ровно через соседнюю шашку на ближайшую свободную клетку.
            for (dr in intArrayOf(-1, 1)) {
                for (dc in intArrayOf(-1, 1)) {
                    val mr = (sq shr 3) + dr
                    val mc = (sq and 7) + dc
                    if (mr !in 0..7 || mc !in 0..7) continue
                    val v = b[mr * 8 + mc]
                    if (v == 0 || sideOf(v) == sideOf(piece)) continue
                    val lr = (sq shr 3) + 2 * dr
                    val lc = (sq and 7) + 2 * dc
                    if (lr !in 0..7 || lc !in 0..7) continue
                    if (b[lr * 8 + lc] == 0) res.add(lr * 8 + lc)
                }
            }
        }
        return res.toIntArray()
    }

    /** Клетки простого хода фигуры с [sq]: простая шашка — только вперёд на 1, дамка — в любом направлении. */
    private fun simpleTargets(b: IntArray, sq: Int): IntArray {
        val piece = b[sq]
        if (piece == 0) return NONE
        val res = ArrayList<Int>()
        if (isKing(piece)) {
            for (d in 0..3) {
                var r = (sq shr 3) + DR[d]
                var c = (sq and 7) + DC[d]
                while (r in 0..7 && c in 0..7 && b[r * 8 + c] == 0) {
                    res.add(r * 8 + c)
                    r += DR[d]
                    c += DC[d]
                }
            }
        } else {
            val fwd = if (piece == W_MAN) -1 else 1
            for (dc in -1..1 step 2) {
                val r = (sq shr 3) + fwd
                val c = (sq and 7) + dc
                if (r in 0..7 && c in 0..7 && b[r * 8 + c] == 0) res.add(r * 8 + c)
            }
        }
        return res.toIntArray()
    }

    /**
     * Один прыжок с [from] на [to] через вражескую шашку: возвращает квадрат побитой
     * или null, если прыжок невозможен. Простая шашка бьёт вперёд и назад, ровно
     * через соседнюю шашку на ближайшую клетку; дамка — в любом направлении, с
     * приземлением на любую свободную клетку диагонали за побитой.
     */
    private fun captureStep(b: IntArray, from: Int, to: Int, piece: Int): Int? {
        if (to == from) return null
        val fr = from shr 3
        val fc = from and 7
        val tr = to shr 3
        val tc = to and 7
        val dr = tr - fr
        val dc = tc - fc
        if (dr == 0 || iabs(dr) != iabs(dc)) return null
        if (b[to] != 0) return null
        val sr = if (dr > 0) 1 else -1
        val sc = if (dc > 0) 1 else -1
        if (!isKing(piece)) {
            if (iabs(dr) != 2) return null // ровно через соседнюю клетку, вперёд и назад
            val mid = (fr + sr) * 8 + (fc + sc)
            val v = b[mid]
            if (v == 0 || sideOf(v) == sideOf(piece)) return null
            return mid
        }
        // Дамка: первая встретившаяся на пути фигура должна быть вражеской,
        // приземление — на любую свободную клетку за ней (вплоть до конца диагонали).
        var r = fr + sr
        var c = fc + sc
        var mid = -1
        while (r in 0..7 && c in 0..7) {
            val v = b[r * 8 + c]
            if (v != 0) {
                if (sideOf(v) == sideOf(piece)) return null
                mid = r * 8 + c
                break
            }
            r += sr
            c += sc
        }
        if (mid < 0) return null
        r += sr
        c += sc
        while (r in 0..7 && c in 0..7) {
            val s = r * 8 + c
            if (s == to) return mid
            if (b[s] != 0) return null
            r += sr
            c += sc
        }
        return null
    }

    /** Простой шаг на одну клетку диагонали: для простой шашки — только вперёд. */
    private fun simpleStepOk(b: IntArray, from: Int, to: Int, piece: Int): Boolean {
        if (to == from) return false
        if (b[to] != 0) return false
        val dr = (to shr 3) - (from shr 3)
        val dc = (to and 7) - (from and 7)
        if (dr == 0 || iabs(dr) != iabs(dc)) return false
        if (isKing(piece)) {
            val sr = if (dr > 0) 1 else -1
            val sc = if (dc > 0) 1 else -1
            var r = (from shr 3) + sr
            var c = (from and 7) + sc
            while (r != (to shr 3) || c != (to and 7)) {
                if (b[r * 8 + c] != 0) return false
                r += sr
                c += sc
            }
            return true
        }
        val fwd = if (piece == W_MAN) -1 else 1
        return dr == fwd
    }

    /**
     * Полная валидация цепочки для стороны [side] на доске [b].
     * Возвращает формат лога ([FMT_SIMPLE]/[FMT_LANDINGS]) либо -1 — нелегальна.
     *
     *  - 2 квадрата: простой ход (только если нет обязательного взятия) либо
     *    одиночное взятие «откуда, площадка»;
     *  - 3 и более: взятие «откуда, площадка, площадка...» (квадраты побитых
     *    вычисляются по доске — формат однозначен).
     *
     * Цепочка взятия обязана быть завершённой (нельзя остановиться при наличии
     * продолжения), выбор конкретной ветви — свободный.
     */
    private fun validateChain(squares: IntArray, side: Side, b: IntArray): Int {
        val n = squares.size
        if (n < 2) return -1
        for (s in squares) if (s < 0 || s > 63) return -1
        val start = b[squares[0]]
        if (start == 0 || sideOf(start) != side) return -1
        if (n == 2) {
            if (!anyCapture(b, side) && simpleStepOk(b, squares[0], squares[1], start)) return FMT_SIMPLE
            return if (jumpChainOk(squares, b, start)) FMT_LANDINGS else -1
        }
        return if (jumpChainOk(squares, b, start)) FMT_LANDINGS else -1
    }

    /**
     * Применяет прыжки цепочки «откуда, площадка...» к копии доски:
     * квадрат побитого вычисляется по доске ([captureStep]). В конце
     * требует, чтобы продолжения не было (п.5 правил).
     */
    private fun jumpChainOk(squares: IntArray, b: IntArray, startPiece: Int): Boolean {
        val work = b.copyOf()
        var piece = startPiece
        var from = squares[0]
        for (i in 1 until squares.size) {
            val to = squares[i]
            val mid = captureStep(work, from, to, piece) ?: return false
            work[from] = 0
            work[mid] = 0
            work[to] = promoted(piece, to)
            piece = work[to]
            from = to
        }
        // Обязательное продолжение: пока есть взятие — останавливаться нельзя.
        return captureTargets(work, from).isEmpty()
    }

    // ------------------------------------------------------------------
    // Применение хода
    // ------------------------------------------------------------------

    private fun applyChain(squares: IntArray, fmt: Int) {
        if (fmt == FMT_SIMPLE) {
            val p = board[squares[0]]
            board[squares[0]] = 0
            board[squares[1]] = promoted(p, squares[1])
        } else {
            // FMT_LANDINGS: площадки по порядку, побитые снимаются по правилам.
            var from = squares[0]
            var piece = board[from]
            for (i in 1 until squares.size) {
                val to = squares[i]
                val mid = captureStep(board, from, to, piece) ?: -1
                board[from] = 0
                board[mid] = 0
                board[to] = promoted(piece, to)
                piece = board[to]
                from = to
            }
        }
        moveLog.add(formatMove(squares, fmt))
        halfmove = (halfmove + 1) and 0xFF
        toMove = toMove.other
        resetSelection()
        if (!hasAnyMove(toMove)) finishNoMoves()
    }

    private fun finishNoMoves() {
        isFinished = true
        gameResult = GameResult(toMove.other, ResultReason.NO_MOVES)
    }

    private fun resetSelection() {
        selFrom = -1
        selMoves = emptyList()
    }

    private fun payloadOf(squares: IntArray): ByteArray {
        val out = ByteArray(squares.size + 1)
        out[0] = squares.size.toByte()
        for (i in squares.indices) out[i + 1] = squares[i].toByte()
        return out
    }

    private fun parsePayload(payload: ByteArray): IntArray? {
        if (payload.isEmpty()) return null
        val n = payload[0].toInt() and 0xFF
        if (n < 2 || n > 63 || payload.size != n + 1) return null
        val squares = IntArray(n)
        for (i in 0 until n) {
            val v = payload[i + 1].toInt() and 0xFF
            if (v > 63) return null
            squares[i] = v
        }
        return squares
    }

    private fun sqName(sq: Int): String = "${('a' + (sq and 7))}${8 - (sq shr 3)}"

    private fun formatMove(squares: IntArray, fmt: Int): String = when (fmt) {
        FMT_SIMPLE -> sqName(squares[0]) + "-" + sqName(squares[1])
        else -> squares.joinToString("x") { sqName(it) }
    }

    // ------------------------------------------------------------------
    // Сериализация
    // ------------------------------------------------------------------

    private fun decodeState(data: ByteArray): Boolean {
        if (data.size < 69) return false
        if ((data[0].toInt() and 0xFF) != STATE_VERSION) return false
        val nb = IntArray(64)
        for (i in 0..63) {
            val v = data[1 + i].toInt() and 0xFF
            if (v > B_KING) return false
            nb[i] = v
        }
        val turnByte = data[65].toInt() and 0xFF
        if (turnByte != 0 && turnByte != 1) return false
        val hm = data[67].toInt() and 0xFF
        val m = data[68].toInt() and 0xFF
        val entries = ArrayList<String>(m)
        var pos = 69
        repeat(m) {
            if (pos >= data.size) return false
            val len = data[pos].toInt() and 0xFF
            pos++
            if (pos + len > data.size) return false
            entries.add(data.copyOfRange(pos, pos + len).decodeToString())
            pos += len
        }
        // Обрыв или неверная версия уже отсеклись выше — фиксируем состояние целиком.
        for (i in 0..63) board[i] = nb[i]
        toMove = if (turnByte == 0) Side.A else Side.B
        halfmove = hm
        moveLog.clear()
        moveLog.addAll(entries)
        isFinished = false
        gameResult = null
        resetSelection()
        return true
    }

    companion object {
        /**
         * Версия [encodeState]: 2 — расклад «a1 тёмная», шашки только на
         * нечётной парности (см. doc класса). Состояние версии 1 (старая
         * раскладка) не читается: такая партия начинается заново с
         * начальной позиции — у обеих сторон, обновивших APK, одинаково.
         */
        const val STATE_VERSION: Int = 2

        private const val W_MAN = 1
        private const val W_KING = 2
        private const val B_MAN = 3
        private const val B_KING = 4

        private const val FMT_SIMPLE = 0
        private const val FMT_LANDINGS = 1

        private val NONE = IntArray(0)
        private val DR = intArrayOf(-1, -1, 1, 1)
        private val DC = intArrayOf(-1, 1, -1, 1)
    }
}
