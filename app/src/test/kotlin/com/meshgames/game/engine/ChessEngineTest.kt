package com.meshgames.game.engine

import com.meshgames.game.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Тесты шахматного движка. Индексы клеток: 0 = a8 ... 63 = h1.
 *
 * Формат вспомогательных ходов: [белые] — localMove (mySide = A),
 * [чёрные] — remoteMove с payload [from, to, promotion].
 */
class ChessEngineTest {

    // ------------------------------------------------------------------ helpers

    private fun eng(state: ByteArray? = null, mySide: Side = Side.A) = ChessEngine(mySide, state)

    private fun white(e: ChessEngine, from: Int, to: Int, promo: Int = 0): ByteArray? =
        e.localMove(MoveInput.FromTo(from, to, promo))

    private fun black(e: ChessEngine, from: Int, to: Int, promo: Int = 0): Boolean =
        e.remoteMove(byteArrayOf(from.toByte(), to.toByte(), promo.toByte()))

    private fun snap(e: ChessEngine): ChessSnapshot = e.snapshot()

    private fun boardOf(vararg cells: Pair<Int, Int>): IntArray {
        val b = IntArray(64)
        for ((sq, code) in cells) b[sq] = code
        return b
    }

    /** Собирает байты состояния в формате encodeState(). */
    private fun stateOf(
        board: IntArray,
        turn: Side = Side.A,
        castling: Int = 0,
        ep: Int = 255,
        halfmove: Int = 0,
        fullmove: Int = 1,
        history: IntArray = IntArray(0),
        log: List<String> = emptyList(),
    ): ByteArray {
        require(board.size == 64)
        val logBytes = log.map { it.encodeToByteArray() }
        var size = 72 + history.size * 2 + 1
        for (bytes in logBytes) size += 1 + bytes.size
        val out = ByteArray(size)
        var i = 0
        out[i++] = 1
        for (v in board) out[i++] = v.toByte()
        out[i++] = (if (turn == Side.A) 0 else 1).toByte()
        out[i++] = castling.toByte()
        out[i++] = if (ep < 0) 255.toByte() else ep.toByte()
        out[i++] = halfmove.coerceAtMost(255).toByte()
        out[i++] = (fullmove and 0xFF).toByte()
        out[i++] = ((fullmove shr 8) and 0xFF).toByte()
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

    // ------------------------------------------------------------------- tests

    @Test
    fun initialPositionAndLegalFirstMoves() {
        val e = eng()
        val s = snap(e)
        assertEquals(Side.A, e.turn)
        assertFalse(e.finished)
        assertNull(e.result)
        assertTrue(e.log.isEmpty())
        // расстановка: сверху чёрные, снизу белые
        assertEquals(-4, s.board[0])   // a8 ладья
        assertEquals(-6, s.board[4])   // e8 король
        assertEquals(-4, s.board[7])   // h8 ладья
        assertEquals(-1, s.board[8])   // a7 пешка
        assertEquals(-1, s.board[9])   // b7 чёрная пешка
        assertEquals(1, s.board[48])   // a2 пешка
        assertEquals(1, s.board[54])   // g2 пешка
        assertEquals(4, s.board[56])   // a1 ладья
        assertEquals(6, s.board[60])   // e1 король
        assertEquals(4, s.board[63])   // h1 ладья
        assertEquals(15, s.castling)
        assertEquals(-1, s.epSquare)
        assertEquals(0, s.halfmove)
        assertEquals(1, s.fullmove)

        // легальные первые ходы белых
        assertEquals(setOf(44, 36), e.legalMoves(52))  // e2: e3, e4
        assertEquals(setOf(45, 47), e.legalMoves(62))  // g1: f3, h3
        assertEquals(setOf(42, 40), e.legalMoves(57))  // b1: c3, a3
        assertEquals(20, (0..63).sumOf { e.legalMoves(it).size })

        val p = white(e, 52, 36)
        assertArrayEquals(byteArrayOf(52, 36, 0), p!!)
        assertEquals(Side.B, e.turn)
        assertEquals(44, snap(e).epSquare) // поле e3 после двойного шага

        // отдельные первые ходы на свежих позициях
        assertNotNull(white(eng(), 52, 44)) // e2-e3
        assertNotNull(white(eng(), 62, 45)) // g1-f3
        assertNotNull(white(eng(), 57, 42)) // b1-c3
        assertNotNull(white(eng(), 48, 40)) // a2-a3

        // ход чёрными первым отклоняется: turn = A
        val f = eng()
        assertNull(white(f, 12, 28)) // e7-e5 чёрной пешкой
        assertEquals(Side.A, f.turn)
        assertTrue(f.log.isEmpty())
        // то же через localMove при mySide = B (turn != mySide)
        assertNull(eng(mySide = Side.B).localMove(MoveInput.FromTo(12, 28)))
    }

    @Test
    fun illegalMovesRejected() {
        val e = eng()
        // конь ходит не в форме коня
        assertNull(white(e, 62, 61)) // g1 -> f1
        assertNull(white(e, 57, 58)) // b1 -> c1
        assertNull(white(e, 62, 60)) // g1 -> e1
        // пешка назад
        assertNull(white(e, 52, 60)) // e2 -> e1
        // взятие своей фигуры
        assertNull(white(e, 52, 51)) // e2 -> d2 (своя пешка)
        // ход «в пустоту», которой нет у фигуры
        assertNull(white(e, 52, 35)) // e2 -> d4

        // пешка назад из середины доски (отдельная позиция)
        val mid = eng(stateOf(boardOf(63 to 6, 0 to -6, 36 to 1)))
        assertEquals(setOf(28), mid.legalMoves(36)) // только e5
        assertNull(white(mid, 36, 44))  // e4 -> e3 (назад)
        assertNull(white(mid, 36, 52))  // e4 -> e2 (назад)
        assertNull(white(mid, 36, 43))  // e4 -> d3 (диагональ назад)
        assertNotNull(white(mid, 36, 28)) // e4 -> e5 легален

        // состояние после отклонённых ходов не изменилось
        val d = eng()
        val digest = d.digest()
        assertNull(white(d, 52, 60))
        assertEquals(digest, d.digest())
        assertTrue(d.log.isEmpty())
    }

    @Test
    fun pawnRules() {
        // двойной шаг с начальной позиции
        val e = eng()
        val p = white(e, 52, 36)
        assertArrayEquals(byteArrayOf(52, 36, 0), p!!)
        assertEquals(44, snap(e).epSquare)
        assertEquals(0, snap(e).halfmove)

        // одинарный шаг не даёт поля en passant
        val f = eng()
        assertNotNull(white(f, 52, 44))
        assertEquals(-1, snap(f).epSquare)

        // через препятствие нельзя
        val g = eng()
        assertNotNull(white(g, 51, 35))  // d2-d4
        assertTrue(black(g, 11, 27))     // d7-d5
        assertNull(white(g, 35, 27))     // d4-d5 вперёд на занятую — нельзя
        assertNull(white(g, 35, 19))     // d4-d6 «через» занятое — нельзя
        assertEquals(1, snap(g).board[35])   // белая пешка на d4
        assertEquals(-1, snap(g).board[27])  // чёрная пешка на d5
        assertEquals(listOf("d2-d4", "d7-d5"), g.log)

        // взятие пешкой
        val c = eng()
        assertNotNull(white(c, 52, 36))  // e2-e4
        assertTrue(black(c, 11, 27))     // d7-d5
        assertNotNull(white(c, 36, 27))  // exd5
        val cs = snap(c)
        assertEquals(1, cs.board[27])
        assertEquals(0, cs.board[36])
        assertEquals(0, cs.board[11])
        assertEquals("exd5", c.log.last())
        assertEquals(0, cs.halfmove) // взятие сбрасывает счётчик
    }

    @Test
    fun enPassant() {
        val e = eng()
        assertNotNull(white(e, 52, 36)) // e2-e4
        assertTrue(black(e, 8, 16))     // a7-a6
        assertNotNull(white(e, 36, 28)) // e4-e5
        assertTrue(black(e, 11, 27))    // d7-d5
        assertEquals(19, snap(e).epSquare) // d6
        assertNotNull(white(e, 28, 19)) // exd6 на проходе
        val s = snap(e)
        assertEquals(0, s.board[27])    // d5 пусто
        assertEquals(1, s.board[19])    // пешка на d6
        assertEquals(0, s.board[28])    // e5 пусто
        assertEquals(-1, s.epSquare)
        assertEquals("exd6", e.log.last())
        assertEquals(Side.B, e.turn)
    }

    @Test
    fun promotion() {
        // белая пешка a7, короли h1/f5
        fun fresh() = eng(stateOf(boardOf(63 to 6, 8 to 1, 29 to -6)))

        val e = fresh()
        assertFalse(e.finished)
        assertEquals(setOf(0), e.legalMoves(8))
        // promotion = 0 для хода на последнюю горизонталь — нелегален
        assertNull(white(e, 8, 0))
        assertEquals(0, snap(e).board[0])
        assertEquals(1, snap(e).board[8])
        assertTrue(e.log.isEmpty())
        // некорректный код превращения
        assertNull(white(e, 8, 0, 5))
        assertEquals(0, snap(e).board[0])
        // promotion = 1 — ферзь
        assertNotNull(white(e, 8, 0, 1))
        val s = snap(e)
        assertEquals(5, s.board[0])
        assertEquals(0, s.board[8])
        assertEquals(Side.B, e.turn)
        assertFalse(e.finished)

        // остальные коды превращения
        val r = fresh()
        assertNotNull(white(r, 8, 0, 2))
        assertEquals(4, snap(r).board[0])
        val b = fresh()
        assertNotNull(white(b, 8, 0, 3))
        assertEquals(3, snap(b).board[0])
        val n = fresh()
        assertNotNull(white(n, 8, 0, 4))
        assertEquals(2, snap(n).board[0])

        // promotion != 0 для обычного хода — нелегален
        val x = eng()
        assertNull(white(x, 52, 36, 1))
        assertEquals(0, snap(x).board[36])
    }

    @Test
    fun castlingKingsideWhite() {
        val e = eng()
        assertNotNull(white(e, 52, 36)) // e2-e4
        assertTrue(black(e, 12, 28))    // e7-e5
        assertNotNull(white(e, 62, 45)) // Ng1-f3
        assertTrue(black(e, 1, 18))     // Nb8-c6
        assertNotNull(white(e, 61, 34)) // Bf1-c4
        assertTrue(black(e, 5, 26))     // Bf8-c5
        val p = white(e, 60, 62)        // O-O
        assertNotNull(p)
        assertArrayEquals(byteArrayOf(60, 62, 0), p!!)
        val s = snap(e)
        assertEquals(6, s.board[62]) // король g1
        assertEquals(4, s.board[61]) // ладья f1
        assertEquals(0, s.board[60])
        assertEquals(0, s.board[63])
        assertEquals(0, s.castling and 3)   // белые права сняты
        assertEquals(12, s.castling and 12) // чёрные права целы
        assertEquals("O-O", e.log.last())
        assertEquals(4, s.fullmove)
        assertEquals(Side.B, e.turn)
        assertFalse(e.finished)
    }

    @Test
    fun castlingForbidden() {
        // (a) через атакованное поле f1
        val a = eng(stateOf(boardOf(60 to 6, 63 to 4, 46 to -2, 0 to -6), castling = 15))
        assertFalse(a.finished)
        assertNull(white(a, 60, 62))          // O-O запрещена
        assertFalse(a.legalMoves(60).contains(62))
        assertEquals(6, snap(a).board[60])
        assertTrue(a.log.isEmpty())
        // другая партия возможна
        assertNotNull(white(a, 60, 59))       // Kd1
        assertEquals(0, snap(a).castling and 3) // король ушёл — права сняты

        // (b) король под шахом
        val c = eng(stateOf(boardOf(60 to 6, 63 to 4, 56 to -4, 0 to -6), castling = 15))
        assertEquals(Side.A, snap(c).checked)
        assertFalse(c.finished)
        assertNull(white(c, 60, 62))          // под шахом рокировка запрещена
        assertEquals(6, snap(c).board[60])
        assertNotNull(white(c, 60, 51))       // Ke2 выходит из шаха

        // (c) после движения короля права утеряны
        val d = eng()
        assertNotNull(white(d, 52, 36))  // e2-e4
        assertTrue(black(d, 12, 28))     // e7-e5
        assertNotNull(white(d, 62, 45))  // Ng1-f3
        assertTrue(black(d, 1, 18))      // Nb8-c6
        assertNotNull(white(d, 60, 52))  // Ke1-e2
        assertEquals(0, snap(d).castling and 3)
        assertTrue(black(d, 5, 26))      // Bf8-c5
        assertNotNull(white(d, 52, 60))  // Ke2-e1
        assertTrue(black(d, 6, 21))      // Ng8-f6
        assertNotNull(white(d, 61, 34))  // Bf1-c4
        assertNull(white(d, 60, 62))     // O-O больше невозможна
        assertEquals(6, snap(d).board[60])
    }

    @Test
    fun castlingOtherVariants() {
        // белые 0-0-0
        val wq = eng(stateOf(boardOf(60 to 6, 56 to 4, 63 to 4, 1 to -6), castling = 15))
        val p = white(wq, 60, 58)
        assertNotNull(p)
        assertArrayEquals(byteArrayOf(60, 58, 0), p!!)
        val s = snap(wq)
        assertEquals(6, s.board[58]) // король c1
        assertEquals(4, s.board[59]) // ладья d1
        assertEquals(0, s.board[56])
        assertEquals(0, s.board[60])
        assertEquals("O-O-O", wq.log.last())

        // чёрные 0-0
        val bk = eng(stateOf(boardOf(40 to 6, 4 to -6, 7 to -4), castling = 4, turn = Side.B))
        assertTrue(black(bk, 4, 6))
        val bs = snap(bk)
        assertEquals(-6, bs.board[6]) // король g8
        assertEquals(-4, bs.board[5]) // ладья f8
        assertEquals(0, bs.board[4])
        assertEquals(0, bs.board[7])
        assertEquals(0, bs.castling)
        assertEquals("O-O", bk.log.last())
        assertEquals(Side.A, bk.turn)

        // чёрные 0-0-0 (натуральная последовательность)
        val e = eng()
        assertNotNull(white(e, 52, 36)) // e2-e4
        assertTrue(black(e, 12, 28))    // e7-e5
        assertNotNull(white(e, 62, 45)) // Ng1-f3
        assertTrue(black(e, 1, 18))     // Nb8-c6
        assertNotNull(white(e, 61, 34)) // Bf1-c4
        assertTrue(black(e, 11, 19))    // d7-d6
        assertNotNull(white(e, 51, 43)) // d2-d3
        assertTrue(black(e, 2, 20))     // Bc8-e6
        assertNotNull(white(e, 55, 47)) // h2-h3
        assertTrue(black(e, 3, 12))     // Qd8-e7
        assertNotNull(white(e, 48, 40)) // a2-a3
        assertTrue(black(e, 4, 2))      // O-O-O
        val es = snap(e)
        assertEquals(-6, es.board[2]) // король c8
        assertEquals(-4, es.board[3]) // ладья d8
        assertEquals(0, es.board[0])
        assertEquals(0, es.board[4])
        assertEquals(3, es.castling)  // сняты чёрные права, белые целы
        assertEquals("O-O-O", e.log.last())
        assertFalse(e.finished)
    }

    @Test
    fun checkmateInTwo() {
        val e = eng()
        assertNotNull(white(e, 53, 45)) // f2-f3
        assertTrue(black(e, 12, 28))     // e7-e5
        assertNotNull(white(e, 54, 38))  // g2-g4
        assertTrue(black(e, 3, 39))      // Qd8-h4#
        assertTrue(e.finished)
        assertNull(e.turn)
        assertEquals(GameResult(Side.B, ResultReason.CHECKMATE), e.result)
        assertEquals("Qd8-h4#", e.log.last())
        val s = snap(e)
        assertTrue(s.finished)
        assertNull(s.checked)
        assertNull(s.turn)
        assertEquals(Side.B, s.result?.winner)
        // после мат ходов больше нет
        assertFalse(black(e, 0, 8))
        assertNull(white(e, 53, 45))
    }

    @Test
    fun stalemate() {
        // белые Kg6 + Qf2, чёрные Kh8; белые ставят пат: Qf2-f7
        val e = eng(stateOf(boardOf(22 to 6, 53 to 5, 7 to -6)))
        assertFalse(e.finished)
        assertNotNull(white(e, 53, 13)) // Qf2-f7
        assertTrue(e.finished)
        assertNull(e.turn)
        assertEquals(GameResult(null, ResultReason.STALEMATE), e.result)
        assertEquals("Qf2-f7", e.log.last())
        // у чёрных нет ходов, но шаха нет
        assertFalse(black(e, 7, 6))
        val s = snap(e)
        assertNull(s.checked)
        assertEquals(ResultReason.STALEMATE, s.result?.reason)
        assertNull(s.result?.winner)
    }

    @Test
    fun insufficientMaterial() {
        // K vs K — ничья сразу после восстановления позиции
        val kk = eng(stateOf(boardOf(35 to 6, 29 to -6)))
        assertTrue(kk.finished)
        assertEquals(GameResult(null, ResultReason.INSUFFICIENT_MATERIAL), kk.result)
        assertNull(kk.turn)

        // K+N vs K — наступает после взятия последней чёрной фигуры
        val kn = eng(stateOf(boardOf(63 to 6, 36 to 2, 0 to -6, 21 to -3)))
        assertFalse(kn.finished)
        assertNotNull(white(kn, 36, 21)) // Nxf6
        assertTrue(kn.finished)
        assertEquals(GameResult(null, ResultReason.INSUFFICIENT_MATERIAL), kn.result)
        assertNull(kn.turn)
    }

    @Test
    fun fiftyMoveRule() {
        // маршрут коня из 51 различной клетки (без повторов позиции);
        // запрещённые клетки: a8/c7/b6 (шах чёрному королю), e6/d4 (чёрный конь),
        // h1 (белый король), a8 — чёрный король
        val path = intArrayOf(
            62, 47, 30, 15, 5, 11, 1, 16, 26, 32, 49, 59, 53, 36, 46, 31, 14, 4,
            21, 6, 23, 38, 55, 61, 51, 57, 40, 25, 8, 2, 12, 29, 19, 9, 3, 13, 7,
            22, 39, 45, 28, 18, 24, 34, 44, 54, 60, 50, 56, 41, 58,
        )
        // белые: Kh1, N с g1; чёрные: Ka8, N с d4 (качается d4 <-> e6)
        val e = eng(stateOf(boardOf(63 to 6, 62 to 2, 0 to -6, 35 to -2)))
        assertFalse(e.finished)
        for (k in 0 until 50) {
            assertNotNull("белый ход №${k + 1}", white(e, path[k], path[k + 1]))
            if (k == 49) {
                assertEquals(99, snap(e).halfmove)
                assertFalse(e.finished)
            }
            val from = if (k % 2 == 0) 35 else 20
            val to = if (k % 2 == 0) 20 else 35
            assertTrue("чёрный ход №${k + 1}", black(e, from, to))
        }
        assertEquals(100, snap(e).halfmove)
        assertTrue(e.finished)
        assertEquals(GameResult(null, ResultReason.FIFTY_MOVES), e.result)
        assertNull(e.turn)
        assertEquals(100, e.log.size)
    }

    @Test
    fun pinAndCheck() {
        // пин: белый конь e2 прикован к королю чёрной ладьёй e8
        val p = eng(stateOf(boardOf(60 to 6, 52 to 2, 4 to -4, 0 to -6)))
        assertFalse(p.finished)
        assertNull(snap(p).checked)
        assertEquals(emptySet<Int>(), p.legalMoves(52))
        assertNull(white(p, 52, 42))          // Nc3 «логичен», но король под шахом
        assertEquals(2, snap(p).board[52])    // состояние не изменилось
        assertTrue(p.log.isEmpty())
        assertNotNull(white(p, 60, 59))       // Kd1 легален — игра продолжается

        // шах: ладья бьёт по королю без фигуры-закрытия
        val c = eng(stateOf(boardOf(60 to 6, 48 to 1, 4 to -4, 0 to -6)))
        assertEquals(Side.A, snap(c).checked)
        assertFalse(c.finished)
        assertEquals(setOf(51, 53, 59, 61), c.legalMoves(60))
        // пешка не может вывести из шаха
        assertEquals(emptySet<Int>(), c.legalMoves(48))
        assertNull(white(c, 48, 32))          // a2-a4 шах не снимает — отклонён
        assertEquals(6, snap(c).board[60])
        // выведение из шаха
        assertNotNull(white(c, 60, 51))       // Kd2
        assertNull(snap(c).checked)
    }

    @Test
    fun stateRoundTrip() {
        val e = eng()
        assertNotNull(white(e, 52, 36)) // e2-e4
        assertTrue(black(e, 12, 28))     // e7-e5
        assertNotNull(white(e, 62, 45)) // Ng1-f3
        assertTrue(black(e, 1, 18))     // Nb8-c6

        val saved = e.encodeState()
        val restored = eng(saved, mySide = Side.B)
        val s0 = snap(e)
        val s1 = snap(restored)
        assertTrue(s0.board.contentEquals(s1.board))
        assertEquals(s0.turn, s1.turn)
        assertEquals(s0.castling, s1.castling)
        assertEquals(s0.epSquare, s1.epSquare)
        assertEquals(s0.halfmove, s1.halfmove)
        assertEquals(s0.fullmove, s1.fullmove)
        assertEquals(s0.finished, s1.finished)
        assertEquals(e.digest(), restored.digest())
        assertEquals(e.log, restored.log)

        // тот же следующий ход принимается на восстановленном экземпляре
        val payload = white(e, 61, 34) // Bf1-c4
        assertNotNull(payload)
        assertTrue(restored.remoteMove(payload!!))
        assertEquals(e.digest(), restored.digest())
        assertEquals(e.log, restored.log)
        assertTrue(snap(e).board.contentEquals(snap(restored).board))
        assertEquals(Side.B, restored.turn)

        // повреждённые состояния отклоняются
        assertThrows(IllegalArgumentException::class.java) { eng(byteArrayOf(1, 2, 3)) }
        val wrongVersion = saved.copyOf().also { it[0] = 9 }
        assertThrows(IllegalArgumentException::class.java) { eng(wrongVersion) }
        val badPiece = saved.copyOf().also { it[5] = 7 }
        assertThrows(IllegalArgumentException::class.java) { eng(badPiece) }
        val badTurn = saved.copyOf().also { it[65] = 2 }
        assertThrows(IllegalArgumentException::class.java) { eng(badTurn) }
        val badCastling = saved.copyOf().also { it[66] = 16 }
        assertThrows(IllegalArgumentException::class.java) { eng(badCastling) }
        val truncated = saved.copyOfRange(0, saved.size - 10)
        assertThrows(IllegalArgumentException::class.java) { eng(truncated) }
    }

    @Test
    fun digestConsistency() {
        val a = eng()
        val b = eng()
        assertEquals(a.digest(), b.digest())
        val before = a.digest()
        assertNotNull(white(a, 52, 36))
        assertNotNull(white(b, 52, 36))
        assertEquals(a.digest(), b.digest())
        assertNotEquals(before, a.digest())
        // другой ход — другой дайджест
        val c = eng()
        assertNotNull(white(c, 52, 44))
        assertNotEquals(a.digest(), c.digest())
        // дайджест не зависит от лога/счётчиков, но меняется при взятии
        val d = eng()
        assertNotNull(white(d, 52, 36))
        assertTrue(black(d, 11, 27)) // d7-d5
        val dBefore = d.digest()
        assertNotNull(white(d, 36, 27)) // exd5
        assertNotEquals(dBefore, d.digest())
    }

    @Test
    fun remoteMoveValidation() {
        val e = eng()
        val d0 = e.digest()
        // очередь белых: ход чёрных отклоняется, состояние не меняется
        assertFalse(e.remoteMove(byteArrayOf(12, 28, 0)))
        // ход стороны, чья очередь, но нелегальный
        assertFalse(e.remoteMove(byteArrayOf(62, 61, 0)))
        // слишком короткий payload
        assertFalse(e.remoteMove(byteArrayOf(52)))
        assertEquals(d0, e.digest())
        assertTrue(e.log.isEmpty())
        assertEquals(Side.A, e.turn)
        // локальная попытка хода чёрной фигурой
        assertNull(white(e, 12, 28))

        // mySide = B: локально белые не ходят, но сеть может применить их ход
        val b = eng(mySide = Side.B)
        assertNull(b.localMove(MoveInput.FromTo(52, 36)))
        assertTrue(b.remoteMove(byteArrayOf(52, 36, 0)))
        assertEquals(Side.B, b.turn)
    }

    @Test
    fun castlingRightsRevoked() {
        // рокировка снимает оба права стороны
        val e = eng()
        assertNotNull(white(e, 52, 36))  // e2-e4
        assertTrue(black(e, 12, 28))      // e7-e5
        assertNotNull(white(e, 62, 45))  // Ng1-f3
        assertTrue(black(e, 1, 18))      // Nb8-c6
        assertNotNull(white(e, 61, 34))  // Bf1-c4
        assertTrue(black(e, 5, 26))      // Bf8-c5
        assertNotNull(white(e, 60, 62))  // O-O
        assertEquals(0, snap(e).castling and 3)
        assertEquals(12, snap(e).castling and 12)

        // взятие ладьи на её поле снимает право, движение ладьи — тоже
        val r = eng(
            stateOf(
                boardOf(60 to 6, 56 to 4, 63 to 4, 4 to -6, 7 to -4),
                castling = 7,
                turn = Side.B,
            ),
        )
        assertTrue(black(r, 7, 63)) // Rxh1
        val rs = snap(r)
        assertEquals(-4, rs.board[63])
        assertEquals(0, rs.board[7])
        assertEquals(4, rs.board[56]) // ладья a1 не тронута
        assertEquals(2, rs.castling)  // 1 (взята h1) и 4 (ладья ушла с h8) сняты
        assertEquals(Side.A, r.turn)
        assertEquals(Side.A, rs.checked) // рокировка даёт шах — игра продолжается
        assertFalse(r.finished)
    }
}
