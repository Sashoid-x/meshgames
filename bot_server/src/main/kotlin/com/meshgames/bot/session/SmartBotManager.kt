package com.meshgames.bot.session

import com.meshgames.bot.BotConfig
import com.meshgames.bot.ai.BotAi
import com.meshgames.bot.ai.BotAiFactory
import com.meshgames.bot.log.BotLogger
import com.meshgames.bot.net.MeshtasticNodeClient
import com.meshgames.bot.stats.StatsManager
import com.meshgames.game.GameResult
import com.meshgames.game.GameType
import com.meshgames.game.Side
import com.meshgames.game.session.JsonJournalStore
import com.meshgames.game.session.JsonSessionStore
import com.meshgames.game.session.SessionEvents
import com.meshgames.game.session.SessionManager
import com.meshgames.game.session.SessionStatus
import com.meshgames.game.session.SessionTransport
import com.meshgames.net.bridge.BridgeCodec
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Высокоуровневый менеджер автономного бота.
 * Отвечает за:
 * - Прямое подключение к ноде Meshtastic (порт 4403) по протоколу TCP PhoneAPI
 * - Многопоточную игру одновременно с несколькими игроками в разные игры
 * - Автоматический прием приглашений и управление жизненным циклом сессий
 * - Применение сильного ИИ для каждой игры (BotAi)
 */
class SmartBotManager(
    val config: BotConfig,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Кэш экземпляров ИИ по типам игр и сложности
    private val aiEngines = ConcurrentHashMap<Pair<GameType, String>, BotAi>()

    // Выбранная сложность для каждой сессии
    private val sessionDifficulties = ConcurrentHashMap<Int, String>()

    // Множество сессий, для которых сейчас в фоне рассчитывается ход (защита от повторного входа)
    private val thinkingSessions = ConcurrentHashMap.newKeySet<Int>()

    // Менеджер статистики сыгранных партий
    val statsManager = StatsManager(File(config.dataDir, "stats.json"))

    // Сетевой клиент к физической ноде Meshtastic (TCP PhoneAPI)
    val nodeClient = MeshtasticNodeClient(
        scope = scope,
        initialMyId = config.myId,
        host = config.nodeHost,
        port = config.nodePort,
        channelIndex = config.channelIndex,
        hopLimit = config.hopLimit,
        bridgePortnum = config.bridgePortnum,
        botNameProvider = { config.botName },
        isExplicitName = config.isExplicitName,
        busyProvider = { false }, // Бот всегда готов играть!
        presenceIntervalMs = config.presenceIntervalMs,
    )

    private val transport: SessionTransport = object : SessionTransport {
        override val myNodeNum: Int
            get() = nodeClient.myId

        override fun sendAsync(to: Int, payload: ByteArray) {
            scope.launch {
                nodeClient.send(to, payload)
            }
        }

        override fun sendNow(to: Int, payload: ByteArray) {
            scope.launch(Dispatchers.IO) {
                nodeClient.send(to, payload)
            }
        }
    }

    private val events = object : SessionEvents {
        override fun onInviteReceived(sessionId: Int, peerNode: Int, gameType: GameType, difficulty: Int) {
            val diffStr = when (difficulty) {
                1 -> "easy"
                2 -> "medium"
                3 -> "hard"
                else -> config.difficulty
            }
            sessionDifficulties[sessionId] = diffStr
            BotLogger.ai(
                "Входящее приглашение sid=$sessionId от 0x${peerNode.toString(16)} на игру «${gameType.title}» [сложность: ${diffStr.uppercase()}]"
            )
            // Принимаем игру
            sessionManager.respondInvite(sessionId, true)
            // Если морской бой — сразу подтверждаем расстановку кораблей
            if (gameType == GameType.SEA_BATTLE) {
                sessionManager.ready(sessionId)
                BotLogger.ai("sid=$sessionId: Морской бой — расстановка подтверждена (READY)")
            }
        }

        override fun onInviteReceived(sessionId: Int, peerNode: Int, gameType: GameType) {
            onInviteReceived(sessionId, peerNode, gameType, 0)
        }

        override fun onGameStarted(sessionId: Int) {
            val s = sessionManager.session(sessionId) ?: return
            BotLogger.ai("Партия sid=$sessionId началась! Игра: «${s.gameId.title}», моя сторона: ${s.mySide}")
            planMoveIfNeeded(sessionId)
        }

        override fun onOpponentAction(sessionId: Int) {
            val s = sessionManager.session(sessionId) ?: return
            BotLogger.log("S", "Ход оппонента в sid=$sessionId (${s.gameId.title})")
            planMoveIfNeeded(sessionId)
        }

        override fun onDrawOffered(sessionId: Int) {
            val s = sessionManager.session(sessionId)
            val engine = if (s != null) sessionManager.engine(sessionId) else null
            val shouldAccept = if (s != null && engine != null) {
                val diffStr = sessionDifficulties[sessionId] ?: config.difficulty
                val ai = aiEngines.computeIfAbsent(s.gameId to diffStr) { (type, diff) ->
                    BotAiFactory.create(type, diff)
                }
                ai.evaluatePosition(engine) <= 0.2
            } else {
                true
            }
            BotLogger.ai("Соперник предложил ничью в sid=$sessionId -> ${if (shouldAccept) "соглашаемся" else "отклоняем"}")
            sessionManager.respondDraw(sessionId, shouldAccept)
        }

        override fun onGameOver(sessionId: Int, result: GameResult) {
            val s = sessionManager.session(sessionId)
            val mySide = s?.mySide
            val outcome = when {
                result.winner == null -> "Ничья (${result.reason})"
                result.winner == mySide -> "ПОБЕДА бота (${result.reason})"
                else -> "Поражение бота (${result.reason})"
            }
            BotLogger.ai("Партия sid=$sessionId завершена! Итог: $outcome")

            // Фиксация статистики напрямую из GAME_OVER без отдельного эфирного пакета
            if (s != null) {
                val playerA = if (s.mySide == Side.A) config.myId else s.peerNode
                val playerB = if (s.mySide == Side.B) config.myId else s.peerNode
                val winnerCode = when (result.winner) {
                    Side.A -> 0
                    Side.B -> 1
                    null -> 2
                }
                val stat = BridgeCodec.GameStat(
                    gameTypeId = s.gameId.id,
                    sessionId = sessionId,
                    winner = winnerCode,
                    reason = result.reason.ordinal,
                    playerA = playerA,
                    playerB = playerB,
                )
                statsManager.recordGame(stat)
            }
        }

        override fun onSyncConflict(sessionId: Int) {
            BotLogger.warn("Конфликт синхронизации в sid=$sessionId")
        }

        override fun onSessionRecovered(sessionId: Int, toEntry: Int) {
            BotLogger.ai("sid=$sessionId восстановлен после сверки к записи #$toEntry")
            planMoveIfNeeded(sessionId)
        }

        override fun onSessionDeletedByPeer(sessionId: Int, gameType: GameType, peerNode: Int) {
            BotLogger.ai("Соперник 0x${peerNode.toString(16)} удалил сессию sid=$sessionId (${gameType.title})")
        }
    }

    val sessionManager: SessionManager = SessionManager(
        store = JsonSessionStore(File(config.dataDir, "sessions.json")),
        transport = transport,
        events = events,
        sideDecider = { if (Random.nextBoolean()) Side.A else Side.B },
        journals = JsonJournalStore(File(config.dataDir, "journals")),
    )

    fun start() {
        BotLogger.info("Запуск SmartBotManager...")

        nodeClient.statsManager = statsManager

        // Обновляем myId и имя бота при идентификации ноды
        nodeClient.onNodeIdentified = { nodeId, name ->
            if (nodeId != 0 && nodeId != config.myId) {
                BotLogger.info("Идентичность обновлена по данным ноды: 0x${nodeId.toString(16)} ($name)")
                config.myId = nodeId

                // Сохраняем стабильный ID ноды на диск
                try {
                    val idFile = File(config.dataDir, "bot_identity.properties")
                    val props = java.util.Properties()
                    props.setProperty("bot_id", nodeId.toString())
                    idFile.outputStream().use { props.store(it, "MeshGames Autonomous Bot Identity (from node)") }
                } catch (e: Exception) {
                    BotLogger.warn("Не удалось сохранить bot_identity: ${e.message}")
                }
            }
            if (!config.isExplicitName && name.isNotBlank()) {
                config.botName = name
            }
            statsManager.recordPlayerName(config.myId, config.botName)
        }

        // Подключаем транспорт ноды
        nodeClient.start()

        // Маршрутизация входящих пакетов из радио в SessionManager
        scope.launch {
            nodeClient.packets.collect { packet ->
                sessionManager.handleIncoming(packet.from, packet.payload)
            }
        }

        // Периодический тик (1 раз в секунду) для ретрансляций и поддержания сессий
        scope.launch {
            while (isActive) {
                delay(1000)
                try {
                    sessionManager.tick()
                    checkAllActiveSessions()
                } catch (e: Exception) {
                    BotLogger.error("Ошибка в тике", e)
                }
            }
        }
    }

    fun stop() {
        BotLogger.info("Остановка SmartBotManager...")
        nodeClient.stop()
    }

    /**
     * Проверяем все активные сессии: если где-то наступил наш ход или висит спор — отвечаем.
     */
    private fun checkAllActiveSessions() {
        for (view in sessionManager.sessions_.value) {
            val s = view.session
            if (s.closed) continue

            when (s.status) {
                SessionStatus.INVITED -> {
                    sessionManager.respondInvite(s.sessionId, true)
                }
                SessionStatus.PLACEMENT -> {
                    if (!s.myReady) {
                        sessionManager.ready(s.sessionId)
                    }
                }
                SessionStatus.ACTIVE -> {
                    // Спор балды
                    if (sessionManager.pendingVerdict(s.sessionId)) {
                        BotLogger.ai("sid=${s.sessionId}: Принятие вердикта по спорному слову")
                        sessionManager.submitVerdict(s.sessionId, true)
                    } else {
                        planMoveIfNeeded(s.sessionId)
                    }
                }
                else -> Unit
            }
        }
    }

    /**
     * Планирование хода бота в фоновом пуле корутин (многопоточно).
     */
    private fun planMoveIfNeeded(sessionId: Int) {
        val s = sessionManager.session(sessionId) ?: return
        if (s.status != SessionStatus.ACTIVE || s.closed) return
        val engine = sessionManager.engine(sessionId) ?: return
        if (engine.finished || engine.turn != s.mySide) return

        // Если Морской бой и выстрел уже в полёте — ждём результат
        if (engine is com.meshgames.game.engine.SeaBattleEngine && !engine.quiescent) {
            return
        }

        if (!thinkingSessions.add(sessionId)) {
            // Уже вычисляется в параллельной корутине
            return
        }

        scope.launch(Dispatchers.Default) {
            try {
                if (config.thinkDelayMs > 0) {
                    delay(config.thinkDelayMs)
                }

                // Повторно проверяем состояние после задержки
                val currentEngine = sessionManager.engine(sessionId)
                val currentSession = sessionManager.session(sessionId)
                if (currentEngine == null || currentSession == null ||
                    currentSession.status != SessionStatus.ACTIVE || currentSession.closed ||
                    currentEngine.finished || currentEngine.turn != currentSession.mySide
                ) {
                    return@launch
                }

                val diffStr = sessionDifficulties[sessionId] ?: config.difficulty
                val ai = aiEngines.computeIfAbsent(currentSession.gameId to diffStr) { (type, diff) ->
                    BotAiFactory.create(type, diff)
                }

                val move = ai.computeMove(currentEngine)
                if (move != null) {
                    val ok = sessionManager.submitMove(sessionId, move)
                    if (ok) {
                        BotLogger.ai("sid=$sessionId: Ход выполнен [${currentSession.gameId.title}] -> $move")
                    } else {
                        BotLogger.warn("sid=$sessionId: Ход отклонен движком: $move")
                    }
                }
            } catch (e: Exception) {
                BotLogger.error("Ошибка при расчете хода для sid=$sessionId", e)
            } finally {
                thinkingSessions.remove(sessionId)
            }
        }
    }
}
