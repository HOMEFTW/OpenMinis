package com.openminis.app.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.openminis.app.util.MemorySharedPreferences
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class FastModePrefsTest {
    @After fun reset() = FastModePrefs.setCachedEnabledForTest(false)

    @Test fun existingObserversFollowGlobalChangesAndReloadedPreferences() {
        val saved = MemorySharedPreferences()
        val context = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = saved
        }
        FastModePrefs.prime(context)
        val firstChat = FastModePrefs.enabled
        val secondChat = FastModePrefs.enabled
        assertFalse(firstChat.value)

        FastModePrefs.setEnabled(context, true)
        assertTrue(firstChat.value)
        assertTrue(secondChat.value)
        assertTrue(FastModePrefs.isEnabled())
        assertTrue(saved.getBoolean("codexFastModeEnabled", false))

        FastModePrefs.setCachedEnabledForTest(false)
        FastModePrefs.prime(context)
        assertTrue(firstChat.value)
        assertTrue(secondChat.value)

        FastModePrefs.setEnabled(context, false)
        assertFalse(firstChat.value)
        assertFalse(secondChat.value)
        assertFalse(saved.getBoolean("codexFastModeEnabled", true))
    }
}
