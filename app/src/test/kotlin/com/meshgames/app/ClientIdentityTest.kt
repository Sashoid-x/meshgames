package com.meshgames.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientIdentityTest {

    /** id в «pref'ах» + флаг ожидания HELLO + история записей. */
    private class MemStore(var stored: Int? = null, var pending: Boolean = false) {
        val writes = ArrayList<Pair<Int, Boolean>>()
        fun identity(generate: () -> Int = { 111 }): ClientIdentity =
            ClientIdentity(
                storedId = stored,
                pending = pending,
                generate = generate,
                persist = { id, p -> stored = id; pending = p; writes += id to p },
            )
    }

    @Test
    fun freshInstallStartsOnTemporaryIdAndExpectsHello() {
        val s = MemStore()
        val id = s.identity()
        assertEquals(111, id.id)
        assertTrue(id.migratable)
        // временный id сразу в pref'ах (стабильность без HELLO) + флаг ожидания
        assertEquals(111, s.stored)
        assertTrue(s.pending)
        assertEquals(listOf(111 to true), s.writes)
    }

    @Test
    fun freshInstallAdoptsNodeIdOnFirstHello() {
        val s = MemStore()
        val id = s.identity()
        assertTrue(id.adoptNodeId(777))
        assertEquals(777, id.id)
        assertFalse(id.migratable)
        // nodeId закреплён, флаг ожидания снят — рестарт больше не мигрирует
        assertEquals(777, s.stored)
        assertFalse(s.pending)
    }

    @Test
    fun existingInstallNeverMigrates() {
        // Обновление приложения: id уже лежал в pref'е без флага —
        // идентичность нельзя менять (инвариант узнаваемости).
        val s = MemStore(stored = 4242, pending = false)
        val id = s.identity()
        assertEquals(4242, id.id)
        assertFalse(id.migratable)
        assertFalse(id.adoptNodeId(777))
        assertEquals(4242, id.id)
        assertEquals(4242, s.stored)
        assertTrue(s.writes.isEmpty())
    }

    @Test
    fun pendingFlagSurvivesRestartUntilHello() {
        // Свежая установка умерла до HELLO: флаг пережил рестарт —
        // идентичность всё ещё мигрируема.
        val s = MemStore(stored = 111, pending = true)
        val id = s.identity()
        assertEquals(111, id.id)
        assertTrue(id.migratable)
        assertTrue(id.adoptNodeId(777))
        assertEquals(777, id.id)
        assertEquals(listOf(777 to false), s.writes)
    }

    @Test
    fun restartAfterMigrationIsFinal() {
        val s = MemStore(stored = 777, pending = false)
        val id = s.identity()
        assertFalse(id.adoptNodeId(999))
        assertEquals(777, id.id)
        assertTrue(s.writes.isEmpty())
    }

    @Test
    fun rejectsInvalidAndSameNodeId() {
        val s = MemStore()
        val id = s.identity()
        assertFalse(id.adoptNodeId(0)) // невалидный номер
        assertEquals(111, id.id)
        assertFalse(id.adoptNodeId(111)) // равен текущему — no-op
        assertEquals(111, id.id)
        // флаг ожидания не снят — HELLO с валидным id ещё закрепит
        assertTrue(s.pending)
        assertTrue(id.adoptNodeId(555))
        assertEquals(555, id.id)
    }
}
