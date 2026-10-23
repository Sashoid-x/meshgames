package com.meshgames.game.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Канонический журнал ходов: цепи, откат, базовая точка, персист. */
class MoveJournalTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun sameEventsProduceSameChainsOnBothSides() {
        // Обе стороны пишут одно и то же событие (сторона абсолютная, байты
        // payload — те же, что летели по сети) → цепи обязаны совпасть.
        val a = MoveJournal().apply { seedBaseline(ByteArray(4), 7) }
        val b = MoveJournal().apply { seedBaseline(ByteArray(9), 7) } // снимки разные — не важно
        val payload = byteArrayOf(4)
        a.record(JOURNAL_MOVE, 0, payload, ByteArray(4), 1)
        b.record(JOURNAL_MOVE, 0, payload, ByteArray(9), 1)
        assertEquals(a.entryAt(1)!!.chain, b.entryAt(1)!!.chain)
        assertEquals(0, a.entryAt(0)!!.chain)

        // Другой payload у одной стороны → цепи расходятся (раскол виден).
        b.record(JOURNAL_MOVE, 0, byteArrayOf(0), ByteArray(9), 2)
        a.record(JOURNAL_MOVE, 0, payload, ByteArray(4), 2)
        assertFalse(a.entryAt(2)!!.chain == b.entryAt(2)!!.chain)
    }

    @Test
    fun chainDistinguishesKindAndSide() {
        assertEquals(
            MoveJournal.chainOf(0, JOURNAL_MOVE, 0, byteArrayOf(1)),
            MoveJournal.chainOf(0, JOURNAL_MOVE, 0, byteArrayOf(1)),
        )
        assertFalse(
            MoveJournal.chainOf(0, JOURNAL_MOVE, 0, byteArrayOf(1)) ==
                MoveJournal.chainOf(0, JOURNAL_SHOT, 0, byteArrayOf(1)),
        )
        assertFalse(
            MoveJournal.chainOf(0, JOURNAL_MOVE, 0, byteArrayOf(1)) ==
                MoveJournal.chainOf(0, JOURNAL_MOVE, 1, byteArrayOf(1)),
        )
    }

    @Test
    fun baselineTracksStateUntilFirstEventThenFreezes() {
        val j = MoveJournal()
        j.seedBaseline(byteArrayOf(1), 1)
        // Пока ходов нет (расстановка/подтверждение) — база следует за состоянием.
        j.seedBaseline(byteArrayOf(2), 2)
        assertEquals(byteArrayOf(2).toList(), j.entryAt(0)!!.state.toList())
        assertTrue(j.dirty)

        j.record(JOURNAL_SHOT, 0, byteArrayOf(5), byteArrayOf(3), 3)
        // После первого события база заморожена.
        j.seedBaseline(byteArrayOf(9), 9)
        assertEquals(byteArrayOf(2).toList(), j.entryAt(0)!!.state.toList())
        assertEquals(1, j.eventCount)
    }

    @Test
    fun truncateKeepsPrefixAndTailReturnsLastEntries() {
        val j = MoveJournal()
        j.seedBaseline(ByteArray(0), 0)
        for (i in 1..5) j.record(JOURNAL_MOVE, 0, byteArrayOf(i.toByte()), ByteArray(0), i)
        assertEquals(listOf(1, 2, 3, 4, 5), j.tail(12).map { it.index }.filter { it > 0 })

        j.truncateTo(3)
        assertEquals(3, j.eventCount)
        assertNull(j.entryAt(4))
        assertEquals(3, j.entryAt(3)!!.index)
        assertTrue(j.dirty)
        // Truncate без изменений — без флага.
        j.clearDirty()
        j.truncateTo(3)
        assertFalse(j.dirty)
    }

    @Test
    fun storeRoundtripAndDelete() {
        val store = InMemoryJournalStore()
        val j = MoveJournal()
        j.seedBaseline(byteArrayOf(1), 1)
        j.record(JOURNAL_MOVE, 0, byteArrayOf(4), byteArrayOf(2), 2)
        store.save(42, j.list)

        val loaded = store.load(42)!!
        assertEquals(2, loaded.size)
        assertEquals(j.entryAt(1)!!.chain, loaded[1].chain)
        assertEquals(listOf(42), store.list().toList())

        store.delete(42)
        assertNull(store.load(42))
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun jsonStorePersistsOnDiskAndListsFiles() {
        val store = JsonJournalStore(tmp.newFolder("journals"))
        val j = MoveJournal()
        j.seedBaseline(byteArrayOf(1), 1)
        j.record(JOURNAL_SHOT_RESULT, 1, byteArrayOf(3, 1), byteArrayOf(2), 5)
        store.save(7, j.list)
        store.save(8, j.list)

        val loaded = store.load(7)!!
        assertEquals(2, loaded.size)
        assertEquals(byteArrayOf(3, 1).toList(), loaded[1].payload.toList())
        assertEquals(setOf(7, 8), store.list().toSet())

        store.delete(7)
        assertNull(store.load(7))
        assertEquals(setOf(8), store.list().toSet())
    }

    @Test
    fun noopStoreIsSilent() {
        NoopJournalStore.save(1, MoveJournal().list)
        assertNull(NoopJournalStore.load(1))
        NoopJournalStore.delete(1)
        assertTrue(NoopJournalStore.list().isEmpty())
    }
}
