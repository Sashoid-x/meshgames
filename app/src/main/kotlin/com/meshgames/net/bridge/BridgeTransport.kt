package com.meshgames.net.bridge

import com.meshgames.debug.DebugLog
import com.meshgames.net.IncomingMeshPacket
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Статус соединения с TCP-мостом. */
sealed class BridgeStatus {
    data object Disconnected : BridgeStatus()
    data object Connecting : BridgeStatus()
    data class Ready(val myId: Int) : BridgeStatus()
    data class Error(val message: String) : BridgeStatus()
}

/** Обнаруженный через presence игрок MeshGames. */
data class BridgePeer(
    val id: Int,
    val name: String,
    val busy: Boolean,
    val lastSeenMs: Long,
    val isBot: Boolean = false,
)

/**
 * Транспорт MeshGames поверх TCP-моста форка Meshtastic-Android
 * (127.0.0.1:8090; portnum моста настраивается в форке, дефолт 69).
 * Клиент шлёт кадры
 * [BridgeCodec] мосту, мост транслирует payload broadcast'ом в эфир
 * с `wantAck=false` (firmware-ретрансмитов нет — экономим дайти-цикл),
 * обратно рассылает эфирные пакеты всем TCP-клиентам.
 *
 * Адресация MeshGames (clientId) живёт во wrapper'е внутри payload —
 * мост не знает о ней и всегда вещает broadcast'ом.
 */
class BridgeTransport(
    private val scope: CoroutineScope,
    myId: Int,
    private val host: String = BridgeCodec.DEFAULT_HOST,
    private val port: Int = BridgeCodec.DEFAULT_PORT,
    private val nameProvider: () -> String = { "Игрок" },
    private val busyProvider: () -> Boolean = { false },
    /**
     * Периодичность presence-announce в миллисекундах (настраивается
     * пользователем, 5..30 минут). Каждый announce — отдельный эфирный
     * пакет, поэтому редкая периодичность щадит дайти-цикл LoRa.
     */
    private val presenceIntervalProvider: () -> Long = { DEFAULT_PRESENCE_INTERVAL_MS },
) {

    /**
     * Мой clientId (wrapper.from). Может смениться один раз за жизнь
     * установки: свежий APK по HELLO-кадру моста мигрирует идентичность
     * на номер ноды (AppGraph) — переустановка приложения больше не
     * меняет игрока. Смена обновляет статус Ready.
     */
    @Volatile
    var myId: Int = myId
        set(value) {
            if (field == value) return
            DebugLog.d("BR", "myId: 0x${field.toString(16)} → 0x${value.toString(16)}")
            field = value
            if (_status.value is BridgeStatus.Ready) _status.value = BridgeStatus.Ready(value)
        }
    private val job = SupervisorJob()
    private val ioScope = CoroutineScope(job + Dispatchers.IO)
    private val writeMutex = Mutex()

    @Volatile private var socket: Socket? = null

    /**
     * Кеш вывода: один BufferedOutputStream на жизнь сокета (сброс — при
     * замене сокета). Раньше на КАЖДУЮ запись создавался новый поток —
     * лишний аллок на каждый кадр; буферизация между вызовами [write] не
     * нужна (кадр пишется целиком и сразу флушится — фреймы у нас ≤220 Б),
     * выигрыш только в отсутствие аллоков и хедера потока.
     */
    @Volatile private var out: BufferedOutputStream? = null

    /** Сокет, которому принадлежит [out] (проверка при реконнекте). */
    @Volatile private var outSocket: Socket? = null

    private val running = AtomicBoolean(false)
    private var loops: List<Job> = emptyList()

    private val _status = MutableStateFlow<BridgeStatus>(BridgeStatus.Disconnected)
    val status: StateFlow<BridgeStatus> = _status.asStateFlow()

    private val _peers = MutableStateFlow<Map<Int, BridgePeer>>(emptyMap())
    val peers: StateFlow<Map<Int, BridgePeer>> = _peers.asStateFlow()

    private val _nodeInfo = MutableStateFlow<BridgeCodec.Hello?>(null)

    /**
     * HELLO от моста: номер и имя СВОЕЙ ноды (null — мост молчит: старый
     * форк без HELLO или нода ещё не известна). AppGraph мигрирует по
     * этому кадру идентичность свежей установки и берёт имя по умолчанию.
     */
    val nodeInfo: StateFlow<BridgeCodec.Hello?> = _nodeInfo.asStateFlow()

    private val _packets = MutableSharedFlow<IncomingMeshPacket>(
        replay = 0, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val packets: SharedFlow<IncomingMeshPacket> = _packets.asSharedFlow()

    val isConnected: Boolean get() = _status.value is BridgeStatus.Ready

    private var lastAnnounceMs: Long = 0

    // ------------------------------------------------------- жизненный цикл

    /** Запустить цикл подключения с авто-reconnect (идемпотентно). */
    fun start() {
        if (!running.compareAndSet(false, true)) return
        loops = listOf(
            ioScope.launch { connectLoop() },
            ioScope.launch { keepaliveLoop() },
            ioScope.launch { announceLoop() },
        )
    }

    /** Остановить всё: сокет, циклы, статус. */
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        loops.forEach { it.cancel() }
        loops = emptyList()
        closeSocket()
        _status.value = BridgeStatus.Disconnected
    }

    fun destroy() {
        stop()
        job.cancel()
    }

    // ---------------------------------------------------------- соединение

    private suspend fun connectLoop() {
        var backoffMs = 2000L
        while (running.get()) {
            val ok = tryConnect()
            if (ok) {
                backoffMs = 2000L
                // Блокирующее чтение до обрыва.
                readUntilDead()
                closeSocket()
                if (_status.value is BridgeStatus.Ready) _status.value = BridgeStatus.Disconnected
            }
            if (!running.get()) return
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
        }
    }

    private suspend fun tryConnect(): Boolean {
        _status.value = BridgeStatus.Connecting
        return try {
            withContext(Dispatchers.IO) {
                val s = Socket()
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(host, port), 5000)
                socket = s
                _status.value = BridgeStatus.Ready(myId)
                DebugLog.d("BR", "подключён $host:$port, myId=0x${myId.toString(16)}")
                announce(force = true)
            }
            true
        } catch (e: Exception) {
            DebugLog.d("BR", "ошибка подключения к $host:$port: ${e.message}")
            closeSocket()
            _status.value = BridgeStatus.Error(e.message ?: e.javaClass.simpleName)
            false
        }
    }

    /** Чтение потока до обрыва/ошибки (вызывается в IO-корутине). */
    private suspend fun readUntilDead() {
        val s = socket ?: return
        val buffer = BridgeFrameBuffer()
        val readBuf = ByteArray(4096)
        try {
            val input = BufferedInputStream(s.getInputStream())
            while (running.get() && !s.isClosed) {
                val n = withContext(Dispatchers.IO) { input.read(readBuf) }
                if (n < 0) return // EOF
                if (n == 0) continue
                for (frame in buffer.append(readBuf.copyOfRange(0, n))) {
                    handleFrame(frame)
                }
            }
            if (running.get()) DebugLog.d("BR", "мост закрыл соединение (EOF)")
        } catch (e: Exception) {
            // Обрыв — вернёмся в connectLoop (штатный stop() не шумит).
            if (running.get()) DebugLog.d("BR", "обрыв чтения: ${e.message}")
        }
    }

    private fun closeSocket() {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        // Кеш вывода принадлежал закрытому сокету — на следующей записи
        // (уже к новому сокету) он не совпадёт по outSocket и пересоздастся.
        out = null
        outSocket = null
    }

    // ------------------------------------------------------------- запись

    /** Записать кадр в сокет (однопоточная запись под мьютексом). */
    private suspend fun write(frame: ByteArray) {
        val s = socket ?: throw IOException("мост не подключён")
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                val stream = out?.takeIf { outSocket === s }
                    ?: s.getOutputStream().buffered().also {
                        out = it
                        outSocket = s
                    }
                stream.write(frame)
                stream.flush()
            }
        }
    }

    /** Отправить игровой payload узлу [to] (0 — broadcast). */
    suspend fun send(to: Int, payload: ByteArray) {
        if (!isConnected) {
            DebugLog.d("BR", "TX пропущен: не подключён (to=$to, ${payload.size}B)")
            return
        }
        val frame = BridgeCodec.gameFrame(myId, to, payload)
        if (frame == null) {
            DebugLog.d("BR", "TX отклонён: payload ${payload.size}B > лимита ${BridgeCodec.MAX_WRAPPER_BYTES}B")
            return
        }
        try {
            write(frame)
            DebugLog.d("BR", "TX to=$to ${payload.size}B")
        } catch (e: Exception) {
            DebugLog.d("BR", "ошибка отправки (to=$to): ${e.message} — обрыв")
            closeSocket()
            _status.value = BridgeStatus.Disconnected
        }
    }

    /** Рассылать presence (имя + занятость). */
    suspend fun announce(force: Boolean = false) {
        if (!isConnected) return
        val now = System.currentTimeMillis()
        if (!force && now - lastAnnounceMs < MIN_ANNOUNCE_INTERVAL_MS) return
        lastAnnounceMs = now
        try {
            write(BridgeCodec.presenceFrame(myId, nameProvider(), busyProvider()))
            DebugLog.d("BR", "presence TX (имя=${nameProvider()}, занят=${busyProvider()})")
        } catch (e: Exception) {
            DebugLog.d("BR", "ошибка presence: ${e.message} — обрыв")
            closeSocket()
            _status.value = BridgeStatus.Disconnected
        }
    }

    // ------------------------------------------------------- обработка RX

    private fun handleFrame(frame: BridgeFrameBuffer.RawFrame) {
        val (from, to, body) = BridgeCodec.unwrap(frame.payload) ?: run {
            DebugLog.d("BR", "битый wrapper ${frame.payload.size}B от моста — пропуск")
            return
        }
        when {
            body.isEmpty() -> Unit
            (body[0].toInt() and 0xFF) == BridgeCodec.GAME_MAGIC -> {
                if (from == myId) {
                    DebugLog.d("BR", "RX: эхо своего пакета (to=$to) — пропуск")
                    return
                }
                if (to != 0 && to != myId) return
                // body — сам envelope: его первый байт 0x47 и есть magic
                // (см. Envelope.decode), отдельный заголовок не срезаем.
                DebugLog.d("BR", "RX от=$from to=$to ${body.size}B (LoRa from=${frame.loraFrom.toString(16)})")
                _packets.tryEmit(IncomingMeshPacket(from, to, body))
            }
            (body[0].toInt() and 0xFF) == BridgeCodec.HELLO_MAGIC -> {
                val h = BridgeCodec.parseHello(body) ?: run {
                    DebugLog.d("BR", "битый HELLO от моста — пропуск")
                    return
                }
                if (_nodeInfo.value != h) {
                    _nodeInfo.value = h
                    DebugLog.d(
                        "BR",
                        "HELLO: нода 0x${h.nodeId.toString(16)}, имя=${h.name.ifEmpty { "—" }}",
                    )
                }
            }
            (body[0].toInt() and 0xFF) == BridgeCodec.PRESENCE_MAGIC -> {
                if (from == myId) return
                val p = BridgeCodec.parsePresence(body) ?: run {
                    DebugLog.d("BR", "битый presence от=$from — пропуск")
                    return
                }
                val now = System.currentTimeMillis()
                val isNew = _peers.value[from] == null
                val isBot = p.isBot || p.name.contains("[AI]") || p.name.contains("[БОТ]") || p.name.contains("MeshBot")
                _peers.value = _peers.value + (from to
                    BridgePeer(from, p.name.ifEmpty { "Игрок ${from}" }, p.busy, now, isBot))
                if (isNew) {
                    DebugLog.d("BR", "presence RX: новый пир $from (${p.name}, занят=${p.busy})")
                    // Быстрое взаимное обнаружение: отвечаем presence'ом новому пиру.
                    ioScope.launch { announce(force = true) }
                }
            }
            (body[0].toInt() and 0xFF) == BridgeCodec.STAT_MAGIC -> {
                if (from == myId) return
                val stat = BridgeCodec.parseStat(body)
                if (stat != null) {
                    DebugLog.d("BR", "RX STAT sid=${stat.sessionId} игра=${stat.gameTypeId} winner=${stat.winner}")
                }
            }
            else -> Unit
        }
    }

    // ----------------------------------------------------------- циклы ---

    /**
     * Ping каждые 15с: проверка живости записи. Обратного служебного
     * трафика от моста нет (тишина в эфире — норма), поэтому RX-тишина
     * здесь не признак обрыва — только ошибка записи или EOF на чтении.
     */
    private suspend fun keepaliveLoop() {
        while (running.get()) {
            delay(15_000)
            if (socket == null) continue
            try {
                write(BridgeCodec.pingFrame())
            } catch (e: Exception) {
                DebugLog.d("BR", "ping не прошёл: ${e.message} — обрыв")
                closeSocket()
                _status.value = BridgeStatus.Disconnected
            }
        }
    }

    /** Presence с настраиваемой периодичностью (джиттер) + чистка пиров. */
    private suspend fun announceLoop() {
        while (running.get()) {
            val interval = presenceIntervalProvider()
                .coerceIn(MIN_PRESENCE_INTERVAL_MS, MAX_PRESENCE_INTERVAL_MS)
            // Джиттер ~interval/6: два телефона не синхронизируют свои эфиры.
            delay(interval + kotlin.random.Random.nextLong(interval / 6 + 1))
            prunePeers(interval)
            announce(force = true)
        }
    }

    private fun prunePeers(intervalMs: Long) {
        // Пир жив, пока видели его presence за 2.5 интервала (мин. 10 мин).
        val ttlMs = (intervalMs * 5 / 2).coerceAtLeast(MIN_PEER_TTL_MS)
        val cutoff = System.currentTimeMillis() - ttlMs
        val map = _peers.value
        if (map.values.none { it.lastSeenMs < cutoff }) return
        _peers.value = map.filterValues { it.lastSeenMs >= cutoff }
    }

    companion object {
        private const val MIN_ANNOUNCE_INTERVAL_MS = 10_000L

        /** Диапазон настройки «периодичность присутствия»: 5..30 минут. */
        const val MIN_PRESENCE_INTERVAL_MS = 5 * 60_000L
        const val MAX_PRESENCE_INTERVAL_MS = 30 * 60_000L
        const val DEFAULT_PRESENCE_INTERVAL_MS = 10 * 60_000L

        /** Минимальный TTL пира при любом интервале presence. */
        private const val MIN_PEER_TTL_MS = 10 * 60_000L
    }
}
