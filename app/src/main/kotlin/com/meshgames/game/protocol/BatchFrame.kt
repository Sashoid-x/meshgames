package com.meshgames.game.protocol

/**
 * Batch-кадр ([MsgType.BATCH]): упаковка нескольких envelope-сообщений
 * в один эфирный слот LoRa для экономии радио-трафика.
 *
 * Формат payload:
 * ```
 * [u8 count]
 * count × [u8 subLen][subLen bytes subEnvelope]
 * ```
 */
object BatchFrame {
    const val MAX_PAYLOAD_SIZE = 200

    /**
     * Упаковать несколько готовых envelope-байтов в один BATCH-кадр.
     * Возвращает null, если общий размер превышает лимит payload моста.
     */
    fun encode(sessionId: Int, envelopes: List<ByteArray>): ByteArray? {
        if (envelopes.isEmpty()) return null
        val totalSubSize = envelopes.sumOf { 1 + it.size }
        if (1 + totalSubSize > MAX_PAYLOAD_SIZE) return null
        val payload = ByteArray(1 + totalSubSize)
        payload[0] = envelopes.size.toByte()
        var o = 1
        for (envBytes in envelopes) {
            payload[o++] = envBytes.size.toByte()
            System.arraycopy(envBytes, 0, payload, o, envBytes.size)
            o += envBytes.size
        }
        return Envelope(MsgType.BATCH, sessionId, seq = 0, payload = payload).encode()
    }

    /**
     * Распаковать тело BATCH-кадра в список отдельных envelope-байтов.
     * Возвращает null при нарушении формата.
     */
    fun decode(payload: ByteArray): List<ByteArray>? {
        if (payload.isEmpty()) return null
        val count = payload[0].toInt() and 0xFF
        if (count == 0) return emptyList()
        val list = ArrayList<ByteArray>(count)
        var o = 1
        repeat(count) {
            if (o >= payload.size) return null
            val len = payload[o++].toInt() and 0xFF
            if (o + len > payload.size) return null
            list.add(payload.copyOfRange(o, o + len))
            o += len
        }
        return list
    }
}
