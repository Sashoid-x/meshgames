package com.meshgames.bot.log

import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Потокобезопасный логгер для автономного сервера бота.
 * Выводит сообщения с ANSI-цветами в консоль и дублирует в файл лога.
 */
object BotLogger {
    private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private var logWriter: PrintWriter? = null
    private val lock = Any()

    // ANSI цвета для терминала Linux
    private const val RESET = "\u001B[0m"
    private const val RED = "\u001B[31m"
    private const val GREEN = "\u001B[32m"
    private const val YELLOW = "\u001B[33m"
    private const val BLUE = "\u001B[34m"
    private const val PURPLE = "\u001B[35m"
    private const val CYAN = "\u001B[36m"
    private const val GRAY = "\u001B[90m"
    private const val BOLD = "\u001B[1m"

    fun initFile(file: File) {
        synchronized(lock) {
            try {
                file.parentFile?.mkdirs()
                logWriter = PrintWriter(FileWriter(file, true), true)
                log("LOG", "Логирование в файл: ${file.absolutePath}")
            } catch (e: Exception) {
                System.err.println("Не удалось открыть файл лога ${file.absolutePath}: ${e.message}")
            }
        }
    }

    fun log(tag: String, msg: String) {
        val now = timeFmt.format(Date())
        val color = when (tag) {
            "TX" -> GREEN
            "TX*" -> GRAY
            "RX" -> BLUE
            "BR" -> CYAN
            "AI", "BOT" -> PURPLE
            "S" -> YELLOW
            "ERR", "ERROR" -> RED
            else -> RESET
        }

        val consoleLine = "$GRAY$now$RESET [$color$tag$RESET] $msg"
        val plainLine = "$now [$tag] $msg"

        synchronized(lock) {
            println(consoleLine)
            logWriter?.println(plainLine)
        }
    }

    fun info(msg: String) = log("INFO", msg)
    fun ai(msg: String) = log("AI", msg)
    fun warn(msg: String) = log("WARN", msg)
    fun error(msg: String, throwable: Throwable? = null) {
        log("ERR", if (throwable != null) "$msg: ${throwable.message}\n${throwable.stackTraceToString()}" else msg)
    }

    fun banner(botName: String, host: String, port: Int, myIdHex: String) {
        val b = StringBuilder()
        b.append("$BOLD$CYAN========================================================$RESET\n")
        b.append("$BOLD$PURPLE  MeshGames Autonomous Bot Server $RESET$GRAY(v1.0.0)$RESET\n")
        b.append("$BOLD$CYAN========================================================$RESET\n")
        b.append("  $GREEN•$RESET Имя бота:       $BOLD$botName$RESET\n")
        b.append("  $GREEN•$RESET Подключение:    $BOLD$host:$port$RESET\n")
        b.append("  $GREEN•$RESET Client ID:      $BOLD$myIdHex$RESET\n")
        b.append("  $GREEN•$RESET Режим:          ${BOLD}Всегда в сети / Многопоточный$RESET\n")
        b.append("  $GREEN•$RESET Поддержка игр:  ${BOLD}Крестики-нолики, Шахматы, Шашки,$RESET\n")
        b.append("                         ${BOLD}Морской бой, Балда, Дурак$RESET\n")
        b.append("$BOLD$CYAN========================================================$RESET")
        println(b.toString())
    }
}
