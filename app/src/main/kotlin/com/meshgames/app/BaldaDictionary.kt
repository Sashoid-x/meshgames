package com.meshgames.app

import android.content.Context
import com.meshgames.debug.DebugLog
import com.meshgames.game.engine.WordChecker
import java.io.ByteArrayInputStream
import java.util.TreeSet
import java.util.zip.GZIPInputStream

/**
 * Словарь Балды: ассет `balda_dict.txt` (66 994 слова, см. balda-dict/SOURCE.md;
 * исходник в репо — `balda_dict.txt.gz`, AGP разжимает его при упаковке)
 * плюс пользовательский `balda_extra.txt` (files/ — свободное расширение).
 *
 * Хранение: сплошной `ByteArray` слов без разделителей + `IntArray` офсетов;
 * contains — двоичный поиск по байтовым диапазонам (~1.5 МБ RAM,
 * без trie). Словарь отсортирован по codepoint, а байтовый порядок UTF-8
 * для кириллицы (все символы 2 байта) совпадает с codepoint — сравнение
 * байтов корректно.
 */
class BaldaDictionary private constructor(
    private val data: ByteArray,
    private val offsets: IntArray, // n+1: начало слова i, [n] = data.size
) : WordChecker {

    private val count = offsets.size - 1
    private var startWordsCache: List<String>? = null

    override fun contains(word: String): Boolean {
        val target = word.toByteArray(Charsets.UTF_8)
        val i = lowerBound(target)
        return i < count && rangeEquals(i, target)
    }

    /** Пятибуквенные слова (10 байт UTF-8) — стартовый пул, кэшируется. */
    override fun startWords(): List<String> =
        startWordsCache ?: buildList {
            for (i in 0 until count) {
                if (offsets[i + 1] - offsets[i] == START_WORD_BYTES) {
                    add(String(data, offsets[i], START_WORD_BYTES, Charsets.UTF_8))
                }
            }
        }.also { startWordsCache = it }

    /** Первый индекс слова, не меньшего target (байтовая лексика). */
    private fun lowerBound(target: ByteArray): Int {
        var lo = 0
        var hi = count
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (compareWord(mid, target) < 0) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** Сравнение слова i с target как байтовых массивов. */
    private fun compareWord(i: Int, target: ByteArray): Int {
        val start = offsets[i]
        val len = minOf(offsets[i + 1] - start, target.size)
        for (k in 0 until len) {
            val a = data[start + k].toInt() and 0xFF
            val b = target[k].toInt() and 0xFF
            if (a != b) return a - b
        }
        return (offsets[i + 1] - start) - target.size
    }

    private fun rangeEquals(i: Int, target: ByteArray): Boolean =
        offsets[i + 1] - offsets[i] == target.size && compareWord(i, target) == 0

    companion object {
        /** 5 кириллических букв = 10 байт UTF-8. */
        private const val START_WORD_BYTES = 10

        /**
         * Содержимое ассета словаря: AGP при упаковке разжимает `.gz`-ассеты
         * (в APK лежит plain `balda_dict.txt`), но на всякий случай читаем
         * и исходный gzip — по магии 1f 8b.
         */
        fun decodeAsset(bytes: ByteArray): String {
            val plain = if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
                GZIPInputStream(ByteArrayInputStream(bytes)).readBytes()
            } else {
                bytes
            }
            return String(plain, Charsets.UTF_8)
        }

        /** Построить из уже нормализованных слов (порядок не важен — отсортируем). */
        fun fromWords(words: Collection<String>): BaldaDictionary {
            val byteWords = words.toSortedSet().map { it.toByteArray(Charsets.UTF_8) }
            var total = 0
            for (b in byteWords) total += b.size
            val data = ByteArray(total)
            val offsets = IntArray(byteWords.size + 1)
            var pos = 0
            for (i in byteWords.indices) {
                offsets[i] = pos
                byteWords[i].copyInto(data, pos)
                pos += byteWords[i].size
            }
            offsets[byteWords.size] = pos
            return BaldaDictionary(data, offsets)
        }

        /**
         * Загрузка: ассет + пользовательский файл. Пусто в ассете — игра
         * не встаёт: пул стартовых слов и спорная механика работают и без
         * словаря (EmptyWordChecker в ядре), но здесь стараемся не деградировать.
         */
        fun load(context: Context): BaldaDictionary {
            val words = TreeSet<String>()
            // AGP разжимает .gz при упаковке (в APK — balda_dict.txt), но
            // читаем оба имени: и на исходном gzip-ассете тоже должно работать.
            for (name in listOf("balda_dict.txt", "balda_dict.txt.gz")) {
                try {
                    val text = context.assets.open(name).use { decodeAsset(it.readBytes()) }
                    text.lineSequence().forEach { line ->
                        val w = normalize(line)
                        if (valid(w)) words.add(w)
                    }
                    if (words.isNotEmpty()) break
                } catch (e: Exception) {
                    DebugLog.d("DICT", "ассет $name не прочитан: $e")
                }
            }
            var userAdded = 0
            for (file in userDictFiles(context)) {
                try {
                    if (!file.exists()) continue
                    file.bufferedReader(Charsets.UTF_8).use { reader ->
                        reader.forEachLine { line ->
                            val w = normalize(line)
                            if (valid(w) && words.add(w)) userAdded++
                        }
                    }
                } catch (e: Exception) {
                    DebugLog.d("DICT", "пользовательский словарь ${file.name}: $e")
                }
            }
            DebugLog.d(
                "DICT",
                "словарь Балды: ${words.size} слов (+$userAdded из balda_extra.txt)",
            )
            return fromWords(words)
        }

        /** Файлы расширения словаря: внешний files/ (виден пользователю) и внутренний. */
        private fun userDictFiles(context: Context): List<java.io.File> {
            val out = ArrayList<java.io.File>(2)
            context.getExternalFilesDir(null)?.let { out.add(java.io.File(it, "balda_extra.txt")) }
            out.add(java.io.File(context.filesDir, "balda_extra.txt"))
            return out
        }

        /** Нормализация как в движке: нижний регистр, «ё» → «е». */
        private fun normalize(s: String) = s.trim().lowercase().replace('ё', 'е')

        private fun valid(w: String) =
            w.length in 2..25 && w.all { it in 'а'..'я' }
    }
}
