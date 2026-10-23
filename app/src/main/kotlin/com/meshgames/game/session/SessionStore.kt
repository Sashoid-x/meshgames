package com.meshgames.game.session

import com.meshgames.debug.DebugLog
import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Транспорт для сессий: отправка payload'а узлу. В проде — BridgeTransport.send
 * (TCP-мост), в тестах — игровой транспорт с потерями/задержками.
 */
interface SessionTransport {
    /** Мой номер узла (0 — ещё не известен). */
    val myNodeNum: Int

    /** Асинхронная отправка; ошибки доставки не фатальны (будет ретрансляция). */
    fun sendAsync(to: Int, payload: ByteArray)

    /**
     * Отправка с наивысшим приоритетом (принудительная синхронизация по
     * кнопке): идёт в обход общей очереди [sendAsync] — мимо уже
     * ожидающих запусков. Дефолт — обычная отправка (тестам приоритет
     * не нужен, важна только доставка).
     */
    fun sendNow(to: Int, payload: ByteArray) = sendAsync(to, payload)
}

/** Персистентность сессий (JSON в filesDir). */
interface SessionStore {
    fun load(): List<GameSession>
    fun save(sessions: List<GameSession>)
}

/** Хранилище в памяти (тесты). */
class InMemorySessionStore : SessionStore {
    var data: List<GameSession> = emptyList()

    override fun load(): List<GameSession> = data
    override fun save(sessions: List<GameSession>) {
        data = sessions
    }
}

/** JSON-хранилище на диске. */
class JsonSessionStore(private val file: File) : SessionStore {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun load(): List<GameSession> {
        return try {
            if (!file.exists()) return emptyList()
            json.decodeFromString(file.readText())
        } catch (e: Exception) {
            // Персистентность не должна ронять игру, но молчать о битом
            // файле нельзя: в поле это выглядит как «пропали партии».
            DebugLog.d("S", "ошибка чтения sessions.json: ${e.message} — начнём с пустого списка")
            emptyList()
        }
    }

    override fun save(sessions: List<GameSession>) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(sessions))
            if (!tmp.renameTo(file)) {
                file.writeText(json.encodeToString(sessions))
                tmp.delete()
            }
        } catch (e: Exception) {
            DebugLog.d("S", "ошибка сохранения sessions.json: ${e.message}")
        }
    }
}
