package com.meshgames.game.engine

import com.meshgames.game.BaldaDispute
import com.meshgames.game.BaldaSnapshot
import com.meshgames.game.BaldaWordEntry
import com.meshgames.game.GameEngine
import com.meshgames.game.GameResult
import com.meshgames.game.GameType
import com.meshgames.game.MoveInput
import com.meshgames.game.ResultReason
import com.meshgames.game.Side

/**
 * Балда 5×5: в центре поля — стартовое слово (средняя строка), каждый ход
 * игрок кладёт букву в пустую клетку и составляет змейку (последовательные
 * соседние клетки без повторов, путь обязан содержать новую клетку).
 *
 * Правила проекта (см. docs/PROTOCOL.md §6):
 *  - жёстко валидируется только структура (змейка, неповтор, уже использовано —
 *    включая стартовое слово, его повторить нельзя);
 *  - словарь мягко: парные буквы е/ё, и/й, ь/ъ при проверке взаимозаменяемы
 *    (перебор вариантов ≤2^10 — чистая детерминированная функция, обе
 *    стороны решают одинаково); на поле и в выборе — канон: е, и, ь;
 *  - слово не найдено в словаре — спор: слово ложится на поле условно,
 *    соперник принимает решение вердиктом (кадр MOVE вне очереди хода);
 *  - принятые слова дают очки (длина слова), сбрасывают счётчики пропусков;
 *  - три пропуска подряд одной стороной — её поражение (THREE_PASSES);
 *  - поле заполнено — сравнение очков (BOARD_FILLED), равенство = ничья.
 *
 * Протокол MOVE payload:
 *  - слово:          [0x01][cell][letter][pathLen][path…]
 *  - спорное слово:  [0x81][cell][letter][pathLen][path…] (тот же формат)
 *  - пропуск:        [0x02]
 *  - вердикт:        [0x03][verdict]  verdict: 1 принять, 0 отклонить
 * letter — код буквы 1..33 (А=1 … Я=33, «ё» нормализуется в «е» = 6).
 *
 * State: [0] turn (0=A,1=B,3=нет), [1] finished, [2] winner (0,1,2=нет),
 * [3] reason, [4] scoreA, [5] scoreB, [6] passA, [7] passB,
 * [8] спор-флаг {cell, letter, mover, pathLen, path…, wordLen, word…},
 * стартовое слово (len, utf8), [usedWords] u16 BE + тройки (side, len, utf8),
 * [lastPath] len + клетки, 25 клеток. Стартовое слово в usedWords не входит
 * (оно не «введённое»), но участвует в проверке повторов. Пропуск хода —
 * запись usedWords с len=0 (входит в дайджест и состояние, симметрично
 * у обеих сторон).
 */
class BaldaEngine(
    override val mySide: Side,
    sessionId: Int = 0,
    private val checker: WordChecker = EmptyWordChecker,
    state: ByteArray? = null,
) : GameEngine {
    override val type: GameType = GameType.BALDA

    /** Спорное слово, лежащее на поле условно. */
    private data class Dispute(
        val cell: Int,
        val letter: Int,
        val path: IntArray,
        val word: String,
        val mover: Side,
    )

    /** Введённое слово: сторона и слово (список «кто что сыграл» в UI). */
    /** Принято слово или пропуск: для пропуска word пустой, passed=true. */
    private data class PlayedWord(val side: Side, val word: String, val passed: Boolean = false)

    /** 0 — пусто, 1..33 — код буквы (см. ALPHABET). */
    private val cells = IntArray(BOARD)
    private var scoreA = 0
    private var scoreB = 0
    /** Сколько раз ПОДРЯД каждая сторона пропустила ход (3 — поражение). */
    private var passA = 0
    private var passB = 0
    /** Стартовое слово (неповторимо, в списке введённых не показывается). */
    private var startWordValue = ""
    private val usedWords = ArrayList<PlayedWord>()
    private var turnSide: Side? = Side.A
    private var finishedFlag = false
    private var resultFlag: GameResult? = null
    private var dispute: Dispute? = null
    private var lastPathValue = IntArray(0)
    private val logList = ArrayList<String>()

    init {
        if (state != null && state.size >= MIN_STATE) {
            decodeState(state)
        } else {
            placeStartWord(sessionId)
        }
    }

    override val turn: Side? get() = turnSide
    override val finished: Boolean get() = finishedFlag
    override val result: GameResult? get() = resultFlag
    override val log: List<String> get() = logList

    override fun snapshot() = BaldaSnapshot(
        cells = cells.copyOf(),
        myScore = scoreOf(mySide),
        peerScore = scoreOf(mySide.other),
        lastPath = lastPathValue.copyOf(),
        dispute = dispute?.let {
            BaldaDispute(it.cell, it.word, it.path.copyOf(), awaitingMyVerdict = it.mover != mySide)
        },
        startWord = startWordValue,
        words = usedWords.map { BaldaWordEntry(it.side, it.word, it.passed) },
        turn = turnSide,
        finished = finishedFlag,
        result = resultFlag,
        log = logList.toList(),
    )

    override fun localMove(input: MoveInput): ByteArray? = when (input) {
        is MoveInput.LetterAdd -> {
            val v = validateWord(input.cell, input.letter, input.path, mySide)
            if (v == null) null else applyWord(input.cell, input.letter, input.path, v.first, v.second, mySide)
        }
        is MoveInput.Skip -> applySkip(mySide)
        else -> null
    }

    override fun remoteMove(payload: ByteArray): Boolean {
        if (payload.isEmpty()) return false
        val side = mySide.other
        val kindByte = payload[0].toInt() and 0xFF
        val isCompact = (kindByte and FLAG_COMPACT) != 0
        val baseKind = kindByte and FLAG_COMPACT.inv()
        return when (baseKind) {
            KIND_WORD, KIND_WORD_DISPUTED -> {
                if (payload.size < 4) return false
                val cell = payload[1].toInt() and 0xFF
                val letter = payload[2].toInt() and 0xFF
                val pathLen = payload[3].toInt() and 0xFF
                if (pathLen < 2) return false
                val path: IntArray
                if (isCompact) {
                    val packedCount = (pathLen - 1 + 3) / 4
                    if (payload.size != 5 + packedCount) return false
                    val startCell = payload[4].toInt() and 0xFF
                    path = IntArray(pathLen)
                    path[0] = startCell
                    var cur = startCell
                    for (step in 0 until pathLen - 1) {
                        val byteVal = payload[5 + step / 4].toInt() and 0xFF
                        val dir = (byteVal shr (2 * (step % 4))) and 0x03
                        cur = stepIn(cur, dir)
                        if (cur !in 0 until BOARD) return false
                        path[step + 1] = cur
                    }
                } else {
                    if (payload.size != 4 + pathLen) return false
                    path = IntArray(pathLen) { payload[4 + it].toInt() and 0xFF }
                }
                val v = validateWord(cell, letter, path, side) ?: return false
                applyWord(cell, letter, path, v.first, v.second, side)
                true
            }
            KIND_SKIP -> applySkip(side) != null
            KIND_VERDICT -> {
                if (payload.size < 2) return false
                val d = dispute ?: return false
                // Вердикт принимает тот, кто не двигал букву: инициатор хода
                // получает ответ от соперника.
                if (d.mover != mySide) return false
                val v = payload[1].toInt()
                if (v != 0 && v != 1) return false
                applyVerdict(v == 1)
                true
            }
            else -> false
        }
    }

    override val pendingVerdict: Boolean
        get() = dispute != null && dispute!!.mover != mySide

    override fun localVerdict(accept: Boolean): ByteArray? {
        val d = dispute ?: return null
        if (d.mover == mySide) return null // инициатор хода вердикт не принимает
        return applyVerdict(accept)
    }

    /**
     * Тестовый бот: обходит змейки через каждую свободную клетку, соседнюю с
     * буквой поля (расширение в оба конца пути, только заполненные клетки,
     * без повторов), и берёт первое по порядку слово из словаря. Если ни
     * одного не нашлось — пропуск: споры сопернику не навязываем.
     *
     * Порядок детерминирован (клетки 0..24, буквы 1..33) — ходы предсказуемы.
     * [localMove] прогоняет найденный ход через та же валидацию, что и ручной.
     */
    override fun botMove(): MoveInput? {
        if (finishedFlag || dispute != null || turnSide != mySide) return null
        botChecks = 0
        for (cell in 0 until BOARD) {
            if (cells[cell] != 0) continue
            // Рядом нет букв — слово через неё не построить («глухая» клетка).
            if ((0 until BOARD).none { cells[it] != 0 && adjacent(cell, it) }) continue
            findBotWord(cell)?.let { return it }
        }
        return MoveInput.Skip()
    }

    /** Бюджет проверок слов за один ход бота (перебор змейок ограничен). */
    private var botChecks = 0

    /** Первое словарное слово через клетку [cell], иначе null. */
    private fun findBotWord(cell: Int): MoveInput.LetterAdd? {
        val path = ArrayList<Int>(BOARD)
        val used = BooleanArray(BOARD)
        path.add(cell)
        used[cell] = true

        fun extend(): MoveInput.LetterAdd? {
            if (botChecks > BOT_MAX_CHECKS) return null
            if (path.size >= 2) {
                val arr = path.toIntArray()
                for (letter in 1..ALPHABET.length) {
                    if (letter == CODE_YO) continue // «ё» не играем: канон «е» (6)
                    if (++botChecks > BOT_MAX_CHECKS) return null
                    val word = wordAt(arr, cell, letter)
                    // Повтор (включая стартовое слово и взаимозаменяемые буквы) — жёсткое нарушение.
                    if (isSameWord(word, startWordValue) || usedWords.any { !it.passed && isSameWord(it.word, word) }) continue
                    if (inDictionary(word)) return MoveInput.LetterAdd(cell, letter, arr)
                }
            }
            if (path.size >= BOT_MAX_WORD) return null
            // Расширяем путь в оба конца: prepend и append по заполненным соседям.
            for (end in 0..1) {
                val anchor = if (end == 0) path.first() else path.last()
                for (n in neighboursOf(anchor)) {
                    if (used[n] || cells[n] == 0) continue
                    if (end == 0) path.add(0, n) else path.add(n)
                    used[n] = true
                    val found = extend()
                    if (found != null) return found
                    if (end == 0) path.removeAt(0) else path.removeAt(path.size - 1)
                    used[n] = false
                    if (botChecks > BOT_MAX_CHECKS) return null
                }
            }
            return null
        }

        return extend()
    }

    /** Слово по пути: в клетке [cell] ложится код [letter], остальное — с поля. */
    private fun wordAt(path: IntArray, cell: Int, letter: Int): String {
        val sb = StringBuilder(path.size)
        for (idx in path) {
            sb.append(if (idx == cell) codeLetter(letter) else codeLetter(cells[idx]))
        }
        return normalize(sb.toString())
    }

    /** Ортогональные соседи клетки [idx] (без диагоналей). */
    private fun neighboursOf(idx: Int): List<Int> {
        val r = idx / SIDE
        val c = idx % SIDE
        val out = ArrayList<Int>(4)
        if (r > 0) out.add(idx - SIDE)
        if (r < SIDE - 1) out.add(idx + SIDE)
        if (c > 0) out.add(idx - 1)
        if (c < SIDE - 1) out.add(idx + 1)
        return out
    }

    override fun digest(): Int {
        var h = 0x47B1
        for (c in cells) h = h * 31 + c
        h = h * 31 + (scoreA + scoreB)
        h = h * 31 + (scoreA * scoreB)
        h = h * 31 + (passA + passB)
        h = h * 31 + (passA * passB)
        h = h * 31 + (turnSide?.ordinal ?: 3)
        h = h * 31 + (if (finishedFlag) 1 else 0)
        h = h * 31 + startWordValue.hashCode()
        for (w in usedWords) {
            h = h * 31 + w.side.ordinal
            h = h * 31 + w.word.hashCode()
        }
        dispute?.let { d ->
            h = h * 31 + d.cell
            h = h * 31 + d.letter
            h = h * 31 + d.mover.ordinal
            for (p in d.path) h = h * 31 + p
            h = h * 31 + d.word.hashCode()
        }
        return h and 0xFFFF
    }

    override fun encodeState(): ByteArray {
        var out = byteArrayOf(
            (turnSide?.ordinal ?: 3).toByte(),
            if (finishedFlag) 1 else 0,
            (resultFlag?.winner?.ordinal ?: 2).toByte(),
            (resultFlag?.reason?.ordinal ?: 0).toByte(),
            scoreA.toByte(), scoreB.toByte(), passA.toByte(), passB.toByte(),
            if (dispute != null) 1 else 0,
        )
        dispute?.let { d ->
            out += d.cell.toByte()
            out += d.letter.toByte()
            out += d.mover.ordinal.toByte()
            out += d.path.size.toByte()
            for (p in d.path) out += p.toByte()
            val w = d.word.toByteArray(Charsets.UTF_8)
            out += w.size.toByte()
            out += w
        }
        // стартовое слово (для проверки повторов после рестарта)
        val sw = startWordValue.toByteArray(Charsets.UTF_8)
        out += sw.size.toByte()
        out += sw
        out += ((usedWords.size shr 8) and 0xFF).toByte()
        out += (usedWords.size and 0xFF).toByte()
        for (w in usedWords) {
            out += w.side.ordinal.toByte()
            val b = w.word.toByteArray(Charsets.UTF_8)
            out += b.size.toByte()
            out += b
        }
        out += lastPathValue.size.toByte()
        for (p in lastPathValue) out += p.toByte()
        for (c in cells) out += c.toByte()
        return out
    }

    // --------------------------------------------------- внутренности ---

    /** Стартовое слово — из словаря по номеру партии (обе стороны одинаково). */
    private fun placeStartWord(sessionId: Int) {
        val pool = checker.startWords()
            .map { normalize(it) }
            .filter { w -> w.length == START_WORD && w.all { letterCode(it) > 0 } }
            .ifEmpty { FALLBACK_START }
        val word = pool[((sessionId % pool.size) + pool.size) % pool.size]
        startWordValue = word
        for (i in 0 until START_WORD) cells[CENTER_ROW * SIDE + i] = letterCode(word[i])
        logList.add("стартовое слово: «$word»")
    }

    /**
     * Проверка змейки и структуры. null — нелегален; иначе
     * (нормализованное слово, найдено ли в словаре).
     */
    private fun validateWord(cell: Int, letter: Int, path: IntArray, side: Side): Pair<String, Boolean>? {
        if (finishedFlag || dispute != null || turnSide != side) return null
        if (cell !in cells.indices || cells[cell] != 0) return null
        // «ё» не передаётся: канон — «е» (6), код 7 отвергается на обеих сторонах.
        if (letter !in 1..ALPHABET.length || letter == CODE_YO) return null
        if (path.size < 2 || path.size > BOARD) return null
        val seen = BooleanArray(BOARD)
        for (i in path.indices) {
            val idx = path[i]
            if (idx !in cells.indices || seen[idx]) return null
            if (idx != cell && cells[idx] == 0) return null // пусто внутри слова
            seen[idx] = true
            if (i > 0 && !adjacent(path[i - 1], idx)) return null
        }
        if (!seen[cell]) return null // новая клетка вне пути
        val sb = StringBuilder(path.size)
        for (idx in path) sb.append(if (idx == cell) codeLetter(letter) else codeLetter(cells[idx]))
        val word = normalize(sb.toString())
        // Повтор (в том числе стартового слова и с учётом взаимозаменяемых пар е/ё, и/й, ь/ъ) — жёсткое нарушение.
        if (isSameWord(word, startWordValue) || usedWords.any { !it.passed && isSameWord(it.word, word) }) return null
        return word to inDictionary(word)
    }

    /**
     * Слово найдено в словаре с учётом парных букв: е/ё уже слиты
     * [normalize], а и↔й и ь↔ъ взаимозаменяемы — перебираем варианты
     * замен (позиции с парными буквами, ≤ [MAX_PAIR_VARIANTS]). Чистая
     * детерминированная проверка: обе стороны принимают слово одинаково.
     */
    private fun inDictionary(word: String): Boolean {
        if (checker.contains(word)) return true
        val pos = word.indices.filter { word[it] in PAIRED_LETTERS }
        if (pos.isEmpty() || pos.size > MAX_PAIR_VARIANTS) return false
        for (mask in 1 until (1 shl pos.size)) {
            val sb = StringBuilder(word)
            for (i in pos.indices) {
                if (mask shr i and 1 == 1) sb[pos[i]] = ALTERNATE.getValue(sb[pos[i]])
            }
            if (checker.contains(sb.toString())) return true
        }
        return false
    }

    /**
     * Слово найдено в словаре — предпросмотр для UI: перед отправкой
     * спорного слова игрок получает предупреждение с кнопками
     * «Отмена/Отправить». Та же мягкая проверка, что и при ходе,
     * включая перебор парных букв.
     */
    fun wordInDictionary(word: String): Boolean = inDictionary(normalize(word))

    /** Положить букву: слово принято — сразу финализация, иначе — спор. */
    private fun applyWord(
        cell: Int,
        letter: Int,
        path: IntArray,
        word: String,
        inDict: Boolean,
        side: Side,
    ): ByteArray {
        cells[cell] = letter
        if (inDict) {
            finalizeAccepted(word, path, side)
        } else {
            dispute = Dispute(cell, letter, path.copyOf(), word, side)
            logList.add("спор: «$word» — не найдено в словаре, ждём вердикта")
        }
        return wordPayload(if (inDict) KIND_WORD else KIND_WORD_DISPUTED, cell, letter, path)
    }

    /** Принятое слово: очки, в использованные, сброс пропусков, конец/ход. */
    private fun finalizeAccepted(word: String, path: IntArray, side: Side) {
        dispute = null
        addScore(side, word.length)
        usedWords.add(PlayedWord(side, word))
        passA = 0
        passB = 0
        lastPathValue = path.copyOf()
        logList.add("«$word» +${word.length} → $scoreA:$scoreB")
        if (cells.all { it != 0 }) finishBoardFull() else turnSide = side.other
    }

    private fun applySkip(side: Side): ByteArray? {
        if (finishedFlag || dispute != null || turnSide != side) return null
        val streak = if (side == Side.A) ++passA else ++passB
        logList.add("пропуск $side ($streak/3)")
        // Пропуск — полноценная запись в истории: входит в список слов,
        // дайджест и состояние (пустое слово, len=0) — обе стороны видят
        // его в таблице счёта одинаково.
        usedWords.add(PlayedWord(side, "", passed = true))
        if (streak >= THREE_PASSES) {
            finish(side.other, ResultReason.THREE_PASSES)
        } else {
            turnSide = side.other
        }
        return byteArrayOf(KIND_SKIP.toByte())
    }

    /**
     * Вердикт спора: принять — слово остаётся и финализируется (очки, ход
     * сопернику), отклонить — буква снимается, очередь хода у инициатора.
     * Вызывается с обеих сторон: локально (я решаю) и по сети (ответ пришёл).
     */
    private fun applyVerdict(accept: Boolean): ByteArray {
        val d = dispute!!
        if (accept) {
            logList.add("спор: «${d.word}» принят")
            finalizeAccepted(d.word, d.path, d.mover)
        } else {
            cells[d.cell] = 0
            dispute = null
            logList.add("спор: «${d.word}» отклонён — буква снята")
            // очередь хода остаётся у инициатора: ход так и не состоялся
        }
        return byteArrayOf(KIND_VERDICT.toByte(), if (accept) 1 else 0)
    }

    private fun finishBoardFull() {
        val winner = when {
            scoreA > scoreB -> Side.A
            scoreB > scoreA -> Side.B
            else -> null
        }
        logList.add(if (winner == null) "поле заполнено — ничья ($scoreA:$scoreB)"
        else "поле заполнено — победа $winner ($scoreA:$scoreB)")
        finish(winner, ResultReason.BOARD_FILLED)
    }

    private fun finish(winner: Side?, reason: ResultReason) {
        finishedFlag = true
        resultFlag = GameResult(winner, reason)
        turnSide = null
        logList.add("партия окончена: $reason")
    }

    private fun decodeState(b: ByteArray) {
        turnSide = when (b[0].toInt()) { 0 -> Side.A; 1 -> Side.B; else -> null }
        finishedFlag = b[1].toInt() == 1
        if (finishedFlag) {
            val w = b[2].toInt()
            resultFlag = GameResult(
                if (w == 0) Side.A else if (w == 1) Side.B else null,
                ResultReason.entries.getOrElse(b[3].toInt()) { ResultReason.SYNC_CONFLICT },
            )
        }
        scoreA = b[4].toInt()
        scoreB = b[5].toInt()
        passA = b[6].toInt()
        passB = b[7].toInt()
        var i = 8
        if (b[i].toInt() != 0) {
            i++
            val cell = b[i++].toInt()
            val letter = b[i++].toInt()
            val mover = if (b[i++].toInt() == 0) Side.A else Side.B
            val plen = b[i++].toInt()
            val path = IntArray(plen) { b[i + it].toInt() }
            i += plen
            val wlen = b[i++].toInt()
            val word = String(b, i, wlen, Charsets.UTF_8)
            i += wlen
            dispute = Dispute(cell, letter, path, word, mover)
        } else {
            i++
        }
        // стартовое слово: (len, utf8) — валидация повторов после рестарта
        val swlen = b[i++].toInt() and 0xFF
        startWordValue = String(b, i, swlen, Charsets.UTF_8)
        i += swlen
        val count = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
        i += 2
        repeat(count) {
            val side = if (b[i++].toInt() == 0) Side.A else Side.B
            val len = b[i++].toInt() and 0xFF
            // len=0 — пропуск (пустое слово, записан applySkip)
            usedWords.add(PlayedWord(side, String(b, i, len, Charsets.UTF_8), passed = len == 0))
            i += len
        }
        val llen = b[i++].toInt()
        lastPathValue = IntArray(llen) { b[i + it].toInt() }
        i += llen
        for (j in cells.indices) cells[j] = b[i + j].toInt()
    }

    // ------------------------------------------------------- утилиты ---

    private fun scoreOf(side: Side) = if (side == Side.A) scoreA else scoreB

    private fun addScore(side: Side, delta: Int) {
        if (side == Side.A) scoreA += delta else scoreB += delta
    }

    companion object {
        const val SIDE = 5
        const val BOARD = SIDE * SIDE
        const val CENTER_ROW = 2
        const val START_WORD = 5
        const val THREE_PASSES = 3

        /**
         * Таблица выбора букв для UI (6×5 = 30 клеток): парные е/ё, и/й, ь/ъ
         * живут в одной клетке, значение — канон-код клетки поля (ё→е = 6,
         * й→и = 10, ъ→ь = 30); словарная «й»/«ъ»-форма слова находится
         * перебором вариантов ([inDictionary]).
         */
        val LETTER_PICKER: List<Pair<String, Int>> = listOf(
            "А" to 1, "Б" to 2, "В" to 3, "Г" to 4, "Д" to 5, "Е/Ё" to 6,
            "Ж" to 8, "З" to 9, "И/Й" to 10, "К" to 12, "Л" to 13, "М" to 14,
            "Н" to 15, "О" to 16, "П" to 17, "Р" to 18, "С" to 19, "Т" to 20,
            "У" to 21, "Ф" to 22, "Х" to 23, "Ц" to 24, "Ч" to 25, "Ш" to 26,
            "Щ" to 27, "Ь/Ъ" to 30, "Ы" to 29, "Э" to 31, "Ю" to 32, "Я" to 33,
        )

        /** Парные буквы для перебора вариантов словаря (е/ё слиты в normalize). */
        private val PAIRED_LETTERS = "ийьъ"

        private val ALTERNATE = mapOf('и' to 'й', 'й' to 'и', 'ь' to 'ъ', 'ъ' to 'ь')

        /** Максимум парных позиций в слове — перебор ≤ 2^10 вариантов. */
        const val MAX_PAIR_VARIANTS = 10

        const val KIND_WORD = 0x01
        const val KIND_WORD_DISPUTED = 0x81
        const val KIND_SKIP = 0x02
        const val KIND_VERDICT = 0x03
        /** Флаг компактного дельта-кодирования пути в байте kind. */
        const val FLAG_COMPACT = 0x40

        /** Длина пути тестового бота (буква + 9 клеток поля, не больше 25). */
        const val BOT_MAX_WORD = 10

        /** Сколько слов проверяет бот за один ход — ограничивает перебор змейок. */
        const val BOT_MAX_CHECKS = 20_000

        /** Нижний регистр + «ё» → «е» (единая форма для словаря и usedWords). */
        fun normalize(s: String) = s.lowercase().replace('ё', 'е')

        /**
         * Каноническая форма слова для проверки повторов:
         * парные буквы е/ё, и/й, ь/ъ взаимозаменяемы по правилам Балды,
         * поэтому слова «майка» и «маика», «полет» и «полёт», «подъем» и «подьем»
         * считаются одним и тем же словом и не могут быть сыграны повторно.
         */
        fun canonical(s: String): String = s.lowercase()
            .replace('ё', 'е')
            .replace('й', 'и')
            .replace('ъ', 'ь')

        fun isSameWord(w1: String, w2: String): Boolean = canonical(w1) == canonical(w2)

        /** Русский алфавит: код = индекс + 1 («а» = 1 … «я» = 33). */
        val ALPHABET = "абвгдеёжзийклмнопрстуфхцчшщъыьэюя"

        fun letterCode(ch: Char): Int = ALPHABET.indexOf(if (ch == 'ё') 'е' else ch) + 1

        fun codeLetter(code: Int): Char = ALPHABET.getOrElse(code - 1) { '?' }

        /** «ё» не передаётся в кадрах и не живёт на поле: канон — «е» (6). */
        private const val CODE_YO = 7 // индекс «ё» в ALPHABET + 1

        fun adjacent(a: Int, b: Int): Boolean {
            val ra = a / SIDE; val ca = a % SIDE
            val rb = b / SIDE; val cb = b % SIDE
            return (ra == rb && kotlin.math.abs(ca - cb) == 1) ||
                (ca == cb && kotlin.math.abs(ra - rb) == 1)
        }

        /** Направление шага между соседними клетками (0=N, 1=S, 2=W, 3=E, -1=не ортогонально). */
        fun dirOf(from: Int, to: Int): Int {
            val rf = from / SIDE; val cf = from % SIDE
            val rt = to / SIDE; val ct = to % SIDE
            return when {
                rf - 1 == rt && cf == ct -> 0 // N
                rf + 1 == rt && cf == ct -> 1 // S
                rf == rt && cf - 1 == ct -> 2 // W
                rf == rt && cf + 1 == ct -> 3 // E
                else -> -1
            }
        }

        /** Переход в соседнюю клетку по направлению (-1 при выходе за пределы поля). */
        fun stepIn(from: Int, dir: Int): Int {
            val r = from / SIDE
            val c = from % SIDE
            return when (dir) {
                0 -> if (r > 0) from - SIDE else -1
                1 -> if (r < SIDE - 1) from + SIDE else -1
                2 -> if (c > 0) from - 1 else -1
                3 -> if (c < SIDE - 1) from + 1 else -1
                else -> -1
            }
        }

        private fun encodePathCompact(cell: Int, letter: Int, path: IntArray, kind: Int): ByteArray? {
            if (path.size < 3) return null
            val dirs = IntArray(path.size - 1)
            for (i in 0 until path.size - 1) {
                val d = dirOf(path[i], path[i + 1])
                if (d == -1) return null
                dirs[i] = d
            }
            val packedCount = (dirs.size + 3) / 4
            val out = ByteArray(5 + packedCount)
            out[0] = (kind or FLAG_COMPACT).toByte()
            out[1] = cell.toByte()
            out[2] = letter.toByte()
            out[3] = path.size.toByte()
            out[4] = path[0].toByte()
            for (i in dirs.indices) {
                val byteIdx = 5 + i / 4
                val shift = 2 * (i % 4)
                out[byteIdx] = (out[byteIdx].toInt() or ((dirs[i] and 0x03) shl shift)).toByte()
            }
            return out
        }

        private fun wordPayload(kind: Int, cell: Int, letter: Int, path: IntArray): ByteArray {
            encodePathCompact(cell, letter, path, kind)?.let { return it }
            val out = ByteArray(4 + path.size)
            out[0] = kind.toByte()
            out[1] = cell.toByte()
            out[2] = letter.toByte()
            out[3] = path.size.toByte()
            for (i in path.indices) out[4 + i] = path[i].toByte()
            return out
        }

        /**
         * Минимальный размер валидного state (пустой спор, стартовое слово
         * длиной 0 — только для максимально урезанных кадров, в игре старт
         * всегда 5 букв; без слов и пути).
         */
        const val MIN_STATE = 8 + 1 + 1 + 2 + 1 + BOARD

        /**
         * Стартовые слова-заглушки (ровно 5 букв), когда словарь недоступен.
         * Источник слов — ассет `balda_dict.txt.gz`, эта вставка только
         * гарантирует играбельность в деградации.
         */
        val FALLBACK_START = listOf(
            "балда", "слово", "книга", "ручка", "волна", "берег", "крыло", "пушка", "доска",
        )
    }
}
