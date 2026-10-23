package com.meshgames.game.engine

import com.meshgames.game.SeaBattleSnapshot
import com.meshgames.game.Side
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeaBattleEngineTest {
    /** Проверка валидной расстановки: 20 палуб, размеры верны, без соприкосновений. */
    private fun assertValidPlacement(grid: IntArray) {
        assertEquals(20, grid.count { it == SeaBattleEngine.CELL_SHIP })
        val visited = HashSet<Int>()
        val sizes = ArrayList<Int>()
        for (i in grid.indices) {
            if (grid[i] != SeaBattleEngine.CELL_SHIP || i in visited) continue
            val cells = ArrayList<Int>()
            val stack = ArrayDeque<Int>()
            stack.add(i)
            while (stack.isNotEmpty()) {
                val c = stack.removeLast()
                if (!visited.add(c)) continue
                if (grid[c] != SeaBattleEngine.CELL_SHIP) continue
                cells.add(c)
                if (c / 10 > 0) stack.add(c - 10)
                if (c / 10 < 9) stack.add(c + 10)
                if (c % 10 > 0) stack.add(c - 1)
                if (c % 10 < 9) stack.add(c + 1)
            }
            sizes.add(cells.size)
            val rows = cells.map { it / 10 }.toSet()
            val cols = cells.map { it % 10 }.toSet()
            assertTrue(rows.size == 1 || cols.size == 1)
        }
        sizes.sort()
        assertEquals(listOf(1, 1, 1, 1, 2, 2, 2, 3, 3, 4), sizes)
        // без соприкосновений (в т.ч. по диагонали)
        for (i in grid.indices) {
            if (grid[i] != SeaBattleEngine.CELL_SHIP) continue
            val r = i / 10
            val c = i % 10
            for (dr in -1..1) for (dc in -1..1) {
                if (dr == 0 && dc == 0) continue
                val rr = r + dr
                val cc = c + dc
                if (rr !in 0..9 || cc !in 0..9) continue
                val v = grid[rr * 10 + cc]
                assertTrue(v != SeaBattleEngine.CELL_SHIP || (dr == 0) != (dc == 0))
            }
        }
    }

    @Test
    fun autoPlacementIsValid() {
        repeat(20) {
            val e = SeaBattleEngine(Side.A)
            assertValidPlacement((e.snapshot() as SeaBattleSnapshot).myGrid)
        }
    }

    @Test
    fun placeShipsAlwaysProducesFullFleet() {
        // Сотни детерминированных раскладок: раньше часть из них «загоняла в угол»
        // (место для следующего корабля уже нет) и оставляла частичный флот —
        // меньше20 палуб. Теперь либо перезапуск, либо запасная раскладка.
        for (seed in 0L until 500L) {
            val grid = IntArray(100)
            SeaBattleEngine.placeShips(grid, Random(seed))
            assertValidPlacement(grid)
        }
    }

    @Test
    fun rerollChangesShipsAndReadyLocks() {
        val e = SeaBattleEngine(Side.A)
        val before = (e.snapshot() as SeaBattleSnapshot).myGrid.copyOf()
        assertTrue(e.rerollShips())
        val after = (e.snapshot() as SeaBattleSnapshot).myGrid
        assertFalse(before.contentEquals(after))
        assertValidPlacement(after)
        assertTrue(e.confirmShips())
        assertFalse(e.rerollShips()) // после подтверждения — нельзя
        assertFalse(e.confirmShips())
    }

    /**
     * Фиксированная раскладка для детерминированных тестов:
     * A: корабль 0,1,2,3 (горизонтально, строка 0); B: корабль 0,10,20,30 (вертикально).
     */
    private fun battlePair(): Pair<SeaBattleEngine, SeaBattleEngine> {
        val a = SeaBattleEngine(Side.A)
        val b = SeaBattleEngine(Side.B)
        a.forceMyShips(listOf(0, 1, 2, 3))
        b.forceMyShips(listOf(0, 10, 20, 30))
        assertTrue(a.confirmShips())
        assertTrue(b.confirmShips())
        assertTrue(a.beginBattle())
        assertTrue(b.beginBattle())
        return a to b
    }

    @Test
    fun battleFlowHitMissKill() {
        val (a, b) = battlePair()
        assertEquals(Side.A, a.turn)

        // A стреляет клетку 1 — мимо (корабль B в столбце 0)
        val s1 = a.localShot(1)!!
        val r1 = b.remoteShot(s1)!!
        assertEquals(SeaBattleEngine.SHOT_MISS, r1[1].toInt() and 0xFF)
        assertTrue(a.applyShotResult(r1))
        assertEquals(Side.B, a.turn) // промах — ход соперника

        // B стреляет клетку 1 — попадание (корабль A в строке 0)
        val s2 = b.localShot(1)!!
        val r2 = a.remoteShot(s2)!!
        assertEquals(SeaBattleEngine.SHOT_HIT, r2[1].toInt() and 0xFF)
        assertTrue(b.applyShotResult(r2))
        assertEquals(Side.B, b.turn) // попадание — стреляет снова

        // B добивает оставшиеся клетки корабля 0..3: сначала ещё два
        // попадания, убийство — только на последней неподбитой клетке.
        val remaining = mapOf(0 to SeaBattleEngine.SHOT_HIT, 2 to SeaBattleEngine.SHOT_HIT, 3 to SeaBattleEngine.SHOT_KILLED)
        for ((coord, expected) in remaining) {
            val s = b.localShot(coord)!!
            val r = a.remoteShot(s)!!
            assertEquals(expected, r[1].toInt() and 0xFF)
            assertTrue(b.applyShotResult(r))
        }
        val sa = a.snapshot() as SeaBattleSnapshot
        val sb = b.snapshot() as SeaBattleSnapshot
        assertEquals(9, sa.myShipsLeft)  // 10 кораблей, убит один
        assertEquals(9, sb.oppShipsLeft)
        assertFalse(a.finished)
        // убитые клетки помечены, границы — промахи
        assertEquals(SeaBattleEngine.CELL_DEAD, sa.myGrid[0])
        assertEquals(SeaBattleEngine.CELL_MISS, sa.myGrid[10])
        assertEquals(SeaBattleEngine.CELL_DEAD, sb.oppGrid[3])
        assertEquals(SeaBattleEngine.CELL_MISS, sb.oppGrid[14])
    }

    @Test
    fun shotValidation() {
        val (a, b) = battlePair()
        // ход A: выстрел валиден
        val shot = a.localShot(50)
        assertNotNull(shot)
        // второй выстрел до результата — нельзя
        assertNull(a.localShot(51))
        // результат: промах, ход переходит к B
        assertTrue(a.applyShotResult(b.remoteShot(shot!!)!!))
        assertNull(a.localShot(52)) // теперь ход B
        // уже обстрелянную клетку бить нельзя
        val s2 = b.localShot(0)!!
        val r2 = a.remoteShot(s2)!!
        assertTrue(b.applyShotResult(r2))
        assertNull(b.localShot(0))
    }

    @Test
    fun abandonPendingShotRestoresQuiescenceWithoutDigestChange() {
        val (a, b) = battlePair()
        val shot = a.localShot(1)!!
        assertFalse(a.quiescent)
        assertEquals(1, (a.snapshot() as SeaBattleSnapshot).pendingShot)
        val before = a.digest()

        // результат выстрела потерян безвозвратно — отказ по таймауту:
        // покой восстановлен, дайджест не изменился (pendingShot не входит)
        assertTrue(a.abandonPendingShot())
        assertTrue(a.quiescent)
        assertEquals(-1, (a.snapshot() as SeaBattleSnapshot).pendingShot)
        assertEquals(before, a.digest())
        assertFalse(a.abandonPendingShot()) // повторный отказ — no-op

        // поздний результат после отказа не принимается, поле не помечалось
        assertFalse(a.applyShotResult(b.remoteShot(shot)!!))
        // ход A не потеряян: клетка 1 всё ещё свободна для выстрела
        assertNotNull(a.localShot(1))
    }

    @Test
    fun stateRoundTripResumes() {
        val (a, b) = battlePair()
        val s = a.localShot(1)!!
        val r = b.remoteShot(s)!!
        a.applyShotResult(r)

        val a2 = SeaBattleEngine(Side.A, a.encodeState())
        assertEquals(a.digest(), a2.digest())
        assertEquals(a.turn, a2.turn)
        val sa = a.snapshot() as SeaBattleSnapshot
        val sa2 = a2.snapshot() as SeaBattleSnapshot
        assertTrue(sa.myGrid.contentEquals(sa2.myGrid))
        assertTrue(sa.oppGrid.contentEquals(sa2.oppGrid))
        // ход B: восстановленный движок принимает выстрел
        val s2 = b.localShot(50)!!
        assertNotNull(a2.remoteShot(s2))
    }

    @Test
    fun digestIsSymmetricAtRest() {
        val (a, b) = battlePair()
        assertEquals(a.digest(), b.digest())

        // A стреляет 1 — мимо; B стреляет 0 — попадание. Оба в покое (нет
        // ожидающих результатов) — дайджесты обязаны совпасть.
        val s1 = a.localShot(1)!!
        a.applyShotResult(b.remoteShot(s1)!!)
        val s2 = b.localShot(0)!!
        b.applyShotResult(a.remoteShot(s2)!!)
        assertEquals(a.digest(), b.digest())
    }

    @Test
    fun randomBattleTerminatesAndDigestsConverge() {
        repeat(10) { seed ->
            val rnd = Random(seed.toLong())
            val a = SeaBattleEngine(Side.A)
            val b = SeaBattleEngine(Side.B)
            val gridA = IntArray(100)
            SeaBattleEngine.placeShips(gridA, rnd)
            val gridB = IntArray(100)
            SeaBattleEngine.placeShips(gridB, rnd)
            a.forceMyShips(gridA.indices.filter { gridA[it] == SeaBattleEngine.CELL_SHIP })
            b.forceMyShips(gridB.indices.filter { gridB[it] == SeaBattleEngine.CELL_SHIP })
            a.confirmShips()
            b.confirmShips()
            a.beginBattle()
            b.beginBattle()

            var guard = 0
            while (!a.finished && guard++ < 3000) {
                val shooter = if (a.turn == Side.A) a else b
                val target = if (a.turn == Side.A) b else a
                val snap = shooter.snapshot() as SeaBattleSnapshot
                val cells = snap.oppGrid.indices.filter { snap.oppGrid[it] == 0 }
                if (cells.isEmpty()) break
                val coord = cells.random(rnd)
                val shot = shooter.localShot(coord) ?: break
                val res = target.remoteShot(shot) ?: break
                val applied = shooter.applyShotResult(res)
                if (!applied) break
            }
            assertTrue("seed=$seed", a.finished)
            assertTrue(a.result!!.winner != null)
            assertEquals(a.digest(), b.digest())
        }
    }
}
