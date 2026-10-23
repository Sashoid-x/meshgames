package com.meshgames.net.bridge

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Фейковый «Mesh Bridge» для тестов: обычный TCP-сервер на ephemeral-порту.
 * Семантика штатного моста: кадр, пришедший от клиента X, рассылается
 * (как эфирный broadcast) всем остальным подключённым клиентам; пинги
 * (len=0) игнорируются. loraFrom/loraTo в пересылаемых кадрах — условные.
 */
class FakeBridgeServer : AutoCloseable {
    private val server: ServerSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val running = AtomicBoolean(true)
    private val clients = CopyOnWriteArrayList<Socket>()

    /** Wrapper'ы, полученные от клиентов (отладка/ассерты). */
    val received = CopyOnWriteArrayList<ByteArray>()

    val port: Int get() = server.localPort

    init {
        startDaemon("fake-bridge-accept") { acceptLoop() }
    }

    private fun acceptLoop() {
        while (running.get()) {
            val s = try {
                server.accept()
            } catch (_: Exception) {
                return
            }
            clients += s
            startDaemon("fake-bridge-read") { readLoop(s) }
        }
    }

    private fun startDaemon(name: String, block: () -> Unit) {
        val t = Thread(block)
        t.name = name
        t.isDaemon = true
        t.start()
    }

    private fun readLoop(s: Socket) {
        // Клиентские кадры без LoRa-заголовка: [len][wrapper].
        val buffer = BridgeFrameBuffer(hasLoraHeader = false)
        try {
            val input = BufferedInputStream(s.getInputStream())
            val readBuf = ByteArray(4096)
            while (running.get() && !s.isClosed) {
                val n = input.read(readBuf)
                if (n < 0) return
                for (raw in buffer.append(readBuf.copyOfRange(0, n))) {
                    received += raw.payload
                    // Эфирный broadcast: payload как есть всем, кроме отправителя.
                    val out = ByteArray(12 + raw.payload.size)
                    BridgeCodec.writeU32(out, 0, 8 + raw.payload.size)
                    BridgeCodec.writeU32(out, 4, 42) // «LoRa from» — условный
                    BridgeCodec.writeU32(out, 8, 0) // «LoRa to» — broadcast
                    raw.payload.copyInto(out, 12)
                    for (other in clients) {
                        if (other === s || other.isClosed) continue
                        BufferedOutputStream(other.getOutputStream()).apply {
                            write(out)
                            flush()
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // обрыв клиента — норма
        }
    }

    /** Разорвать все клиентские соединения (сервер продолжает слушать). */
    fun disconnectAll() {
        clients.forEach {
            try {
                it.close()
            } catch (_: Exception) {
            }
        }
        clients.clear()
    }

    /** Отправить готовый кадр «мост → клиент» всем подключённым клиентам. */
    fun sendRawToAll(frame: ByteArray) {
        for (s in clients) {
            if (s.isClosed) continue
            try {
                BufferedOutputStream(s.getOutputStream()).apply {
                    write(frame)
                    flush()
                }
            } catch (_: Exception) {
                // обрыв клиента — норма
            }
        }
    }

    override fun close() {
        running.set(false)
        disconnectAll()
        try {
            server.close()
        } catch (_: Exception) {
        }
    }
}
