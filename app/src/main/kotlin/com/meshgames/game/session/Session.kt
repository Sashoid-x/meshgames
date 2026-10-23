package com.meshgames.game.session

import com.meshgames.game.GameResult
import com.meshgames.game.GameType
import com.meshgames.game.Side
import kotlinx.serialization.Serializable

/** Статусы игровой сессии. */
@Serializable
enum class SessionStatus {
    /** Я отправил приглашение, жду ответа. */
    INVITING,

    /** Мне пришло приглашение, жду решения пользователя. */
    INVITED,

    /** Морской бой: расстановка кораблей (ждём READY от обоих). */
    PLACEMENT,

    /** Партия идёт. */
    ACTIVE,

    /** Приглашение отклонено (получатель). */
    DECLINED,

    /** Приглашение отклонено (инициатор) / отменено. */
    CANCELLED,

    /** Партия завершена. */
    FINISHED,
}

/** Один неподтверждённый кадр (для восстановления после рестарта). */
@Serializable
data class PendingFrame(
    val type: Int,
    val seq: Int,
    val payload: ByteArray,
    /** Сколько раз кадр уже переотправлен (не сбрасывается рестартом). */
    val attempts: Int = 0,
) {
    override fun equals(other: Any?): Boolean =
        other is PendingFrame && type == other.type && seq == other.seq &&
            attempts == other.attempts && payload.contentEquals(other.payload)

    override fun hashCode(): Int = 31 * (31 * (31 * type + seq) + attempts) + payload.contentHashCode()
}

/**
 * Полностью сериализуемое состояние сессии: движок + надёжный канал.
 * Восстанавливается с диска → прерванную игру можно продолжить.
 */
@Serializable
data class GameSession(
    val sessionId: Int,
    val gameId: GameType,
    val mySide: Side,
    val peerNode: Int,
    val status: SessionStatus,
    /** Состояние движка ([GameEngine.encodeState]). */
    val engineState: ByteArray = ByteArray(0),
    /** Счётчики надёжного канала (направление: я → соперник). */
    val nextSeq: Int = 0,
    /** Ожидаемый seq от соперника. */
    val rxNext: Int = 0,
    val unacked: List<PendingFrame> = emptyList(),
    /** Подтвердил ли я расстановку (морской бой). */
    val myReady: Boolean = false,
    /** Подтвердил ли соперник расстановку. */
    val peerReady: Boolean = false,
    /** Последний ответ на INVITE (для повторных INVITE от того же seq). */
    val inviteAnswered: Boolean = false,
    val inviteAccepted: Boolean = false,
    /** Предложена ли ничья (мной или соперником). */
    val drawOfferByPeer: Boolean = false,
    val drawOfferByMe: Boolean = false,
    /** Игра закрыта с сохранением (мной или соперником) — сессия возобновляема. */
    val closed: Boolean = false,
    val result: GameResult? = null,
    val updatedAtMs: Long = 0,
    /** Уровень сложности (0 = дефолт/человек, 1 = лёгкий, 2 = средний, 3 = сложный). */
    val difficulty: Int = 0,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GameSession) return false
        return sessionId == other.sessionId && gameId == other.gameId &&
            mySide == other.mySide && peerNode == other.peerNode &&
            status == other.status && engineState.contentEquals(other.engineState) &&
            nextSeq == other.nextSeq && rxNext == other.rxNext &&
            unacked == other.unacked && myReady == other.myReady &&
            peerReady == other.peerReady && inviteAnswered == other.inviteAnswered &&
            inviteAccepted == other.inviteAccepted && drawOfferByPeer == other.drawOfferByPeer &&
            drawOfferByMe == other.drawOfferByMe && closed == other.closed &&
            result == other.result && updatedAtMs == other.updatedAtMs
    }

    override fun hashCode(): Int = sessionId
}

/** Представление сессии для UI. */
data class SessionView(
    val session: GameSession,
    /** Снимок доски (null, пока движок не создан). */
    val snapshot: com.meshgames.game.BoardSnapshot? = null,
    /** Есть ли ожидающие переотправки кадры (индикатор связи). */
    val hasPending: Boolean = false,
)
