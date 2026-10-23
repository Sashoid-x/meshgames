package com.meshgames.game.engine

import com.meshgames.game.GameEngine
import com.meshgames.game.GameResult
import com.meshgames.game.GameType
import com.meshgames.game.MoveInput
import com.meshgames.game.ResultReason
import com.meshgames.game.SeaBattleSnapshot
import com.meshgames.game.SeaPhase
import com.meshgames.game.Side
import kotlin.random.Random

/**
 * Морской бой 10×10. Корабли: 4×1, 3×2, 2×3, 1×4 (20 палуб), без соприкосновений
 * (в т.ч. по диагонали). Сторона A стреляет первой.
 *
 * Протокол:
 *  - SHOT payload: [coord] (0..99)
 *  - SHOT_RESULT payload: [coord, res], res: 0 промах, 1 попадание, 2 убит
 *
 * Правила: попадание — стреляешь снова; промах — ход соперника.
 * Коды клеток: 0 вода/неизвестно, 1 корабль, 2 подбит, 3 убит, 4 промах.
 */
class SeaBattleEngine(
    override val mySide: Side,
    state: ByteArray? = null,
) : GameEngine {
    override val type: GameType = GameType.SEA_BATTLE

    private val myGrid = IntArray(100)   // моё поле: мои корабли + выстрелы соперника
    private val oppGrid = IntArray(100)  // поле соперника: только мои выстрелы
    private var phaseFlag = SeaPhase.PLACEMENT
    private var turnSide: Side? = null
    private var pendingShotValue = -1
    private var myShipsLeft = SHIPS.sumOf { it.first }
    private var oppShipsLeft = SHIPS.sumOf { it.first }
    private var myConfirmed = false
    private var finishedFlag = false
    private var resultFlag: GameResult? = null
    private val logList = ArrayList<String>()

    init {
        if (state != null && state.size >= 210) {
            decodeState(state)
        } else {
            placeShips(myGrid, Random.Default)
        }
    }

    override val turn: Side? get() = if (finishedFlag) null else turnSide
    override val finished: Boolean get() = finishedFlag
    override val result: GameResult? get() = resultFlag
    override val log: List<String> get() = logList

    /**
     * Дайджест сопоставим, только когда мой выстрел не в полёте. Соперник
     * применяет мой выстрел ДО ответа (remoteShot), поэтому он никогда не
     * отстаёт, а «обгонять» он может лишь в окне [ответ соперника, применение
     * результата мной] — ровно то окно, где pendingShot != -1, т.е. я сам
     * не в покое. Значит, достаточно проверять покой у стороны, которая
     * сравнивает.
     */
    override val quiescent: Boolean
        get() = pendingShotValue == -1

    override fun snapshot() = SeaBattleSnapshot(
        myGrid = myGrid.copyOf(),
        oppGrid = oppGrid.copyOf(),
        phase = phaseFlag,
        turn = turn,
        pendingShot = pendingShotValue,
        myShipsLeft = myShipsLeft,
        oppShipsLeft = oppShipsLeft,
        finished = finishedFlag,
        result = resultFlag,
        log = logList.toList(),
    )

    override fun legalMoves(from: Int): Set<Int> =
        if (phaseFlag != SeaPhase.BATTLE || finishedFlag || turnSide != mySide ||
            pendingShotValue != -1
        ) emptySet()
        else oppGrid.indices.filter { oppGrid[it] == 0 }.toSet()

    /**
     * В морском бое ходы идут через SHOT/SHOT_RESULT; MOVE не используется.
     * Держим делегирование для полноты контракта.
     */
    override fun localMove(input: MoveInput): ByteArray? =
        (input as? MoveInput.Shoot)?.let { localShot(it.coord) }

    override fun remoteMove(payload: ByteArray): Boolean = remoteShot(payload) != null

    /**
     * Тестовый бот: добиваем непробитых соседей попаданий, иначе первая
     * необстрелянная клетка (детерминированный обход 0..99). Расстановка
     * ботом не делается — она уже случайная в init, [confirmShips] шлётся
     * сессионным слоем.
     */
    override fun botMove(): MoveInput? {
        if (finishedFlag || phaseFlag != SeaPhase.BATTLE) return null
        if (turnSide != mySide || pendingShotValue != -1) return null
        for (i in 0..99) {
            if (oppGrid[i] != CELL_HIT) continue
            val r = i / 10
            val c = i % 10
            val near = ArrayList<Int>(4)
            if (r > 0) near.add(i - 10)
            if (r < 9) near.add(i + 10)
            if (c > 0) near.add(i - 1)
            if (c < 9) near.add(i + 1)
            for (n in near) if (oppGrid[n] == CELL_WATER) return MoveInput.Shoot(n)
        }
        val target = oppGrid.indices.firstOrNull { oppGrid[it] == CELL_WATER } ?: return null
        return MoveInput.Shoot(target)
    }

    // --- расстановка ---

    override fun rerollShips(): Boolean {
        if (phaseFlag != SeaPhase.PLACEMENT || myConfirmed) return false
        val backup = myGrid.copyOf()
        placeShips(myGrid, Random.Default)
        logList.add("reroll")
        return myGrid.contentEquals(backup).not()
    }

    override fun confirmShips(): Boolean {
        if (phaseFlag != SeaPhase.PLACEMENT || myConfirmed) return false
        myConfirmed = true
        logList.add("ready")
        return true
    }

    override fun beginBattle(): Boolean {
        if (phaseFlag != SeaPhase.PLACEMENT || !myConfirmed) return false
        phaseFlag = SeaPhase.BATTLE
        turnSide = Side.A // инициатор стреляет первым
        logList.add("battle")
        return true
    }

    // --- бой ---

    override fun localShot(coord: Int): ByteArray? {
        if (phaseFlag != SeaPhase.BATTLE || finishedFlag || turnSide != mySide) return null
        if (pendingShotValue != -1) return null
        if (coord !in 0..99 || oppGrid[coord] != 0) return null
        pendingShotValue = coord
        return byteArrayOf(coord.toByte())
    }

    override fun remoteShot(payload: ByteArray): ByteArray? {
        if (phaseFlag != SeaPhase.BATTLE || finishedFlag || turnSide != mySide.other) return null
        if (payload.isEmpty()) return null
        val coord = payload[0].toInt() and 0xFF
        if (coord !in 0..99) return null
        if (myGrid[coord] != 0 && myGrid[coord] != 1) return null // уже обстреляна

        val res: Int
        if (myGrid[coord] == 0) {
            myGrid[coord] = CELL_MISS
            res = SHOT_MISS
            turnSide = mySide // промах — ход ко мне
            logList.add("miss:$coord")
        } else {
            myGrid[coord] = CELL_HIT
            val sunk = shipAt(coord, myGrid)
            if (sunk != null && sunk.all { myGrid[it] == CELL_HIT }) {
                for (c in sunk) myGrid[c] = CELL_DEAD
                markBorders(sunk, myGrid)
                myShipsLeft--
                res = SHOT_KILLED
                logList.add("killed:$coord")
                if (myShipsLeft == 0) {
                    finishedFlag = true
                    resultFlag = GameResult(mySide.other, ResultReason.ALL_SHIPS_SUNK)
                    turnSide = null
                }
            } else {
                res = SHOT_HIT
                logList.add("hit:$coord")
            }
            // попадание/убийство — соперник стреляет снова
        }
        return byteArrayOf(coord.toByte(), res.toByte())
    }

    override fun applyShotResult(payload: ByteArray): Boolean {
        if (payload.size < 2) return false
        val coord = payload[0].toInt() and 0xFF
        val res = payload[1].toInt() and 0xFF
        if (coord != pendingShotValue || coord !in 0..99) return false
        if (res !in 0..2) return false
        pendingShotValue = -1

        when (res) {
            SHOT_MISS -> {
                oppGrid[coord] = CELL_MISS
                turnSide = mySide.other
            }
            SHOT_HIT -> {
                oppGrid[coord] = CELL_HIT
                // продолжаем стрелять
            }
            SHOT_KILLED -> {
                // Сначала помечаем текущую клетку — иначе shipAt не найдёт
                // корабль (компонент строится по 1/2) и «умрёт» только одна
                // клетка, а соседние попадания и их границы останутся
                // непомеченными → расхождение с полем соперника.
                oppGrid[coord] = CELL_HIT
                val sunk = shipAt(coord, oppGrid) ?: listOf(coord)
                for (c in sunk) oppGrid[c] = CELL_DEAD
                markBorders(sunk, oppGrid)
                oppShipsLeft--
                if (oppShipsLeft == 0) {
                    finishedFlag = true
                    resultFlag = GameResult(mySide, ResultReason.ALL_SHIPS_SUNK)
                    turnSide = null
                }
            }
        }
        return true
    }

    /**
     * Отказ от зависшего выстрела (SHOT_RESULT потерян безвозвратно —
     * таймаут сессионного слоя). Клетка в oppGrid остаётся неизвестной
     * (0): результат не применялся, выстрел можно повторить позже; ход не
     * передаётся — turnSide не менялся с момента localShot. Дайджест не
     * зависит от pendingShot, поэтому после отказа дайджесты сторон либо
     * уже совпадают (SHOT до соперника не дошёл — партия продолжается),
     * либо честно разошлись (пир результат применил — SYNC_CONFLICT).
     */
    override fun abandonPendingShot(): Boolean {
        if (pendingShotValue == -1) return false
        pendingShotValue = -1
        return true
    }

    // --- вспомогательные ---

    /** Клетки корабля, которому принадлежит [coord] (связная компонента 1/2). */
    private fun shipAt(coord: Int, grid: IntArray): List<Int>? {
        if (grid[coord] != 1 && grid[coord] != 2 && grid[coord] != CELL_HIT) return null
        val out = ArrayList<Int>(4)
        val stack = ArrayDeque<Int>()
        stack.add(coord)
        val seen = HashSet<Int>()
        while (stack.isNotEmpty()) {
            val c = stack.removeLast()
            if (!seen.add(c)) continue
            val v = grid[c]
            if (v == 1 || v == CELL_HIT) {
                out.add(c)
                val r = c / 10
                val col = c % 10
                if (r > 0) stack.add(c - 10)
                if (r < 9) stack.add(c + 10)
                if (col > 0) stack.add(c - 1)
                if (col < 9) stack.add(c + 1)
            }
        }
        return if (out.isEmpty()) null else out
    }

    /** Пометить 8-соседей корабля как промахи (классическое правило). */
    private fun markBorders(ship: List<Int>, grid: IntArray) {
        for (c in ship) {
            val r = c / 10
            val col = c % 10
            for (dr in -1..1) for (dc in -1..1) {
                val rr = r + dr
                val cc = col + dc
                if (rr !in 0..9 || cc !in 0..9) continue
                val idx = rr * 10 + cc
                if (grid[idx] == 0) grid[idx] = CELL_MISS
            }
        }
    }

    /**
     * Дайджест: сумма хэшей «снимков выстрелов» обоих полей симметрична —
     * мои выстрелы по сопернику == его выстрелы по мне (коды совпадают),
     * поэтому обе стороны получают одно и то же значение.
     */
    override fun digest(): Int {
        var h = 0
        h += shotHash(myGrid)
        h += shotHash(oppGrid)
        h += phaseFlag.ordinal * 257
        h += (turnSide?.ordinal ?: 3) * 31
        // Только симметричные функции от (my, opp): у стороны A это
        // (сколько у неё осталось, сколько у B), у B — наоборот.
        h += (myShipsLeft + oppShipsLeft) * 7
        h += (myShipsLeft * oppShipsLeft) * 13
        return h and 0xFFFF
    }

    private fun shotHash(grid: IntArray): Int {
        var h = 0x1234
        for (v in grid) {
            // 0 для нетронутых клеток (вода/корабль), иначе код выстрела
            val shot = if (v == CELL_HIT || v == CELL_DEAD || v == CELL_MISS) v else 0
            h = (h * 31 + shot) and 0xFFFF
        }
        return h
    }

    // --- персистентность ---

    override fun encodeState(): ByteArray {
        val out = ByteArray(210)
        out[0] = phaseFlag.ordinal.toByte()
        out[1] = (turnSide?.ordinal ?: 3).toByte()
        out[2] = (pendingShotValue + 1).toByte()
        out[3] = myShipsLeft.toByte()
        out[4] = oppShipsLeft.toByte()
        out[5] = if (finishedFlag) 1 else 0
        out[6] = when (resultFlag?.winner) {
            Side.A -> 0
            Side.B -> 1
            null -> 2
        }.toByte()
        out[7] = (resultFlag?.reason?.ordinal ?: 0).toByte()
        for (i in 0..99) {
            out[8 + i] = myGrid[i].toByte()
            out[108 + i] = oppGrid[i].toByte()
        }
        out[208] = if (myConfirmed) 1 else 0
        return out
    }

    private fun decodeState(s: ByteArray) {
        phaseFlag = SeaPhase.entries.getOrElse(s[0].toInt()) { SeaPhase.PLACEMENT }
        turnSide = when (s[1].toInt()) {
            0 -> Side.A
            1 -> Side.B
            else -> null
        }
        pendingShotValue = s[2].toInt() - 1
        myShipsLeft = s[3].toInt()
        oppShipsLeft = s[4].toInt()
        finishedFlag = s[5].toInt() != 0
        resultFlag = if (finishedFlag) {
            val winner = when (s[6].toInt()) {
                0 -> Side.A
                1 -> Side.B
                else -> null
            }
            GameResult(
                winner,
                ResultReason.entries.getOrElse(s[7].toInt()) { ResultReason.ALL_SHIPS_SUNK },
            )
        } else null
        for (i in 0..99) {
            myGrid[i] = s[8 + i].toInt()
            oppGrid[i] = s[108 + i].toInt()
        }
        myConfirmed = s[208].toInt() != 0
    }

    /** Отладка/тест: расставить корабли заданной раскладкой. */
    internal fun forceMyShips(cells: List<Int>) {
        myGrid.fill(0)
        for (c in cells) myGrid[c] = 1
    }

    companion object {
        const val CELL_WATER = 0
        const val CELL_SHIP = 1
        const val CELL_HIT = 2
        const val CELL_DEAD = 3
        const val CELL_MISS = 4

        const val SHOT_MISS = 0
        const val SHOT_HIT = 1
        const val SHOT_KILLED = 2

        /** (сколько кораблей, размер). */
        private val SHIPS = listOf(4 to 1, 3 to 2, 2 to 3, 1 to 4)

        /**
         * Расставить корабли в [grid] случайно (без соприкосновений).
         *
         * Перебор «все валидные позиции» может загнать в угол: случайно выбранные
         * корабли иногда оставляют место, куда следующий уже не влезает. Тогда
         * начинаем расстановку заново; если и это не помогло — кладём заведомо
         * корректную запасную раскладку. Полный флот (20 палуб) — всегда.
         */
        fun placeShips(grid: IntArray, rnd: Random) {
            repeat(ATTEMPTS) {
                grid.fill(0)
                if (placeShipsOnce(grid, rnd)) return
            }
            grid.fill(0)
            for (coord in FALLBACK_LAYOUT) grid[coord] = CELL_SHIP
        }

        /** Одна попытка: false — «загнали в угол», сетка может быть мусорной. */
        private fun placeShipsOnce(grid: IntArray, rnd: Random): Boolean {
            for ((count, size) in SHIPS) {
                repeat(count) {
                    // Собираем все валидные позиции и выбираем случайную:
                    // чисто случайные пробы на переполненном поле не находят
                    // свободное место для 4-палубного за разумное число попыток.
                    val options = ArrayList<List<Int>>()
                    for (horizontal in listOf(true, false)) {
                        val maxR = if (horizontal) 9 else 10 - size
                        val maxC = if (horizontal) 10 - size else 9
                        if (maxR < 0 || maxC < 0) continue
                        for (r in 0..maxR) for (c in 0..maxC) {
                            val cells = ArrayList<Int>(size)
                            for (i in 0 until size) {
                                cells.add(
                                    if (horizontal) r * 10 + c + i else (r + i) * 10 + c,
                                )
                            }
                            if (cells.all { freeAround(it, grid) }) options.add(cells)
                        }
                    }
                    if (options.isEmpty()) return false
                    for (cell in options[rnd.nextInt(options.size)]) grid[cell] = CELL_SHIP
                }
            }
            return true
        }

        private const val ATTEMPTS = 50

        /** Запасная раскладка: каждому кораблю — своя «полоса», контактов нет. */
        private val FALLBACK_LAYOUT = intArrayOf(
            0, 1, 2, 3, // 4-палубный
            20, 21, 22, 26, 27, 28, // два 3-палубных
            40, 41, 44, 45, 48, 49, // три 2-палубных
            60, 64, 68, 99, // четыре однопалубных
        )

        /** Клетка и все её 8 соседей свободны. */
        private fun freeAround(coord: Int, grid: IntArray): Boolean {
            if (grid[coord] != 0) return false
            val r = coord / 10
            val col = coord % 10
            for (dr in -1..1) for (dc in -1..1) {
                val rr = r + dr
                val cc = col + dc
                if (rr !in 0..9 || cc !in 0..9) continue
                if (grid[rr * 10 + cc] != 0) return false
            }
            return true
        }
    }
}
