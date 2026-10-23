package com.meshgames.game.engine

import com.meshgames.game.BaldaSnapshot
import com.meshgames.game.BaldaWordEntry
import com.meshgames.game.GameResult
import com.meshgames.game.MoveInput
import com.meshgames.game.ResultReason
import com.meshgames.game.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Балда: валидация змейки, спор (вердикт), очки, пропуски, заполнение
 * поля, восстановление состояния. Словарь — фейковый, движок от него
 * не зависит (кроме contains/startWords).
 */
class BaldaEngineTest {

    /** Фейковый словарь: contains по заданному набору. */
    private class FakeWords(words: Collection<String>) : WordChecker {
        private val set = words.map { BaldaEngine.normalize(it) }.toSet()

        override fun contains(word: String) = word in set
        override fun startWords() = listOf("балда")
    }

    private val defaultWords = FakeWords(listOf("балда", "ра", "ма"))

    private fun pair(
        checker: WordChecker = defaultWords,
        sid: Int = 7,
    ): Pair<BaldaEngine, BaldaEngine> =
        BaldaEngine(Side.A, sid, checker) to BaldaEngine(Side.B, sid, checker)

    /** A делает ход, B принимает; дайджесты обязаны совпасть. */
    private fun aMove(a: BaldaEngine, b: BaldaEngine, cell: Int, letter: Char, path: IntArray): ByteArray {
        val payload = a.localMove(
            MoveInput.LetterAdd(cell, BaldaEngine.letterCode(letter), path),
        )!!
        assertTrue(b.remoteMove(payload))
        assertEquals(a.digest(), b.digest())
        return payload
    }

    @Test
    fun startWordIdenticalOnBothSides() {
        val (a, b) = pair()
        val sa = a.snapshot() as BaldaSnapshot
        val sb = b.snapshot() as BaldaSnapshot
        assertTrue(sa.cells.contentEquals(sb.cells))
        assertEquals("балда", (10..14).joinToString("") { BaldaEngine.codeLetter(sa.cells[it]).toString() })
        assertEquals(5, sa.cells.count { it != 0 }) // только средняя строка
        assertEquals(Side.A, sa.turn)
        assertEquals(0, sa.myScore)
        assertTrue(a.quiescent && b.quiescent)
        // Один sid — одно слово; другой sid берёт то же (в словаре одно слово).
        val other = BaldaEngine(Side.A, 999, defaultWords)
        assertTrue((other.snapshot() as BaldaSnapshot).cells.contentEquals(sa.cells))
    }

    @Test
    fun acceptedWordScoresAndHandsTurnOver() {
        val (a, b) = pair()
        // «ра»: клетка 6 над «а» (11) — путь [6,11]
        aMove(a, b, 6, 'р', intArrayOf(6, 11))
        val sa = a.snapshot() as BaldaSnapshot
        assertEquals(2, sa.myScore)
        assertEquals(Side.B, sa.turn)
        assertTrue(sa.lastPath.contentEquals(intArrayOf(6, 11)))
        val sb = b.snapshot() as BaldaSnapshot
        assertEquals(2, sb.peerScore)
        assertEquals(0, sb.myScore)
        // B ходит «ма» [16,11] — счёт растёт у B
        val payload = b.localMove(
            MoveInput.LetterAdd(16, BaldaEngine.letterCode('м'), intArrayOf(16, 11)),
        )!!
        assertTrue(a.remoteMove(payload))
        assertEquals(a.digest(), b.digest())
        assertEquals(2, (a.snapshot() as BaldaSnapshot).myScore) // у A не изменился
        assertEquals(2, (a.snapshot() as BaldaSnapshot).peerScore)
        assertEquals(Side.A, (a.snapshot() as BaldaSnapshot).turn)
    }

    @Test
    fun structuralViolationsRejectedLocallyAndRemotely() {
        val (a, b) = pair()
        val code = BaldaEngine.letterCode('р')
        // не моя очередь
        assertNull(b.localMove(MoveInput.LetterAdd(6, code, intArrayOf(6, 11))))
        assertNull(b.localMove(MoveInput.Skip()))
        // занятая клетка
        assertNull(a.localMove(MoveInput.LetterAdd(11, code, intArrayOf(11, 12))))
        // путь из одной клетки
        assertNull(a.localMove(MoveInput.LetterAdd(6, code, intArrayOf(6))))
        // новая клетка вне пути
        assertNull(a.localMove(MoveInput.LetterAdd(6, code, intArrayOf(10, 11))))
        // пустая клетка внутри слова
        assertNull(a.localMove(MoveInput.LetterAdd(6, code, intArrayOf(6, 5))))
        // повтор клетки
        assertNull(a.localMove(MoveInput.LetterAdd(6, code, intArrayOf(6, 11, 6))))
        // диагональ между клетками
        assertNull(a.localMove(MoveInput.LetterAdd(6, code, intArrayOf(6, 12))))
        // недопустимый код буквы
        assertNull(a.localMove(MoveInput.LetterAdd(6, 0, intArrayOf(6, 11))))
        assertNull(a.localMove(MoveInput.LetterAdd(6, 99, intArrayOf(6, 11))))
        // мусор от пира
        assertFalse(b.remoteMove(byteArrayOf(9)))
        assertFalse(b.remoteMove(ByteArray(0)))
        assertFalse(b.remoteMove(byteArrayOf(0x01, 6, 1, 1))) // pathLen < 2
        assertFalse(b.remoteMove(byteArrayOf(0x03, 1)))        // вердикт без спора
        assertFalse(a.remoteMove(byteArrayOf(0x77)))           // неизвестный kind
        // ничего не применилось
        assertEquals(5, (a.snapshot() as BaldaSnapshot).cells.count { it != 0 })
        assertEquals(Side.A, (a.snapshot() as BaldaSnapshot).turn)
    }

    @Test
    fun usedWordCannotBeRepeated() {
        val (a, b) = pair()
        aMove(a, b, 6, 'р', intArrayOf(6, 11)) // «ра» сыграно
        // повтор локально: «ра» другой клеткой (9 над 14)
        assertNull(a.localMove(MoveInput.LetterAdd(9, BaldaEngine.letterCode('р'), intArrayOf(9, 14))))
        // повтор удалённо: B (его очередь) шлёт «ра» — отклоняется
        val dup = byteArrayOf(
            0x01, 9, BaldaEngine.letterCode('р').toByte(), 2, 9, 14,
        )
        assertFalse(b.remoteMove(dup))
        assertEquals(a.digest(), b.digest())
    }

    @Test
    fun disputeTentativeApplyAndAcceptFinalizes() {
        // «ка» в словаре нет — спор
        val dict = FakeWords(listOf("балда", "ра"))
        val (a, b) = pair(dict)
        val payload = a.localMove(
            MoveInput.LetterAdd(9, BaldaEngine.letterCode('к'), intArrayOf(9, 14)),
        )!!
        assertEquals(0x81, payload[0].toInt() and 0xFF)
        assertEquals(9, payload[1].toInt())
        assertEquals(2, payload[3].toInt())

        var sa = a.snapshot() as BaldaSnapshot
        assertNotNull(sa.dispute)
        assertEquals("ка", sa.dispute!!.word)
        // A — инициатор: ждёт вердикта, а не принимает решение
        assertFalse(sa.dispute!!.awaitingMyVerdict)
        assertFalse(a.pendingVerdict)
        assertEquals(Side.A, sa.turn) // очередь не ушла: ход ещё не состоялся
        assertEquals(BaldaEngine.letterCode('к'), sa.cells[9]) // условно лежит

        assertTrue(b.remoteMove(payload))
        assertEquals(a.digest(), b.digest())
        val sb = b.snapshot() as BaldaSnapshot
        assertTrue(sb.dispute!!.awaitingMyVerdict)
        assertTrue(b.pendingVerdict)
        // инициатору спор решать нельзя
        assertNull(a.localVerdict(true))

        // B принимает: очки A, очередь — B
        val verdict = b.localVerdict(true)!!
        assertTrue(verdict.contentEquals(byteArrayOf(0x03, 1)))
        assertFalse(b.pendingVerdict)
        assertEquals(2, (b.snapshot() as BaldaSnapshot).peerScore)

        assertTrue(a.remoteMove(verdict))
        sa = a.snapshot() as BaldaSnapshot
        assertNull(sa.dispute)
        assertEquals(2, sa.myScore)
        assertEquals(Side.B, sa.turn)
        assertEquals(a.digest(), b.digest())
        assertFalse(a.pendingVerdict)
    }

    @Test
    fun disputeRejectRevertsLetterAndKeepsTurn() {
        val dict = FakeWords(listOf("балда"))
        val (a, b) = pair(dict)
        val payload = a.localMove(
            MoveInput.LetterAdd(9, BaldaEngine.letterCode('к'), intArrayOf(9, 14)),
        )!!
        assertTrue(b.remoteMove(payload))
        val verdict = b.localVerdict(false)!!
        assertTrue(verdict.contentEquals(byteArrayOf(0x03, 0)))
        assertTrue(a.remoteMove(verdict))
        // буква снята, очередь у инициатора, очков нет
        for (e in listOf(a, b)) {
            val s = e.snapshot() as BaldaSnapshot
            assertNull(s.dispute)
            assertEquals(0, s.cells[9])
            assertEquals(0, s.myScore + s.peerScore)
            assertEquals(Side.A, s.turn)
            assertFalse(e.pendingVerdict)
        }
        assertEquals(a.digest(), b.digest())
        // A снова может ходить
        aMove(a, b, 6, 'р', intArrayOf(6, 11))
    }

    @Test
    fun startWordCannotBeRepeated() {
        val (a, b) = pair()
        // Змейка через новую клетку 8 складывает «балда» заново — стартовое
        // слово повторять нельзя ни локально, ни по сети (тот же путь).
        assertNull(
            a.localMove(
                MoveInput.LetterAdd(8, BaldaEngine.letterCode('а'), intArrayOf(10, 11, 12, 13, 8)),
            ),
        )
        assertFalse(
            b.remoteMove(
                byteArrayOf(0x01, 8, BaldaEngine.letterCode('а').toByte(), 5, 10, 11, 12, 13, 8),
            ),
        )
        assertEquals(a.digest(), b.digest())
        val s = a.snapshot() as BaldaSnapshot
        assertEquals(0, s.cells[8]) // буква не легла
        assertEquals(Side.A, s.turn) // очередь не сдвинулась
        assertEquals(5, s.cells.count { it != 0 })
    }

    @Test
    fun pairedLettersInterchangeableInDictionaryLookup() {
        // и/й, ь/ъ взаимозаменяемы при проверке словаря: на поле канон из
        // таблицы выбора, словарь знает другую форму слова — принимается.
        // Перебор вариантов — чистая функция: обе стороны решают одинаково.
        val dict = FakeWords(listOf("балда", "бй", "ьа"))
        val (a, b) = pair(dict)
        aMove(a, b, 15, 'и', intArrayOf(10, 15)) // «би» ≡ «бй» из словаря
        assertEquals(2, (a.snapshot() as BaldaSnapshot).myScore)
        // B: «ъа» ≡ «ьа» — та же проверка для пары ь/ъ.
        val payload = b.localMove(
            MoveInput.LetterAdd(6, BaldaEngine.letterCode('ъ'), intArrayOf(6, 11)),
        )!!
        assertTrue(a.remoteMove(payload))
        assertEquals(a.digest(), b.digest())
        assertEquals(2, (a.snapshot() as BaldaSnapshot).peerScore)
    }

    @Test
    fun repeatedWordWithInterchangeableLettersRejected() {
        val dict = FakeWords(listOf("балда", "би", "бй"))
        val (a, b) = pair(dict)
        // A играет «би» (клетка 15)
        aMove(a, b, 15, 'и', intArrayOf(10, 15))
        // Теперь B пытается сыграть то же слово с заменой на парную букву «бй» (клетка 5)
        val moveWithPaired = b.localMove(
            MoveInput.LetterAdd(5, BaldaEngine.letterCode('й'), intArrayOf(10, 5)),
        )
        // Повтор слова через взаимозаменяемую букву обязан быть отклонён!
        assertNull("Повтор слова через взаимозаменяемую букву (и↔й) должен быть отклонён", moveWithPaired)
    }

    @Test
    fun letterPickerCoversWholeAlphabetOnce() {
        // Таблица выбора — 30 клеток (6×5): пары е/ё, и/й, ь/ъ сведены к
        // канон-коду, каждая буква алфавита покрыта ровно одной клеткой.
        val codes = BaldaEngine.LETTER_PICKER.map { it.second }
        assertEquals(30, codes.size)
        assertEquals(codes.size, codes.distinct().size)
        val canon = mapOf('ё' to 'е', 'й' to 'и', 'ъ' to 'ь')
        for (ch in BaldaEngine.ALPHABET) {
            val code = BaldaEngine.letterCode(canon[ch] ?: ch)
            assertTrue("буква «$ch» не покрыта таблицей", code in codes)
        }
    }

    @Test
    fun newLetterMustTouchFilledCell() {
        // Правило балды: букву ставят только рядом с занятой клеткой.
        // Структурная валидация движка это гарантирует — путь через
        // «глухую» клетку не построить (все клетки пути, кроме новой,
        // заняты и соседние) — ход отклоняется до отправки, по сети
        // тоже; счётчики и дайджесты не двигаются.
        val (a, b) = pair()
        assertNull(
            a.localMove(
                MoveInput.LetterAdd(0, BaldaEngine.letterCode('р'), intArrayOf(5, 0)),
            ),
        )
        assertFalse(
            b.remoteMove(
                byteArrayOf(0x01, 0, BaldaEngine.letterCode('р').toByte(), 2, 5, 0),
            ),
        )
        assertEquals(a.digest(), b.digest())
        assertEquals(5, (a.snapshot() as BaldaSnapshot).cells.count { it != 0 })
    }

    @Test
    fun wordInDictionaryPreviewMatchesMoveCheck() {
        // Предпросмотр для UI (диалог «Отмена/Отправить» перед спором) —
        // та же мягкая проверка, что и при ходе: точная форма слова или
        // вариант с парными буквами («би» ≡ «бй» из словаря).
        val dict = FakeWords(listOf("балда", "ра", "бй"))
        val (a, _) = pair(dict)
        assertTrue(a.wordInDictionary("ра"))
        assertFalse(a.wordInDictionary("рх"))
        assertTrue(a.wordInDictionary("би"))
    }

    @Test
    fun playedWordsListedWithSidesAndScores() {
        val (a, b) = pair()
        aMove(a, b, 6, 'р', intArrayOf(6, 11)) // A: «ра»
        val q = b.localMove(
            MoveInput.LetterAdd(16, BaldaEngine.letterCode('м'), intArrayOf(16, 11)),
        )!!
        assertTrue(a.remoteMove(q))

        val sa = a.snapshot() as BaldaSnapshot
        assertEquals("балда", sa.startWord)
        assertEquals(
            listOf(BaldaWordEntry(Side.A, "ра"), BaldaWordEntry(Side.B, "ма")),
            sa.words,
        )
        assertEquals(4, sa.myScore + sa.peerScore) // общий счёт: 2 + 2
        // список и стартовое слово переживают рестарт
        val a2 = BaldaEngine(Side.A, 7, defaultWords, a.encodeState())
        val s2 = a2.snapshot() as BaldaSnapshot
        assertEquals(sa.words, s2.words)
        assertEquals("балда", s2.startWord)
        assertEquals(a.digest(), a2.digest())
        // стартовое слово по-прежнему нельзя повторить после рестарта
        assertNull(
            a2.localMove(
                MoveInput.LetterAdd(8, BaldaEngine.letterCode('а'), intArrayOf(10, 11, 12, 13, 8)),
            ),
        )
    }

    @Test
    fun threeConsecutivePassesEndWithLoss() {
        val (a, b) = pair()
        fun pass(x: BaldaEngine, y: BaldaEngine) {
            val p = x.localMove(MoveInput.Skip())!!
            assertTrue(p.contentEquals(byteArrayOf(0x02)))
            assertTrue(y.remoteMove(p))
            assertEquals(x.digest(), y.digest())
        }
        pass(a, b) // A: 1
        assertEquals(1, a.encodeState()[6].toInt())
        pass(b, a) // B: 1 — счётчик A не трогает
        assertEquals(1, a.encodeState()[6].toInt())
        assertEquals(1, a.encodeState()[7].toInt())
        pass(a, b) // A: 2
        pass(b, a) // B: 2
        pass(a, b) // A: 3 → поражение A
        assertTrue(a.finished && b.finished)
        assertEquals(GameResult(Side.B, ResultReason.THREE_PASSES), a.result)
        assertEquals(a.result, b.result)
        assertEquals(a.digest(), b.digest())
        assertNull(a.localMove(MoveInput.Skip())) // после конца — ходов нет
    }

    @Test
    fun skipRecordedInScoreTableAndState() {
        val (a, b) = pair()
        // A пропускает: в истории обеих сторон появляется запись «пропуск» —
        // таблица счёта показывает её симметрично (дайджесты совпали).
        val p = a.localMove(MoveInput.Skip())!!
        assertTrue(b.remoteMove(p))
        assertEquals(a.digest(), b.digest())
        val e = b.snapshot().words.single()
        assertEquals(Side.A, e.side)
        assertTrue(e.passed)
        assertEquals("", e.word)
        assertEquals(e, a.snapshot().words.single())
        // Запись переживает рестарт (в состоянии — len=0), дайджест тот же.
        val restored = BaldaEngine(Side.B, 7, defaultWords, b.encodeState())
        assertEquals(b.digest(), restored.digest())
        assertEquals(b.snapshot().words, restored.snapshot().words)
        // Очередные пропуски до конца партии: все пять в истории.
        repeat(4) { i ->
            val (x, y) = if (i % 2 == 0) b to a else a to b
            val q = x.localMove(MoveInput.Skip())!!
            assertTrue(y.remoteMove(q))
        }
        assertTrue(a.finished && b.finished)
        val all = a.snapshot().words
        assertEquals(5, all.size)
        assertTrue("не все записи — пропуски", all.all { it.passed })
        assertEquals(all, b.snapshot().words)
    }

    @Test
    fun acceptedWordResetsBothPassCounters() {
        val (a, b) = pair()
        val p = a.localMove(MoveInput.Skip())!!
        assertTrue(b.remoteMove(p))
        assertEquals(1, a.encodeState()[6].toInt())
        // B играет слово — оба счётчика обнуляются
        val q = b.localMove(
            MoveInput.LetterAdd(16, BaldaEngine.letterCode('м'), intArrayOf(16, 11)),
        )!!
        assertTrue(a.remoteMove(q))
        assertEquals(0, a.encodeState()[6].toInt())
        assertEquals(0, a.encodeState()[7].toInt())
        assertEquals(2, (a.snapshot() as BaldaSnapshot).peerScore)
    }

    @Test
    fun boardFilledEndsByScore() {
        // Полный сценарий: 20 ходов заполняют поле, у A слово длиннее («вон»).
        val dict = FakeWords(
            listOf(
                "балда",
                "ба", "ва", "ло", "до", "ла",
                "бо", "да", "ле", "де", "на",
                "ма", "ов", "вон", "го", "ли",
                "ок", "ди", "ме", "бе", "ан",
            ),
        )
        val (a, b) = pair(dict)
        fun am(cell: Int, letter: Char, path: IntArray) = aMove(a, b, cell, letter, path)
        fun bm(cell: Int, letter: Char, path: IntArray) {
            val p = b.localMove(
                MoveInput.LetterAdd(cell, BaldaEngine.letterCode(letter), path),
            )!!
            assertTrue(a.remoteMove(p))
            assertEquals(a.digest(), b.digest())
        }

        am(5, 'а', intArrayOf(10, 5))   // ба
        bm(6, 'в', intArrayOf(6, 11))   // ва
        am(7, 'о', intArrayOf(12, 7))   // ло
        bm(8, 'о', intArrayOf(13, 8))   // до
        am(9, 'л', intArrayOf(9, 14))   // ла
        bm(15, 'о', intArrayOf(10, 15)) // бо
        am(16, 'д', intArrayOf(16, 11)) // да
        bm(17, 'е', intArrayOf(12, 17)) // ле
        am(18, 'е', intArrayOf(13, 18)) // де
        bm(19, 'н', intArrayOf(19, 14)) // на
        am(0, 'м', intArrayOf(0, 5))    // ма
        bm(1, 'о', intArrayOf(1, 6))    // ов
        am(2, 'н', intArrayOf(6, 1, 2)) // вон — три буквы, решает счёт
        bm(3, 'г', intArrayOf(3, 8))    // го
        am(4, 'и', intArrayOf(9, 4))    // ли
        bm(20, 'к', intArrayOf(15, 20)) // ок
        am(21, 'и', intArrayOf(16, 21)) // ди
        bm(22, 'м', intArrayOf(22, 17)) // ме
        am(23, 'б', intArrayOf(23, 18)) // бе
        bm(24, 'а', intArrayOf(24, 19)) // ан — поле заполнено

        for (e in listOf(a, b)) {
            assertTrue(e.finished)
            assertEquals(GameResult(Side.A, ResultReason.BOARD_FILLED), e.result)
        }
        val sa = a.snapshot() as BaldaSnapshot
        val sb = b.snapshot() as BaldaSnapshot
        assertEquals(21, sa.myScore)
        assertEquals(20, sa.peerScore)
        assertEquals(20, sb.myScore)
        assertEquals(21, sb.peerScore)
        assertEquals(25, sa.cells.count { it != 0 })
        assertEquals(a.digest(), b.digest())
        assertNull(a.localMove(MoveInput.Skip()))
        assertNull(b.localMove(MoveInput.Skip()))
    }

    @Test
    fun stateSurvivesRestoreMidDispute() {
        val dict = FakeWords(listOf("балда"))
        val (a, b) = pair(dict)
        val payload = a.localMove(
            MoveInput.LetterAdd(9, BaldaEngine.letterCode('к'), intArrayOf(9, 14)),
        )!!
        assertTrue(b.remoteMove(payload))

        val a2 = BaldaEngine(Side.A, 7, dict, a.encodeState())
        val b2 = BaldaEngine(Side.B, 7, dict, b.encodeState())
        assertEquals(a.digest(), a2.digest())
        assertEquals(b.digest(), b2.digest())
        assertTrue(b2.pendingVerdict)
        assertFalse(a2.pendingVerdict)

        // Рестарт не мешает: вердикт после восстановления проходит у обоих
        val verdict = b2.localVerdict(false)!!
        assertTrue(a2.remoteMove(verdict))
        // оригиналы доводим тем же вердиктом — состояния должны сойтись
        assertTrue(a.remoteMove(verdict))
        assertTrue(b.localVerdict(false) != null)
        assertEquals(a.digest(), a2.digest())
        assertEquals(b.digest(), b2.digest())
        assertEquals(0, (a2.snapshot() as BaldaSnapshot).cells[9])
    }

    @Test
    fun stateSurvivesRestoreAfterFinish() {
        val dict = FakeWords(listOf("балда", "ра", "ма"))
        val (a, b) = pair(dict)
        aMove(a, b, 6, 'р', intArrayOf(6, 11))
        val a2 = BaldaEngine(Side.A, 7, dict, a.encodeState())
        val b2 = BaldaEngine(Side.B, 7, dict, b.encodeState())
        assertEquals(a.digest(), a2.digest())
        assertEquals(b.digest(), b2.digest())
        val s2 = a2.snapshot() as BaldaSnapshot
        assertEquals(2, s2.myScore)
        assertEquals(Side.B, s2.turn)
        assertTrue(s2.lastPath.contentEquals(intArrayOf(6, 11)))
        // использованное слово помнится после восстановления
        assertNull(
            a2.localMove(MoveInput.LetterAdd(9, BaldaEngine.letterCode('р'), intArrayOf(9, 14))),
        )
    }

    @Test
    fun letterCodesAndNormalization() {
        // «ё» = код «е» (6), «я» = 33; код 7 («ё») в кадрах не принимается
        assertEquals(6, BaldaEngine.letterCode('ё'))
        assertEquals(6, BaldaEngine.letterCode('е'))
        assertEquals(33, BaldaEngine.letterCode('я'))
        assertEquals('я', BaldaEngine.codeLetter(33))
        assertEquals("слово", BaldaEngine.normalize("СЛОВО"))
        assertEquals("село", BaldaEngine.normalize("СЁЛО"))
    }

    @Test
    fun letterCodeSevenRejected() {
        // Код 7 («ё») из старых сборок больше не отождествляется с «е» (6):
        // и локальный ввод, и кадр пира с этим кодом отклоняются.
        val (a, b) = pair()
        assertNull(a.localMove(MoveInput.LetterAdd(5, 7, intArrayOf(5, 10))))
        assertFalse(
            b.remoteMove(byteArrayOf(BaldaEngine.KIND_WORD.toByte(), 5, 7, 2, 5, 10)),
        )
        // тот же кадр с каноническим «е» (6) принимается
        assertTrue(
            b.remoteMove(byteArrayOf(BaldaEngine.KIND_WORD.toByte(), 5, 6, 2, 5, 10)),
        )
    }

    @Test
    fun compactDeltaPathEncodingAndDecoding() {
        val (a, b) = pair()
        // Слово из 3 букв: ставим 'р' в 15 (под 'б'=10). Путь [15, 10, 11] ('р', 'б', 'а')
        val code = BaldaEngine.letterCode('р')
        val payload = a.localMove(MoveInput.LetterAdd(15, code, intArrayOf(15, 10, 11)))!!
        // Должен быть выставлен флаг FLAG_COMPACT (0x40)
        assertEquals(BaldaEngine.KIND_WORD_DISPUTED or BaldaEngine.FLAG_COMPACT, payload[0].toInt() and 0xFF)
        // Размер: 1 (kind) + 1 (cell) + 1 (letter) + 1 (pathLen) + 1 (startCell) + 1 (packed dirs) = 6 байт вместо 7
        assertEquals(6, payload.size)
        // b принимает компактный payload и декодирует тот же путь
        assertTrue(b.remoteMove(payload))
        assertEquals(a.digest(), b.digest())
        val sb = b.snapshot() as BaldaSnapshot
        assertEquals(15, sb.dispute?.cell)
        assertTrue(sb.dispute?.path.contentEquals(intArrayOf(15, 10, 11)))
    }
}
