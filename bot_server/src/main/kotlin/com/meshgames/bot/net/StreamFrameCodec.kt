package com.meshgames.bot.net

import com.meshgames.bot.log.BotLogger
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Кодек фреймов Meshtastic Stream Protocol (START1=0x94, START2=0xC3, 2 байта MSB/LSB длины, payload).
 * Используется для прямого взаимодействия с нодой по TCP (порт 4403) и Serial.
 */
class StreamFrameCodec(
    private val onPacketReceived: (ByteArray) -> Unit,
) {
    companion object {
        const val START1: Byte = 0x94.toByte()
        const val START2: Byte = 0xC3.toByte()
        const val MAX_PACKET_SIZE = 512
        const val HEADER_SIZE = 4
        const val DEFAULT_NODE_PORT = 4403

        val WAKE_BYTES = byteArrayOf(START1, START1, START1, START1)

        fun framePacket(payload: ByteArray): ByteArray {
            require(payload.size <= MAX_PACKET_SIZE) { "Payload size ${payload.size} > $MAX_PACKET_SIZE" }
            val out = ByteArray(HEADER_SIZE + payload.size)
            out[0] = START1
            out[1] = START2
            out[2] = (payload.size shr 8).toByte()
            out[3] = (payload.size and 0xFF).toByte()
            System.arraycopy(payload, 0, out, HEADER_SIZE, payload.size)
            return out
        }
    }

    private var ptr = 0
    private var msb = 0
    private var lsb = 0
    private var packetLen = 0
    private val rxPacket = ByteArray(MAX_PACKET_SIZE)
    private val desyncCount = AtomicLong(0)

    fun reset() {
        ptr = 0
        msb = 0
        lsb = 0
        packetLen = 0
    }

    fun processByte(b: Byte) {
        if (!step(b)) step(b)
    }

    private fun step(c: Byte): Boolean = when (ptr) {
        0 -> {
            if (c == START1) ptr = 1
            true
        }
        1 -> {
            when (c) {
                START2 -> {
                    ptr = 2
                    true
                }
                START1 -> true // Padding
                else -> {
                    desyncCount.incrementAndGet()
                    ptr = 0
                    false
                }
            }
        }
        2 -> {
            msb = c.toInt() and 0xFF
            ptr = 3
            true
        }
        3 -> {
            lsb = c.toInt() and 0xFF
            packetLen = (msb shl 8) or lsb
            when {
                packetLen > MAX_PACKET_SIZE -> {
                    desyncCount.incrementAndGet()
                    ptr = 0
                    false
                }
                packetLen == 0 -> {
                    ptr = 0
                    true
                }
                else -> {
                    ptr = HEADER_SIZE
                    true
                }
            }
        }
        else -> {
            rxPacket[ptr - HEADER_SIZE] = c
            if (ptr - HEADER_SIZE + 1 == packetLen) {
                val complete = rxPacket.copyOf(packetLen)
                ptr = 0
                onPacketReceived(complete)
            } else {
                ptr++
            }
            true
        }
    }
}
