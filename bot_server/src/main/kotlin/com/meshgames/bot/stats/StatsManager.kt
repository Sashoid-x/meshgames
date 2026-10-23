package com.meshgames.bot.stats

import com.meshgames.bot.log.BotLogger
import com.meshgames.game.GameType
import com.meshgames.game.ResultReason
import com.meshgames.net.bridge.BridgeCodec
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class StoredGame(
    val sessionId: Int,
    val gameTypeId: Int,
    val playerA: Int,
    val playerB: Int,
    val winner: Int, // 0 = A, 1 = B, 2 = Draw
    val reason: Int, // ResultReason ordinal
    val timestampMs: Long,
)

@Serializable
data class StatsData(
    val games: MutableList<StoredGame> = mutableListOf(),
    val playerNames: MutableMap<String, String> = mutableMapOf(),
)

data class PlayerRecord(
    val nodeId: Int,
    val name: String,
    var games: Int = 0,
    var wins: Int = 0,
    var losses: Int = 0,
    var draws: Int = 0,
) {
    val winRate: Double
        get() = if (games > 0) (wins * 100.0) / games else 0.0
}

/**
 * Менеджер персистентной статистики сыгранных партий.
 * Хранит историю игр, имена игроков, считает победы/поражения/ничьи
 * и формирует сводки для консоли сервера ('table') и радио-канала ('!table').
 */
class StatsManager(
    val file: File,
) {
    private val lock = Any()
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private val data: StatsData = load()
    private val recordedGameKeys = HashSet<String>()

    init {
        synchronized(lock) {
            for (g in data.games) {
                recordedGameKeys.add(gameKey(g.sessionId, g.gameTypeId, g.playerA, g.playerB))
            }
        }
    }

    private fun gameKey(sessionId: Int, gameTypeId: Int, playerA: Int, playerB: Int): String =
        "${sessionId}_${gameTypeId}_${playerA}_${playerB}"

    private fun load(): StatsData {
        if (!file.exists()) return StatsData()
        return try {
            val content = file.readText()
            json.decodeFromString<StatsData>(content)
        } catch (e: Exception) {
            BotLogger.warn("Ошибка чтения ${file.name}: ${e.message}, создана новая база статистики")
            StatsData()
        }
    }

    private fun save() {
        try {
            val content = json.encodeToString(data)
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(content)
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            BotLogger.warn("Ошибка сохранения ${file.name}: ${e.message}")
        }
    }

    fun recordPlayerName(nodeId: Int, name: String) {
        if (nodeId == 0 || name.isBlank() || name == "—") return
        synchronized(lock) {
            val key = nodeId.toString()
            if (data.playerNames[key] != name) {
                data.playerNames[key] = name
                save()
            }
        }
    }

    fun getPlayerName(nodeId: Int): String {
        synchronized(lock) {
            val name = data.playerNames[nodeId.toString()]
            if (!name.isNullOrBlank()) return name
            return "0x${nodeId.toString(16).uppercase()}"
        }
    }

    fun recordGame(stat: BridgeCodec.GameStat, timestampMs: Long = System.currentTimeMillis()): Boolean {
        synchronized(lock) {
            val key = gameKey(stat.sessionId, stat.gameTypeId, stat.playerA, stat.playerB)
            if (!recordedGameKeys.add(key)) {
                return false // Дубликат уже зафиксирован
            }

            val game = StoredGame(
                sessionId = stat.sessionId,
                gameTypeId = stat.gameTypeId,
                playerA = stat.playerA,
                playerB = stat.playerB,
                winner = stat.winner,
                reason = stat.reason,
                timestampMs = timestampMs,
            )
            data.games.add(game)
            save()
            BotLogger.info("Статистика: зафиксирована игра sid=${stat.sessionId} (${GameType.fromId(stat.gameTypeId)?.title ?: stat.gameTypeId})")
            return true
        }
    }

    fun getLeaderboard(): List<PlayerRecord> {
        synchronized(lock) {
            val records = mutableMapOf<Int, PlayerRecord>()

            fun getRecord(nodeId: Int): PlayerRecord =
                records.computeIfAbsent(nodeId) { id ->
                    PlayerRecord(nodeId = id, name = getPlayerName(id))
                }

            for (g in data.games) {
                val pA = getRecord(g.playerA)
                val pB = getRecord(g.playerB)
                pA.games++
                pB.games++

                when (g.winner) {
                    0 -> { // Side.A won
                        pA.wins++
                        pB.losses++
                    }
                    1 -> { // Side.B won
                        pB.wins++
                        pA.losses++
                    }
                    else -> { // Draw
                        pA.draws++
                        pB.draws++
                    }
                }
            }

            return records.values.sortedWith(
                compareByDescending<PlayerRecord> { it.wins }
                    .thenByDescending { it.winRate }
                    .thenByDescending { it.games }
                    .thenBy { it.name }
            )
        }
    }

    /**
     * Форматирует подробную таблицу для консоли сервера (команда 'table' / 't').
     */
    fun formatFullTable(): String {
        synchronized(lock) {
            if (data.games.isEmpty()) {
                return "\n============================== ТАБЛИЦА СТАТИСТИКИ ==============================\n" +
                    "  Статистика пуста. Игры еще не проводились.\n" +
                    "================================================================================\n"
            }

            val sb = StringBuilder()
            sb.append("\n=================================== ТАБЛИЦА СТАТИСТИКИ ===================================\n")
            sb.append("Всего проведенных игр: ${data.games.size}\n")

            // Разбивка по играм
            val byGame = data.games.groupingBy { it.gameTypeId }.eachCount()
            val gameBreakdown = GameType.entries.mapNotNull { gt ->
                val count = byGame[gt.id] ?: 0
                if (count > 0) "${gt.title}: $count" else null
            }.joinToString(", ")
            if (gameBreakdown.isNotEmpty()) {
                sb.append("По играм: $gameBreakdown\n")
            }
            sb.append("------------------------------------------------------------------------------------------\n")
            sb.append(String.format(Locale.ROOT, "%-3s | %-24s | %-4s | %-5s | %-9s | %-6s | %-7s\n",
                "№", "Игрок", "Игр", "Побед", "Поражений", "Ничьих", "Винрейт"))
            sb.append("----+--------------------------+------+-------+-----------+--------+---------\n")

            val leaderboard = getLeaderboard()
            for ((idx, p) in leaderboard.withIndex()) {
                val displayName = if (p.name.startsWith("0x")) p.name else "${p.name} (0x${p.nodeId.toString(16).uppercase()})"
                val truncated = if (displayName.length > 24) displayName.take(21) + "..." else displayName
                sb.append(String.format(Locale.ROOT, "%-3d | %-24s | %4d | %5d | %9d | %6d | %6.1f%%\n",
                    idx + 1, truncated, p.games, p.wins, p.losses, p.draws, p.winRate))
            }
            sb.append("------------------------------------------------------------------------------------------\n")

            // Последние 5 игр
            val recent = data.games.takeLast(5).reversed()
            if (recent.isNotEmpty()) {
                sb.append("Последние партии:\n")
                val sdf = SimpleDateFormat("dd.MM HH:mm", Locale.ROOT)
                for (g in recent) {
                    val gTitle = GameType.fromId(g.gameTypeId)?.title ?: "Игра #${g.gameTypeId}"
                    val nameA = getPlayerName(g.playerA)
                    val nameB = getPlayerName(g.playerB)
                    val outcome = when (g.winner) {
                        0 -> "Победил: $nameA"
                        1 -> "Победил: $nameB"
                        else -> "Ничья"
                    }
                    val reason = ResultReason.entries.getOrElse(g.reason) { ResultReason.OPPONENT_LEFT }
                    val timeStr = sdf.format(Date(g.timestampMs))
                    sb.append(" • $timeStr | $gTitle | $nameA vs $nameB -> $outcome ($reason)\n")
                }
            }
            sb.append("==========================================================================================\n")
            return sb.toString()
        }
    }

    /**
     * Форматирует краткую сводку для отправки в радио-канал в ответ на '!table'.
     * Строго соблюдает ограничение: максимум 100 символов кириллицы (<= 200 байт UTF-8).
     */
    fun formatBriefTable(): String {
        synchronized(lock) {
            if (data.games.isEmpty()) {
                return "Игр пока нет. Начните партию в MeshGames!"
            }

            val sb = StringBuilder()
            val total = data.games.size
            sb.append("🏆 Топ MeshGames (игр: $total):")

            val top = getLeaderboard().take(3)
            for ((idx, p) in top.withIndex()) {
                val shortName = p.name.take(9)
                sb.append("\n${idx + 1}. $shortName: ${p.games}и ${p.wins}В/${p.draws}Н/${p.losses}П")
            }

            var res = sb.toString()
            if (res.length > 100) {
                res = res.take(100)
            }
            val bytes = res.toByteArray(Charsets.UTF_8)
            if (bytes.size > 200) {
                var len = 200
                while (len > 0 && (bytes[len].toInt() and 0xC0) == 0x80) len--
                res = String(bytes, 0, len, Charsets.UTF_8)
            }
            return res
        }
    }
}
