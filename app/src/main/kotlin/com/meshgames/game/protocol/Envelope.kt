package com.meshgames.game.protocol

/**
 * Application envelope — полезная нагрузка игрового события TCP-моста
 * (wrapper `[from][to][envelope]` внутри payload'а кадра моста).
 *
 * Заголовок (12 bytes, little-endian) несёт piggyback-ACK — кумулятивное
 * подтверждение в каждом кадре, чтобы не плодить отдельные эфирные
 * ACK-пакеты (каждый кадр в эфире стоит дайти-цикла LoRa):
 *   [0]      magic 0x47
 *   [1]      version (2)
 *   [2]      type (MsgType)
 *   [3]      flags = 0
 *   [4..5]   sessionId u16 LE
 *   [6..7]   seq u16 LE
 *   [8..9]   cumAck u16 LE — подтверждено всё до seq включительно
 *   [10..11] bitmap u16 LE — бит i = принят seq cumAck+1+i
 *
 * [ACK_NONE] (cumAck = 0xFFFF) — «ничего не подтверждено»: честное значение
 * до первого прихода (rxNext = 0). Приёмник такое подтверждение игнорирует —
 * ложное снятие повторов пира недопустимо. Всё после заголовка —
 * типо-специфичный payload. Иная версия заголовка отвергается: протокол
 * один, старых версий в поле больше нет.
 */
const val ENVELOPE_MAGIC = 0x47
const val ENVELOPE_VERSION = 2
const val ENVELOPE_HEADER_SIZE = 12

/** Wire-сентинел «ничего не подтверждено» (вместо cumAck). */
const val ACK_NONE = 0xFFFF

enum class MsgType(val id: Int) {
    INVITE(1),
    INVITE_RESP(2),
    MOVE(3),
    SHOT(4),
    SHOT_RESULT(5),
    SYNC_REQ(7),
    SYNC_RES(8),
    GAME_OVER(9),
    DRAW_OFFER(10),
    DRAW_RESP(11),
    INVITE_CANCEL(12),
    READY(13),
    /** Игра закрыта с сохранением (payload[0]=1 закрыто / 0 возобновлено). */
    SESSION_CLOSE(14),
    /** Сессия удалена у инициатора — удалить у себя и сообщить пользователю. */
    SESSION_DELETE(15),
    /** Легковесный кадр подтверждения (12 байт, без payload, seq=0) для гашения повторов пира. */
    ACK_ONLY(16),
    /** Составной пакет (несколько envelope'ов в одном эфирном слоте). */
    BATCH(17),
    ;

    companion object {
        fun fromId(id: Int): MsgType? = entries.firstOrNull { it.id == id }
    }
}

data class Envelope(
    val type: MsgType,
    val sessionId: Int,
    val seq: Int,
    val payload: ByteArray = ByteArray(0),
    /** Piggyback-подтверждение (v2): подтверждённые кадры пира, null — без ACK. */
    val cumAck: Int? = null,
    /** Битовая карта принятых кадров выше cumAck (см. [AckPayload]). */
    val bitmap: Int = 0,
) {
    /** true, если кадр несёт piggyback-ACK (v2-заголовок). */
    val hasAck: Boolean get() = cumAck != null

    fun encode(): ByteArray {
        require(sessionId in 0..0xFFFF) { "sessionId out of range: $sessionId" }
        require(seq in 0..0xFFFF) { "seq out of range: $seq" }
        return ByteArray(ENVELOPE_HEADER_SIZE + payload.size).also {
            it[0] = ENVELOPE_MAGIC.toByte()
            it[1] = ENVELOPE_VERSION.toByte()
            it[2] = type.id.toByte()
            it[3] = 0
            it[4] = (sessionId and 0xFF).toByte()
            it[5] = ((sessionId shr 8) and 0xFF).toByte()
            it[6] = (seq and 0xFF).toByte()
            it[7] = ((seq shr 8) and 0xFF).toByte()
            val ack = cumAck ?: ACK_NONE
            it[8] = (ack and 0xFF).toByte()
            it[9] = ((ack shr 8) and 0xFF).toByte()
            it[10] = (bitmap and 0xFF).toByte()
            it[11] = ((bitmap shr 8) and 0xFF).toByte()
            System.arraycopy(payload, 0, it, ENVELOPE_HEADER_SIZE, payload.size)
        }
    }

    companion object {
        /** Returns null for anything that is not a well-formed envelope. */
        fun decode(bytes: ByteArray): Envelope? {
            if (bytes.size < ENVELOPE_HEADER_SIZE) return null
            if ((bytes[0].toInt() and 0xFF) != ENVELOPE_MAGIC) return null
            if ((bytes[1].toInt() and 0xFF) != ENVELOPE_VERSION) return null
            val type = MsgType.fromId(bytes[2].toInt() and 0xFF) ?: return null
            val sessionId = (bytes[4].toInt() and 0xFF) or ((bytes[5].toInt() and 0xFF) shl 8)
            val seq = (bytes[6].toInt() and 0xFF) or ((bytes[7].toInt() and 0xFF) shl 8)
            val rawAck = (bytes[8].toInt() and 0xFF) or ((bytes[9].toInt() and 0xFF) shl 8)
            val bitmap = (bytes[10].toInt() and 0xFF) or ((bytes[11].toInt() and 0xFF) shl 8)
            return Envelope(
                type, sessionId, seq,
                bytes.copyOfRange(ENVELOPE_HEADER_SIZE, bytes.size),
                // «ничего не подтверждено» — не подтверждение, а честный ноль:
                // у приёмника это не снимает ни одного кадра отправителя.
                if (rawAck == ACK_NONE) null else rawAck,
                bitmap,
            )
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Envelope) return false
        return type == other.type && sessionId == other.sessionId &&
            seq == other.seq && cumAck == other.cumAck && bitmap == other.bitmap &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var r = type.hashCode()
        r = 31 * r + sessionId
        r = 31 * r + seq
        r = 31 * r + (cumAck ?: -1)
        r = 31 * r + bitmap
        r = 31 * r + payload.contentHashCode()
        return r
    }
}

/** Piggyback-подтверждение: cumulative ack + 16-bit bitmap of out-of-order packets above it. */
data class AckPayload(val cumAck: Int, val bitmap: Int)

/** SYNC_RES payload: peer's cumulative ack, whose turn, session status, state digest. */
data class SyncResPayload(val cumAck: Int, val turn: Int, val status: Int, val digest: Int) {
    fun encode(): ByteArray = byteArrayOf(
        (cumAck and 0xFF).toByte(), ((cumAck shr 8) and 0xFF).toByte(),
        turn.toByte(), status.toByte(),
        (digest and 0xFF).toByte(), ((digest shr 8) and 0xFF).toByte(),
    )

    companion object {
        fun decode(b: ByteArray): SyncResPayload? {
            if (b.size < 6) return null
            return SyncResPayload(
                (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8),
                b[2].toInt() and 0xFF,
                b[3].toInt() and 0xFF,
                (b[4].toInt() and 0xFF) or ((b[5].toInt() and 0xFF) shl 8),
            )
        }
    }
}

data class GameOverPayload(val winner: Int, val reason: Int) {
    fun encode(): ByteArray = byteArrayOf(winner.toByte(), reason.toByte())

    companion object {
        fun decode(b: ByteArray): GameOverPayload? {
            if (b.size < 2) return null
            return GameOverPayload(b[0].toInt() and 0xFF, b[1].toInt() and 0xFF)
        }
    }
}

/**
 * INVITE payload: `[gameTypeId, inviterSide]` или `[gameTypeId, inviterSide, difficulty]`.
 * Второй байт — сторона инициатора (0 = A, 1 = B).
 * Третий байт (опциональный) — уровень сложности (0 = дефолт, 1 = лёгкий, 2 = средний, 3 = сложный).
 * INVITE строго от 2 байт; однобайтовый (ранняя версия протокола) не принимается.
 */
data class InvitePayload(
    val gameTypeId: Int,
    val inviterSide: Int = 0,
    val difficulty: Int = 0,
) {
    fun encode(): ByteArray = if (difficulty > 0) {
        byteArrayOf(gameTypeId.toByte(), inviterSide.toByte(), difficulty.toByte())
    } else {
        byteArrayOf(gameTypeId.toByte(), inviterSide.toByte())
    }

    companion object {
        fun decode(b: ByteArray): InvitePayload? {
            if (b.size < 2) return null
            val diff = if (b.size >= 3) b[2].toInt() and 0xFF else 0
            return InvitePayload(b[0].toInt() and 0xFF, b[1].toInt() and 1, diff)
        }
    }
}

/** INVITE_RESP payload: 1 = accepted, 0 = declined. */
data class InviteRespPayload(val accepted: Boolean) {
    fun encode(): ByteArray = byteArrayOf(if (accepted) 1 else 0)

    companion object {
        fun decode(b: ByteArray): InviteRespPayload? {
            if (b.isEmpty()) return null
            return InviteRespPayload(b[0].toInt() != 0)
        }
    }
}

/** DRAW_RESP payload: 1 = accepted, 0 = declined. */
data class DrawRespPayload(val accepted: Boolean) {
    fun encode(): ByteArray = byteArrayOf(if (accepted) 1 else 0)

    companion object {
        fun decode(b: ByteArray): DrawRespPayload? {
            if (b.isEmpty()) return null
            return DrawRespPayload(b[0].toInt() != 0)
        }
    }
}

/**
 * Хвост канонического журнала ходов — сверка «последних ходов» при
 * расхождении состояний (восстановление партии вместо SYNC_CONFLICT).
 *
 * Формат (LE):
 * ```
 * [u16 digest]              — дайджест движка отправителя сейчас
 * [u8 count]                — сколько записей в хвосте (≤ MAX_ENTRIES)
 * count × [u16 index][u32 chain][u16 digest]
 * ```
 *
 * Вшивается (1) в payload `SYNC_REQ` целиком и (2) в хвост `SYNC_RES` после
 * его 6-байтового ядра (смещение [SYNC_RES_TAIL_OFFSET]; дайджест дублируется
 * — у пира он уже в ядре). Хвост опционален: `SyncResPayload.decode`
 * игнорирует лишние байты — формат расширяется без нового MsgType.
 */
data class JournalTailPayload(val digest: Int, val entries: List<TailEntry>) {
    /** Одна запись хвоста: индекс, цепная сумма префикса, дайджест после неё. */
    data class TailEntry(val index: Int, val chain: Int, val digest: Int)

    fun encode(): ByteArray {
        val n = minOf(entries.size, MAX_ENTRIES)
        val out = ByteArray(3 + n * 8)
        out[0] = (digest and 0xFF).toByte()
        out[1] = ((digest shr 8) and 0xFF).toByte()
        out[2] = n.toByte()
        var o = 3
        for (e in entries.take(n)) {
            out[o] = (e.index and 0xFF).toByte()
            out[o + 1] = ((e.index shr 8) and 0xFF).toByte()
            out[o + 2] = (e.chain and 0xFF).toByte()
            out[o + 3] = ((e.chain shr 8) and 0xFF).toByte()
            out[o + 4] = ((e.chain shr 16) and 0xFF).toByte()
            out[o + 5] = ((e.chain shr 24) and 0xFF).toByte()
            out[o + 6] = (e.digest and 0xFF).toByte()
            out[o + 7] = ((e.digest shr 8) and 0xFF).toByte()
            o += 8
        }
        return out
    }

    companion object {
        /** Максимум записей в хвосте: 3 + 12×8 = 99 байт ≤ 200 байт payload. */
        const val MAX_ENTRIES = 12

        /** Смещение хвоста в payload SYNC_RES (после 6 байт ядра). */
        const val SYNC_RES_TAIL_OFFSET = 6

        /** null — хвоста нет (битые байты). */
        fun decode(b: ByteArray): JournalTailPayload? {
            if (b.size < 3) return null
            val digest = (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)
            val n = minOf(b[2].toInt() and 0xFF, MAX_ENTRIES)
            if (b.size < 3 + n * 8) return null
            val entries = ArrayList<TailEntry>(n)
            var o = 3
            repeat(n) {
                val index = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
                val chain = (b[o + 2].toInt() and 0xFF) or
                    ((b[o + 3].toInt() and 0xFF) shl 8) or
                    ((b[o + 4].toInt() and 0xFF) shl 16) or
                    ((b[o + 5].toInt() and 0xFF) shl 24)
                val d = (b[o + 6].toInt() and 0xFF) or ((b[o + 7].toInt() and 0xFF) shl 8)
                entries.add(TailEntry(index, chain, d))
                o += 8
            }
            return JournalTailPayload(digest, entries)
        }
    }
}
