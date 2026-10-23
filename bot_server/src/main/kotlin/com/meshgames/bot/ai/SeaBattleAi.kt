package com.meshgames.bot.ai

import com.meshgames.game.GameEngine
import com.meshgames.game.MoveInput
import com.meshgames.game.SeaBattleSnapshot
import com.meshgames.game.SeaPhase
import kotlin.random.Random

/**
 * Интеллектуальный ИИ для Морского боя.
 * Реализует режим охоты (Hunt Mode) по вероятностной карте с паритетом
 * и режим точечного добивания (Target Mode) по линии обнаруженных ранений.
 */
class SeaBattleAi(val difficulty: String = "hard") : BotAi {

    companion object : BotAi by SeaBattleAi("hard") {
        private const val CELL_UNKNOWN = 0
        private const val CELL_HIT = 2
        private const val CELL_DEAD = 3
        private const val CELL_MISS = 4
    }

    override fun computeMove(engine: GameEngine): MoveInput? {
        if (engine.finished) return null
        val snap = engine.snapshot() as? SeaBattleSnapshot ?: return null

        if (snap.phase == SeaPhase.PLACEMENT) {
            engine.confirmShips()
            return null
        }

        if (snap.phase != SeaPhase.BATTLE || snap.turn != engine.mySide || snap.pendingShot != -1) {
            return null
        }

        val oppGrid = snap.oppGrid
        val targetCell = when (difficulty.lowercase()) {
            "easy" -> {
                val hitTarget = findTargetShot(oppGrid)
                if (hitTarget != null && Random.nextFloat() >= 0.40f) {
                    hitTarget
                } else {
                    findRandomUnknown(oppGrid)
                }
            }
            "medium" -> {
                findTargetShot(oppGrid) ?: findCheckerboardShot(oppGrid) ?: findRandomUnknown(oppGrid)
            }
            else -> {
                findTargetShot(oppGrid) ?: findHuntShot(oppGrid) ?: findFirstUnknown(oppGrid)
            }
        }
        return if (targetCell != null) MoveInput.Shoot(targetCell) else null
    }

    private fun findRandomUnknown(grid: IntArray): Int? {
        val unknowns = (0..99).filter { grid[it] == CELL_UNKNOWN }
        return if (unknowns.isNotEmpty()) unknowns.random() else null
    }

    private fun findCheckerboardShot(grid: IntArray): Int? {
        val candidates = (0..99).filter { grid[it] == CELL_UNKNOWN && (it / 10 + it % 10) % 2 == 0 }
        return if (candidates.isNotEmpty()) candidates.random() else null
    }

    /**
     * Режим добивания (Target Mode): находит клетки рядом с подбитыми (CELL_HIT),
     * но еще не потопленными кораблями.
     */
    private fun findTargetShot(grid: IntArray): Int? {
        val hitCells = (0..99).filter { grid[it] == CELL_HIT }
        if (hitCells.isEmpty()) return null

        // Находим кластер ранений
        val clusters = ArrayList<MutableList<Int>>()
        val visited = HashSet<Int>()

        for (h in hitCells) {
            if (h in visited) continue
            val cluster = ArrayList<Int>()
            val queue = ArrayDeque<Int>()
            queue.add(h)
            visited.add(h)

            while (queue.isNotEmpty()) {
                val curr = queue.removeFirst()
                cluster.add(curr)
                val r = curr / 10
                val c = curr % 10
                for (d in listOf(-10, 10, -1, 1)) {
                    val next = curr + d
                    if (next in 0..99 && (next / 10 == r || next % 10 == c)) {
                        if (grid[next] == CELL_HIT && visited.add(next)) {
                            queue.add(next)
                        }
                    }
                }
            }
            clusters.add(cluster)
        }

        // Для каждого кластера ищем кандидатов для продолжения огня
        for (cluster in clusters) {
            if (cluster.size == 1) {
                // Одна раненая клетка — стреляем в 4 соседа
                val cell = cluster[0]
                val r = cell / 10
                val c = cell % 10
                val candidates = ArrayList<Int>()
                if (r > 0 && grid[cell - 10] == CELL_UNKNOWN) candidates.add(cell - 10)
                if (r < 9 && grid[cell + 10] == CELL_UNKNOWN) candidates.add(cell + 10)
                if (c > 0 && grid[cell - 1] == CELL_UNKNOWN) candidates.add(cell - 1)
                if (c < 9 && grid[cell + 1] == CELL_UNKNOWN) candidates.add(cell + 1)
                if (candidates.isNotEmpty()) {
                    return candidates.random()
                }
            } else {
                // 2 или более раненых клеток — линия известна
                cluster.sort()
                val isHorizontal = (cluster[1] - cluster[0]) == 1
                val candidates = ArrayList<Int>()
                if (isHorizontal) {
                    val left = cluster.first()
                    val right = cluster.last()
                    if (left % 10 > 0 && grid[left - 1] == CELL_UNKNOWN) candidates.add(left - 1)
                    if (right % 10 < 9 && grid[right + 1] == CELL_UNKNOWN) candidates.add(right + 1)
                } else {
                    val top = cluster.first()
                    val bottom = cluster.last()
                    if (top >= 10 && grid[top - 10] == CELL_UNKNOWN) candidates.add(top - 10)
                    if (bottom <= 89 && grid[bottom + 10] == CELL_UNKNOWN) candidates.add(bottom + 10)
                }
                if (candidates.isNotEmpty()) {
                    return candidates.random()
                }
            }
        }

        return null
    }

    /**
     * Режим поиска (Hunt Mode): строит карту вероятностей для оставшихся кораблей
     * с приоритетом по диагональной/шахматной сетке.
     */
    private fun findHuntShot(grid: IntArray): Int? {
        val heatMap = IntArray(100)

        // Предполагаемые размеры оставшихся кораблей (от 4 до 2)
        val shipSizes = listOf(4, 3, 2)

        for (len in shipSizes) {
            // Горизонтальное размещение
            for (r in 0..9) {
                for (c in 0..(10 - len)) {
                    var canFit = true
                    for (i in 0 until len) {
                        val cell = r * 10 + c + i
                        if (grid[cell] == CELL_MISS || grid[cell] == CELL_DEAD) {
                            canFit = false
                            break
                        }
                    }
                    if (canFit) {
                        for (i in 0 until len) {
                            val cell = r * 10 + c + i
                            if (grid[cell] == CELL_UNKNOWN) {
                                heatMap[cell] += 1
                            }
                        }
                    }
                }
            }

            // Вертикальное размещение
            for (c in 0..9) {
                for (r in 0..(10 - len)) {
                    var canFit = true
                    for (i in 0 until len) {
                        val cell = (r + i) * 10 + c
                        if (grid[cell] == CELL_MISS || grid[cell] == CELL_DEAD) {
                            canFit = false
                            break
                        }
                    }
                    if (canFit) {
                        for (i in 0 until len) {
                            val cell = (r + i) * 10 + c
                            if (grid[cell] == CELL_UNKNOWN) {
                                heatMap[cell] += 1
                            }
                        }
                    }
                }
            }
        }

        // Фильтр паритета (шахматка), выбираем клетку с максимальной вероятностью
        var maxWeight = -1
        val bestCandidates = ArrayList<Int>()

        for (i in 0..99) {
            if (grid[i] != CELL_UNKNOWN) continue
            val r = i / 10
            val c = i % 10
            val isChecker = (r + c) % 2 == 0
            val weight = heatMap[i] + (if (isChecker) 3 else 0)

            if (weight > maxWeight) {
                maxWeight = weight
                bestCandidates.clear()
                bestCandidates.add(i)
            } else if (weight == maxWeight) {
                bestCandidates.add(i)
            }
        }

        return if (bestCandidates.isNotEmpty()) bestCandidates.random() else null
    }

    private fun findFirstUnknown(grid: IntArray): Int? =
        (0..99).firstOrNull { grid[it] == CELL_UNKNOWN }
}
