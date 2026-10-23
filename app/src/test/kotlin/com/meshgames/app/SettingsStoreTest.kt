package com.meshgames.app

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory SharedPreferences для JVM-тестов (никаких android-реализаций). */
class FakeSharedPreferences : SharedPreferences {
    val data = HashMap<String, Any?>()

    override fun getAll(): MutableMap<String, *> = HashMap(data)

    override fun getString(key: String?, defValue: String?): String? =
        data[key] as? String ?: defValue

    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
        @Suppress("UNCHECKED_CAST")
        return (data[key] as? MutableSet<String>) ?: defValues
    }

    override fun getInt(key: String?, defValue: Int): Int = data[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = data[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = data[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        data[key] as? Boolean ?: defValue

    override fun contains(key: String?): Boolean = data.containsKey(key)

    override fun edit(): SharedPreferences.Editor = FakeEditor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    inner class FakeEditor : SharedPreferences.Editor {
        private val pending = HashMap<String, Any?>()
        private var clearFlag = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            pending[key!!] = value
            return this
        }

        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
            pending[key!!] = values?.let { HashSet(it) }
            return this
        }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
            pending[key!!] = value
            return this
        }

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
            pending[key!!] = value
            return this
        }

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
            pending[key!!] = value
            return this
        }

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
            pending[key!!] = value
            return this
        }

        override fun remove(key: String?): SharedPreferences.Editor {
            pending[key!!] = null
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clearFlag = true
            return this
        }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            if (clearFlag) data.clear()
            for ((k, v) in pending) {
                if (v == null) data.remove(k) else data[k] = v
            }
            pending.clear()
            clearFlag = false
        }
    }
}

/**
 * Настройки приложения: тема/звук/уведомления/избранные/presence.
 * FakeSharedPreferences позволяет гонять всё на JVM без Android.
 */
class SettingsStoreTest {

    @Test
    fun themeSoundNotificationsDefaultsAndSetters() {
        val prefs = FakeSharedPreferences()
        val store = SettingsStore(prefs)

        assertEquals(ThemeMode.SYSTEM, store.theme.value)
        assertTrue(store.sound.value)
        assertTrue(store.notifications.value)

        store.setTheme(ThemeMode.DARK)
        store.setSound(false)
        store.setNotifications(false)

        assertEquals(ThemeMode.DARK, store.theme.value)
        assertFalse(store.sound.value)
        assertFalse(store.notifications.value)

        // персистентность
        val store2 = SettingsStore(prefs)
        assertEquals(ThemeMode.DARK, store2.theme.value)
        assertFalse(store2.sound.value)
        assertFalse(store2.notifications.value)
    }

    @Test
    fun brokenValuesFallBackToDefaults() {
        val prefs = FakeSharedPreferences()
        prefs.data["theme"] = "НЕ ТА ТЕМА"
        prefs.data["favorites"] = setOf("10", "мусор", "0x20", "-3")

        val store = SettingsStore(prefs)
        assertEquals(ThemeMode.SYSTEM, store.theme.value)
        assertEquals(setOf(10, -3), store.favorites.value)
    }

    @Test
    fun favoritesToggle() {
        val prefs = FakeSharedPreferences()
        val store = SettingsStore(prefs)

        assertFalse(store.isFavorite(77))
        store.toggleFavorite(77)
        assertTrue(store.isFavorite(77))
        assertEquals(setOf(77), store.favorites.value)

        store.toggleFavorite(77)
        assertFalse(store.isFavorite(77))
        assertEquals(emptySet<Int>(), store.favorites.value)

        // избранные переживают пересоздание
        store.toggleFavorite(5)
        assertEquals(setOf(5), SettingsStore(prefs).favorites.value)
    }

    @Test
    fun presenceIntervalDefaultsClampsAndPersists() {
        val prefs = FakeSharedPreferences()
        val store = SettingsStore(prefs)

        // по умолчанию — 10 минут
        assertEquals(SettingsStore.DEFAULT_PRESENCE_MIN, store.presenceIntervalMinutes.value)
        assertEquals(10 * 60_000L, store.presenceIntervalMs())

        // заданный интервал применяется и переживает пересоздание
        store.setPresenceInterval(20)
        assertEquals(20, store.presenceIntervalMinutes.value)
        assertEquals(20 * 60_000L, store.presenceIntervalMs())
        assertEquals(20, SettingsStore(prefs).presenceIntervalMinutes.value)

        // вылезающие за 5..30 значения зажимаются
        store.setPresenceInterval(2)
        assertEquals(SettingsStore.MIN_PRESENCE_MIN, store.presenceIntervalMinutes.value)
        store.setPresenceInterval(120)
        assertEquals(SettingsStore.MAX_PRESENCE_MIN, store.presenceIntervalMinutes.value)

        // мусор в prefs → дефолт
        prefs.data["presence_interval_min"] = -100
        assertEquals(SettingsStore.DEFAULT_PRESENCE_MIN, SettingsStore(prefs).presenceIntervalMinutes.value)
    }
}
