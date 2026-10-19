package com.openminis.app.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.openminis.app.util.MemorySharedPreferences
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class SpeedTierPrefsTest {
    @After fun reset() = FastModePrefs.setCachedEnabledForTest(false)
    private fun context(saved: MemorySharedPreferences) = object : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = saved
    }

    @Test fun migratesOldBooleanAndKeepsSharedFlowsConsistentAcrossReloads() {
        val saved = MemorySharedPreferences(mapOf("codexFastModeEnabled" to true))
        val context = context(saved)
        val first = FastModePrefs.speedTier
        val second = FastModePrefs.enabled
        FastModePrefs.prime(context)
        assertEquals("fast", first.value)
        assertTrue(second.value)
        FastModePrefs.setSpeedTier(context, "ultrafast")
        assertEquals("ultrafast", first.value)
        assertTrue(second.value)
        assertTrue(saved.getBoolean("codexFastModeEnabled", false))
        FastModePrefs.setCachedEnabledForTest(false)
        FastModePrefs.prime(context)
        assertEquals("ultrafast", first.value)
        assertTrue(FastModePrefs.isEnabled())
        assertSame(first, FastModePrefs.speedTier)
    }

    @Test fun checkboxOffMeansStandardAndOnExplicitlyChoosesFast() {
        val context = context(MemorySharedPreferences())
        FastModePrefs.prime(context)
        assertEquals("standard", FastModePrefs.speedTier.value)
        assertFalse(FastModePrefs.enabled.value)
        FastModePrefs.setSpeedTier(context, "ultrafast")
        FastModePrefs.setEnabled(context, false)
        assertEquals("standard", FastModePrefs.speedTier.value)
        FastModePrefs.setEnabled(context, true)
        assertEquals("fast", FastModePrefs.speedTier.value)
    }

    @Test fun invalidTierDoesNotOverwriteExistingPreferences() {
        val saved = MemorySharedPreferences()
        val context = context(saved)
        FastModePrefs.setSpeedTier(context, "fast")
        assertTrue(runCatching { FastModePrefs.setSpeedTier(context, "turbo") }.isFailure)
        assertEquals("fast", FastModePrefs.speedTier.value)
        assertEquals("fast", saved.getString("speedTier", null))
    }

    @Test fun legacyAppCanDisableFastAfterAStoredUltrafastSelection() {
        val saved = MemorySharedPreferences(mapOf("speedTier" to "ultrafast", "codexFastModeEnabled" to false))
        FastModePrefs.prime(context(saved))
        assertEquals("standard", FastModePrefs.speedTier.value)
        assertFalse(FastModePrefs.isEnabled())
    }
}
