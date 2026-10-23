package com.meshgames.bot.ai

import com.meshgames.game.GameEngine
import com.meshgames.game.GameType
import com.meshgames.game.MoveInput
import com.meshgames.game.Side

/**
 * Интерфейс интеллектуального игрока для MeshGames.
 * Принимает движок текущей игры и вычисляет лучший ход.
 */
interface BotAi {
    /**
     * Подобрать оптимальный ход для [engine].
     * Возвращает null, если сейчас не наш ход или игра завершена.
     */
    fun computeMove(engine: GameEngine): MoveInput?

    /**
     * Оценить позицию (положительное число — перевес бота, отрицательное — соперника).
     */
    fun evaluatePosition(engine: GameEngine): Double = 0.0
}

object BotAiFactory {
    fun create(type: GameType, difficulty: String = "hard"): BotAi {
        val diff = difficulty.lowercase()
        return when (type) {
            GameType.TIC_TAC_TOE -> TttAi(diff)
            GameType.CHESS -> ChessAi(
                depth = when (diff) {
                    "easy" -> 2
                    "medium" -> 3
                    else -> 4
                },
                difficulty = diff,
            )
            GameType.CHECKERS -> CheckersAi(
                depth = when (diff) {
                    "easy" -> 2
                    "medium" -> 4
                    else -> 6
                },
                difficulty = diff,
            )
            GameType.SEA_BATTLE -> SeaBattleAi(diff)
            GameType.BALDA -> BaldaAi(diff)
            GameType.DURAK -> DurakAi(diff)
        }
    }
}
