package com.meshgames.bot

import com.meshgames.bot.net.MeshtasticNodeClient
import com.meshgames.bot.net.RouteDestination
import com.meshgames.bot.net.StreamFrameCodec
import com.meshgames.net.bridge.BridgeCodec
import com.meshgames.net.bridge.BridgeFrameBuffer
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.meshtastic.proto.Data
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.MyNodeInfo
import org.meshtastic.proto.NodeInfo
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.ToRadio
import org.meshtastic.proto.User

class NodeIntegrationTest {

    private lateinit var testScope: CoroutineScope
    private var mockRadioServer: ServerSocket? = null
    private var mockRadioPort: Int = 0

    @Before
    fun setUp() {
        testScope = CoroutineScope(Dispatchers.Default + Job())
        mockRadioServer = ServerSocket(0)
        mockRadioServer!!.soTimeout = 5000
        mockRadioPort = mockRadioServer!!.localPort
    }

    @After
    fun tearDown() {
        try {
            mockRadioServer?.close()
        } catch (_: Exception) {}
        testScope.cancel()
    }

    @Test(timeout = 10000)
    fun testNodeClientConnectionAndPacketExchange() = runBlocking {
        val radioConnectedLatch = CountDownLatch(1)
        val envelopeReceivedLatch = CountDownLatch(1)
        var receivedEnvelopeMesh: MeshPacket? = null
        var radioClientSocket: Socket? = null

        val mockRadioJob = testScope.launch(Dispatchers.IO) {
            val client = try {
                mockRadioServer!!.accept()
            } catch (e: Exception) {
                return@launch
            }
            radioClientSocket = client
            client.soTimeout = 5000
            radioConnectedLatch.countDown()
            val input = BufferedInputStream(client.getInputStream())
            val output = BufferedOutputStream(client.getOutputStream())

            val codec = StreamFrameCodec { packetBytes ->
                val tr = ToRadio.ADAPTER.decode(packetBytes)
                val pkt = tr.packet
                val dec = pkt?.decoded
                if (dec != null) {
                    val pld = dec.payload.toByteArray()
                    if (pld.size >= 9 && pld[8] == 0x47.toByte()) {
                        receivedEnvelopeMesh = pkt
                        envelopeReceivedLatch.countDown()
                    }
                }
            }

            // Читаем 4 wake-байта
            val wake = ByteArray(4)
            var readWake = 0
            while (readWake < 4) {
                val n = input.read(wake, readWake, 4 - readWake)
                if (n < 0) break
                readWake += n
            }

            // Отправляем FromRadio с MyNodeInfo
            val myInfo = FromRadio.Builder()
                .my_info(MyNodeInfo.Builder().my_node_num(0x55AA1122).build())
                .build()
            output.write(StreamFrameCodec.framePacket(FromRadio.ADAPTER.encode(myInfo)))

            // Отправляем FromRadio с NodeInfo (имя ноды)
            val nodeInfo = FromRadio.Builder()
                .node_info(
                    NodeInfo.Builder()
                        .num(0x55AA1122)
                        .user(User.Builder().long_name("TestNode-Radio").build())
                        .build()
                )
                .build()
            output.write(StreamFrameCodec.framePacket(FromRadio.ADAPTER.encode(nodeInfo)))

            // Отправляем FromRadio с MeshPacket SIMULATOR_APP от пира 0x99887766
            val peerPayload = ByteArray(BridgeCodec.WRAPPER_OVERHEAD + 4)
            BridgeCodec.writeU32(peerPayload, 0, 0x1234) // clientId пира
            BridgeCodec.writeU32(peerPayload, 4, 0x55AA1122) // адресовано нам
            peerPayload[8] = 0x47 // envelope
            peerPayload[9] = 2
            peerPayload[10] = 1
            peerPayload[11] = 0

            val peerPacket = FromRadio.Builder()
                .packet(
                    MeshPacket.Builder()
                        .from(0x99887766.toInt())
                        .to(0x55AA1122)
                        .want_ack(false)
                        .decoded(
                            Data.Builder()
                                .portnum(PortNum.SIMULATOR_APP)
                                .payload(peerPayload.toByteString())
                                .build()
                        )
                        .build()
                )
                .build()
            output.write(StreamFrameCodec.framePacket(FromRadio.ADAPTER.encode(peerPacket)))
            output.flush()

            val buf = ByteArray(1024)
            while (!client.isClosed) {
                val n = try {
                    input.read(buf)
                } catch (_: Exception) {
                    break
                }
                if (n < 0) break
                for (i in 0 until n) {
                    codec.processByte(buf[i])
                }
            }
        }

        val nodeClient = MeshtasticNodeClient(
            scope = testScope,
            initialMyId = 0,
            host = "127.0.0.1",
            port = mockRadioPort,
            botNameProvider = { "TestBot" },
        )

        try {
            nodeClient.start()

            assertTrue("Радио должно подключиться", radioConnectedLatch.await(5, TimeUnit.SECONDS))

            // Ждем обновления номера ноды и имени
            withTimeout(5000) {
                while (nodeClient.myNodeNum.value != 0x55AA1122 || nodeClient.nodeName.value != "TestNode-Radio") {
                    delay(50)
                }
            }

            assertEquals(0x55AA1122, nodeClient.myNodeNum.value)
            assertEquals("TestNode-Radio", nodeClient.nodeName.value)

            // Проверяем прием пакета от пира
            val incoming = nodeClient.packets.first()
            assertEquals(0x1234, incoming.from)
            assertEquals(0x55AA1122, incoming.to)
            assertEquals(0x47, incoming.payload[0].toInt())

            // Проверяем обучение таблицы маршрутизации
            assertEquals(0x99887766.toInt(), nodeClient.routingTable.resolve(0x1234))

            // Отправляем ответный кадр envelope пиру 0x1234
            val envelopeBody = byteArrayOf(0x47, 2, 2, 0, 1, 0, 0, 0, 0, 0, 0, 0)
            val sendOk = nodeClient.send(to = 0x1234, body = envelopeBody)
            assertTrue("Отправка должна быть успешной", sendOk)

            assertTrue(
                "MeshPacket с envelope должен быть принят радио",
                envelopeReceivedLatch.await(5, TimeUnit.SECONDS)
            )
            val sentMesh = receivedEnvelopeMesh
            assertNotNull(sentMesh)
            assertEquals(0x99887766.toInt(), sentMesh?.to) // Unicast по обученной карте!
            assertEquals(0x55AA1122, sentMesh?.from)
            assertFalse("want_ack обязан быть false!", sentMesh?.want_ack ?: true)
            assertEquals(PortNum.SIMULATOR_APP, sentMesh?.decoded?.portnum)
        } finally {
            nodeClient.stop()
            try { radioClientSocket?.close() } catch (_: Exception) {}
            mockRadioJob.cancel()
        }
    }

    @Test(timeout = 10000)
    fun testNamePriorityExplicitVsNodeNameVsDefault(): Unit = runBlocking {
        // 1. Проверяем парсинг параметров CLI
        val cfgExplicit = BotConfig.parseArgs(arrayOf("--name", "CustomBotName", "-c", "1"))
        assertEquals("CustomBotName", cfgExplicit.botName)
        assertTrue(cfgExplicit.isExplicitName)

        val cfgDefault = BotConfig.parseArgs(arrayOf("-c", "1"))
        assertEquals(BotConfig.DEFAULT_BOT_NAME, cfgDefault.botName)
        assertFalse(cfgDefault.isExplicitName)

        // 2. Проверяем приоритет в клиенте при поступлении пакетов от радио
        val radioConnectedLatch = CountDownLatch(1)
        var radioClientSocket: Socket? = null
        var radioOutput: BufferedOutputStream? = null

        val mockRadioJob = testScope.launch(Dispatchers.IO) {
            val client = try {
                mockRadioServer!!.accept()
            } catch (e: Exception) {
                return@launch
            }
            radioClientSocket = client
            client.soTimeout = 5000
            val input = BufferedInputStream(client.getInputStream())
            val output = BufferedOutputStream(client.getOutputStream())
            radioOutput = output
            radioConnectedLatch.countDown()

            val wake = ByteArray(4)
            var readWake = 0
            while (readWake < 4) {
                val n = input.read(wake, readWake, 4 - readWake)
                if (n < 0) break
                readWake += n
            }
            val buf = ByteArray(1024)
            while (!client.isClosed) {
                val n = try { input.read(buf) } catch (_: Exception) { -1 }
                if (n < 0) break
            }
        }

        // Клиент с явным именем: имя ноды НЕ должно его перезаписывать
        val clientExplicit = MeshtasticNodeClient(
            scope = testScope,
            initialMyId = 0x11223344,
            host = "127.0.0.1",
            port = mockRadioPort,
            botNameProvider = { "CustomBotName" },
            isExplicitName = true,
        )
        clientExplicit.start()

        assertTrue("Радио должно подключиться", radioConnectedLatch.await(5, TimeUnit.SECONDS))

        // Передаем my_info и node_info с радио
        val myInfo = FromRadio.Builder()
            .my_info(MyNodeInfo.Builder().my_node_num(0x11223344).build())
            .build()
        radioOutput?.write(StreamFrameCodec.framePacket(FromRadio.ADAPTER.encode(myInfo)))

        val nodeInfo = FromRadio.Builder()
            .node_info(
                NodeInfo.Builder()
                    .num(0x11223344)
                    .user(User.Builder().long_name("HardwareRadioNode").build())
                    .build()
            )
            .build()
        radioOutput?.write(StreamFrameCodec.framePacket(FromRadio.ADAPTER.encode(nodeInfo)))
        radioOutput?.flush()

        delay(300)

        // Явное имя имеет приоритет 1 -> должно сохраниться "CustomBotName"
        assertEquals("CustomBotName", clientExplicit.nodeName.value)
        clientExplicit.stop()
        mockRadioJob.cancel()
        try { radioClientSocket?.close() } catch (_: Exception) {}
    }

    @Test(timeout = 10000)
    fun testTableCommandTextResponseAndStatPacket() = runBlocking {
        val radioConnectedLatch = CountDownLatch(1)
        val textResponseLatch = CountDownLatch(1)
        var textResponseBody: String? = null
        var radioClientSocket: Socket? = null
        var radioOutput: BufferedOutputStream? = null

        val mockRadioJob = testScope.launch(Dispatchers.IO) {
            val client = try {
                mockRadioServer!!.accept()
            } catch (e: Exception) {
                return@launch
            }
            radioClientSocket = client
            client.soTimeout = 5000
            val input = BufferedInputStream(client.getInputStream())
            val output = BufferedOutputStream(client.getOutputStream())
            radioOutput = output
            radioConnectedLatch.countDown()

            val codec = StreamFrameCodec { packetBytes ->
                val tr = ToRadio.ADAPTER.decode(packetBytes)
                val pkt = tr.packet
                val dec = pkt?.decoded
                if (dec != null && dec.portnum == PortNum.TEXT_MESSAGE_APP) {
                    textResponseBody = dec.payload.toByteArray().toString(Charsets.UTF_8)
                    textResponseLatch.countDown()
                }
            }

            val wake = ByteArray(4)
            var readWake = 0
            while (readWake < 4) {
                val n = input.read(wake, readWake, 4 - readWake)
                if (n < 0) break
                readWake += n
            }

            val readBuf = ByteArray(1024)
            while (!client.isClosed) {
                val n = try { input.read(readBuf) } catch (_: Exception) { -1 }
                if (n < 0) break
                for (i in 0 until n) {
                    codec.processByte(readBuf[i])
                }
            }
        }

        val tmpStatsFile = File.createTempFile("stats_test", ".json")
        tmpStatsFile.deleteOnExit()
        val statsManager = com.meshgames.bot.stats.StatsManager(tmpStatsFile)

        val nodeClient = MeshtasticNodeClient(
            scope = testScope,
            initialMyId = 0x9999,
            host = "127.0.0.1",
            port = mockRadioPort,
            channelIndex = 0,
            hopLimit = 3,
        )
        nodeClient.statsManager = statsManager
        nodeClient.start()

        assertTrue("Клиент должен подключиться к mock ноде", radioConnectedLatch.await(3, TimeUnit.SECONDS))
        delay(300)

        // 1. Отправляем в радио STAT пакет: игрок 0x111 выиграл у 0x222 в Крестики-нолики
        val statBody = BridgeCodec.encodeStat(
            gameTypeId = 1,
            sessionId = 1234,
            winner = 0,
            reason = 0,
            playerA = 0x111,
            playerB = 0x222,
        )
        val wrapper = ByteArray(BridgeCodec.WRAPPER_OVERHEAD + statBody.size)
        BridgeCodec.writeU32(wrapper, 0, 0x111)
        BridgeCodec.writeU32(wrapper, 4, 0) // broadcast
        statBody.copyInto(wrapper, BridgeCodec.WRAPPER_OVERHEAD)

        val statPacket = MeshPacket.Builder()
            .from(0xAAAA)
            .to(0xFFFFFFFF.toInt())
            .channel(0)
            .decoded(
                Data.Builder()
                    .portnum(PortNum.SIMULATOR_APP)
                    .payload(wrapper.toByteString())
                    .build()
            )
            .build()
        val fromRadioStat = FromRadio.Builder().packet(statPacket).build()
        val framedStat = StreamFrameCodec.framePacket(FromRadio.ADAPTER.encode(fromRadioStat))
        radioOutput?.write(framedStat)
        radioOutput?.flush()

        delay(300)

        // Проверяем, что результат игры попал в StatsManager
        val lb = statsManager.getLeaderboard()
        assertEquals(2, lb.size)
        val winnerP = lb.find { it.nodeId == 0x111 }
        assertNotNull(winnerP)
        assertEquals(1, winnerP?.wins)

        // 2. Отправляем в радио текстовое сообщение "!table"
        val textMsgPacket = MeshPacket.Builder()
            .from(0xBBBB)
            .to(0xFFFFFFFF.toInt())
            .channel(0)
            .decoded(
                Data.Builder()
                    .portnum(PortNum.TEXT_MESSAGE_APP)
                    .payload("!table".toByteArray(Charsets.UTF_8).toByteString())
                    .build()
            )
            .build()
        val fromRadioText = FromRadio.Builder().packet(textMsgPacket).build()
        val framedText = StreamFrameCodec.framePacket(FromRadio.ADAPTER.encode(fromRadioText))
        radioOutput?.write(framedText)
        radioOutput?.flush()

        // 3. Ожидаем ответ от бота в эфир
        assertTrue("Бот должен ответить текстовым сообщением на !table", textResponseLatch.await(3, TimeUnit.SECONDS))
        val resp = textResponseBody
        assertNotNull(resp)
        println("Получен ответ на !table в радио: $resp")
        assertTrue(resp!!.contains("🏆 Топ MeshGames"))
        assertTrue(resp.length <= 100)
        assertTrue(resp.toByteArray(Charsets.UTF_8).size <= 200)

        nodeClient.stop()
        mockRadioJob.cancel()
        tmpStatsFile.delete()
        Unit
    }
}
