package com.meshgames.bot.ai

import com.meshgames.game.DurakPhase
import com.meshgames.game.DurakSnapshot
import com.meshgames.game.GameEngine
import com.meshgames.game.MoveInput
import kotlin.random.Random

/**
 * Интеллектуальный ИИ для игры «Дурак» (подкидной, 36 карт).
 * Поддерживает уровни сложности:
 * - easy: играет случайно, отдает старшие козыри, легко берет
 * - medium: базовая тактика (бережет козыри, заходит с младших карт)
 * - hard: продвинутая тактика (парные заходы, прижим соперника в эндшпиле, расчет козырей)
 */
class DurakAi(val difficulty: String = "hard") : BotAi {

    companion object : BotAi by DurakAi("hard")

    override fun computeMove(engine: GameEngine): MoveInput? {
        if (engine.finished) return null
        val snap = engine.snapshot() as? DurakSnapshot ?: return engine.botMove()

        val isAttacker = snap.attacker == engine.mySide
        val trumpSuit = snap.trump % 4

        // ---------------- 1. Мы защищаемся ----------------
        if (!isAttacker) {
            if (snap.taking) return null // уже берем, ждем окончания хода атакующего
            if (snap.phase != DurakPhase.DEFEND) return null

            val beats = snap.legalBeats
            if (beats.isEmpty()) return MoveInput.DurakTake

            if (difficulty.lowercase() == "easy") {
                // На легком уровне в 35% случаев берем сразу, иначе бьем случайной подходящей картой
                if (Random.nextFloat() < 0.35f) {
                    return MoveInput.DurakTake
                }
                return MoveInput.DurakBeat(beats.random())
            }

            val nonTrumps = beats.filter { it % 4 != trumpSuit }
            val trumps = beats.filter { it % 4 == trumpSuit }

            // Если можно побить некозырем — берем самый младший некозырь
            if (nonTrumps.isNotEmpty()) {
                val bestCard = nonTrumps.minByOrNull { it / 4 }!!
                return MoveInput.DurakBeat(bestCard)
            }

            // Иначе можно побить только козырем. Оценим, стоит ли тратить козырь:
            val minTrump = trumps.minByOrNull { it / 4 }!!
            val trumpRank = minTrump / 4

            // Мелкий козырь (6..9) тратим без колебаний
            if (trumpRank <= 3) {
                return MoveInput.DurakBeat(minTrump)
            }

            // Старший козырь (10..Т): если на столе всего одна карта и в колоде полно карт,
            // выгоднее взять одну карту, чем отдавать козырного короля или туза
            val tableCount = snap.attacks.size
            if (tableCount == 1 && snap.deckCount > 6 && snap.myHand.size <= 8) {
                return MoveInput.DurakTake
            }

            return MoveInput.DurakBeat(minTrump)
        }

        // ---------------- 2. Мы атакуем ----------------
        if (isAttacker) {
            // Защитник нажал «Беру» — подкидываем ему вдогонку некозыри
            if (snap.taking) {
                if (difficulty.lowercase() == "easy" && Random.nextFloat() < 0.5f) {
                    return MoveInput.DurakEnough
                }
                val throws = snap.legalThrows
                val nonTrumps = throws.filter { it % 4 != trumpSuit }
                if (nonTrumps.isNotEmpty()) {
                    val cardToThrow = nonTrumps.minByOrNull { it / 4 }!!
                    return MoveInput.DurakAttack(cardToThrow)
                }
                return MoveInput.DurakEnough
            }

            // Стол пуст — начинаем свежий заход
            if (snap.attacks.isEmpty()) {
                if (difficulty.lowercase() == "easy" && Random.nextFloat() < 0.5f) {
                    return MoveInput.DurakAttack(snap.myHand.random())
                }

                val nonTrumps = snap.myHand.filter { it % 4 != trumpSuit }

                // Ищем парные некозыри (например, две семерки)
                val rankCounts = nonTrumps.groupBy { it / 4 }
                val pairEntry = rankCounts.filter { it.value.size >= 2 }.minByOrNull { it.key }

                if (pairEntry != null) {
                    return MoveInput.DurakAttack(pairEntry.value.first())
                }

                if (nonTrumps.isNotEmpty()) {
                    // Самый младший некозырь
                    val minCard = nonTrumps.minByOrNull { it / 4 }!!
                    return MoveInput.DurakAttack(minCard)
                }

                // Остались только козыри
                val minTrump = snap.myHand.minByOrNull { it / 4 }!!
                return MoveInput.DurakAttack(minTrump)
            }

            // Подкидывание в процессе захода
            val throws = snap.legalThrows
            if (throws.isNotEmpty()) {
                val nonTrumps = throws.filter { it % 4 != trumpSuit }
                if (nonTrumps.isNotEmpty()) {
                    // Подкидываем младший некозырь
                    val cardToThrow = nonTrumps.minByOrNull { it / 4 }!!
                    return MoveInput.DurakAttack(cardToThrow)
                }

                // Козыри для подкидывания: только если у соперника осталась 1 карта и колода пуста
                if (snap.deckCount == 0 && snap.peerHandCount <= 2) {
                    val minTrump = throws.minByOrNull { it / 4 }!!
                    return MoveInput.DurakAttack(minTrump)
                }
            }

            // Все атаки побиты — жмем «Хватит» (бито)
            val allBeaten = snap.tableBeats.all { it >= 0 }
            if (allBeaten) {
                return MoveInput.DurakEnough
            }
        }

        return null
    }
}
