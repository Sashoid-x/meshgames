package com.meshgames.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Двоичный поиск BaldaDictionary: contains/границы/startWords. */
class BaldaDictionaryTest {
    // fromWords сам сортирует — порядок подачи не важен.
    private val dict = BaldaDictionary.fromWords(
        listOf("слово", "село", "собака", "елка", "яма", "аб"),
    )

    @Test
    fun exactMatchFoundOnlyWholeWords() {
        assertTrue(dict.contains("слово"))
        assertTrue(dict.contains("елка"))
        assertTrue(dict.contains("аб"))
        assertFalse(dict.contains("сл"))     // префикс — не слово
        assertFalse(dict.contains("словоо")) // удлинение — не слово
        assertFalse(dict.contains("кот"))    // чужого нет
        assertFalse(dict.contains(""))
    }

    @Test
    fun boundariesBetweenWordsDoNotFalseHit() {
        // «слова» (нет) лексикографически между «слово» (есть) и соседями
        assertFalse(dict.contains("слова"))
        // цель после последнего слова словаря
        assertFalse(dict.contains("яяя"))
        assertTrue(dict.contains("яма"))
        // цель перед первым словом
        assertFalse(dict.contains("аа"))
        assertTrue(dict.contains("аб"))
    }

    @Test
    fun startWordsAreFiveLettersOnly() {
        assertEquals(listOf("слово"), dict.startWords())
    }

    @Test
    fun decodeAssetHandlesGzipAndPlain() {
        // AGP разжимает .gz при упаковке (в APK plain), но исходник — gzip:
        // загрузчик обязан читать оба варианта по магии файла.
        val text = "слово\nсело\n"
        val gzip = java.io.ByteArrayOutputStream().also { out ->
            java.util.zip.GZIPOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        }.toByteArray()
        assertEquals(text, BaldaDictionary.decodeAsset(gzip))
        assertEquals(text, BaldaDictionary.decodeAsset(text.toByteArray(Charsets.UTF_8)))
    }
}
