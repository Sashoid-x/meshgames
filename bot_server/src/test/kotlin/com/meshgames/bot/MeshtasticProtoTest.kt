package com.meshgames.bot

import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.meshtastic.proto.Data
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.ToRadio

class MeshtasticProtoTest {

    @Test
    fun testBuildAndEncodeToRadioPacket() {
        val payloadBytes = byteArrayOf(1, 2, 3, 4, 0x47, 0x02)
        val data = Data.Builder()
            .portnum(PortNum.SIMULATOR_APP)
            .payload(payloadBytes.toByteString())
            .build()

        val packet = MeshPacket.Builder()
            .to(-1) // broadcast 0xFFFFFFFF
            .channel(0)
            .want_ack(false)
            .decoded(data)
            .build()

        val toRadio = ToRadio.Builder()
            .packet(packet)
            .build()

        val encoded = ToRadio.ADAPTER.encode(toRadio)
        assertNotNull(encoded)

        val decoded = ToRadio.ADAPTER.decode(encoded)
        assertNotNull(decoded.packet)
        assertEquals(-1, decoded.packet?.to)
        assertEquals(false, decoded.packet?.want_ack)
        assertEquals(PortNum.SIMULATOR_APP, decoded.packet?.decoded?.portnum)
        assertEquals(payloadBytes.size, decoded.packet?.decoded?.payload?.size)
    }
}
