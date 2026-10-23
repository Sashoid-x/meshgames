package com.meshgames.app

/**
 * Идентичность MeshGames (clientId) и правила миграции на номер ноды:
 *
 *  - в pref'е уже есть id (обновление приложения) — идентичность не
 *    меняется никогда: смена сломала бы узнаваемость телефона у
 *    соперника и ссылки сохранённых сессий;
 *  - свежая установка стартует на временном id с флагом «ожидаем HELLO»
 *    (флаг переживает рестарты приложения, пока мост не сообщит ноду);
 *  - первый HELLO от моста (номер + имя своей ноды) закрепляет
 *    `id = номер ноды` — переустановка приложения больше не меняет
 *    игрока; без HELLO (старый форк) временный id живёт как раньше.
 *
 * @param storedId сохранённый id (null — свежая установка)
 * @param pending флаг «ожидаем HELLO» из pref'а (для обновлений=false)
 * @param generate генератор временного id (≠0, ≠ зарезервированных)
 * @param persist (id, pending) → pref'ы: id + флаг ожидания
 */
class ClientIdentity(
    storedId: Int?,
    pending: Boolean,
    generate: () -> Int,
    private val persist: (id: Int, pending: Boolean) -> Unit,
) {

    /** Текущий clientId. @Volatile — миграция из корутины, чтение из UI. */
    @Volatile
    var id: Int
        private set

    /** Допустима ли миграция на номер ноды (свежая установка/ожидание). */
    @Volatile
    var migratable: Boolean
        private set

    init {
        if (storedId != null) {
            id = storedId
            migratable = pending
        } else {
            id = generate()
            migratable = true
            persist(id, true)
        }
    }

    /** HELLO от моста: закрепить номер ноды. true — идентичность сменена. */
    fun adoptNodeId(nodeId: Int): Boolean {
        // 0 — невалидный номер (защита: codec и так отфильтровывает).
        if (!migratable || nodeId == 0 || nodeId == id) return false
        id = nodeId
        migratable = false
        persist(nodeId, false)
        return true
    }
}
