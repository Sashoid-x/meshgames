package com.meshgames.app

import android.content.Context
import android.content.SharedPreferences
import com.meshgames.debug.DebugLog
import com.meshgames.game.GameResult
import com.meshgames.game.GameType
import com.meshgames.game.Side
import com.meshgames.game.engine.GameEngines
import com.meshgames.game.session.JsonJournalStore
import com.meshgames.game.session.JsonSessionStore
import com.meshgames.game.session.SessionEvents
import com.meshgames.game.session.SessionManager
import com.meshgames.game.session.SessionTransport
import com.meshgames.game.session.SessionStatus
import com.meshgames.net.bridge.BridgeStatus
import com.meshgames.net.bridge.BridgeTransport
import java.io.File
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** (sessionId, (peerNode, gameType)) — входящее приглашение. */
typealias InviteEvent = Pair<Int, Pair<Int, GameType>>

/** Сведённый статус-link для UI и foreground-сервиса. */
sealed class LinkStatus {
    data object Disconnected : LinkStatus()
    data class Connecting(val via: String) : LinkStatus()
    data class Ready(val via: String, val myNodeNum: Int) : LinkStatus()
    data class Error(val via: String, val message: String) : LinkStatus()
}

/**
 * События сессий: реализация [SessionEvents], публикующая всё в SharedFlow,
 * чтобы подписались и уведомления (сервис), и UI (диалоги).
 */
class AppEvents : SessionEvents {
    val inviteReceived = MutableSharedFlow<InviteEvent>(replay = 0, extraBufferCapacity = 16)
    val inviteDeclined = MutableSharedFlow<Int>(replay = 0, extraBufferCapacity = 16)
    val gameStarted = MutableSharedFlow<Int>(replay = 0, extraBufferCapacity = 16)
    val gameOver = MutableSharedFlow<Pair<Int, GameResult>>(replay = 0, extraBufferCapacity = 16)
    val drawOffered = MutableSharedFlow<Int>(replay = 0, extraBufferCapacity = 16)
    val syncConflict = MutableSharedFlow<Int>(replay = 0, extraBufferCapacity = 16)

    /** (sessionId, запись журнала) — партия откатена после сверки ходов. */
    val sessionRecovered = MutableSharedFlow<Pair<Int, Int>>(replay = 0, extraBufferCapacity = 16)

    /** (sessionId, closed) — соперник закрыл/возобновил игру. */
    val gameClosed = MutableSharedFlow<Pair<Int, Boolean>>(replay = 0, extraBufferCapacity = 16)

    /** (sessionId, gameType, peerNode) — соперник удалил сохранённую сессию. */
    val sessionDeleted = MutableSharedFlow<Triple<Int, GameType, Int>>(replay = 0, extraBufferCapacity = 16)

    /** sessionId — соперник сделал ход (для звука). */
    val opponentAction = MutableSharedFlow<Int>(replay = 0, extraBufferCapacity = 16)

    override fun onInviteReceived(sessionId: Int, peerNode: Int, gameType: GameType) {
        inviteReceived.tryEmit(sessionId to (peerNode to gameType))
    }

    override fun onInviteDeclined(sessionId: Int) {
        inviteDeclined.tryEmit(sessionId)
    }

    override fun onGameStarted(sessionId: Int) {
        gameStarted.tryEmit(sessionId)
    }

    override fun onGameOver(sessionId: Int, result: GameResult) {
        gameOver.tryEmit(sessionId to result)
    }

    override fun onDrawOffered(sessionId: Int) {
        drawOffered.tryEmit(sessionId)
    }

    override fun onSyncConflict(sessionId: Int) {
        syncConflict.tryEmit(sessionId)
    }

    override fun onSessionRecovered(sessionId: Int, toEntry: Int) {
        sessionRecovered.tryEmit(sessionId to toEntry)
    }

    override fun onGameClosed(sessionId: Int, closed: Boolean) {
        gameClosed.tryEmit(sessionId to closed)
    }

    override fun onSessionDeletedByPeer(sessionId: Int, gameType: GameType, peerNode: Int) {
        sessionDeleted.tryEmit(Triple(sessionId, gameType, peerNode))
    }

    override fun onOpponentAction(sessionId: Int) {
        opponentAction.tryEmit(sessionId)
    }
}

/**
 * Граф приложения: транспорт — TCP-клиент моста форка Meshtastic
 * (порт 8090; portnum настраивается в форке, дефолт 69 SIMULATOR_APP), SessionManager, маршрутизация
 * входящих пакетов, тик раз в секунду, LinkStatus.
 */
class AppGraph(private val appContext: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val prefs: SharedPreferences = prefs(appContext)

    /** Настройки: тема, звук, уведомления, избранные, presence. */
    val settings = SettingsStore(prefs)

    // ------------------------------------------------- идентичность ----

    /**
     * Идентичность (clientId): обновление приложения сохраняет свой id
     * навсегда (инвариант TAK-эпохи: ключ `tak_client_id` менять нельзя);
     * свежая установка стартует на временном id и мигрирует на номер
     * ноды по HELLO-кадру моста — переустановка приложения больше не
     * меняет игрока. Подробнее — [ClientIdentity].
     */
    private val identity = ClientIdentity(
        storedId = prefs.getString("tak_client_id", null)?.toIntOrNull(),
        pending = prefs.getBoolean("tak_client_id_pending", false),
        generate = { Random.nextInt(1, Int.MAX_VALUE) },
        persist = { id, pending ->
            prefs.edit()
                .putString("tak_client_id", id.toString())
                .putBoolean("tak_client_id_pending", pending)
                .apply()
        },
    )

    /** Имя ноды из HELLO — дефолт имени игрока, пока своё не задано. */
    @Volatile
    private var nodeNameDefault: String? = null

    init {
        // Лог-файл открываем раньше всех компонентов: конструктор
        // SessionManager уже пишет строки восстановления сессий.
        DebugLog.init(appContext, identity.id)
        DebugLog.d("APP", "старт; id=0x${identity.id.toString(16)}; имя=${playerName()}; путь=мост (порт 8090)")
        // Словарь Балды — строго ДО SessionManager: restore сессий создаёт
        // движки, и балда должна получить словарь, а не заглушку.
        try {
            GameEngines.wordChecker = BaldaDictionary.load(appContext)
        } catch (e: Exception) {
            DebugLog.d("DICT", "словарь Балды не загружен: $e — слова пойдут на спор")
        }
    }

    // ---------------------------------------------------------- мост -----

    /** «Занят» для presence: активных игр ≥ 1 (обновляется в [start]). */
    private val _busy = MutableStateFlow(false)

    val bridge: BridgeTransport = BridgeTransport(
        scope = scope,
        myId = identity.id,
        nameProvider = { playerName() },
        busyProvider = { _busy.value },
        presenceIntervalProvider = { settings.presenceIntervalMs() },
    )

    init {
        // HELLO от моста: имя ноды — дефолт имени игрока, а для свежей
        // установки id мигрирует на номер ноды (переустановка не меняет
        // игрока). Сразу переобъявляем presence — имя/id в эфир.
        scope.launch {
            bridge.nodeInfo.collect { h ->
                if (h == null) return@collect
                if (h.name.isNotBlank()) nodeNameDefault = h.name
                if (identity.adoptNodeId(h.nodeId)) {
                    DebugLog.d("APP", "идентичность: → нода 0x${identity.id.toString(16)}")
                    bridge.myId = identity.id
                }
                bridge.announce(force = true)
            }
        }
    }

    private val _link = MutableStateFlow<LinkStatus>(LinkStatus.Disconnected)
    val link: StateFlow<LinkStatus> = _link.asStateFlow()

    val events = AppEvents()

    private val transport: SessionTransport = object : SessionTransport {
        override val myNodeNum: Int
            get() = if (bridge.isConnected) bridge.myId else 0

        override fun sendAsync(to: Int, payload: ByteArray) {
            // Отправка асинхронна; при ошибке расписание повторов позаботится.
            scope.launch {
                try {
                    bridge.send(to, payload)
                } catch (e: Exception) {
                    DebugLog.d("NET", "TX ошибка (to=$to): ${e.message}")
                }
            }
        }

        override fun sendNow(to: Int, payload: ByteArray) {
            // Приоритет: отдельный запуск на IO-пуле мимо очереди
            // ожиданий sendAsync (Dispatchers.Default) — SYNC_REQ по
            // кнопке пользователя уходит немедленно, раньше накопившихся
            // отправок; запись в сокет сериализуется мьютексом моста.
            scope.launch(Dispatchers.IO) {
                try {
                    bridge.send(to, payload)
                } catch (e: Exception) {
                    DebugLog.d("NET", "TX (приоритет) ошибка (to=$to): ${e.message}")
                }
            }
        }
    }

    val manager: SessionManager = SessionManager(
        store = JsonSessionStore(File(appContext.filesDir, "sessions.json")),
        transport = transport,
        events = events,
        // Цвета/кто первым ходит — случайно при каждом приглашении.
        sideDecider = { if (Random.nextBoolean()) Side.A else Side.B },
        // Журналы ходов — файл на сессию (удаляются вместе с сессией).
        journals = JsonJournalStore(File(appContext.filesDir, "journals")),
    )

    /**
     * Счётчик запросов «открыть список партий» — кнопка «К списку» в
     * уведомлении о закрытии игры (переход к списку из любого состояния UI).
     */
    val goListTick = MutableStateFlow(0)

    private var jobs: List<Job> = emptyList()

    /** Запуск маршрутизации: пакеты → менеджер, тик, статус-link. */
    fun start() {
        if (jobs.isNotEmpty()) return
        DebugLog.d("APP", "маршрутизация запущена")
        val j2 = scope.launch {
            while (isActive) {
                delay(1000)
                manager.tick()
            }
        }
        val j3 = scope.launch {
            bridge.packets.collect { p ->
                manager.handleIncoming(p.from, p.payload)
            }
        }
        val j4 = scope.launch {
            var lastLink: LinkStatus? = null
            while (isActive) {
                val cur = computeLink()
                if (cur != lastLink) {
                    DebugLog.d("APP", "link: $lastLink → $cur")
                    lastLink = cur
                }
                _link.value = cur
                delay(1000)
            }
        }
        val j5 = scope.launch {
            var last = false
            manager.sessions_.collect { list ->
                val busy = list.any {
                    (it.session.status == SessionStatus.ACTIVE ||
                        it.session.status == SessionStatus.PLACEMENT) && !it.session.closed
                }
                if (busy != last) {
                    last = busy
                    _busy.value = busy
                    DebugLog.d("APP", "presence: занят=$busy")
                    // Маркер готовности/занятости — сразу в эфир.
                    scope.launch { bridge.announce(force = true) }
                }
            }
        }
        jobs = listOf(j2, j3, j4, j5)
    }

    private fun computeLink(): LinkStatus = when (val s = bridge.status.value) {
        is BridgeStatus.Disconnected -> LinkStatus.Disconnected
        is BridgeStatus.Connecting -> LinkStatus.Connecting("мост")
        is BridgeStatus.Ready -> LinkStatus.Ready("мост", s.myId)
        is BridgeStatus.Error -> LinkStatus.Error("мост", s.message)
    }

    /** Подключение/переподключение к мосту (вызывается сервисом и UI). */
    fun ensureConnected() {
        bridge.start()
    }

    // ---------------------------------------------------- идентичность ----

    /**
     * Имя игрока: своё из настроек, иначе имя ноды из HELLO, иначе
     * заглушка по id. Свежая переустановка так сохраняет имя ноды.
     */
    fun playerName(): String =
        prefs.getString("player_name", null)?.takeIf { it.isNotBlank() }
            ?: nodeNameDefault?.takeIf { it.isNotBlank() }
            ?: "Игрок-${identity.id.toString(16).takeLast(4).uppercase()}"

    fun setPlayerName(name: String) {
        prefs.edit().putString("player_name", name).apply()
        scope.launch { bridge.announce(force = true) }
    }

    /** Имя соперника по его id: presence-справочник моста. */
    fun peerName(id: Int): String =
        bridge.peers.value[id]?.name
            ?: "Узел $id"

    companion object {
        @Volatile private var instance: AppGraph? = null

        fun get(context: Context): AppGraph =
            instance ?: synchronized(this) {
                instance ?: AppGraph(context.applicationContext).also {
                    instance = it
                    it.start()
                }
            }

        private fun prefs(c: Context) =
            c.getSharedPreferences("meshgames", Context.MODE_PRIVATE)
    }
}
