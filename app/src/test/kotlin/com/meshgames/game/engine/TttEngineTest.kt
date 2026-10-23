package com.meshgames.game.engine

import com.meshgames.game.MoveInput
import com.meshgames.game.ResultReason
import com.meshgames.game.Side
import com.meshgames.game.TttSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TttEngineTest {
    @Test
    fun aStartsWithXAndWinWorks() {
        val a = TttEngine(Side.A)
        val b = TttEngine(Side.B)

        // A:0 B:3 A:1 B:4 A:2 — верхняя линия, A побеждает
        b.remoteMove(a.localMove(MoveInput.Cell(0))!!)
        a.remoteMove(b.localMove(MoveInput.Cell(3))!!)
        b.remoteMove(a.localMove(MoveInput.Cell(1))!!)
        a.remoteMove(b.localMove(MoveInput.Cell(4))!!)
        val win = a.localMove(MoveInput.Cell(2))!!
        assertTrue(a.finished)
        assertEquals(Side.A, a.result!!.winner)
        assertTrue(b.remoteMove(win))
        assertTrue(b.finished)
        assertEquals(Side.A, b.result!!.winner)
        assertEquals(a.digest(), b.digest())
    }

    @Test
    fun illegalMovesRejectedLocally() {
        val a = TttEngine(Side.A)
        val b = TttEngine(Side.B)
        // очередь A: B ходить не может
        assertNull(b.localMove(MoveInput.Cell(1)))
        val p = a.localMove(MoveInput.Cell(0))!!
        assertTrue(b.remoteMove(p))
        // снова не моя очередь (A)
        assertNull(a.localMove(MoveInput.Cell(1)))
        // занятая клетка
        assertFalse(a.remoteMove(p))
        // мусор
        assertFalse(a.remoteMove(byteArrayOf(9)))
        assertFalse(a.remoteMove(ByteArray(0)))
    }

    @Test
    fun drawDetected() {
        val a = TttEngine(Side.A)
        val b = TttEngine(Side.B)
        // итоговая доска X O X / X X O / O X O — ничья, никто не выигрывает по ходу
        val order = listOf(0, 1, 2, 5, 3, 6, 4, 8, 7)
        var turnA = true
        for (cell in order) {
            val payload = if (turnA) {
                a.localMove(MoveInput.Cell(cell))!!
            } else {
                b.localMove(MoveInput.Cell(cell))!!
            }
            if (turnA) b.remoteMove(payload) else a.remoteMove(payload)
            turnA = !turnA
        }
        assertTrue(a.finished)
        assertNull(a.result!!.winner)
        assertEquals(ResultReason.STALEMATE, a.result!!.reason)
        assertEquals(a.digest(), b.digest())
    }

    @Test
    fun stateRoundTripResumes() {
        val a = TttEngine(Side.A)
        val b = TttEngine(Side.B)
        b.remoteMove(a.localMove(MoveInput.Cell(4))!!)
        a.remoteMove(b.localMove(MoveInput.Cell(0))!!)

        // рестарт: восстановили из состояния
        val a2 = TttEngine(Side.A, a.encodeState())
        val b2 = TttEngine(Side.B, b.encodeState())
        assertEquals(a.digest(), a2.digest())
        assertEquals(b.digest(), b2.digest())
        // и игра продолжается (сейчас ход A)
        assertNotNull(a2.localMove(MoveInput.Cell(8)))
    }

    @Test
    fun snapshotsAgree() {
        val a = TttEngine(Side.A)
        val b = TttEngine(Side.B)
        b.remoteMove(a.localMove(MoveInput.Cell(0))!!)
        val sa = a.snapshot() as TttSnapshot
        val sb = b.snapshot() as TttSnapshot
        assertTrue(sa.cells.contentEquals(sb.cells))
        assertEquals(sb.turn, sa.turn)
    }
}
