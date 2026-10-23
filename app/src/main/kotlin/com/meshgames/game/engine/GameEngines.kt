package com.meshgames.game.engine

import com.meshgames.game.GameEngine
import com.meshgames.game.GameType
import com.meshgames.game.Side

/**
 * Фабрика движков: создаёт/восстанавливает движок по типу игры.
 * Шахматы и шашки — отдельные движки (см. ChessEngine / CheckersEngine).
 *
 * [sessionId] нужен балде — от него зависит стартовое слово (обе стороны
 * выбирают его одинаково: слово — функция sid). Restore из непустого
 * state берёт слово уже из клеток.
 */
object GameEngines {
    /**
     * Словарь Балды: приложение подставляет реализацию (ассет
     * `balda_dict.txt.gz` + пользовательский `balda_extra.txt`) до
     * создания менеджера сессий. Заглушка [EmptyWordChecker] —
     * деградация: каждое слово уходит сопернику на спор.
     */
    @Volatile
    var wordChecker: WordChecker = EmptyWordChecker

    fun create(type: GameType, mySide: Side, sessionId: Int = 0): GameEngine = when (type) {
        GameType.TIC_TAC_TOE -> TttEngine(mySide)
        GameType.CHECKERS -> CheckersEngine(mySide)
        GameType.CHESS -> ChessEngine(mySide)
        GameType.SEA_BATTLE -> SeaBattleEngine(mySide)
        GameType.BALDA -> BaldaEngine(mySide, sessionId, wordChecker)
        GameType.DURAK -> DurakEngine(mySide, sessionId)
    }

    fun restore(type: GameType, mySide: Side, state: ByteArray, sessionId: Int = 0): GameEngine {
        if (state.isEmpty()) return create(type, mySide, sessionId)
        return when (type) {
            GameType.TIC_TAC_TOE -> TttEngine(mySide, state)
            GameType.CHECKERS -> CheckersEngine(mySide, state)
            GameType.CHESS -> ChessEngine(mySide, state)
            GameType.SEA_BATTLE -> SeaBattleEngine(mySide, state)
            GameType.BALDA -> BaldaEngine(mySide, sessionId, wordChecker, state)
            GameType.DURAK -> DurakEngine(mySide, sessionId, state)
        }
    }
}
