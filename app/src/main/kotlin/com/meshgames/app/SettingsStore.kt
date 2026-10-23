package com.meshgames.app

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Тема оформления. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Настройки приложения в SharedPreferences: тема, звук, уведомления,
 * избранные соперники и периодичность presence.
 */
class SettingsStore(private val prefs: SharedPreferences) {

    // ------------------------------------------------------------- тема ---

    private val _theme = MutableStateFlow(readTheme())
    val theme: StateFlow<ThemeMode> = _theme.asStateFlow()

    fun setTheme(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME, mode.name).apply()
        _theme.value = mode
    }

    // ------------------------------------------------------------- звук ---

    private val _sound = MutableStateFlow(prefs.getBoolean(KEY_SOUND, true))
    val sound: StateFlow<Boolean> = _sound.asStateFlow()

    fun setSound(on: Boolean) {
        prefs.edit().putBoolean(KEY_SOUND, on).apply()
        _sound.value = on
    }

    // ----------------------------------------------------- уведомления ---

    private val _notifications = MutableStateFlow(prefs.getBoolean(KEY_NOTIFICATIONS, true))
    val notifications: StateFlow<Boolean> = _notifications.asStateFlow()

    fun setNotifications(on: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIFICATIONS, on).apply()
        _notifications.value = on
    }

    // ---------------------------------------------------- тестовый бот ---

    // ------------------------------------------------------ диагностика ---

    /**
     * Панель дебага поверх экрана. По умолчанию скрыта (кнопка в настройках);
     * включённая — полупрозрачный оверлей внизу, макету экрана не мешает.
     */
    private val _debugPanel = MutableStateFlow(prefs.getBoolean(KEY_DEBUG_PANEL, false))
    val debugPanel: StateFlow<Boolean> = _debugPanel.asStateFlow()

    fun setDebugPanel(on: Boolean) {
        prefs.edit().putBoolean(KEY_DEBUG_PANEL, on).apply()
        _debugPanel.value = on
    }

    // ------------------------------------------------------ присутствие ---

    /**
     * Периодичность вещания presence-маркеров в канал, минут (5..30).
     * Каждый маркер — отдельный эфирный пакет, поэтому периодичность
     * напрямую влияет на нагрузку канала.
     */
    private val _presenceIntervalMinutes = MutableStateFlow(readPresenceInterval())
    val presenceIntervalMinutes: StateFlow<Int> = _presenceIntervalMinutes.asStateFlow()

    fun setPresenceInterval(minutes: Int) {
        val v = minutes.coerceIn(MIN_PRESENCE_MIN, MAX_PRESENCE_MIN)
        prefs.edit().putInt(KEY_PRESENCE_INTERVAL, v).apply()
        _presenceIntervalMinutes.value = v
    }

    /** Периодичность presence в миллисекундах (для транспорта). */
    fun presenceIntervalMs(): Long = _presenceIntervalMinutes.value * 60_000L

    // ------------------------------------------------------ избранные ---

    private val _favorites = MutableStateFlow(readFavorites())
    val favorites: StateFlow<Set<Int>> = _favorites.asStateFlow()

    fun isFavorite(id: Int): Boolean = id in _favorites.value

    fun toggleFavorite(id: Int) {
        val next = if (id in _favorites.value) _favorites.value - id else _favorites.value + id
        prefs.edit().putStringSet(KEY_FAVORITES, next.map { it.toString() }.toSet()).apply()
        _favorites.value = next
    }

    // ------------------------------------------------------------ io -----

    private fun readTheme(): ThemeMode = runCatching {
        ThemeMode.valueOf(prefs.getString(KEY_THEME, ThemeMode.SYSTEM.name)!!)
    }.getOrDefault(ThemeMode.SYSTEM)

    private fun readFavorites(): Set<Int> =
        prefs.getStringSet(KEY_FAVORITES, emptySet())!!
            .mapNotNull { it.toIntOrNull() }.toSet()

    private fun readPresenceInterval(): Int {
        val raw = prefs.getInt(KEY_PRESENCE_INTERVAL, DEFAULT_PRESENCE_MIN)
        // Вне диапазона (мусор в prefs) — дефолт, а не агрессивные 5 минут.
        return if (raw in MIN_PRESENCE_MIN..MAX_PRESENCE_MIN) raw else DEFAULT_PRESENCE_MIN
    }

    companion object {
        private const val KEY_THEME = "theme"
        private const val KEY_SOUND = "sound"
        private const val KEY_NOTIFICATIONS = "notifications"
        private const val KEY_FAVORITES = "favorites"
        private const val KEY_PRESENCE_INTERVAL = "presence_interval_min"
        private const val KEY_DEBUG_PANEL = "debug_panel"

        /** Границы настройки периодичности presence, минуты. */
        const val MIN_PRESENCE_MIN = 5
        const val MAX_PRESENCE_MIN = 30
        const val DEFAULT_PRESENCE_MIN = 10
    }
}
