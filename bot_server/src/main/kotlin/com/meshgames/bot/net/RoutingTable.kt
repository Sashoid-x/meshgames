package com.meshgames.bot.net

/**
 * LRU-карта маршрутизации clientId -> номер узла LoRa (nodeNum).
 * Емкость 32 записи (в соответствии с протоколом MeshBridge).
 * Потокобезопасна.
 */
class RoutingTable(private val capacity: Int = 32) {

    private val map = object : LinkedHashMap<Int, Int>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Int>?): Boolean {
            return size > capacity
        }
    }

    private val lock = Any()

    /**
     * Обучение карты на принятом пакете: связываем clientId игрока с loraFrom узла.
     * Повторный приход того же clientId перезаписывает запись (последняя победила).
     */
    fun record(clientId: Int, nodeNum: Int) {
        if (clientId == 0 || nodeNum == 0 || nodeNum == -1) return
        synchronized(lock) {
            map[clientId] = nodeNum
        }
    }

    /**
     * Поиск номера узла по clientId. Возвращает null, если узел еще не известен.
     */
    fun resolve(clientId: Int): Int? {
        if (clientId == 0) return null
        synchronized(lock) {
            return map[clientId]
        }
    }

    fun size(): Int = synchronized(lock) { map.size }

    fun clear() = synchronized(lock) { map.clear() }

    fun snapshot(): Map<Int, Int> = synchronized(lock) { java.util.HashMap(map) }
}
