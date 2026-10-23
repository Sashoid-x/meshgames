package com.meshgames.net

/**
 * Входящий игровой пакет из сети: от кого, кому (0 = broadcast)
 * и полезная нагрузка (envelope). Единая модель для TCP-моста —
 * её собирает BridgeTransport и раздаёт подписчикам
 * (AppGraph → SessionManager).
 */
data class IncomingMeshPacket(
    val from: Int,
    val to: Int,
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is IncomingMeshPacket &&
            from == other.from && to == other.to &&
            payload.contentEquals(other.payload)

    override fun hashCode(): Int {
        var result = from
        result = 31 * result + to
        result = 31 * result + payload.contentHashCode()
        return result
    }
}
