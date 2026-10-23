package com.meshgames.bot

import com.meshgames.bot.net.MeshBridgeRouter
import com.meshgames.bot.net.RouteDestination
import com.meshgames.bot.net.RoutingTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingTest {

    @Test
    fun testRoutingTableLruAndOverwrite() {
        val table = RoutingTable(capacity = 3)

        table.record(1, 100)
        table.record(2, 200)
        table.record(3, 300)

        assertEquals(3, table.size())
        assertEquals(100, table.resolve(1))
        assertEquals(200, table.resolve(2))
        assertEquals(300, table.resolve(3))

        // Перезапись при новом узле того же clientId
        table.record(2, 250)
        assertEquals(250, table.resolve(2))

        // Обращение к 1 делает его недавно использованным
        assertEquals(100, table.resolve(1))

        // Добавляем 4-й элемент -> вытесняется 3 (так как к 2 и 1 недавно обращались)
        table.record(4, 400)
        assertEquals(3, table.size())
        assertEquals(100, table.resolve(1))
        assertEquals(250, table.resolve(2))
        assertEquals(400, table.resolve(4))
        assertNull(table.resolve(3))
    }

    @Test
    fun testRouterCases() {
        val table = RoutingTable(capacity = 32)
        table.record(0x1234, 0xABCDE)

        val presenceBody = byteArrayOf(0x50, 1, 0, 4, 'T'.code.toByte(), 'e'.code.toByte(), 's'.code.toByte(), 't'.code.toByte())
        val envelopeBody = byteArrayOf(0x47, 2, 1, 0, 1, 0, 0, 0, 0, 0, 0, 0)
        val unknownBody = byteArrayOf(0x99.toByte(), 1, 2)

        // 1. Presence -> Broadcast
        val r1 = MeshBridgeRouter.route(0x111, 0, presenceBody, table)
        assertTrue(r1 is RouteDestination.Broadcast)

        // 2. Envelope to=0 -> Broadcast
        val r2 = MeshBridgeRouter.route(0x111, 0, envelopeBody, table)
        assertTrue(r2 is RouteDestination.Broadcast)

        // 3. Envelope to != 0 (есть в карте) -> Unicast
        val r3 = MeshBridgeRouter.route(0x111, 0x1234, envelopeBody, table)
        assertTrue(r3 is RouteDestination.Unicast)
        assertEquals(0xABCDE, (r3 as RouteDestination.Unicast).nodeNum)

        // 4. Envelope to != 0 (нет в карте) -> Broadcast fallback
        val r4 = MeshBridgeRouter.route(0x111, 0x9999, envelopeBody, table)
        assertTrue(r4 is RouteDestination.Broadcast)

        // 5. Unknown magic -> Broadcast
        val r5 = MeshBridgeRouter.route(0x111, 0x1234, unknownBody, table)
        assertTrue(r5 is RouteDestination.Broadcast)

        // 6. Empty body -> Broadcast
        val r6 = MeshBridgeRouter.route(0x111, 0x1234, byteArrayOf(), table)
        assertTrue(r6 is RouteDestination.Broadcast)
    }
}
