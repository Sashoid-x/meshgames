package com.meshgames.debug

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Диагностический лог приложения: кольцевой буфер в памяти (для панели в UI)
 * + дозапись в файл `meshgames-<clientId>.log` во внешнем каталоге приложения
 * (`Android/data/com.meshgames/files/`) — его можно забрать через файловый
 * менеджер/adb и прислать разработчику. Имя файла содержит clientId в hex —
 * тот же идентификатор, что в wrapper'е моста, так что лог легко коррелировать
 * с экспортом пакетов из приложения Meshtastic (форк с Mesh Bridge).
 *
 * Пишут: SessionManager (TX/RX/тик/диспатч), BridgeTransport (соединение,
 * приём/фильтры), AppGraph (режимы, link, presence). Потокобезопасно.
 * Объём ограничен: последние [MAX_LINES] строк в памяти, файл обрезается
 * при превышении [MAX_FILE_BYTES] (старый уходит в `*.old`).
 */
object DebugLog {
    private const val MAX_LINES = 500
    private const val MAX_FILE_BYTES = 1_000_000L

    /** Минимальный интервал публикации хвоста в UI (мс). */
    private const val EMIT_GAP_MS = 250L

    private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val lines = ArrayDeque<String>()
    private val _tail = MutableStateFlow<List<String>>(emptyList())

    // Throttle публикации: StateFlow для панели обновляется не чаще раза в
    // EMIT_GAP_MS, иначе пачки записей (тик, повторы) колотят recomposition
    // оверлея. Строки не теряются: «частый» хвост публикуется отложенным
    // тиком демон-потока. Порядок блокировок: монитор объекта ИЛИ emitLock —
    // никогда одновременно (дедлок исключён по построению).
    private val emitLock = Any()
    private var lastEmitMs = 0L
    private var trailingScheduled = false
    private val emitter = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "meshgames-debuglog").apply { isDaemon = true }
    }

    /** Последние строки для панели в UI (с throttle, см. [emitTail]). */
    val tail: StateFlow<List<String>> = _tail.asStateFlow()

    @Volatile private var file: File? = null
    @Volatile private var clientIdTag: String = "????????"

    /** Инициализация (один раз в AppGraph): файл + шапка с идентичностью. */
    @Synchronized
    fun init(context: Context, clientId: Int) {
        clientIdTag = clientId.toString(16).padStart(8, '0').uppercase(Locale.US)
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val f = File(dir, "meshgames-$clientIdTag.log")
        rotateIfNeeded(f)
        file = f
        append("LOG", "лог начат; clientId=0x$clientIdTag; файл=${f.absolutePath}")
    }

    /** Записать строку: время, тег, сообщение. */
    @Synchronized
    fun d(tag: String, msg: String) {
        val line = "${timeFmt.format(Date())} [$tag] $msg"
        lines.addLast(line)
        while (lines.size > MAX_LINES) lines.removeFirst()
        try {
            file?.appendText(line + "\n")
        } catch (_: Exception) {
            // Файл недоступен — панель в UI всё равно работает.
        }
        emitTail()
    }

    /**
     * Публикация хвоста в UI не чаще раза в [EMIT_GAP_MS]: при частых
     * записях хвост догоняет отложенным тиком ([trailingScheduled]) —
     * ни одна строка не теряется, recomposition получает ≤4 обновления/с.
     */
    private fun emitTail() {
        val now = System.currentTimeMillis()
        synchronized(emitLock) {
            if (now - lastEmitMs < EMIT_GAP_MS) {
                if (!trailingScheduled) {
                    trailingScheduled = true
                    emitter.schedule(
                        {
                            synchronized(emitLock) { trailingScheduled = false }
                            emitTail()
                        },
                        EMIT_GAP_MS,
                        TimeUnit.MILLISECONDS,
                    )
                }
                return
            }
            lastEmitMs = now
        }
        publishTail()
    }

    /** Снять копию буфера и опубликовать атомарно (под монитором объекта). */
    private fun publishTail() {
        synchronized(this) { _tail.value = lines.toList() }
    }

    /** Весь буфер одним текстом (для копирования в буфер обмена). */
    @Synchronized
    fun snapshot(): String = lines.joinToString("\n")

    @Synchronized
    fun clear() {
        lines.clear()
        publishTail()
    }

    fun filePath(): String? = file?.absolutePath

    fun clientIdTag(): String = clientIdTag

    /**
     * Экспорт полного файла лога в «Загрузки» (MediaStore, без разрешений):
     * файл появится в Файлы → Загрузки с понятным именем. Возвращает имя
     * файла или null при ошибке.
     */
    fun exportToDownloads(context: Context): String? {
        val stamp = SimpleDateFormat("MMdd-HHmm", Locale.US).format(Date())
        val name = "meshgames-$clientIdTag-$stamp.log"
        return try {
            val bytes = try {
                file?.readBytes() ?: snapshot().toByteArray()
            } catch (_: Exception) {
                snapshot().toByteArray()
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = context.contentResolver
                .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            name
        } catch (_: Exception) {
            null
        }
    }

    private fun append(tag: String, msg: String) = d(tag, msg)

    private fun rotateIfNeeded(f: File) {
        try {
            if (f.exists() && f.length() > MAX_FILE_BYTES) {
                val old = File(f.parentFile, f.name + ".old")
                old.delete()
                f.renameTo(old)
            }
        } catch (_: Exception) {
        }
    }
}
