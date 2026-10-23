package com.meshgames.bot

import com.meshgames.game.engine.GameEngines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BaldaDictionarySyncTest {

    @Test
    fun testBaldaDictionarySyncWithAndroid() {
        val dict = BaldaDictionaryLoader.loadDefault()
        GameEngines.wordChecker = dict

        // В эталонном Android-словаре balda_dict.txt ровно 4143 пятибуквенных стартовых слов!
        val startWords = dict.startWords()
        assertEquals("Количество стартовых слов должно быть ровно 4143 как в Android", 4143, startWords.size)

        // Проверяем наличие обычных и парных слов
        assertTrue(dict.contains("книга"))
        assertTrue(dict.contains("балда"))
        assertTrue(dict.contains("чай")) // слово с 'й'
        assertTrue(dict.contains("подъезд")) // слово с 'ъ'

        // Проверяем стартовое слово для sid = 51233
        val sid = 51233
        val pool = startWords.filter { it.length == 5 }
        val chosenWord = pool[((sid % pool.size) + pool.size) % pool.size]
        println("Стартовое слово для sid=$sid: «$chosenWord» (индекс: ${sid % pool.size})")
        assertTrue("Слово должно состоять из 5 букв", chosenWord.length == 5)
    }
}
