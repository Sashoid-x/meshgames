package com.meshgames.bot

import com.meshgames.bot.net.StreamFrameCodec
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties
import kotlin.random.Random

/**
 * Конфигурация автономного сервера MeshGames Bot.
 */
data class BotConfig(
    // Параметры подключения к ноде Meshtastic (TCP PhoneAPI порт 4403)
    var nodeHost: String = "127.0.0.1",
    var nodePort: Int = StreamFrameCodec.DEFAULT_NODE_PORT,
    var channelIndex: Int = 0,
    var hopLimit: Int = 3,
    var bridgePortnum: Int = 69, // SIMULATOR_APP

    // Игровые параметры бота
    var botName: String = DEFAULT_BOT_NAME,
    var isExplicitName: Boolean = false, // true если задано через аргументы запуска (--name / -n)
    var myId: Int = 0,
    var difficulty: String = "hard",
    var thinkDelayMs: Long = 1000L,
    var presenceIntervalMs: Long = 10 * 60 * 1000L,
    var dataDir: File = File("bot_data"),
    var verbose: Boolean = true,
) {
    companion object {
        const val DEFAULT_BOT_NAME = "MeshBot"

        fun loadOrInit(dataDir: File = File("bot_data")): BotConfig {
            dataDir.mkdirs()
            val idFile = File(dataDir, "bot_identity.properties")
            val props = Properties()
            var id = 0

            if (idFile.exists()) {
                try {
                    FileInputStream(idFile).use { props.load(it) }
                    id = props.getProperty("bot_id")?.toIntOrNull() ?: 0
                } catch (_: Exception) {}
            }

            if (id == 0) {
                // Генерируем случайный стабильный положительный u32 clientId
                id = Random.nextInt(0x100000, 0x7FFFFFFF)
                props.setProperty("bot_id", id.toString())
                try {
                    FileOutputStream(idFile).use { props.store(it, "MeshGames Autonomous Bot Identity") }
                } catch (_: Exception) {}
            }

            return BotConfig(myId = id, dataDir = dataDir)
        }

        fun parseArgs(args: Array<String>): BotConfig {
            val cfg = loadOrInit()
            var i = 0
            while (i < args.size) {
                when (args[i]) {
                    "--node-host", "-nh", "--host", "-h" -> {
                        if (i + 1 < args.size) cfg.nodeHost = args[++i]
                    }
                    "--node-port", "-np", "--port", "-p" -> {
                        if (i + 1 < args.size) cfg.nodePort = args[++i].toIntOrNull() ?: cfg.nodePort
                    }
                    "--channel", "-c" -> {
                        if (i + 1 < args.size) cfg.channelIndex = args[++i].toIntOrNull() ?: cfg.channelIndex
                    }
                    "--hop-limit", "-hl" -> {
                        if (i + 1 < args.size) cfg.hopLimit = args[++i].toIntOrNull() ?: cfg.hopLimit
                    }
                    "--portnum" -> {
                        if (i + 1 < args.size) cfg.bridgePortnum = args[++i].toIntOrNull() ?: cfg.bridgePortnum
                    }
                    "--name", "-n" -> {
                        if (i + 1 < args.size) {
                            val n = args[++i].trim()
                            if (n.isNotEmpty()) {
                                cfg.botName = n
                                cfg.isExplicitName = true
                            }
                        }
                    }
                    "--id" -> {
                        if (i + 1 < args.size) {
                            val raw = args[++i]
                            cfg.myId = if (raw.startsWith("0x", ignoreCase = true)) {
                                raw.substring(2).toLong(16).toInt()
                            } else {
                                raw.toIntOrNull() ?: cfg.myId
                            }
                        }
                    }
                    "--difficulty", "-d" -> {
                        if (i + 1 < args.size) cfg.difficulty = args[++i].lowercase()
                    }
                    "--delay" -> {
                        if (i + 1 < args.size) cfg.thinkDelayMs = args[++i].toLongOrNull() ?: cfg.thinkDelayMs
                    }
                    "--dir" -> {
                        if (i + 1 < args.size) cfg.dataDir = File(args[++i])
                    }
                    "--quiet", "-q" -> {
                        cfg.verbose = false
                    }
                }
                i++
            }
            return cfg
        }
    }
}
