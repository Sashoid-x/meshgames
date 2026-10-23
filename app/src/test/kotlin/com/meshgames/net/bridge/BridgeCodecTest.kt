package com.meshgames.net.bridge

import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeCodecTest {

    // ------------------------------------------------------------ game ---

    @Test
    fun gameFrameRoundTrip() {
        val env = byteArrayOf(0x47, 0x02, 0x03, 0x04)
        val frame = BridgeCodec.gameFrame(from = 0x12345678, to = 0x0A0B0C0D, envelope = env)!!
        val frames = BridgeFrameBuffer(hasLoraHeader = false).append(frame)
        assertEquals(1, frames.size)
        val (from, to, body) = BridgeCodec.unwrap(frames[0].payload)!!
        assertEquals(0x12345678, from)
        assertEquals(0x0A0B0C0D, to)
        // body[0] — магический байт самого envelope (envelope и есть body)
        assertEquals(BridgeCodec.GAME_MAGIC, body[0].toInt() and 0xFF)
        assertArrayEquals(env, body)
    }

    @Test
    fun gameFrameRejectsOversizedEnvelope() {
        // wrapper = 8 + envelope ≤ 220 → envelope ≤ 212
        assertNull(BridgeCodec.gameFrame(1, 2, ByteArray(213)))
        assertNotNull(BridgeCodec.gameFrame(1, 2, ByteArray(212)))
    }

    @Test
    fun gameFrameBroadcastTo() {
        val frame = BridgeCodec.gameFrame(7, 0, ByteArray(4) { 1 })!!
        val (from, to, _) = BridgeCodec.unwrap(BridgeFrameBuffer(hasLoraHeader = false).append(frame)[0].payload)!!
        assertEquals(7, from)
        assertEquals(0, to)
    }

    // --------------------------------------------------------- presence ---

    @Test
    fun presenceFrameRoundTrip() {
        val frame = BridgeCodec.presenceFrame(0x7BEEF, "Алиса", busy = true)
        val raw = BridgeFrameBuffer(hasLoraHeader = false).append(frame).single()
        val (from, _, body) = BridgeCodec.unwrap(raw.payload)!!
        assertEquals(0x7BEEF, from)
        assertEquals(BridgeCodec.PRESENCE_MAGIC, body[0].toInt() and 0xFF)
        val p = BridgeCodec.parsePresence(body)!!
        assertEquals(true, p.busy)
        assertEquals("Алиса", p.name)
    }

    @Test
    fun presenceNameTruncatedToLimit() {
        val long = "Ю".repeat(50) // 100 байт UTF-8
        val raw = BridgeFrameBuffer(hasLoraHeader = false)
            .append(BridgeCodec.presenceFrame(1, long, busy = false)).single()
        val (_, _, body) = BridgeCodec.unwrap(raw.payload)!!
        val nameLen = body[3].toInt() and 0xFF
        assertEquals(BridgeCodec.MAX_NAME_BYTES, nameLen)
        val p = BridgeCodec.parsePresence(body)!!
        // multibyte не разорван: имя декодируется и укорочено
        assertTrue(p.name.startsWith("ЮЮ"))
        assertEquals(true, p.name.length < 50)
    }

    @Test
    fun presenceEmptyNameAllowed() {
        val raw = BridgeFrameBuffer(hasLoraHeader = false)
            .append(BridgeCodec.presenceFrame(2, "", busy = false)).single()
        val p = BridgeCodec.parsePresence(BridgeCodec.unwrap(raw.payload)!!.third)!!
        assertEquals("", p.name)
        assertEquals(false, p.busy)
        assertEquals(false, p.isBot)
    }

    @Test
    fun presenceBotFlagRoundTrip() {
        val frame = BridgeCodec.presenceFrame(0x5555, "MeshBot", busy = false, isBot = true)
        val raw = BridgeFrameBuffer(hasLoraHeader = false).append(frame).single()
        val p = BridgeCodec.parsePresence(BridgeCodec.unwrap(raw.payload)!!.third)!!
        assertEquals(true, p.isBot)
        assertEquals(false, p.busy)
        assertEquals("MeshBot", p.name)
    }

    @Test
    fun presenceRejectsWrongVersionAndTruncation() {
        // ver = 2 → не наша версия
        val wrongVer = byteArrayOf(0x50, 0x02, 0x00, 0x00)
        assertNull(BridgeCodec.parsePresence(wrongVer))
        // nameLen заявлен больше фактического
        val truncated = byteArrayOf(0x50, 0x01, 0x00, 0x05, 0x41)
        assertNull(BridgeCodec.parsePresence(truncated))
        // короче минимального заголовка
        assertNull(BridgeCodec.parsePresence(byteArrayOf(0x50, 0x01)))
    }

    // ----------------------------------------------------------- hello ---

    @Test
    fun helloFrameGoldenBytes() {
        // Формат мост → клиент: [len][loraFrom=0][loraTo=0][from=0][to=0]
        //   [0x49][ver][nodeId u32 LE][nameLen][name…]
        val frame = BridgeCodec.helloFrame(0x12345678, "Саша") // "Саша" = 8 B UTF-8
        assertEquals(35, frame.size)
        // len = 16 (LoRa+wrapper) + 7 (заголовок body) + 8 (имя)
        assertEquals(31, BridgeCodec.readU32(frame, 0))
        // LoRa-from/to и from/to — нули (иначе старый APK оборвёт связь)
        assertEquals(0, BridgeCodec.readU32(frame, 4))
        assertEquals(0, BridgeCodec.readU32(frame, 8))
        assertEquals(0, BridgeCodec.readU32(frame, 12))
        assertEquals(0, BridgeCodec.readU32(frame, 16))
        assertEquals(0x49, frame[20].toInt() and 0xFF)
        assertEquals(1, frame[21].toInt() and 0xFF)
        assertEquals(0x12345678, BridgeCodec.readU32(frame, 22))
        assertEquals(8, frame[26].toInt() and 0xFF)
        assertEquals("Саша", String(frame, 27, 8, Charsets.UTF_8))
    }

    @Test
    fun helloRoundTripThroughLoraBuffer() {
        // Старый APK: кадр парсится целиком и молча игнорируется (ветка else);
        // новый — видит номер и имя ноды. Обе проверки — на одних байтах.
        val frame = BridgeCodec.helloFrame(777, "Узел-Ло")
        val raw = BridgeFrameBuffer(hasLoraHeader = true).append(frame).single()
        assertEquals(0, raw.loraFrom)
        assertEquals(0, raw.loraTo)
        val (from, to, body) = BridgeCodec.unwrap(raw.payload)!!
        assertEquals(0, from)
        assertEquals(0, to)
        assertEquals(BridgeCodec.HELLO_MAGIC, body[0].toInt() and 0xFF)
        val h = BridgeCodec.parseHello(body)!!
        assertEquals(777, h.nodeId)
        assertEquals("Узел-Ло", h.name)
    }

    @Test
    fun helloNameTruncatedToLimit() {
        val frame = BridgeCodec.helloFrame(9, "Ю".repeat(50)) // 100 B UTF-8
        val raw = BridgeFrameBuffer(hasLoraHeader = true).append(frame).single()
        val body = BridgeCodec.unwrap(raw.payload)!!.third
        assertEquals(BridgeCodec.MAX_NAME_BYTES, body[6].toInt() and 0xFF)
        val h = BridgeCodec.parseHello(body)!!
        assertTrue(h.name.startsWith("ЮЮ"))
        assertTrue(h.name.length < 50) // multibyte не разорван — декодируется
    }

    @Test
    fun parseHelloRejectsGarbage() {
        // версия 2 — не наша
        assertNull(BridgeCodec.parseHello(byteArrayOf(0x49, 0x02, 1, 0, 0, 0, 0)))
        // nameLen заявлен больше фактического
        assertNull(BridgeCodec.parseHello(byteArrayOf(0x49, 0x01, 1, 0, 0, 0, 5, 0x41)))
        // короче минимального заголовка
        assertNull(BridgeCodec.parseHello(byteArrayOf(0x49, 0x01)))
        // зарезервированные номера нод не могут быть идентичностью
        for (bad in listOf(0, -1, -2)) {
            val body = ByteArray(7)
            body[0] = 0x49
            body[1] = 0x01
            BridgeCodec.writeU32(body, 2, bad)
            assertNull("nodeId=$bad", BridgeCodec.parseHello(body))
        }
    }

    // ----------------------------------------------------------- буфер ---

    @Test
    fun bufferAssemblesPartialReads() {
        val frame = BridgeCodec.presenceFrame(3, "Боб", busy = false)
        val buf = BridgeFrameBuffer(hasLoraHeader = false)
        var out = emptyList<BridgeFrameBuffer.RawFrame>()
        for (b in frame) {
            out = buf.append(byteArrayOf(b))
        }
        assertEquals(1, out.size)
        assertEquals("Боб", BridgeCodec.parsePresence(BridgeCodec.unwrap(out[0].payload)!!.third)!!.name)
    }

    @Test
    fun bufferReturnsAllFramesFromOneChunk() {
        val f1 = BridgeCodec.gameFrame(1, 0, byteArrayOf(0x47, 1))
        val f2 = BridgeCodec.gameFrame(2, 0, byteArrayOf(0x47, 2))
        val both = f1!! + f2!!
        val frames = BridgeFrameBuffer(hasLoraHeader = false).append(both)
        assertEquals(2, frames.size)
        assertEquals(1, BridgeCodec.unwrap(frames[0].payload)!!.first)
        assertEquals(2, BridgeCodec.unwrap(frames[1].payload)!!.first)
    }

    @Test
    fun bufferSkipsPingFrames() {
        val ping = BridgeCodec.pingFrame()
        val game = BridgeCodec.gameFrame(5, 0, byteArrayOf(0x47, 9))!!
        val frames = BridgeFrameBuffer(hasLoraHeader = false).append(ping + game)
        assertEquals(1, frames.size)
        assertEquals(5, BridgeCodec.unwrap(frames[0].payload)!!.first)
    }

    @Test(expected = IOException::class)
    fun bufferRejectsOverlongFrame() {
        val bad = ByteArray(4)
        BridgeCodec.writeU32(bad, 0, BridgeCodec.MAX_FRAME_BYTES + 1)
        BridgeFrameBuffer(hasLoraHeader = false).append(bad)
    }

    @Test(expected = IOException::class)
    fun bufferRejectsUndersizedFrame() {
        val bad = ByteArray(4)
        BridgeCodec.writeU32(bad, 0, 5) // < wrapper overhead
        BridgeFrameBuffer(hasLoraHeader = false).append(bad)
    }

    @Test
    fun unwrapRejectsShortPayload() {
        assertNull(BridgeCodec.unwrap(ByteArray(8))) // ровно заголовок, body пуст
        assertNull(BridgeCodec.unwrap(ByteArray(4)))
    }

    // ------------------------------------------------------------ stat ---

    @Test
    fun statFrameRoundTrip() {
        val encoded = BridgeCodec.encodeStat(
            gameTypeId = 3, // Chess
            sessionId = 42123,
            winner = 0, // Side.A
            reason = 2, // CHECKMATE
            playerA = 0x5EA1F654,
            playerB = 0x060D05FA,
        )
        assertEquals(15, encoded.size)
        assertEquals(BridgeCodec.STAT_MAGIC, encoded[0].toInt() and 0xFF)
        assertEquals(BridgeCodec.STAT_VERSION, encoded[1].toInt() and 0xFF)

        val parsed = BridgeCodec.parseStat(encoded)
        assertNotNull(parsed)
        assertEquals(3, parsed!!.gameTypeId)
        assertEquals(42123, parsed.sessionId)
        assertEquals(0, parsed.winner)
        assertEquals(2, parsed.reason)
        assertEquals(0x5EA1F654, parsed.playerA)
        assertEquals(0x060D05FA, parsed.playerB)
    }

    @Test
    fun statFrameRejectsInvalid() {
        assertNull(BridgeCodec.parseStat(ByteArray(14))) // < 15
        val wrongMagic = BridgeCodec.encodeStat(1, 1, 0, 0, 1, 2)
        wrongMagic[0] = 0x47
        assertNull(BridgeCodec.parseStat(wrongMagic))
        val wrongVer = BridgeCodec.encodeStat(1, 1, 0, 0, 1, 2)
        wrongVer[1] = 2
        assertNull(BridgeCodec.parseStat(wrongVer))
    }
}
