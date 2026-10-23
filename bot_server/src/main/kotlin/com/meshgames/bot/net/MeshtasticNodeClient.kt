package com.meshgames.bot.net

import com.meshgames.bot.log.BotLogger
import com.meshgames.bot.stats.StatsManager
import com.meshgames.net.IncomingMeshPacket
import com.meshgames.net.bridge.BridgeCodec
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.Data
import org.meshtastic.proto.Heartbeat
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.ToRadio

/**
 * TCP-клиент автономного сервера, подключающийся напрямую к ноде Meshtastic (порт 4403).
 * Полностью заменяет собой MeshBridge:
 * - Поддерживает Stream Framing (0x94 0xC3) и Protobuf (ToRadio/FromRadio)
 * - Извлекает my_node_num и имя ноды
 * - Маршрутизирует исходящие пакеты (presence -> broadcast, envelope -> DM/unicast)
 * - Жёстко привязывается к заданному каналу [channelIndex] (по умолчанию 0)
 * - Устанавливает настраиваемый [hopLimit] (по умолчанию 3)
 * - Гарантирует want_ack = false для всех пакетов
 * - Обучает таблицу маршрутизации на всех входящих пакетах
 * - Поддерживает соединение heartbeat-пакетами
 */
class MeshtasticNodeClient(
    private val scope: CoroutineScope,
    initialMyId: Int,
    private val host: String = "127.0.0.1",
    private val port: Int = StreamFrameCodec.DEFAULT_NODE_PORT,
    val channelIndex: Int = 0,
    val hopLimit: Int = 3,
    private val bridgePortnum: Int = 69, // PortNum.SIMULATOR_APP
    private val botNameProvider: () -> String = { "MeshBot" },
    val isExplicitName: Boolean = false,
    private val busyProvider: () -> Boolean = { false },
    private val presenceIntervalMs: Long = 10 * 60 * 1000L,
    val routingTable: RoutingTable = RoutingTable(32),
) {
    @Volatile
    var myId: Int = initialMyId
        set(value) {
            if (field == value) return
            BotLogger.log("BR", "myId обновлен: 0x${field.toString(16)} → 0x${value.toString(16)}")
            field = value
        }

    @Volatile
    var statsManager: StatsManager? = null

    var onNodeIdentified: ((nodeId: Int, nodeName: String) -> Unit)? = null

    private var lastTableResponseMs = 0L

    private val job = SupervisorJob()
    private val ioScope = CoroutineScope(job + Dispatchers.IO)
    private val writeMutex = Mutex()

    @Volatile private var socket: Socket? = null
    @Volatile private var out: BufferedOutputStream? = null

    private val running = AtomicBoolean(false)
    private var loops: List<Job> = emptyList()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _myNodeNum = MutableStateFlow(0)
    val myNodeNum: StateFlow<Int> = _myNodeNum.asStateFlow()

    private val _nodeName = MutableStateFlow(botNameProvider())
    val nodeName: StateFlow<String> = _nodeName.asStateFlow()

    private val _packets = MutableSharedFlow<IncomingMeshPacket>(
        replay = 1,
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val packets: SharedFlow<IncomingMeshPacket> = _packets.asSharedFlow()

    private var lastPresenceMs = 0L

    fun start() {
        if (!running.compareAndSet(false, true)) return
        loops = listOf(
            ioScope.launch { connectLoop() },
            ioScope.launch { heartbeatLoop() },
            ioScope.launch { presenceLoop() },
        )
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        loops.forEach { it.cancel() }
        loops = emptyList()
        closeSocket()
        _connected.value = false
    }

    private suspend fun connectLoop() {
        var backoffMs = 2000L
        while (running.get()) {
            val ok = tryConnect()
            if (ok) {
                backoffMs = 2000L
                readUntilDead()
                closeSocket()
                _connected.value = false
            }
            if (!running.get()) return
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
        }
    }

    private suspend fun tryConnect(): Boolean {
        return try {
            withContext(Dispatchers.IO) {
                val s = Socket()
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(host, port), 5000)
                socket = s
                out = BufferedOutputStream(s.getOutputStream())
                _connected.value = true
                BotLogger.info("Подключено к ноде Meshtastic $host:$port (ch=$channelIndex, hopLimit=$hopLimit)")

                // Инициализация протокола: wake bytes + запрос конфигурации ноды
                writeMutex.withLock {
                    val stream = out ?: return@withLock
                    stream.write(StreamFrameCodec.WAKE_BYTES)
                    val wantConfig = ToRadio.Builder()
                        .want_config_id(Random.nextInt(1000, 0x7FFFFFFF))
                        .build()
                    val frame = StreamFrameCodec.framePacket(ToRadio.ADAPTER.encode(wantConfig))
                    stream.write(frame)
                    stream.flush()
                }

                // Даем ноде до 1.5 сек на ответ my_info перед первым анонсом
                scope.launch {
                    delay(1500)
                    if (running.get() && _connected.value && lastPresenceMs == 0L) {
                        announcePresence(force = true)
                    }
                }
            }
            true
        } catch (e: Exception) {
            BotLogger.warn("Не удалось подключиться к ноде $host:$port: ${e.message}")
            closeSocket()
            _connected.value = false
            false
        }
    }

    private suspend fun readUntilDead() {
        val s = socket ?: return
        val codec = StreamFrameCodec { packetBytes ->
            handleFromRadioPacket(packetBytes)
        }
        val readBuf = ByteArray(2048)
        try {
            val input = BufferedInputStream(s.getInputStream())
            while (running.get() && !s.isClosed) {
                val n = withContext(Dispatchers.IO) { input.read(readBuf) }
                if (n < 0) return // EOF
                if (n == 0) continue
                for (i in 0 until n) {
                    codec.processByte(readBuf[i])
                }
            }
            if (running.get()) BotLogger.warn("Нода закрыла TCP соединение (EOF)")
        } catch (e: Exception) {
            if (running.get()) BotLogger.warn("Обрыв чтения от ноды: ${e.message}")
        }
    }

    private fun handleFromRadioPacket(bytes: ByteArray) {
        val fromRadio = try {
            FromRadio.ADAPTER.decode(bytes)
        } catch (e: Exception) {
            BotLogger.warn("Ошибка декодирования FromRadio: ${e.message}")
            return
        }

        // 1. Извлечение информации о ноде
        val myInfo = fromRadio.my_info
        if (myInfo != null && myInfo.my_node_num != 0 && myInfo.my_node_num != -1) {
            val num = myInfo.my_node_num
            if (_myNodeNum.value != num) {
                _myNodeNum.value = num
                myId = num
                BotLogger.info("Идентичность ноды: myNodeNum = 0x${num.toString(16)}")
                onNodeIdentified?.invoke(num, _nodeName.value)
                announcePresence(force = true)
            }
        }

        val nodeInfo = fromRadio.node_info
        if (nodeInfo != null && nodeInfo.num == _myNodeNum.value) {
            val radioName = nodeInfo.user?.long_name?.trim()?.takeIf { it.isNotEmpty() }
                ?: nodeInfo.user?.short_name?.trim()?.takeIf { it.isNotEmpty() }
            if (radioName != null) {
                BotLogger.info("Имя ноды из радио: «$radioName»")
                if (!isExplicitName) {
                    if (_nodeName.value != radioName) {
                        _nodeName.value = radioName
                        BotLogger.info("Принято имя ноды: «$radioName»")
                        onNodeIdentified?.invoke(_myNodeNum.value, radioName)
                        announcePresence(force = true)
                    }
                } else {
                    BotLogger.info("Сохранено явное имя из параметров запуска: «${_nodeName.value}» (игнорируем имя ноды «$radioName»)")
                }
            }
        }

        // 2. Извлечение входящего mesh-пакета
        val packet = fromRadio.packet ?: return
        val decoded = packet.decoded ?: return

        // Проверка текстовых сообщений (PortNum.TEXT_MESSAGE_APP) на команду запроса таблицы !table
        if (decoded.portnum == PortNum.TEXT_MESSAGE_APP) {
            val senderLora = packet.from
            if (senderLora != _myNodeNum.value) {
                val text = try {
                    decoded.payload.toByteArray().toString(Charsets.UTF_8).trim()
                } catch (_: Exception) { "" }
                if (text.equals("!table", ignoreCase = true) || text.equals("!таблица", ignoreCase = true)) {
                    handleTableRequest(packet.channel, senderLora)
                }
            }
        }

        val pnum = decoded.portnum.value
        if (pnum == bridgePortnum || decoded.portnum == PortNum.SIMULATOR_APP) {
            val payload = decoded.payload.toByteArray()
            if (payload.size >= BridgeCodec.WRAPPER_OVERHEAD) {
                val from = BridgeCodec.readU32(payload, 0)
                val to = BridgeCodec.readU32(payload, 4)
                val body = payload.copyOfRange(BridgeCodec.WRAPPER_OVERHEAD, payload.size)

                // Фильтр эхо: не обрабатываем свои собственные пакеты
                if (from == myId) {
                    return
                }

                // Обучаем таблицу маршрутизации: связка clientId игрока -> loraFrom ноды
                routingTable.record(from, packet.from)

                if (body.isEmpty()) return

                val magic = body[0].toInt() and 0xFF
                when (magic) {
                    BridgeCodec.GAME_MAGIC -> {
                        // Доставляем боту только если адресовано ему или широковещательно
                        if (to == 0 || to == myId) {
                            BotLogger.log(
                                "RX",
                                "RX GAME portnum=$pnum ch=${packet.channel} from=0x${from.toString(16)} to=0x${to.toString(16)} bytes=${body.size}"
                            )
                            _packets.tryEmit(IncomingMeshPacket(from = from, to = to, payload = body))
                        }
                    }
                    BridgeCodec.PRESENCE_MAGIC -> {
                        val p = BridgeCodec.parsePresence(body)
                        if (p != null) {
                            BotLogger.log("BR", "RX Presence от 0x${from.toString(16)}: «${p.name}», занят=${p.busy}")
                            statsManager?.recordPlayerName(from, p.name)
                        }
                    }
                    BridgeCodec.STAT_MAGIC -> {
                        val stat = BridgeCodec.parseStat(body)
                        if (stat != null) {
                            BotLogger.log("STAT", "RX STAT sid=${stat.sessionId} игра=${stat.gameTypeId} winner=${stat.winner}")
                            statsManager?.recordGame(stat)
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun handleTableRequest(channel: Int, senderLora: Int) {
        val now = System.currentTimeMillis()
        if (now - lastTableResponseMs < 10_000L) {
            BotLogger.info("Запрос '!table' от 0x${senderLora.toString(16)} пропущен (кулдаун 10с)")
            return
        }
        lastTableResponseMs = now
        val stats = statsManager ?: return
        val response = stats.formatBriefTable()
        BotLogger.info("Запрос '!table' от 0x${senderLora.toString(16)} в канале $channel -> отправка краткой сводки")
        scope.launch(Dispatchers.IO) {
            sendTextMessage(response, channel = channel)
        }
    }

    private suspend fun heartbeatLoop() {
        while (running.get()) {
            delay(15_000L)
            if (!_connected.value) continue
            try {
                val hb = ToRadio.Builder()
                    .heartbeat(Heartbeat.Builder().build())
                    .build()
                val frame = StreamFrameCodec.framePacket(ToRadio.ADAPTER.encode(hb))
                writeFrame(frame)
            } catch (e: Exception) {
                BotLogger.warn("Ошибка отправки heartbeat ноде: ${e.message}")
            }
        }
    }

    private suspend fun presenceLoop() {
        while (running.get()) {
            delay(30_000L)
            if (!_connected.value) continue
            val now = System.currentTimeMillis()
            if (now - lastPresenceMs >= presenceIntervalMs) {
                announcePresence(force = false)
            }
        }
    }

    fun announcePresence(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastPresenceMs < 10_000L) return
        lastPresenceMs = now
        val name = _nodeName.value.takeIf { it.isNotBlank() } ?: botNameProvider()
        val busy = busyProvider()
        val frame = BridgeCodec.presenceFrame(myId, name, busy, isBot = true)
        val wrapper = frame.copyOfRange(4, frame.size)
        val body = wrapper.copyOfRange(BridgeCodec.WRAPPER_OVERHEAD, wrapper.size)

        scope.launch(Dispatchers.IO) {
            sendRaw(myId, 0, body)
        }
    }

    /**
     * Отправка игрового кадра от имени бота (from = myId).
     */
    suspend fun send(to: Int, body: ByteArray): Boolean {
        return sendRaw(myId, to, body)
    }

    /**
     * Отправка кадра в эфир LoRa с маршрутизацией по спецификации MeshBridge.
     * Жёстко использует заданный [channelIndex].
     */
    suspend fun sendRaw(from: Int, to: Int, body: ByteArray): Boolean {
        if (!_connected.value) {
            BotLogger.warn("Отправка невозможна: нет связи с нодой")
            return false
        }

        val dest = MeshBridgeRouter.route(from, to, body, routingTable)
        val loraTo = when (dest) {
            is RouteDestination.Broadcast -> MeshBridgeRouter.BROADCAST_ADDR
            is RouteDestination.Unicast -> dest.nodeNum
        }

        // Жёстко привязываемся к заданному в конфигурации каналу channelIndex
        val targetChannel = channelIndex

        // Формируем wrapper: [u32 from LE][u32 to LE][body...]
        val wrapper = ByteArray(BridgeCodec.WRAPPER_OVERHEAD + body.size)
        BridgeCodec.writeU32(wrapper, 0, from)
        BridgeCodec.writeU32(wrapper, 4, to)
        body.copyInto(wrapper, BridgeCodec.WRAPPER_OVERHEAD)

        val portNumEnum = PortNum.fromValue(bridgePortnum) ?: PortNum.SIMULATOR_APP

        val data = Data.Builder()
            .portnum(portNumEnum)
            .payload(wrapper.toByteString())
            .want_response(false)
            .build()

        val meshPacket = MeshPacket.Builder()
            .from(_myNodeNum.value)
            .to(loraTo)
            .channel(targetChannel)
            .hop_limit(hopLimit)
            .hop_start(hopLimit)
            .want_ack(false) // КРИТИЧНО! Никаких firmware-ретрансмитов!
            .id(Random.nextInt(1, 0x7FFFFFFF))
            .decoded(data)
            .build()

        val toRadio = ToRadio.Builder()
            .packet(meshPacket)
            .build()

        val framed = StreamFrameCodec.framePacket(ToRadio.ADAPTER.encode(toRadio))
        return writeFrame(framed)
    }

    /**
     * Отправка широковещательного текстового сообщения в канал Meshtastic (порт TEXT_MESSAGE_APP = 1).
     * Используется для вывода краткой сводки таблицы по запросу '!table'.
     */
    suspend fun sendTextMessage(text: String, channel: Int = channelIndex): Boolean {
        if (!_connected.value) {
            BotLogger.warn("Отправка текста невозможна: нет связи с нодой")
            return false
        }

        val data = Data.Builder()
            .portnum(PortNum.TEXT_MESSAGE_APP)
            .payload(text.toByteArray(Charsets.UTF_8).toByteString())
            .want_response(false)
            .build()

        val meshPacket = MeshPacket.Builder()
            .from(_myNodeNum.value)
            .to(MeshBridgeRouter.BROADCAST_ADDR)
            .channel(channel)
            .hop_limit(hopLimit)
            .hop_start(hopLimit)
            .want_ack(false)
            .id(Random.nextInt(1, 0x7FFFFFFF))
            .decoded(data)
            .build()

        val toRadio = ToRadio.Builder()
            .packet(meshPacket)
            .build()

        val framed = StreamFrameCodec.framePacket(ToRadio.ADAPTER.encode(toRadio))
        val ok = writeFrame(framed)
        if (ok) {
            BotLogger.info("TX [TEXT_MESSAGE] ch=$channel: $text")
        }
        return ok
    }

    private suspend fun writeFrame(frame: ByteArray): Boolean {
        return try {
            writeMutex.withLock {
                val stream = out ?: return false
                withContext(Dispatchers.IO) {
                    stream.write(frame)
                    stream.flush()
                }
            }
            true
        } catch (e: Exception) {
            BotLogger.warn("Ошибка записи в сокет ноды: ${e.message}")
            closeSocket()
            _connected.value = false
            false
        }
    }

    private fun closeSocket() {
        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null
        out = null
    }
}
