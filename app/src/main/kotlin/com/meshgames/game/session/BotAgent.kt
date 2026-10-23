package com.meshgames.game.session

import com.meshgames.debug.DebugLog

/**
 * Тестовый бот (настройка «Тестовый бот»).
 *
 * Назначение — полевая проверка радиосвязи: телефон с включённым ботом
 * остаётся на месте, человек уходит на расстояние и играет с него. Бот
 * принимает все входящие приглашения и запросы (ничью, вердикт спора
 * балды), сам игры не инициирует, а в начатую партию ходит по правилам —
 * ход подбирает движок ([GameEngine.botMove]), сложность не нужна.
 *
 * Шагает раз в секунду из общего тика AppGraph ([SessionManager.tick]),
 * после чего менеджер. Один шаг — одно действие на сессию: так нет
 * re-entrancy (тик держит монитор менеджера, пока тот разгребает кадры),
 * а секундная задержка на ответ приглашению/ходу на радиоканале не заметна.
 *
 * События UI подавляются на стороне бота (диалог приглашения, уведомление,
 * диалог ничьей/спор) — он отвечает сам, ему не за чем спрашивать человека.
 */
class BotAgent(
    private val manager: SessionManager,
    private val enabled: () -> Boolean,
) {

    /** Один шаг: пройтись по снимку сессий, по одному действию на сессию. */
    fun step() {
        if (!enabled()) return
        // Снимок immutable — менеджер отдаёт копию списка, итерация безопасна.
        for (view in manager.sessions_.value) {
            val s = view.session
            try {
                when (s.status) {
                    // На приглашение отвечаем сразу и всегда «да».
                    SessionStatus.INVITED -> {
                        DebugLog.d("BOT", "sid=${s.sessionId} принимаю приглашение (${s.gameId.title})")
                        manager.respondInvite(s.sessionId, true)
                    }

                    // Морской бой: расстановка уже случайная в движке — сразу готов.
                    SessionStatus.PLACEMENT -> {
                        if (!s.myReady) {
                            DebugLog.d("BOT", "sid=${s.sessionId} расстановка подтверждена")
                            manager.ready(s.sessionId)
                        }
                    }

                    SessionStatus.ACTIVE -> stepActive(s)

                    // INVITING (своё приглашение — бот не шлёт), FINISHED,
                    // DECLINED, CANCELLED и закрытые сессии — действий нет.
                    else -> Unit
                }
            } catch (e: Exception) {
                // Бот не должен ронять общий тик — сообщаем и идём дальше.
                DebugLog.d("BOT", "ошибка sid=${s.sessionId}: $e")
            }
        }
    }

    /** Действия в активной партии: спор → ничья → свой ход (по одному за шаг). */
    private fun stepActive(s: GameSession) {
        if (s.closed) return
        val sid = s.sessionId
        when {
            // Балда: спорное слово соперника принимаем молча, без диалога.
            manager.pendingVerdict(sid) -> {
                DebugLog.d("BOT", "sid=$sid спорное слово — принимаю")
                manager.submitVerdict(sid, true)
            }

            // Ничью от соперника принимаем тоже сразу.
            s.drawOfferByPeer -> {
                DebugLog.d("BOT", "sid=$sid принимаю ничью")
                manager.respondDraw(sid, true)
            }

            // Свой ход — движок подберёт легальный (null — ходить нечего:
            // чужой ход, выстрел в полёте, конец партии).
            manager.submitBotMove(sid) -> DebugLog.d("BOT", "sid=$sid ход отправлен")
        }
    }
}
