package com.meshgames.bot

import com.meshgames.bot.log.BotLogger
import com.meshgames.game.engine.WordChecker
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.TreeSet
import java.util.zip.GZIPInputStream

/**
 * Словарь Балды для автономного сервера: загружает balda_dict.txt.gz из ресурсов или файла.
 * Полностью идентичен BaldaDictionary из Android-клиента:
 * ровно 66 994 слова, ровно 4143 пятибуквенных стартовых слов.
 */
class BaldaDictionaryLoader private constructor(
    private val data: ByteArray,
    private val offsets: IntArray,
) : WordChecker {

    private val count = offsets.size - 1
    private var startWordsCache: List<String>? = null

    override fun contains(word: String): Boolean {
        val target = word.toByteArray(Charsets.UTF_8)
        val i = lowerBound(target)
        return i < count && rangeEquals(i, target)
    }

    override fun startWords(): List<String> =
        startWordsCache ?: buildList {
            for (i in 0 until count) {
                if (offsets[i + 1] - offsets[i] == START_WORD_BYTES) {
                    add(String(data, offsets[i], START_WORD_BYTES, Charsets.UTF_8))
                }
            }
        }.also { startWordsCache = it }

    private fun lowerBound(target: ByteArray): Int {
        var lo = 0
        var hi = count
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (compareWord(mid, target) < 0) lo = mid + 1 else hi = mid
        }
        return lo
    }

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

    private fun rangeEquals(i: Int, target: ByteArray): Boolean {
        val start = offsets[i]
        if (offsets[i + 1] - start != target.size) return false
        for (k in target.indices) {
            if (data[start + k] != target[k]) return false
        }
        return true
    }

    companion object {
        /** 5 кириллических букв = 10 байт UTF-8. */
        private const val START_WORD_BYTES = 10

        fun loadFromStream(stream: InputStream): BaldaDictionaryLoader {
            val bytes = stream.readBytes()
            val plain = if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
                GZIPInputStream(ByteArrayInputStream(bytes)).readBytes()
            } else {
                bytes
            }
            val text = String(plain, Charsets.UTF_8)
            val words = TreeSet<String>()
            text.lineSequence().forEach { line ->
                val w = normalize(line)
                if (valid(w)) words.add(w)
            }
            return fromWords(words)
        }

        fun loadDefault(): BaldaDictionaryLoader {
            // 1. Попробуем из ресурсов classpath
            val res = BaldaDictionaryLoader::class.java.getResourceAsStream("/balda_dict.txt.gz")
                ?: BaldaDictionaryLoader::class.java.getResourceAsStream("/assets/balda_dict.txt.gz")
            if (res != null) {
                return res.use { loadFromStream(it) }
            }
            // 2. Попробуем из файловой системы
            val candidates = listOf(
                File("bot_server/src/main/resources/balda_dict.txt.gz"),
                File("app/src/main/assets/balda_dict.txt.gz"),
                File("balda_dict.txt.gz"),
            )
            for (f in candidates) {
                if (f.exists()) {
                    return f.inputStream().use { loadFromStream(it) }
                }
            }
            BotLogger.warn("Словарь Балды не найден — будет использован пустой словарь")
            return fromWords(emptyList())
        }

        fun fromWords(words: Collection<String>): BaldaDictionaryLoader {
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
            return BaldaDictionaryLoader(data, offsets)
        }

        /** Нормализация в точности как в Android BaldaDictionary: нижний регистр, «ё» → «е». */
        private fun normalize(s: String): String = s.trim().lowercase().replace('ё', 'е')

        /** Валидация слова в точности как в Android: длина 2..25, все буквы 'а'..'я'. */
        private fun valid(w: String): Boolean =
            w.length in 2..25 && w.all { it in 'а'..'я' }
    }
}
