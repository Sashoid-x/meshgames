package com.meshgames.game.session

import com.meshgames.debug.DebugLog
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Канонические типы событий журнала ходов: одно событие = одно сообщение,
 * применённое движком (ход, выстрел, результат выстрела).
 */
const val JOURNAL_MOVE = 1
const val JOURNAL_SHOT = 2
const val JOURNAL_SHOT_RESULT = 3

/**
 * Запись канонического журнала ходов одной сессии.
 *
 * Каноническая часть (`kind`, `by`, `payload`) — байт-в-байт одинакова на
 * обеих сторонах: сторона `by` — абсолютная (кто сделал ход), `payload` —
 * те же байты, что летели по сети. Поэтому хвосты записей обеих сторон
 * сравниваются напрямую при сверке (см. SessionManager.reconcile).
 *
 * Локальная часть (`state`, `digest`) — снимок [com.meshgames.game.GameEngine.encodeState]
 * и дайджест ПОСЛЕ события: свои у каждой стороны (морской бой стартует со
 * случайной раскладкой — реплей из нуля невозможен, откат идёт по снимкам).
 *
 * `chain` — цепная FNV-хеш-сумма префикса: равна у обеих сторон, если все
 * записи до этой включительно совпадают. Индекс 0 — базовая точка (без
 * события): состояние движка до первого хода.
 */
@Serializable
data class JournalEntry(
    val index: Int,
    val kind: Int,
    val by: Int,
    val payload: ByteArray = ByteArray(0),
    val state: ByteArray,
    val chain: Int,
    val digest: Int,
    /** Мой исходящий seq кадра с этим событием (-1 — чужая запись/база). */
    val seq: Int = -1,
) {
    override fun equals(other: Any?): Boolean =
        other is JournalEntry && index == other.index && kind == other.kind &&
            by == other.by && chain == other.chain && digest == other.digest &&
            seq == other.seq &&
            payload.contentEquals(other.payload) && state.contentEquals(other.state)

    override fun hashCode(): Int =
        31 * (31 * (31 * (31 * (31 * index + kind) + by) + chain) + digest) + seq
}

/**
 * Журнал ходов сессии: базовая точка (индекс 0) + события. Живёт в
 * [SessionManager.Runtime]; на диск пишется через [JournalStore] (файл на
 * сессию). Записи каноничны — сверка «последних ходов» при расхождении
 * состояний идёт по цепным суммам хвостов, откат — по локальным снимкам.
 */
class MoveJournal {
    private val entries = ArrayList<JournalEntry>()

    val list: List<JournalEntry> get() = entries

    val isEmpty: Boolean get() = entries.isEmpty()

    /** Число записанных событий (базовая точка не считается). */
    val eventCount: Int get() = entries.size - 1

    /** Есть ли несохранённые изменения (для [JournalStore.save] из refresh). */
    var dirty = false
        private set

    /** Восстановление с диска (индексы уже возрастающие). */
    fun restore(loaded: List<JournalEntry>) {
        entries.clear()
        entries.addAll(loaded.sortedBy { it.index })
        dirty = false
    }

    /**
     * Базовая точка (индекс 0): создаёт журнал или обновляет базу, пока не
     * записано ни одного хода (расстановка/подтверждение в морском бое меняют
     * состояние до первого выстрела — база следует за ними).
     */
    fun seedBaseline(state: ByteArray, digest: Int) {
        if (entries.isEmpty()) {
            entries.add(JournalEntry(0, 0, 255, ByteArray(0), state, 0, digest))
            dirty = true
        } else if (eventCount == 0) {
            val base = entries[0]
            if (!base.state.contentEquals(state) || base.digest != digest) {
                entries[0] = base.copy(state = state, digest = digest)
                dirty = true
            }
        }
    }

    /**
     * Записать событие; возвращает индекс записи. [seq] — мой исходящий seq
     * кадра (для досылки «по дырке»); в цепь не входит (seq у сторон разный).
     */
    fun record(
        kind: Int,
        by: Int,
        payload: ByteArray,
        state: ByteArray,
        digest: Int,
        seq: Int = -1,
    ): Int {
        require(entries.isNotEmpty()) { "базовая точка не создана" }
        val chain = chainOf(entries.last().chain, kind, by, payload)
        val index = entries.size
        entries.add(
            JournalEntry(index, kind, by, payload.copyOf(), state, chain, digest, seq),
        )
        dirty = true
        return index
    }

    fun entryAt(index: Int): JournalEntry? = entries.getOrNull(index)

    /** Последние [max] записей (включая базовую, если она в окне). */
    fun tail(max: Int): List<JournalEntry> = entries.takeLast(max)

    /** Оставить записи [0..index] (откат). */
    fun truncateTo(index: Int) {
        if (index + 1 < entries.size) {
            entries.subList(index + 1, entries.size).clear()
            dirty = true
        }
    }

    fun clearDirty() {
        dirty = false
    }

    companion object {
        /** Цепная FNV-1a хеш-сумма: prev-chain + kind + by + payload. */
        fun chainOf(prev: Int, kind: Int, by: Int, payload: ByteArray): Int {
            var h = prev
            fun mix(b: Int) {
                h = h xor (b and 0xFF)
                h *= 0x01000193
            }
            mix(prev and 0xFF)
            mix((prev shr 8) and 0xFF)
            mix((prev shr 16) and 0xFF)
            mix((prev shr 24) and 0xFF)
            mix(kind)
            mix(by)
            for (b in payload) mix(b.toInt())
            return h
        }
    }
}

/** Персист журналов ходов (файл на сессию; удаление — вместе с сессией). */
interface JournalStore {
    /** null — журнала у сессии нет (старое сохранение/ещё не создан). */
    fun load(sessionId: Int): List<JournalEntry>?

    fun save(sessionId: Int, entries: List<JournalEntry>)

    fun delete(sessionId: Int)

    fun list(): Collection<Int>
}

/** Без персиста (по умолчанию; журнал работает в памяти). */
object NoopJournalStore : JournalStore {
    override fun load(sessionId: Int): List<JournalEntry>? = null
    override fun save(sessionId: Int, entries: List<JournalEntry>) = Unit
    override fun delete(sessionId: Int) = Unit
    override fun list(): Collection<Int> = emptyList()
}

/** Хранилище в памяти (тесты). */
class InMemoryJournalStore : JournalStore {
    val data = HashMap<Int, List<JournalEntry>>()

    override fun load(sessionId: Int): List<JournalEntry>? = data[sessionId]
    override fun save(sessionId: Int, entries: List<JournalEntry>) {
        data[sessionId] = entries.toList()
    }

    override fun delete(sessionId: Int) {
        data.remove(sessionId)
    }

    override fun list(): Collection<Int> = data.keys.toList()
}

/** JSON-хранилище на диске: `journal-<sid>.json` (атомарно: tmp + rename). */
class JsonJournalStore(private val dir: File) : JournalStore {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun file(sessionId: Int) = File(dir, "journal-$sessionId.json")

    override fun load(sessionId: Int): List<JournalEntry>? = try {
        val f = file(sessionId)
        if (!f.exists()) null else json.decodeFromString(f.readText())
    } catch (e: Exception) {
        DebugLog.d("S", "ошибка чтения журнала sid=$sessionId: ${e.message}")
        null
    }

    override fun save(sessionId: Int, entries: List<JournalEntry>) {
        try {
            dir.mkdirs()
            val f = file(sessionId)
            val tmp = File(dir, f.name + ".tmp")
            tmp.writeText(json.encodeToString(entries))
            if (!tmp.renameTo(f)) {
                f.writeText(json.encodeToString(entries))
                tmp.delete()
            }
        } catch (e: Exception) {
            // Персистентность не должна ронять игру — но след остаёмся.
            DebugLog.d("S", "ошибка сохранения журнала sid=$sessionId: ${e.message}")
        }
    }

    override fun delete(sessionId: Int) {
        try {
            file(sessionId).delete()
        } catch (e: Exception) {
            DebugLog.d("S", "ошибка удаления журнала sid=$sessionId: ${e.message}")
        }
    }

    override fun list(): Collection<Int> = try {
        dir.listFiles { f -> f.name.startsWith("journal-") && f.name.endsWith(".json") }
            ?.mapNotNull { it.name.removePrefix("journal-").removeSuffix(".json").toIntOrNull() }
            ?: emptyList()
    } catch (e: Exception) {
        DebugLog.d("S", "ошибка списка журналов: ${e.message}")
        emptyList()
    }
}
