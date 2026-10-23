package com.meshgames.debug

import com.meshgames.bot.log.BotLogger

/**
 * JVM-реализация DebugLog для bot_server (заменяет Android-специфичный логгер).
 * Перенаправляет все диагностические вызовы движков и сессионного слоя в BotLogger.
 */
object DebugLog {
    @Volatile
    var verbose: Boolean = true

    fun d(tag: String, msg: String) {
        if (!verbose && (tag == "TX*" || tag == "PING")) return
        BotLogger.log(tag, msg)
    }
}
