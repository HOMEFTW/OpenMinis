package com.openminis.app.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.openminis.app.data.model.LLMModel
import com.openminis.app.util.MemorySharedPreferences
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class FastModePrefsTest {
    @After fun reset() = FastModePrefs.setCachedEnabledForTest(false)

    private fun context(saved: MemorySharedPreferences) = object : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = saved
    }

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

    @Test fun switchingAwayFromGptClearsBothPreferencesWithoutRestoringThemOnReturnOrReload() {
        val saved = MemorySharedPreferences()
        val context = context(saved)
        for (tier in listOf("fast", "ultrafast")) {
            for (id in listOf("grok-4", "deepseek-flash", "claude-sonnet-5")) {
                FastModePrefs.setSpeedTier(context, tier)
                FastModePrefs.onModelSelected(context, LLMModel.gpt61Sol, isForeground = true)
                assertEquals(tier, FastModePrefs.speedTier.value)

                FastModePrefs.onModelSelected(context, LLMModel(id, id, "Other"), isForeground = true)
                assertFalse(FastModePrefs.enabled.value)
                assertEquals("standard", FastModePrefs.speedTier.value)
                assertFalse(saved.getBoolean("codexFastModeEnabled", true))
                assertEquals("standard", saved.getString("speedTier", null))

                FastModePrefs.onModelSelected(context, LLMModel.gpt61Sol, isForeground = true)
                FastModePrefs.prime(context)
                assertFalse("$tier / $id", FastModePrefs.isEnabled())
                assertEquals("standard", FastModePrefs.speedTier.value)
            }
        }
        FastModePrefs.setEnabled(context, true)
        assertTrue(FastModePrefs.isEnabled())
        assertEquals("fast", FastModePrefs.speedTier.value)
    }

    @Test fun restoringANonGptForegroundChatClearsTheLegacyEnabledPreference() {
        val saved = MemorySharedPreferences(mapOf("codexFastModeEnabled" to true))
        val context = context(saved)
        FastModePrefs.prime(context)
        assertTrue(FastModePrefs.isEnabled())
        FastModePrefs.onModelSelected(context, LLMModel("grok-4", "Grok", "xAI"), isForeground = true)
        FastModePrefs.prime(context)
        assertFalse(FastModePrefs.isEnabled())
        assertEquals("standard", FastModePrefs.speedTier.value)
    }

    @Test fun backgroundModelsCannotClearANewerForegroundGptPreference() {
        val saved = MemorySharedPreferences()
        val context = context(saved)
        val grok = LLMModel("grok-4", "Grok", "xAI")
        FastModePrefs.onModelSelected(context, grok, isForeground = true)
        FastModePrefs.onModelSelected(context, LLMModel.gpt61Sol, isForeground = true)
        for (tier in listOf("fast", "ultrafast")) {
            FastModePrefs.setSpeedTier(context, tier)
            repeat(3) {
                FastModePrefs.onModelSelected(context, grok, isForeground = false)
                FastModePrefs.onModelSelected(context, LLMModel("deepseek-flash", "DeepSeek", "DeepSeek"), isForeground = false)
            }
            FastModePrefs.prime(context)
            assertTrue(FastModePrefs.isEnabled())
            assertEquals(tier, FastModePrefs.speedTier.value)
            assertEquals(tier, saved.getString("speedTier", null))
        }
        // Reopening that cached non-GPT chat is a foreground selection and must reset.
        FastModePrefs.onModelSelected(context, grok, isForeground = true)
        assertFalse(FastModePrefs.isEnabled())
        assertEquals("standard", saved.getString("speedTier", null))
    }

    @Test fun unresolvedModelsAndGptTextMetadataDoNotResetThePreference() {
        val context = context(MemorySharedPreferences())
        FastModePrefs.setSpeedTier(context, "ultrafast")
        FastModePrefs.onModelSelected(context, null, isForeground = true)
        FastModePrefs.onModelSelected(context,
            LLMModel.gpt61Sol.copy(id = "OPENAI/GPT-6.1-SOL", outputModalities = listOf("TEXT_OUTPUT")),
            isForeground = true)
        assertEquals("ultrafast", FastModePrefs.speedTier.value)

        FastModePrefs.onModelSelected(context, LLMModel.gpt61Sol.copy(outputModalities = listOf("image")), isForeground = true)
        assertFalse(FastModePrefs.isEnabled())
        assertEquals("standard", FastModePrefs.speedTier.value)
    }
}
