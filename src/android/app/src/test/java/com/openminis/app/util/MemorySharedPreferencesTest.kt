package com.openminis.app.util

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemorySharedPreferencesTest {

    @Test
    fun `editor keeps all supported value types and isolates returned collections`() {
        val prefs = MemorySharedPreferences()
        prefs.edit()
            .putString("text", "value")
            .putInt("int", 7)
            .putLong("long", 8L)
            .putFloat("float", 1.5f)
            .putBoolean("bool", true)
            .putStringSet("set", setOf("a", "b"))
            .commit()

        val returned = prefs.getStringSet("set", mutableSetOf())!!
        returned.add("leak")

        assertEquals("value", prefs.getString("text", null))
        assertEquals(7, prefs.getInt("int", 0))
        assertEquals(8L, prefs.getLong("long", 0L))
        assertEquals(1.5f, prefs.getFloat("float", 0f))
        assertTrue(prefs.getBoolean("bool", false))
        assertEquals(setOf("a", "b"), prefs.getStringSet("set", mutableSetOf()))
    }

    @Test
    fun `apply and clear notify listeners only for changed keys`() {
        val prefs = MemorySharedPreferences()
        val changes = mutableListOf<String?>()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> changes += key }
        prefs.registerOnSharedPreferenceChangeListener(listener)

        prefs.edit().putString("one", "1").apply()
        prefs.edit().putString("one", "1").apply()
        prefs.edit().putString("two", "2").putString("one", null).commit()
        prefs.edit().clear().commit()

        assertEquals(listOf("one", "one", "two", "two"), changes)
        assertFalse(prefs.contains("one"))
        assertFalse(prefs.contains("two"))
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    @Test
    fun `clear applies before staged puts regardless of call order`() {
        val prefs = MemorySharedPreferences(mapOf("old" to "value"))

        prefs.edit().putString("before_clear", "kept").clear().putString("after_clear", "new").commit()

        assertFalse(prefs.contains("old"))
        assertEquals("kept", prefs.getString("before_clear", null))
        assertEquals("new", prefs.getString("after_clear", null))
    }

    @Test
    fun `recovery policy can use memory without deleting any source`() {
        val memory = MemorySharedPreferences()
        memory.edit().putString("api_key", "synthetic-secret").commit()
        var createCalls = 0
        var cleanupCalls = 0
        val resolution = EncryptedPrefsRecovery(
            fileName = "provider_secrets",
            createEncrypted = {
                createCalls++
                error("synthetic keystore failure")
            },
            memoryPrefs = memory,
            legacyPrefs = null,
            clearLegacy = { cleanupCalls++; true },
            policy = EncryptedPrefsRecoveryPolicy { _, _ -> false },
        ).resolve()

        assertEquals(EncryptedPrefsStorage.MEMORY, resolution.storage)
        assertEquals(1, createCalls)
        assertEquals(0, cleanupCalls)
        assertEquals("synthetic-secret", resolution.prefs.getString("api_key", null))
    }

    @Test
    fun `legacy plaintext is removed only after encrypted values verify`() {
        val legacy = MemorySharedPreferences()
        legacy.edit().putString("api_key", "legacy-synthetic-secret").commit()
        val encrypted = MemorySharedPreferences()
        var cleanupCalls = 0
        val resolution = EncryptedPrefsRecovery(
            fileName = "provider_secrets",
            createEncrypted = { encrypted },
            memoryPrefs = MemorySharedPreferences(),
            legacyPrefs = legacy,
            clearLegacy = {
                cleanupCalls++
                legacy.edit().clear().commit()
            },
            policy = EncryptedPrefsRecoveryPolicy { _, _ -> true },
        ).resolve()

        assertEquals(EncryptedPrefsStorage.ENCRYPTED, resolution.storage)
        assertEquals("legacy-synthetic-secret", encrypted.getString("api_key", null))
        assertEquals(1, cleanupCalls)
        assertTrue(legacy.all.isEmpty())
    }

    @Test
    fun `legacy cleanup failure keeps values in memory and source`() {
        val legacy = MemorySharedPreferences()
        legacy.edit().putString("api_key", "legacy-synthetic-secret").commit()
        val memory = MemorySharedPreferences()
        memory.edit().putString("api_key", "legacy-synthetic-secret").commit()
        var cleanupCalls = 0
        val resolution = EncryptedPrefsRecovery(
            fileName = "provider_secrets",
            createEncrypted = { MemorySharedPreferences() },
            memoryPrefs = memory,
            legacyPrefs = legacy,
            clearLegacy = { cleanupCalls++; false },
            policy = EncryptedPrefsRecoveryPolicy { _, _ -> true },
        ).resolve()

        assertEquals(EncryptedPrefsStorage.MEMORY, resolution.storage)
        assertEquals("legacy-synthetic-secret", resolution.prefs.getString("api_key", null))
        assertEquals(1, cleanupCalls)
        assertEquals("legacy-synthetic-secret", legacy.getString("api_key", null))
    }

    @Test
    fun `unreadable encrypted store falls back to memory`() {
        val memory = MemorySharedPreferences()
        memory.edit().putString("api_key", "synthetic-secret").commit()
        val resolution = EncryptedPrefsRecovery(
            fileName = "provider_secrets",
            createEncrypted = { ThrowingSharedPreferences(MemorySharedPreferences(), readFails = true) },
            memoryPrefs = memory,
            legacyPrefs = null,
            clearLegacy = { error("must not cleanup") },
            policy = EncryptedPrefsRecoveryPolicy.MEMORY_ONLY,
        ).resolve()

        assertEquals(EncryptedPrefsStorage.MEMORY, resolution.storage)
        assertEquals("synthetic-secret", resolution.prefs.getString("api_key", null))
    }

    @Test
    fun `encrypted migration commit failure retains source`() {
        val legacy = MemorySharedPreferences()
        legacy.edit().putString("api_key", "legacy-synthetic-secret").commit()
        val encrypted = ThrowingSharedPreferences(MemorySharedPreferences(), commitFails = true)
        var cleanupCalls = 0
        val resolution = EncryptedPrefsRecovery(
            fileName = "provider_secrets",
            createEncrypted = { encrypted },
            memoryPrefs = MemorySharedPreferences(),
            legacyPrefs = legacy,
            clearLegacy = { cleanupCalls++; true },
            policy = EncryptedPrefsRecoveryPolicy.MEMORY_ONLY,
        ).resolve()

        assertEquals(EncryptedPrefsStorage.MEMORY, resolution.storage)
        assertEquals("legacy-synthetic-secret", resolution.prefs.getString("api_key", null))
        assertEquals(0, cleanupCalls)
        assertEquals("legacy-synthetic-secret", legacy.getString("api_key", null))
    }

    @Test
    fun `legacy read failure keeps encrypted source untouched`() {
        val memory = MemorySharedPreferences()
        memory.edit().putString("api_key", "memory-synthetic-secret").commit()
        val encrypted = MemorySharedPreferences()
        var cleanupCalls = 0
        val resolution = EncryptedPrefsRecovery(
            fileName = "provider_secrets",
            createEncrypted = { encrypted },
            memoryPrefs = memory,
            legacyPrefs = ThrowingSharedPreferences(MemorySharedPreferences(), readFails = true),
            clearLegacy = { cleanupCalls++; true },
            policy = EncryptedPrefsRecoveryPolicy.MEMORY_ONLY,
        ).resolve()

        assertEquals(EncryptedPrefsStorage.MEMORY, resolution.storage)
        assertEquals("memory-synthetic-secret", resolution.prefs.getString("api_key", null))
        assertEquals(0, cleanupCalls)
        assertTrue(encrypted.all.isEmpty())
    }

    private class ThrowingSharedPreferences(
        private val delegate: SharedPreferences,
        private val readFails: Boolean = false,
        private val commitFails: Boolean = false,
    ) : SharedPreferences by delegate {
        override fun getAll(): Map<String, *> {
            if (readFails) error("synthetic encrypted read failure")
            return delegate.all
        }

        override fun edit(): SharedPreferences.Editor {
            val editor = delegate.edit()
            if (!commitFails) return editor
            return object : SharedPreferences.Editor by editor {
                override fun commit(): Boolean = error("synthetic encrypted commit failure")
            }
        }
    }
}
