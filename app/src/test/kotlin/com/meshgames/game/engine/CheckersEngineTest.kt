package com.meshgames.game.engine

import com.meshgames.game.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Тесты движка русских шашек.
 *
 * Индексация: index = row*8 + col (row 0 — верх, col 0 — файл a),
 * коды: 1 белая шашка, 2 белая дамка, 3 чёрная шашка, 4 чёрная дамка.
 */
class CheckersEngineTest {

    private companion object {
        const val W = 1   // белая шашка
        const val WK = 2  // белая дамка
        const val B = 3   // чёрная шашка
        const val BK = 4  // чёрная дамка

        /** "d3" -> индекс клетки. */
        fun sq(name: String): Int {
            val col = name[0] - 'a'
            val rank = name.substring(1).toInt()
            return (8 - rank) * 8 + col
        }

        /** Состояние из набора фигур (заголовок формата encodeState, лог пуст). */
        fun pos(vararg pieces: Pair<String, Int>, turn: Side = Side.A): ByteArray {
            val b = IntArray(64)
            for ((name, code) in pieces) b[sq(name)] = code
            val out = ByteArray(69)
            out[0] = CheckersEngine.STATE_VERSION.toByte()
            for (i in 0..63) out[1 + i] = b[i].toByte()
            out[65] = (if (turn == Side.A) 0 else 1).toByte()
            out[66] = 0
            out[67] = 0
            out[68] = 0
            return out
        }

        /** MoveInput.Chain по именам клеток. */
        fun chain(vararg names: String): MoveInput.Chain =
            MoveInput.Chain(IntArray(names.size) { sq(names[it]) })
    }

    // 1. Начальная позиция ------------------------------------------------

    @Test
    fun initialPosition() {
        val e = CheckersEngine(Side.A)
        val snap = e.snapshot()
        var white = 0
        var black = 0
        for (i in 0..63) {
            val v = snap.board[i]
            // Тёмные клетки — как на настоящей доске: a1 (и h8) тёмная.
            val dark = ((i / 8) + (i % 8)) % 2 != 0
            if (v == W || v == WK) {
                white++
                assertTrue("белая шашка на светлой клетке $i", dark)
                assertTrue("белая вне ряда 5..7: $i", i / 8 in 5..7)
            }
            if (v == B || v == BK) {
                black++
                assertTrue("чёрная шашка на светлой клетке $i", dark)
                assertTrue("чёрная вне ряда 0..2: $i", i / 8 in 0..2)
            }
            if (v != 0) assertTrue("фигура на светлой клетке $i", dark)
        }
        assertEquals(12, white)
        assertEquals(12, black)
        assertEquals(B, snap.board[sq("h8")]) // чёрная шашка в углу чёрных
        assertEquals(W, snap.board[sq("a1")]) // белая шашка в углу белых
        assertEquals(Side.A, e.turn)
        assertFalse(e.finished)
        assertNull(e.result)
        assertFalse(snap.mustCapture)
        assertEquals(-1, e.selected)
        assertTrue(e.destinations.isEmpty())
        assertNull(e.route(sq("c3"))) // c3 — светлая клетка: шашек нет
        assertTrue(e.log.isEmpty())
    }

    // 2. Ход не своей очередью -------------------------------------------

    @Test
    fun localMoveRequiresOurTurn() {
        val e = CheckersEngine(Side.A)
        assertNotNull(e.localMove(chain("c3", "b4")))
        assertEquals(Side.B, e.turn)
        // чёрные ходят, а mySide = A — любой ход отклоняется
        assertNull(e.localMove(chain("d6", "c5")))
        assertNull(e.localMove(chain("e5", "d4")))
        assertFalse(e.select(sq("e5")))
        assertNull(e.commitAt(sq("d4")))
    }

    // 3. Простые ходы вперёд / назад -------------------------------

    @Test
    fun whiteMovesOnlyForward() {
        val e = CheckersEngine(Side.A)
        // b3 — светлая клетка при заданном раскладе тёмных ((r+c) нечёт): финашек нет
        assertTrue(e.legalMoves(sq("b3")).isEmpty())
        assertEquals(setOf(sq("b4"), sq("d4")), e.legalMoves(sq("c3")))
        assertNotNull(e.localMove(chain("c3", "b4")))
    }

    @Test
    fun whiteCannotMoveBackward() {
        val e = CheckersEngine(Side.A, pos("c4" to W, "g8" to B))
        assertEquals(setOf(sq("b5"), sq("d5")), e.legalMoves(sq("c4")))
        assertNull(e.localMove(chain("c4", "d3"))) // назад
        assertNotNull(e.localMove(chain("c4", "b5"))) // вперёд
    }

    @Test
    fun blackMovesOnlyForward() {
        val e = CheckersEngine(Side.B, pos("c6" to B, "d3" to W, turn = Side.B))
        assertEquals(setOf(sq("b5"), sq("d5")), e.legalMoves(sq("c6")))
        assertNull(e.localMove(chain("c6", "b7"))) // назад для чёрных
        assertNotNull(e.localMove(chain("c6", "d5"))) // вперёд
    }

    // 4. Взятие обязательно --------------------------------------------

    @Test
    fun captureIsMandatory() {
        val e = CheckersEngine(Side.A, pos("b5" to W, "f5" to W, "c6" to B, "g8" to B))
        val snap = e.snapshot()
        assertTrue(snap.mustCapture)
        // «простой» ход при наличии взятия — нелегален
        assertNull(e.localMove(chain("f5", "e6")))
        // подсветка: у f5 нет взятий, только площадки взятий разрешены
        assertTrue(e.legalMoves(sq("f5")).isEmpty())
        assertEquals(setOf(sq("d7")), e.legalMoves(sq("b5")))
        // цепочка взятия принимается, побитая шашка снимается
        val p = e.localMove(chain("b5", "d7"))
        assertNotNull(p)
        assertArrayEquals(byteArrayOf(2, 25, 11), p)
        val after = e.snapshot()
        assertEquals(0, after.board[sq("c6")])
        assertEquals(W, after.board[sq("d7")])
        assertEquals(Side.B, e.turn)
        assertFalse(e.finished)
        assertEquals(listOf("b5xd7"), e.log)
    }

    // 5. Простая шашка бьёт вперёд и назад ------------------------------

    @Test
    fun manCapturesBackwardToo() {
        // Русские шашки: простая шашка бьёт в обе стороны. У белой на d7
        // вперёд бить нечем, но c6 пробивается назад — взятие обязательно.
        val e = CheckersEngine(Side.A, pos("d7" to W, "c6" to B, "a8" to B))
        assertTrue(e.snapshot().mustCapture)
        assertEquals(setOf(sq("b5")), e.legalMoves(sq("d7")))
        // простой ход при наличии взятия нелегален
        assertNull(e.localMove(chain("d7", "c8")))
        // цепочка назад применяется, побитая снимается
        val p = e.localMove(chain("d7", "b5"))
        assertNotNull(p)
        assertArrayEquals(byteArrayOf(2, sq("d7").toByte(), sq("b5").toByte()), p)
        val snap = e.snapshot()
        assertEquals(W, snap.board[sq("b5")])
        assertEquals(0, snap.board[sq("c6")])
        assertEquals(Side.B, e.turn)
        assertEquals(listOf("d7xb5"), e.log)
    }

    @Test
    fun manCaptureChainContinuesBackward() {
        // Полевая позиция 17.10 (скриншот): чёрная b5 бьёт c4 вперёд, затем
        // e4 назад — цепочка завершается только на f5; d3 промежуточная и
        // не может быть целью выбора. (h1 — белая вне боя, чтобы партия
        // не закончилась после снятия обеих белых.)
        val setup = pos("b5" to B, "c4" to W, "e4" to W, "h1" to W, turn = Side.B)
        val e = CheckersEngine(Side.B, setup)
        assertTrue(e.snapshot().mustCapture)
        assertTrue(e.select(sq("b5")))
        assertArrayEquals(intArrayOf(sq("f5")), e.destinations)
        assertNull(e.route(sq("d3")))
        assertNull(e.commitAt(sq("d3")))
        assertArrayEquals(intArrayOf(sq("b5"), sq("d3"), sq("f5")), e.route(sq("f5")))
        val p = e.commitAt(sq("f5"))
        assertArrayEquals(
            byteArrayOf(3, sq("b5").toByte(), sq("d3").toByte(), sq("f5").toByte()),
            p,
        )
        val snap = e.snapshot()
        assertEquals(B, snap.board[sq("f5")])
        assertEquals(0, snap.board[sq("c4")])
        assertEquals(0, snap.board[sq("e4")])
        assertEquals(Side.A, e.turn)
        assertEquals(listOf("b5xd3xf5"), e.log)
        // белые-пиры принимают payload, дайджесты сходятся
        val peer = CheckersEngine(Side.A, setup)
        assertTrue(peer.remoteMove(p!!))
        assertEquals(e.digest(), peer.digest())
    }

    // 6. Цепочка взятий, применяется атомарно --------------------------

    @Test
    fun multiJumpChainAppliedAtomically() {
        val e = CheckersEngine(
            Side.A,
            pos("h1" to W, "g2" to B, "e4" to B, "c6" to B, "g8" to B),
        )
        val p = e.localMove(chain("h1", "f3", "d5", "b7"))
        assertNotNull(p)
        assertArrayEquals(byteArrayOf(4, 63, 45, 27, 9), p)
        val snap = e.snapshot()
        for (name in listOf("g2", "e4", "c6")) assertEquals("побита $name", 0, snap.board[sq(name)])
        for (name in listOf("h1", "f3", "d5")) assertEquals(0, snap.board[sq(name)])
        assertEquals(W, snap.board[sq("b7")])
        assertEquals(B, snap.board[sq("g8")])
        assertEquals(Side.B, e.turn)
        assertFalse(e.finished)
        assertEquals(listOf("h1xf3xd5xb7"), e.log)
    }

    @Test
    fun captureChainMustContinueWhilePossible() {
        // та же позиция: остановиться после двух прыжков нельзя (есть продолжение)
        val e = CheckersEngine(
            Side.A,
            pos("h1" to W, "g2" to B, "e4" to B, "c6" to B, "g8" to B),
        )
        assertNull(e.localMove(chain("h1", "f3", "d5")))
        // а полностью завершённая цепочка (3 прыжка) — принимается
        assertNotNull(e.localMove(chain("h1", "f3", "d5", "b7")))
    }

    // 7. Дамка ----------------------------------------------------------

    @Test
    fun kingFliesAnyDistanceAndBackward() {
        val e = CheckersEngine(Side.A, pos("b7" to WK, "f5" to B))
        val moves = e.legalMoves(sq("b7"))
        assertTrue(moves.contains(sq("a8"))) // вперёд на 1
        assertTrue(moves.contains(sq("h1"))) // полёт назад через полдоски
        assertTrue(moves.contains(sq("c6")))
        // d3 не по диагонали
        assertFalse(moves.contains(sq("d3")))
        val after = e.localMove(chain("b7", "h1"))
        assertNotNull(after)
        assertEquals(WK, e.snapshot().board[sq("h1")])
    }

    @Test
    fun kingCapturesBackwardAndFarLanding() {
        // дамка бьёт назад (вниз) и может встать на отдалённую клетку диагонали
        val e = CheckersEngine(Side.A, pos("d5" to WK, "c4" to B, "g8" to B))
        assertTrue(e.snapshot().mustCapture)
        // «простой» ход при наличии взятия нелегален
        assertNull(e.localMove(chain("d5", "e4")))
        // ближайшая площадка
        val e1 = CheckersEngine(Side.A, pos("d5" to WK, "c4" to B, "g8" to B))
        assertNotNull(e1.localMove(chain("d5", "b3")))
        assertEquals(WK, e1.snapshot().board[sq("b3")])
        assertEquals(0, e1.snapshot().board[sq("c4")])
        // отдалённая площадка (a2) для дамки легальна
        val e2 = CheckersEngine(Side.A, pos("d5" to WK, "c4" to B, "g8" to B))
        assertNotNull(e2.localMove(chain("d5", "a2")))
        assertEquals(WK, e2.snapshot().board[sq("a2")])
        assertFalse(e2.finished)
    }

    @Test
    fun manCannotLandFarAfterCapture() {
        // простая шашка бьёт только на ближайшую клетку за побитой
        val e = CheckersEngine(Side.A, pos("b5" to W, "c6" to B, "g8" to B))
        assertNotNull(e.localMove(chain("b5", "d7")))
        assertEquals(W, e.snapshot().board[sq("d7")])
        // та же «дальняя» landing для простой шашки нелегальна
        val e2 = CheckersEngine(Side.A, pos("b5" to W, "c6" to B, "g8" to B))
        assertNull(e2.localMove(chain("b5", "e8")))
        // а для дамки — легальна
        val e3 = CheckersEngine(Side.A, pos("b5" to WK, "c6" to B, "g8" to B))
        assertNotNull(e3.localMove(chain("b5", "e8")))
        assertEquals(WK, e3.snapshot().board[sq("e8")])
    }

    // 8. Превращение в дамку посреди цепочки ----------------------------

    @Test
    fun promotionInMiddleOfChain() {
        val e = CheckersEngine(
            Side.A,
            pos("c6" to W, "b7" to B, "d5" to B, "g8" to B),
        )
        // остановиться после первого прыжка нельзя: продолжение есть
        assertNull(e.localMove(chain("c6", "a8")))
        // дошла до последней горизонтали, стала дамкой и ударила назад (d5 «за спиной»)
        val p = e.localMove(chain("c6", "a8", "e4"))
        assertNotNull(p)
        assertArrayEquals(byteArrayOf(3, 18, 0, 36), p)
        val snap = e.snapshot()
        // шашка промежуточно встала на a8, стала дамкой и продолжила бить уже дамкой:
        // второй прыжок (d5 «за спиной») возможен только для дамки, поэтому
        // завершение цепочки на e4 кодом дамки и доказывает превращение
        assertEquals(0, snap.board[sq("a8")]) // a8 — промежуточная площадка, фигура ушла дальше
        assertEquals(WK, snap.board[sq("e4")])
        assertEquals(0, snap.board[sq("b7")])
        assertEquals(0, snap.board[sq("d5")])
        assertEquals(0, snap.board[sq("c6")])
        assertEquals(Side.B, e.turn)
        assertEquals(listOf("c6xa8xe4"), e.log)
    }

    // 9. Нет легальных ходов → поражение --------------------------------

    @Test
    fun noLegalMovesMeansDefeat() {
        // белые: две шашки на последней горизонтали, бить нечем — ходов нет
        val e = CheckersEngine(Side.A, pos("a8" to W, "c8" to W, "d3" to B))
        assertTrue(e.finished)
        assertEquals(GameResult(Side.B, ResultReason.NO_MOVES), e.result)
        assertNull(e.turn)
        val snap = e.snapshot()
        assertTrue(snap.finished)
        assertEquals(GameResult(Side.B, ResultReason.NO_MOVES), snap.result)
        assertNull(snap.turn)
        // после завершения ходы не принимаются
        assertNull(e.localMove(chain("a8", "b7")))
        assertFalse(e.remoteMove(byteArrayOf(2, 0, 9)))
    }

    // 10. API выбора хода (фигура → конечные клетки → маршрут) ----------

    @Test
    fun selectionApiMatchesLocalMove() {
        val setup = pos("b5" to W, "f5" to W, "c6" to B, "g8" to B)
        val e = CheckersEngine(Side.A, setup)
        assertEquals(-1, e.selected)
        assertTrue(e.destinations.isEmpty())
        assertNull(e.route(sq("d7"))) // выбора нет
        // при обязательном взятии выбираем только бьющую фигуру
        assertFalse(e.select(sq("f5")))
        assertEquals(-1, e.selected)
        assertTrue(e.select(sq("b5")))
        assertEquals(sq("b5"), e.selected)
        assertArrayEquals(intArrayOf(sq("d7")), e.destinations)
        val midSnap = e.snapshot()
        assertEquals(sq("b5"), midSnap.chainFrom)
        assertArrayEquals(intArrayOf(sq("d7")), midSnap.destinations)
        // чужая/нелегальная клетка — маршрута нет, ход не применяется
        assertNull(e.route(sq("a6")))
        assertNull(e.route(99))
        assertNull(e.commitAt(0))
        assertEquals(sq("b5"), e.selected)
        // маршрут одиночного взятия и ход по подтверждению
        assertArrayEquals(intArrayOf(sq("b5"), sq("d7")), e.route(sq("d7")))
        val p1 = e.commitAt(sq("d7"))
        assertNotNull(p1)
        assertArrayEquals(byteArrayOf(2, 25, 11), p1)
        assertEquals(-1, e.selected)
        assertTrue(e.destinations.isEmpty())
        // та же цепочка через localMove даёт то же состояние
        val e2 = CheckersEngine(Side.A, setup)
        val p2 = e2.localMove(chain("b5", "d7"))
        assertNotNull(p2)
        assertEquals(e.digest(), e2.digest())
        assertEquals(e.log, e2.log)
        assertArrayEquals(e.snapshot().board, e2.snapshot().board)
        assertArrayEquals(p1, p2) // тот же формат кадра
        // пир принимает наш payload (площадки приземления)
        val e3 = CheckersEngine(Side.B, setup)
        assertTrue(e3.remoteMove(p1!!))
        assertEquals(e.digest(), e3.digest())
        // после хода выбор чужой фигурой невозможен
        assertFalse(e.select(sq("g8")))
    }

    @Test
    fun selectionMultiJumpMatchesLocalMove() {
        val setup = pos("h1" to W, "g2" to B, "e4" to B, "c6" to B, "g8" to B)
        val e = CheckersEngine(Side.A, setup)
        assertTrue(e.select(sq("h1")))
        // цели — только финальные клетки: промежуточные площадки не подсвечиваются
        assertArrayEquals(intArrayOf(sq("b7")), e.destinations)
        assertArrayEquals(
            intArrayOf(sq("h1"), sq("f3"), sq("d5"), sq("b7")),
            e.route(sq("b7")),
        )
        // подтвердить можно только завершение цепочки, не её середину
        assertNull(e.route(sq("f3")))
        assertNull(e.commitAt(sq("f3")))
        assertEquals(sq("h1"), e.selected)
        // ход применяется атомарно, payload — квадраты площадок
        val p1 = e.commitAt(sq("b7"))
        assertArrayEquals(byteArrayOf(4, 63, 45, 27, 9), p1)
        assertEquals(listOf("h1xf3xd5xb7"), e.log)
        // та же цепочка через localMove — тот же формат кадра (площадки)
        val e2 = CheckersEngine(Side.A, setup)
        val p2 = e2.localMove(chain("h1", "f3", "d5", "b7"))
        assertNotNull(p2)
        assertEquals(e.digest(), e2.digest())
        assertEquals(e.log, e2.log)
        assertArrayEquals(e.snapshot().board, e2.snapshot().board)
        // пир принимает наш payload
        val e3 = CheckersEngine(Side.B, setup)
        assertTrue(e3.remoteMove(p1!!))
        assertEquals(e.digest(), e3.digest())
    }

    @Test
    fun kingCaptureRouteCanEndAtOriginSquare() {
        // Редкий случай из UI: дамка бьёт по кругу и возвращается на исходную
        // клетку — конечные клетки содержат старт, маршрут замыкается.
        val setup = pos("d4" to WK, "c5" to B, "c7" to B, "e7" to B, "e5" to B, "a5" to B)
        val e = CheckersEngine(Side.A, setup)
        assertTrue(e.select(sq("d4")))
        assertTrue(sq("d4") in e.destinations.toSet())
        val r = e.route(sq("d4"))
        assertNotNull(r)
        assertEquals(sq("d4"), r!!.first())
        assertEquals(sq("d4"), r.last())
        assertEquals(5, r.size)
        // ход применяется: дамка снова на d4, все четыре побитые сняты
        val p = e.commitAt(sq("d4"))
        assertNotNull(p)
        assertArrayEquals(
            byteArrayOf(5, sq("d4").toByte(), sq("b6").toByte(), sq("d8").toByte(), sq("f6").toByte(), sq("d4").toByte()),
            p,
        )
        assertEquals(WK, e.snapshot().board[sq("d4")])
        for (name in listOf("c5", "c7", "e7", "e5")) {
            assertEquals(0, e.snapshot().board[sq(name)])
        }
        assertEquals(listOf("d4xb6xd8xf6xd4"), e.log)
        assertEquals(Side.B, e.turn)
        assertFalse(e.finished)
    }

    @Test
    fun selectionCancelAndSimpleMoveSelection() {
        val setup = pos("b5" to W, "f5" to W, "c6" to B, "g8" to B)
        val e = CheckersEngine(Side.A, setup)
        assertTrue(e.select(sq("b5")))
        assertNotNull(e.route(sq("d7")))
        e.cancelSelect()
        assertEquals(-1, e.selected)
        assertTrue(e.destinations.isEmpty())
        assertNull(e.route(sq("d7")))
        // доска не тронута, выбор сброшен — ход через localMove работает
        val p = e.localMove(chain("b5", "d7"))
        assertNotNull(p)
        // выбор простого хода (без обязательного взятия)
        val e2 = CheckersEngine(Side.A)
        assertTrue(e2.select(sq("c3")))
        assertArrayEquals(intArrayOf(sq("b4"), sq("d4")), e2.destinations)
        assertArrayEquals(intArrayOf(sq("c3"), sq("b4")), e2.route(sq("b4")))
        val p2 = e2.commitAt(sq("b4"))
        assertArrayEquals(byteArrayOf(2, 42, 33), p2)
        assertEquals(listOf("c3-b4"), e2.log)
    }

    // 11. encodeState / восстановление ----------------------------------

    @Test
    fun stateRoundTrip() {
        val e = CheckersEngine(Side.A)
        val p = e.localMove(chain("c3", "b4"))
        assertNotNull(p)
        val st = e.encodeState()
        val e2 = CheckersEngine(Side.A, st)
        assertArrayEquals(e.snapshot().board, e2.snapshot().board)
        assertEquals(e.turn, e2.turn)
        assertEquals(e.digest(), e2.digest())
        assertEquals(e.log, e2.log)
        assertEquals(-1, e2.selected)
        assertTrue(e2.destinations.isEmpty())
        assertFalse(e2.finished)
        // повторная кодировка идентична
        assertArrayEquals(st, e2.encodeState())
        // тот же ход принимается через remoteMove зеркальной стороной
        val mirror = CheckersEngine(Side.B, st)
        val pB = mirror.localMove(chain("d6", "c5"))
        assertNotNull(pB)
        assertTrue(e2.remoteMove(pB!!))
        assertEquals(mirror.digest(), e2.digest())
        assertArrayEquals(mirror.snapshot().board, e2.snapshot().board)
        // и ход той же стороны, что и в исходной партии, — на свежем состоянии
        val init = CheckersEngine(Side.A).encodeState()
        val recv = CheckersEngine(Side.B, init)
        val sender = CheckersEngine(Side.A)
        val pA = sender.localMove(chain("c3", "b4"))
        assertNotNull(pA)
        assertTrue(recv.remoteMove(pA!!))
        assertEquals(sender.digest(), recv.digest())
        assertArrayEquals(sender.snapshot().board, recv.snapshot().board)
        assertEquals(sender.log, recv.log)
    }

    @Test
    fun corruptStateRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            CheckersEngine(Side.A, byteArrayOf(CheckersEngine.STATE_VERSION.toByte(), 0, 0))
        }
        // неверная версия — в т.ч. раскладка прежней версии 1 (старые партии
        // не читаются: движок создаётся заново с начальной позиции)
        for (bad in listOf(9, 1)) {
            val badVersion = CheckersEngine(Side.A).encodeState().also { it[0] = bad.toByte() }
            assertThrows(IllegalArgumentException::class.java) {
                CheckersEngine(Side.A, badVersion)
            }
        }
        // обрезанный лог
        val e = CheckersEngine(Side.A)
        assertNotNull(e.localMove(chain("c3", "b4")))
        val truncated = e.encodeState().copyOf(e.encodeState().size - 2)
        assertThrows(IllegalArgumentException::class.java) {
            CheckersEngine(Side.A, truncated)
        }
    }

    @Test
    fun legacyExplicitMidsChainRejected() {
        // Легаси-формат «откуда, (побита, площадка)...» удалён из протокола:
        // цепочка с квадратами побитых между площадками не принимается ни
        // локально, ни от пира. Единственный формат — «откуда, площадки».
        val setup = pos("b5" to W, "c6" to B, "g8" to B)
        val legacy = byteArrayOf(3, sq("b5").toByte(), sq("c6").toByte(), sq("d7").toByte())
        val legacyChain = IntArray(legacy.size) { legacy[it].toInt() and 0xFF }
        assertNull(CheckersEngine(Side.A, setup).localMove(MoveInput.Chain(legacyChain)))
        assertFalse(CheckersEngine(Side.B, setup).remoteMove(legacy))
        // тот же ход в формате площадок принимается обеими сторонами
        assertNotNull(CheckersEngine(Side.A, setup).localMove(chain("b5", "d7")))
        assertTrue(CheckersEngine(Side.B, setup).remoteMove(byteArrayOf(2, sq("b5").toByte(), sq("d7").toByte())))
    }

    // 12. digest ---------------------------------------------------------

    @Test
    fun digestConsistentAcrossSides() {
        val a = CheckersEngine(Side.A)
        val b = CheckersEngine(Side.B)
        assertEquals(a.digest(), b.digest())
        val before = a.digest()
        val p = a.localMove(chain("c3", "b4"))
        assertNotNull(p)
        val after = a.digest()
        assertNotEquals(before, after)
        assertTrue(after in 0..0xFFFF)
        assertTrue(b.remoteMove(p!!))
        assertEquals(after, b.digest())
        // одинаковая последовательность → одинаковый дайджест
        val c = CheckersEngine(Side.A)
        val d = CheckersEngine(Side.B)
        assertNotNull(c.localMove(chain("c3", "b4")))
        assertNotNull(d.remoteMove(p!!))
        assertEquals(c.digest(), d.digest())
    }

    // 13. Несколько маршрутов в одну клетку (полевой скриншот 20.10) -------

    /**
     * Поле из полевого отчёта: белая шашка бьёт одну шашку и может закончить
     * в одной и той же клетке двумя разными цепочками — коротой (два взятия)
     * и длинной (три: на последней горизонтали шашка немедленно становится
     * дамкой и добивает ещё одну). Русские шашки не требуют максимального
     * взятия, поэтому игрок обязан иметь возможность выбрать любую из них.
     */
    @Test
    fun severalRoutesToSameSquarePlayerPicks() {
        val setup = pos(
            "d8" to B, "b8" to B, "g7" to B, "e7" to B, "c7" to B, "a7" to B,
            "h6" to B, "f6" to B, "b6" to B, "g5" to B, "e5" to B, "c5" to B,
            "a5" to W, "f4" to W, "g3" to W, "e3" to W, "c3" to W, "a3" to W,
            "h2" to W, "f2" to W, "d2" to W, "b2" to W, "g1" to W, "e1" to W,
        )
        val e = CheckersEngine(Side.A, setup)
        assertTrue(e.select(sq("f4")))
        // конечная клетка одна — но ведут в неё две разные цепочки
        assertArrayEquals(intArrayOf(sq("b4")), e.destinations)
        val routes = e.routes(sq("b4"))
        assertEquals(2, routes.size)
        assertArrayEquals(intArrayOf(sq("f4"), sq("d6"), sq("f8"), sq("b4")), routes[0])
        assertArrayEquals(intArrayOf(sq("f4"), sq("d6"), sq("b4")), routes[1])
        // индекс выбирает ветвь: обе применимы и дают разные payload'ы
        val short = CheckersEngine(Side.A, setup).also { assertTrue(it.select(sq("f4"))) }
            .commitAt(sq("b4"), 1)
        val long = CheckersEngine(Side.A, setup).also { assertTrue(it.select(sq("f4"))) }
            .commitAt(sq("b4"), 0)
        assertNotNull(short)
        assertNotNull(long)
        assertNotEquals(short!!.toList(), long!!.toList())
        // обе цепочки легальны и для пира (формат payload не меняется)
        assertTrue(CheckersEngine(Side.B, setup).remoteMove(short))
        assertTrue(CheckersEngine(Side.B, setup).remoteMove(long))
        // несуществующий индекс — не ход, состояние не тронуто
        val untouched = CheckersEngine(Side.A, setup)
        assertTrue(untouched.select(sq("f4")))
        val before = untouched.digest()
        assertNull(untouched.commitAt(sq("b4"), 2))
        assertEquals(before, untouched.digest())
    }
}
