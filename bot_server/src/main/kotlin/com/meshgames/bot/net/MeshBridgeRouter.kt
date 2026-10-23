package com.meshgames.bot.net

import com.meshgames.bot.log.BotLogger
import com.meshgames.net.bridge.BridgeCodec

/**
 * Адресат отправки в сеть Meshtastic LoRa.
 */
sealed class RouteDestination {
    /**
     * Broadcast в канал (0xFFFFFFFF).
     */
    data class Broadcast(val reason: String = "") : RouteDestination()

    /**
     * Прямая адресация (DM) конкретному узлу LoRa (to = nodeNum).
     */
    data class Unicast(val nodeNum: Int) : RouteDestination()
}

/**
 * Маршрутизатор пакетов MeshBridge по заголовкам.
 * presence (0x50) -> broadcast в канал;
 * envelope (0x47) с известным адресатом -> DM (unicast);
 * envelope с неизвестным адресатом -> broadcast (fallback).
 */
object MeshBridgeRouter {

    const val BROADCAST_ADDR = -1 // 0xFFFFFFFF

    fun route(
        wrapperFrom: Int,
        wrapperTo: Int,
        body: ByteArray,
        table: RoutingTable,
    ): RouteDestination {
        if (body.isEmpty()) {
            return RouteDestination.Broadcast("empty payload")
        }

        val magic = body[0].toInt() and 0xFF

        return when (magic) {
            BridgeCodec.PRESENCE_MAGIC -> {
                BotLogger.log("BR", "meshbridge: TX presence → broadcast")
                RouteDestination.Broadcast("presence")
            }

            BridgeCodec.GAME_MAGIC -> {
                if (wrapperTo == 0) {
                    BotLogger.log("BR", "meshbridge: TX envelope toClient=0x0 → broadcast")
                    RouteDestination.Broadcast("envelope to=0")
                } else {
                    val destNode = table.resolve(wrapperTo)
                    if (destNode != null) {
                        BotLogger.log(
                            "BR",
                            "meshbridge: TX envelope toClient=0x${wrapperTo.toString(16)} → node=0x${destNode.toString(16)} (DM)"
                        )
                        RouteDestination.Unicast(destNode)
                    } else {
                        BotLogger.log(
                            "BR",
                            "meshbridge: TX envelope toClient=0x${wrapperTo.toString(16)} → broadcast (нет карты)"
                        )
                        RouteDestination.Broadcast("envelope fallback (no peer in routing table)")
                    }
                }
            }

            else -> {
                BotLogger.log(
                    "BR",
                    "meshbridge: TX unknown magic 0x${magic.toString(16)} → broadcast"
                )
                RouteDestination.Broadcast("unknown magic")
            }
        }
    }
}
