package com.meshgames.game.engine

/**
 * Проверка слов для Балды. Интерфейс живёт в игровом ядре, реализация —
 * в слое app (ассет `balda_dict.txt.gz` + пользовательский `balda_extra.txt`).
 *
 * Все слова поступают нормализованными: нижний регистр, «ё» → «е».
 */
interface WordChecker {
    /** Есть ли слово в словаре. */
    fun contains(word: String): Boolean

    /** Слова для первой строки поля (ровно 5 букв); пусто — используй заглушку. */
    fun startWords(): List<String> = emptyList()
}

/**
 * Пустая заглушка (словарь не загрузился): ни одно слово не найдено —
 * каждое слово уходит сопернику на спор, игра остаётся управляемой людьми.
 */
object EmptyWordChecker : WordChecker {
    override fun contains(word: String) = false
}
