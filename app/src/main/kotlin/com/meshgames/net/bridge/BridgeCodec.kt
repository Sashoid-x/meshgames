package com.meshgames.net.bridge

import java.io.IOException

/**
 * Кадры TCP-моста MeshGames ↔ форк Meshtastic-Android («Mesh Bridge»,
 * 127.0.0.1:8090, portnum моста (настраивается в форке, дефолт 69
 * SIMULATOR_APP), wantAck=false — firmware-
 * ретрансмитов нет, всю надёжность несёт наша ретрансляция).
 *
 * Кадр в TCP-потоке:
 * ```
 * [u32 LE len][u32 from LE][u32 to LE][body…]   len = 8 + body.size
 * ```
 * Мост при отправке в эфир подставляет свои заголовки LoRa (broadcast),
 * обратно возвращает `from`/`to` MeshPacket поверх payload'а — но
 * адресация MeshGames живёт во wrapper'е [from][to] внутри payload:
 * clientId (случайный u32) ≠ номер узла LoRa, мост о нём не знает.
 *
 * body[0] — магический байт: 0x47 — игровой envelope (его собственный
 * стартовый байт), 0x50 — presence; 0x49 — HELLO (мост → клиент: номер
 * и имя своей ноды, см. [helloFrame]/[parseHello]). ping — кадр len=0
 * (keepalive TCP).
 */
object BridgeCodec {
    const val DEFAULT_HOST = "127.0.0.1"
    const val DEFAULT_PORT = 8090

    /** Заголовок wrapper'а: [u32 from][u32 to]. */
    const val WRAPPER_OVERHEAD = 8

    /** Лимит wrapper'а — payload моста (Data proto 233 − служебные байты). */
    const val MAX_WRAPPER_BYTES = 220

    /** Максимум кадра на приём: [len][wrapper с лимитом Data proto 233]. */
    const val MAX_FRAME_BYTES = 4 + WRAPPER_OVERHEAD + 233

    const val GAME_MAGIC = 0x47
    const val PRESENCE_MAGIC = 0x50
    const val PRESENCE_VERSION = 1
    /** Служебный кадр мост → клиент: номер и имя своей ноды (HELLO). */
    const val HELLO_MAGIC = 0x49
    const val HELLO_VERSION = 1
    const val MAX_NAME_BYTES = 32

    /** Широковещательный кадр результата завершенной игры для сервера статистики. */
    const val STAT_MAGIC = 0x53 // 'S'
    const val STAT_VERSION = 1
    const val STAT_BODY_BYTES = 15

    /** Кадр ping (len=0) — безвредный keepalive для соединения с мостом. */
    fun pingFrame(): ByteArray = ByteArray(4)

    /** Игровой кадр `[len][from][to][envelope]`; null — envelope не влезает. */
    fun gameFrame(from: Int, to: Int, envelope: ByteArray): ByteArray? {
        val bodyLen = WRAPPER_OVERHEAD + envelope.size
        if (bodyLen > MAX_WRAPPER_BYTES) return null
        val out = ByteArray(4 + bodyLen)
        writeU32(out, 0, bodyLen)
        writeU32(out, 4, from)
        writeU32(out, 8, to)
        envelope.copyInto(out, 12)
        return out
    }

    /**
     * Presence-кадр `[len][id][0][0x50][ver][flags][nameLen][name…]`.
     * flags: bit 0 = busy, bit 1 = isBot.
     * Имя обрезается по [MAX_NAME_BYTES] без разрыва multibyte-последовательности.
     */
    fun presenceFrame(clientId: Int, name: String, busy: Boolean, isBot: Boolean = false): ByteArray {
        val raw = nameTruncated(name, MAX_NAME_BYTES)
        val bodyLen = 4 + raw.size
        val out = ByteArray(4 + WRAPPER_OVERHEAD + bodyLen)
        writeU32(out, 0, WRAPPER_OVERHEAD + bodyLen)
        writeU32(out, 4, clientId)
        writeU32(out, 8, 0)
        out[12] = PRESENCE_MAGIC.toByte()
        out[13] = PRESENCE_VERSION.toByte()
        val flags = (if (busy) 1 else 0) or (if (isBot) 2 else 0)
        out[14] = flags.toByte()
        out[15] = raw.size.toByte()
        raw.copyInto(out, 16)
        return out
    }

    /**
     * HELLO-кадр мост → клиент (для тестов и фейкового моста; форк шлёт
     * эквивалент по своему контракту):
     * `[len][loraFrom=0][loraTo=0][from=0][to=0][0x49][ver][nodeId u32][nameLen][name…]`.
     * Лора-заголовок из нулей обязателен: старый APK игнорирует
     * неизвестный magic-байт, но кадр короче минимума (len < 17) счёл бы
     * повреждённым и разорвал соединение.
     */
    fun helloFrame(nodeId: Int, name: String): ByteArray {
        val raw = nameTruncated(name, MAX_NAME_BYTES)
        val bodyLen = 7 + raw.size
        val out = ByteArray(4 + 16 + bodyLen) // len + LoRa-заголовок + body
        writeU32(out, 0, 16 + bodyLen)
        out[20] = HELLO_MAGIC.toByte()
        out[21] = HELLO_VERSION.toByte()
        writeU32(out, 22, nodeId)
        out[26] = raw.size.toByte()
        raw.copyInto(out, 27)
        return out
    }

    /** Разбор HELLO-body (body[0] уже проверен на magic). null — битый. */
    fun parseHello(body: ByteArray): Hello? {
        if (body.size < 7) return null
        if ((body[1].toInt() and 0xFF) != HELLO_VERSION) return null
        val nodeId = readU32(body, 2)
        // Зарезервированные номера (0, broadcast 0xFFFFFFFF, NODENUM_MAX
        // 0xFFFFFFFE) идентичностью быть не могут.
        if (nodeId == 0 || nodeId == -1 || nodeId == -2) return null
        val nameLen = body[6].toInt() and 0xFF
        if (body.size < 7 + nameLen) return null
        return Hello(nodeId, String(body, 7, nameLen, Charsets.UTF_8))
    }

    /** Содержимое HELLO-body: номер и имя своей ноды (для тестов/логов). */
    data class Hello(val nodeId: Int, val name: String)

    /** Имя → UTF-8 с обрезкой по лимиту без разрыва multibyte-символа. */
    private fun nameTruncated(name: String, max: Int): ByteArray {
        val raw = name.toByteArray(Charsets.UTF_8)
        var len = minOf(raw.size, max)
        // Не оставлять висеть continuation-байт обрезанного UTF-8-символа.
        while (len > 0 && len < raw.size && (raw[len].toInt() and 0xC0) == 0x80) len--
        return raw.copyOf(len)
    }

    /** Разбор wrapper'а payload'а кадра: (from, to, body). null — битый. */
    fun unwrap(payload: ByteArray): Triple<Int, Int, ByteArray>? {
        if (payload.size <= WRAPPER_OVERHEAD) return null
        return Triple(
            readU32(payload, 0),
            readU32(payload, 4),
            payload.copyOfRange(WRAPPER_OVERHEAD, payload.size),
        )
    }

    /** Разбор presence-body (body[0] уже проверен на magic). null — битый. */
    fun parsePresence(body: ByteArray): Presence? {
        if (body.size < 4) return null
        if ((body[1].toInt() and 0xFF) != PRESENCE_VERSION) return null
        val flags = body[2].toInt() and 0xFF
        val nameLen = body[3].toInt() and 0xFF
        if (body.size < 4 + nameLen) return null
        return Presence(
            busy = (flags and 1) != 0,
            isBot = (flags and 2) != 0,
            name = String(body, 4, nameLen, Charsets.UTF_8),
        )
    }

    /** Содержимое presence-body (для тестов/логов). */
    data class Presence(val busy: Boolean, val name: String, val isBot: Boolean = false)

    /**
     * Кодирует широковещательный body результата завершенной игры (15 байт):
     * [0]=0x53, [1]=1, [2]=gameTypeId, [3..4]=sessionId LE, [5]=winner (0=A, 1=B, 2=ничья),
     * [6]=reason (ResultReason ordinal), [7..10]=playerA LE, [11..14]=playerB LE.
     */
    fun encodeStat(
        gameTypeId: Int,
        sessionId: Int,
        winner: Int,
        reason: Int,
        playerA: Int,
        playerB: Int,
    ): ByteArray {
        val out = ByteArray(STAT_BODY_BYTES)
        out[0] = STAT_MAGIC.toByte()
        out[1] = STAT_VERSION.toByte()
        out[2] = (gameTypeId and 0xFF).toByte()
        out[3] = (sessionId and 0xFF).toByte()
        out[4] = ((sessionId ushr 8) and 0xFF).toByte()
        out[5] = (winner and 0xFF).toByte()
        out[6] = (reason and 0xFF).toByte()
        writeU32(out, 7, playerA)
        writeU32(out, 11, playerB)
        return out
    }

    /** Разбор stat-body (15 байт). null — битый или неизвестная версия. */
    fun parseStat(body: ByteArray): GameStat? {
        if (body.size < STAT_BODY_BYTES) return null
        if ((body[0].toInt() and 0xFF) != STAT_MAGIC) return null
        if ((body[1].toInt() and 0xFF) != STAT_VERSION) return null
        val gameTypeId = body[2].toInt() and 0xFF
        val sessionId = (body[3].toInt() and 0xFF) or ((body[4].toInt() and 0xFF) shl 8)
        val winner = body[5].toInt() and 0xFF
        val reason = body[6].toInt() and 0xFF
        val playerA = readU32(body, 7)
        val playerB = readU32(body, 11)
        return GameStat(gameTypeId, sessionId, winner, reason, playerA, playerB)
    }

    /** Содержимое stat-body: результат завершенной партии для сервера статистики. */
    data class GameStat(
        val gameTypeId: Int,
        val sessionId: Int,
        val winner: Int, // 0 = Side.A, 1 = Side.B, 2 = Draw
        val reason: Int, // ResultReason ordinal
        val playerA: Int,
        val playerB: Int,
    )

    internal fun writeU32(a: ByteArray, off: Int, v: Int) {
        a[off] = (v and 0xFF).toByte()
        a[off + 1] = ((v ushr 8) and 0xFF).toByte()
        a[off + 2] = ((v ushr 16) and 0xFF).toByte()
        a[off + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    internal fun readU32(a: ByteArray, off: Int): Int =
        (a[off].toInt() and 0xFF) or
            ((a[off + 1].toInt() and 0xFF) shl 8) or
            ((a[off + 2].toInt() and 0xFF) shl 16) or
            ((a[off + 3].toInt() and 0xFF) shl 24)
}

/**
 * Сборка кадров из TCP-потока: read() ≠ кадр. append() копит буфер и
 * возвращает все целые кадры; пинги (len=0) пропускаются; кадр за пределами
 * лимитов — IOException (мост так не шлёт — значит поток повреждён,
 * соединение надо закрывать).
 *
 * [hasLoraHeader] = true (клиентские кадры от моста): после [len] идут
 * `u32 loraFrom`/`u32 loraTo`, payload — wrapper. false (кадры, которые
 * клиент шлёт мосту): wrapper сразу за [len] — используется фейковым
 * сервером в тестах.
 */
class BridgeFrameBuffer(private val hasLoraHeader: Boolean = true) {
    private var buf = ByteArray(0)

    data class RawFrame(val loraFrom: Int, val loraTo: Int, val payload: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is RawFrame && loraFrom == other.loraFrom && loraTo == other.loraTo &&
                payload.contentEquals(other.payload)

        override fun hashCode(): Int {
            var r = loraFrom
            r = 31 * r + loraTo
            r = 31 * r + payload.contentHashCode()
            return r
        }
    }

    fun append(chunk: ByteArray): List<RawFrame> {
        buf += chunk
        val out = ArrayList<RawFrame>()
        // LoRa-заголовок [from][to] есть только в кадрах от моста; клиентские
        // кадры — wrapper сразу за [len] (его разбирает BridgeCodec.unwrap).
        val hdr = if (hasLoraHeader) 8 else 0
        var off = 0
        while (buf.size - off >= 4) {
            val len = BridgeCodec.readU32(buf, off)
            if (len == 0) {
                off += 4 // ping
                continue
            }
            if (len < hdr + BridgeCodec.WRAPPER_OVERHEAD + 1) {
                throw IOException("мост: кадр $len B < минимума")
            }
            if (len > MAX_WRAPPER_BYTES_LIMIT) {
                throw IOException("мост: кадр $len B > лимита $MAX_WRAPPER_BYTES_LIMIT")
            }
            if (buf.size - off - 4 < len) break // кадр не дочитан — ждём хвост
            val start = off + 4
            out += RawFrame(
                loraFrom = if (hasLoraHeader) BridgeCodec.readU32(buf, start) else 0,
                loraTo = if (hasLoraHeader) BridgeCodec.readU32(buf, start + 4) else 0,
                payload = buf.copyOfRange(start + hdr, start + len),
            )
            off = start + len
        }
        buf = if (off == 0) buf else buf.copyOfRange(off, buf.size)
        return out
    }

    companion object {
        private const val MAX_WRAPPER_BYTES_LIMIT = BridgeCodec.MAX_WRAPPER_BYTES
    }
}
