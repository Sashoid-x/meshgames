package com.meshgames.bot

import com.meshgames.bot.log.BotLogger
import com.meshgames.bot.session.SmartBotManager
import com.meshgames.game.engine.GameEngines
import java.io.File
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val config = BotConfig.parseArgs(args)

    // Инициализация лог-файла в папке данных
    BotLogger.initFile(File(config.dataDir, "bot_server.log"))

    // Вывод приветственного баннера
    println("""
        ==================================================================
           __  __           _      ____                              
          |  \/  | ___  ___| |__  / ___| __ _ _ __ ___   ___  ___   
          | |\/| |/ _ \/ __| '_ \| |  _ / _` | '_ ` _ \ / _ \/ __|  
          | |  | |  __/\__ \ | | | |_| | (_| | | | | | |  __/\__ \  
          |_|  |_|\___||___/_| |_|\____|\__,_|_| |_| |_|\___||___/  
                                    AUTONOMOUS SERVER & AI BOT
        ==================================================================
          Бот:              ${config.botName} [${config.difficulty.uppercase()}]
          Имя:              ${config.botName}${if (config.isExplicitName) " (задано аргументом)" else " (авто/по умолчанию)"}
          Идентичность:     0x${config.myId.toString(16).uppercase()}
          Нода Meshtastic:  ${config.nodeHost}:${config.nodePort} (PortNum: ${config.bridgePortnum}, Ch: ${config.channelIndex}, HopLimit: ${config.hopLimit})
          Папка данных:     ${config.dataDir.absolutePath}
        ==================================================================
    """.trimIndent())

    // Загрузка словаря Балды до старта менеджера сессий
    try {
        val dict = BaldaDictionaryLoader.loadDefault()
        GameEngines.wordChecker = dict
        BotLogger.info("Словарь Балды успешно загружен (слов: ${dict.startWords().size}+)")
    } catch (e: Exception) {
        BotLogger.warn("Ошибка загрузки словаря Балды: ${e.message}")
    }

    val botManager = SmartBotManager(config)
    botManager.statsManager.recordPlayerName(config.myId, config.botName)
    botManager.start()

    Runtime.getRuntime().addShutdownHook(Thread {
        BotLogger.info("Остановка сервера бота...")
        botManager.stop()
    })

    BotLogger.info("Сервер готов к игре! Ожидание входящих приглашений по сети Meshtastic...")
    BotLogger.info("Доступные команды консоли: 'status', 'table', 'routing', 'presence', 'quit'")

    // Консольный цикл управления
    val reader = System.`in`.bufferedReader()
    while (true) {
        val line = reader.readLine() ?: break
        when (line.trim().lowercase()) {
            "status", "s" -> {
                val sessions = botManager.sessionManager.sessions_.value
                println("\n--- Активные сессии (${sessions.size}) ---")
                if (sessions.isEmpty()) {
                    println("  (нет активных сессий)")
                } else {
                    for (view in sessions) {
                        val s = view.session
                        println("  sid=${s.sessionId} | ${s.gameId.title} | статус=${s.status} | оппонент=0x${s.peerNode.toString(16)} | моя сторона=${s.mySide}")
                    }
                }
                println("------------------------------------------\n")
            }
            "table", "t" -> {
                println(botManager.statsManager.formatFullTable())
            }
            "routing", "r" -> {
                val table = botManager.nodeClient.routingTable.snapshot()
                println("\n--- Таблица маршрутизации (clientId -> loraFrom) (${table.size}) ---")
                if (table.isEmpty()) {
                    println("  (таблица пуста)")
                } else {
                    for ((clientId, nodeNum) in table) {
                        println("  clientId=0x${clientId.toString(16)} -> nodeNum=0x${nodeNum.toString(16)}")
                    }
                }
                println("---------------------------------------------------------\n")
            }
            "presence", "p" -> {
                BotLogger.info("Ручная отправка presence в канал...")
                botManager.nodeClient.announcePresence(force = true)
            }
            "quit", "exit", "q" -> {
                BotLogger.info("Завершение работы...")
                botManager.stop()
                exitProcess(0)
            }
            "help", "?" -> {
                println("Команды:")
                println("  status   (s) - состояние игровых сессий")
                println("  table    (t) - статистика проведенных игр")
                println("  routing  (r) - карта маршрутизации (clientId -> loraFrom)")
                println("  presence (p) - отправить присутствие в сеть")
                println("  quit     (q) - остановить сервер")
            }
        }
    }
}
