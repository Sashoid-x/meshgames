package com.meshgames.bot.ai

import com.meshgames.game.BaldaSnapshot
import com.meshgames.game.GameEngine
import com.meshgames.game.MoveInput
import com.meshgames.game.engine.BaldaEngine

/**
 * Интеллектуальный ИИ для игры «Балда».
 * Поддерживает уровни сложности:
 * - easy: ищет простые короткие слова (3-4 буквы)
 * - medium: ищет слова средней длины (4-5 букв)
 * - hard: нацелен на поиск самых длинных слов (максимальный набор очков)
 */
class BaldaAi(val difficulty: String = "hard") : BotAi {

    companion object : BotAi by BaldaAi("hard") {
        private const val SIDE = BaldaEngine.SIDE
        private const val BOARD = BaldaEngine.BOARD

        // Канонические кандидаты букв (30 букв, исключая «ё»=7, «й»=11, «ъ»=28 —
        // на поле и в выборе кладутся канонические «е»=6, «и»=10, «ь»=30).
        val CANONICAL_LETTERS = intArrayOf(
            1, 2, 3, 4, 5, 6, 8, 9, 10, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 29, 30, 31, 32, 33
        )
    }

    override fun computeMove(engine: GameEngine): MoveInput? {
        if (engine.finished || engine.turn != engine.mySide) return null
        val snap = engine.snapshot() as? BaldaSnapshot ?: return engine.botMove()
        val balda = engine as? BaldaEngine ?: return engine.botMove()

        val cells = snap.cells
        // Все ранее сыгранные слова и стартовое слово сводятся к канонической форме
        // с учетом взаимозаменяемых пар е/ё, и/й, ь/ъ.
        val usedWords = snap.words.filter { !it.passed }.map { BaldaEngine.canonical(it.word) }.toSet() +
            BaldaEngine.canonical(snap.startWord)

        val targetMaxLen = when (difficulty.lowercase()) {
            "easy" -> 4
            "medium" -> 5
            else -> 8
        }

        val goodEnoughLen = when (difficulty.lowercase()) {
            "easy" -> 3
            "medium" -> 5
            else -> 6
        }

        data class Candidate(val cell: Int, val letter: Int, val path: IntArray, val word: String)

        var bestCandidate: Candidate? = null
        var maxWordLen = 0

        // Перебираем все свободные клетки, соседствующие с уже занятыми
        for (cell in 0 until BOARD) {
            if (cells[cell] != 0) continue
            val neighbours = neighboursOf(cell)
            if (neighbours.none { cells[it] != 0 }) continue // клетка изолирована

            for (letter in CANONICAL_LETTERS) {
                // Ищем пути через эту клетку
                val found = findWord(cells, cell, letter, usedWords, balda, targetMaxLen)
                if (found != null && found.path.size > maxWordLen) {
                    maxWordLen = found.path.size
                    bestCandidate = Candidate(cell, letter, found.path, found.word)
                    if (maxWordLen >= goodEnoughLen) break
                }
            }
            if (maxWordLen >= goodEnoughLen) break
        }

        if (bestCandidate != null) {
            return MoveInput.LetterAdd(bestCandidate.cell, bestCandidate.letter, bestCandidate.path)
        }

        // Если умный поиск не нашел слов, фоллбэк на штатный botMove
        return engine.botMove() ?: MoveInput.Skip()
    }

    private class WordMatch(val path: IntArray, val word: String)

    private fun findWord(
        cells: IntArray,
        placedCell: Int,
        placedLetter: Int,
        usedWords: Set<String>,
        balda: BaldaEngine,
        targetMaxLen: Int,
    ): WordMatch? {
        var best: WordMatch? = null
        val visited = BooleanArray(BOARD)

        fun dfs(curr: Int, path: MutableList<Int>, sb: StringBuilder, containsPlaced: Boolean) {
            val len = path.size
            if (len > targetMaxLen) return

            if (containsPlaced && len >= 3) {
                val candidateWord = BaldaEngine.normalize(sb.toString())
                val candidateCanonical = BaldaEngine.canonical(candidateWord)
                if (candidateCanonical !in usedWords && balda.wordInDictionary(candidateWord)) {
                    if (best == null || len > best!!.path.size) {
                        best = WordMatch(path.toIntArray(), candidateWord)
                    }
                }
            }

            for (next in neighboursOf(curr)) {
                if (visited[next]) continue
                val nextLetterCode = if (next == placedCell) placedLetter else cells[next]
                if (nextLetterCode == 0) continue // пустая клетка

                val ch = BaldaEngine.codeLetter(nextLetterCode)
                visited[next] = true
                path.add(next)
                sb.append(ch)

                dfs(next, path, sb, containsPlaced || (next == placedCell))

                sb.setLength(sb.length - 1)
                path.removeAt(path.size - 1)
                visited[next] = false
            }
        }

        // Стартовать путь можно с любой занятой клетки поля или с самой новой клетки
        for (start in 0 until BOARD) {
            val startLetter = if (start == placedCell) placedLetter else cells[start]
            if (startLetter == 0) continue

            visited[start] = true
            val path = ArrayList<Int>(targetMaxLen)
            path.add(start)
            val sb = StringBuilder(targetMaxLen)
            sb.append(BaldaEngine.codeLetter(startLetter))

            dfs(start, path, sb, start == placedCell)

            visited[start] = false
        }

        return best
    }

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
}
