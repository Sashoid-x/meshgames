package com.meshgames.game.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvelopeTest {
    @Test
    fun roundTrip() {
        val env = Envelope(MsgType.MOVE, sessionId = 4242, seq = 65535, payload = byteArrayOf(1, 2, 3))
        val dec = Envelope.decode(env.encode())!!
        assertEquals(env, dec)
    }

    @Test
    fun headerLayout() {
        val enc = Envelope(MsgType.INVITE, 0x0201, 0x0403, byteArrayOf(7)).encode()
        assertEquals(13, enc.size)
        assertEquals(0x47, enc[0].toInt() and 0xFF)
        assertEquals(2, enc[1].toInt() and 0xFF)
        assertEquals(MsgType.INVITE.id, enc[2].toInt() and 0xFF)
        // sessionId LE
        assertEquals(0x01, enc[4].toInt() and 0xFF)
        assertEquals(0x02, enc[5].toInt() and 0xFF)
        // seq LE
        assertEquals(0x03, enc[6].toInt() and 0xFF)
        assertEquals(0x04, enc[7].toInt() and 0xFF)
        // без подтверждения — сентинел «ничего не подтверждено»
        assertEquals(0xFF, enc[8].toInt() and 0xFF)
        assertEquals(0xFF, enc[9].toInt() and 0xFF)
        assertEquals(0, enc[10].toInt() and 0xFF)
        assertEquals(0, enc[11].toInt() and 0xFF)
        // payload начинается после 12-байтового заголовка
        assertEquals(7, enc[12].toInt() and 0xFF)
    }

    @Test
    fun rejectsBadMagicVersionType() {
        val good = Envelope(MsgType.MOVE, 1, 1, byteArrayOf(1)).encode()
        assertNull(Envelope.decode(good.copyOf(4)))           // короткий
        val badMagic = good.copyOf().also { it[0] = 0x48 }
        assertNull(Envelope.decode(badMagic))
        // версия заголовка только одна — 2; «1» (ранняя версия) отвергается
        assertNull(Envelope.decode(good.copyOf().also { it[1] = 1 }))
        // …а неизвестная версия отвергается вовсе
        assertNull(Envelope.decode(good.copyOf().also { it[1] = 3 }))
        val badType = good.copyOf().also { it[2] = 99 }
        assertNull(Envelope.decode(badType))
    }

    @Test
    fun v2PiggybackRoundTripAndLayout() {
        val env = Envelope(MsgType.MOVE, 0x0201, 0x0403, byteArrayOf(7),
            cumAck = 0x0605, bitmap = 0x8001)
        val enc = env.encode()
        assertEquals(12 + 1, enc.size)
        assertEquals(2, enc[1].toInt() and 0xFF)               // version = 2
        // cumAck LE в байтах 8..9
        assertEquals(0x05, enc[8].toInt() and 0xFF)
        assertEquals(0x06, enc[9].toInt() and 0xFF)
        // bitmap LE в байтах 10..11
        assertEquals(0x01, enc[10].toInt() and 0xFF)
        assertEquals(0x80, enc[11].toInt() and 0xFF)
        // payload начинается после 12-байтового заголовка
        assertEquals(7, enc[12].toInt() and 0xFF)

        val dec = Envelope.decode(enc)!!
        assertEquals(env, dec)
        assertTrue(dec.hasAck)
        assertEquals(0x0605, dec.cumAck)
        assertEquals(0x8001, dec.bitmap)
    }

    @Test
    fun frameWithoutAckEncodesSentinelAndDecodesAsNoAck() {
        val enc = Envelope(MsgType.MOVE, 1, 0, byteArrayOf(9)).encode() // без cumAck
        assertEquals(13, enc.size) // 12 байт заголовка + payload
        assertEquals(ACK_NONE, (enc[8].toInt() and 0xFF) or ((enc[9].toInt() and 0xFF) shl 8))
        val dec = Envelope.decode(enc)!!
        assertFalse(dec.hasAck)
        assertNull(dec.cumAck)
    }

    @Test
    fun syncResPayloadRoundTrip() {
        val p = SyncResPayload(1000, 1, 3, 0xBEEF)
        val dec = SyncResPayload.decode(p.encode())!!
        assertEquals(p.cumAck, dec.cumAck)
        assertEquals(p.turn, dec.turn)
        assertEquals(p.status, dec.status)
        assertEquals(p.digest, dec.digest)
    }

    @Test
    fun gameOverAndInvitePayloads() {
        val over = GameOverPayload.decode(GameOverPayload(2, 5).encode())!!
        assertEquals(2, over.winner)
        assertEquals(5, over.reason)
        assertTrue(InviteRespPayload.decode(InviteRespPayload(true).encode())!!.accepted)
        assertTrue(!InviteRespPayload.decode(InviteRespPayload(false).encode())!!.accepted)
        assertEquals(4, InvitePayload.decode(InvitePayload(4).encode())!!.gameTypeId)
    }

    @Test
    fun invitePayloadCarriesInviterSide() {
        // Разворот: тип игры + сторона инициатора (0 = A, 1 = B).
        val dec = InvitePayload.decode(InvitePayload(2, 1).encode())!!
        assertEquals(2, dec.gameTypeId)
        assertEquals(1, dec.inviterSide)
        assertEquals(0, InvitePayload.decode(InvitePayload(3, 0).encode())!!.inviterSide)
        // Легаси-кадр в один байт (ранняя версия протокола) не принимается.
        assertNull(InvitePayload.decode(byteArrayOf(2)))
        // Мусорные значения байта нормализуются в 0/1.
        assertEquals(1, InvitePayload.decode(byteArrayOf(2, 7))!!.inviterSide)
        // Проверка опциональной сложности (0 = дефолт, 1 = легкий, 2 = средний, 3 = сложный)
        val withDiff = InvitePayload.decode(InvitePayload(3, 0, 2).encode())!!
        assertEquals(3, withDiff.gameTypeId)
        assertEquals(0, withDiff.inviterSide)
        assertEquals(2, withDiff.difficulty)
        assertEquals(2, InvitePayload(3, 0).encode().size)
        assertEquals(3, InvitePayload(3, 0, 1).encode().size)
    }

    @Test
    fun journalTailRoundTrip() {
        val tail = JournalTailPayload(
            digest = 0xABCD,
            entries = listOf(
                JournalTailPayload.TailEntry(0, 0, 1),
                JournalTailPayload.TailEntry(1, 0x7EADBEEF, 0x1234),
                JournalTailPayload.TailEntry(400, -1, 0xFFFF),
            ),
        )
        val dec = JournalTailPayload.decode(tail.encode())!!
        assertEquals(0xABCD, dec.digest)
        assertEquals(tail.entries, dec.entries)
    }

    @Test
    fun journalTailDecodeRejectsShortOrTruncatedBytes() {
        // Старый пир / отсутствие хвоста: null — восстановление невозможно.
        assertNull(JournalTailPayload.decode(ByteArray(0)))
        assertNull(JournalTailPayload.decode(byteArrayOf(1, 2)))
        // Заявлено 2 записи, лежит одна — битые байты не разбираются.
        val head = byteArrayOf(0, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0)
        assertNull(JournalTailPayload.decode(head))
    }

    @Test
    fun journalTailFitsPayloadBudget() {
        // 12 записей по 8 байт + заголовок = 99 байт ≤ 200 байт под envelope.
        val entries = (1..20).map { JournalTailPayload.TailEntry(it, it, it) }
        val enc = JournalTailPayload(7, entries).encode()
        assertEquals(3 + JournalTailPayload.MAX_ENTRIES * 8, enc.size)
        assertEquals(JournalTailPayload.MAX_ENTRIES, JournalTailPayload.decode(enc)!!.entries.size)
    }

    @Test
    fun syncResKeepsCoreAndAppendedTailSeparately() {
        val core = SyncResPayload(cumAck = 5, turn = 1, status = 3, digest = 0x7777)
        val tail = JournalTailPayload(0x7777, listOf(JournalTailPayload.TailEntry(1, 42, 9)))
        val payload = core.encode() + tail.encode()

        // Ядро по-прежнему читается старым способом (6 байт, лишние — в хвост).
        val dec = SyncResPayload.decode(payload)!!
        assertEquals(5, dec.cumAck)
        assertEquals(0x7777, dec.digest)
        // Хвост читается смещением; старый 6-байтовый SYNC_RES → null.
        val decTail = JournalTailPayload.decode(
            payload.copyOfRange(JournalTailPayload.SYNC_RES_TAIL_OFFSET, payload.size),
        )!!
        assertEquals(listOf(JournalTailPayload.TailEntry(1, 42, 9)), decTail.entries)
        assertNull(
            JournalTailPayload.decode(
                core.encode().copyOfRange(JournalTailPayload.SYNC_RES_TAIL_OFFSET, core.encode().size),
            ),
        )
    }

    @Test
    fun batchFrameEncodeAndDecode() {
        val env1 = Envelope(MsgType.SESSION_CLOSE, 1234, 0, byteArrayOf(0)).encode()
        val env2 = Envelope(MsgType.SYNC_REQ, 1234, 1, byteArrayOf(1, 2, 3)).encode()

        val batchBytes = BatchFrame.encode(1234, listOf(env1, env2))!!
        val decodedBatch = Envelope.decode(batchBytes)!!
        assertEquals(MsgType.BATCH, decodedBatch.type)
        assertEquals(1234, decodedBatch.sessionId)

        val subEnvelopes = BatchFrame.decode(decodedBatch.payload)!!
        assertEquals(2, subEnvelopes.size)
        assertEquals(Envelope.decode(env1), Envelope.decode(subEnvelopes[0]))
        assertEquals(Envelope.decode(env2), Envelope.decode(subEnvelopes[1]))
    }

    @Test
    fun ackOnlyEnvelope() {
        val env = Envelope(MsgType.ACK_ONLY, 555, 0, ByteArray(0), cumAck = 42, bitmap = 0b101)
        val encoded = env.encode()
        assertEquals(12, encoded.size)
        val decoded = Envelope.decode(encoded)!!
        assertEquals(MsgType.ACK_ONLY, decoded.type)
        assertEquals(42, decoded.cumAck)
        assertEquals(0b101, decoded.bitmap)
        assertTrue(decoded.payload.isEmpty())
    }
}
