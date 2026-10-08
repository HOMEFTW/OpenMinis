package com.openminis.app.data.repository

import com.openminis.app.data.db.toProviderConfig
import com.openminis.app.data.db.toSnapshot
import com.openminis.app.data.model.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ModelAbsenceTest {
    @Test fun `missing model retains overrides and first observation through database reload`() {
        val entry = ModelEntry("provider", LLMModel("model", "Model", "Custom"), ModelOverrides(contextWindow = 64000))
        val first = retainAbsentModels(listOf(entry), 1000).single()
        val config = ProviderConfig(modelEntries = mutableListOf(first))
        val loaded = config.toSnapshot(Json).toProviderConfig(Json).modelEntries.single()
        assertEquals(1000L, loaded.absentSince)
        assertEquals(entry.overrides, loaded.overrides)
        assertEquals(1000L, retainAbsentModels(listOf(loaded), 2000).single().absentSince)
        assertTrue(retainAbsentModels(listOf(loaded), 1001 + MODEL_ABSENCE_GRACE_MS).isEmpty())
    }

    @Test fun `provider defaults never override explicit modalities or invent custom endpoint support`() {
        val base = LLMModel("new-model", "New", "OpenAI")
        assertTrue("image" in ModelEntry("p", base).model.inputModalities.orEmpty())
        assertEquals(emptyList<String>(), ModelEntry("p", base, ModelOverrides(inputModalities = emptyList())).model.inputModalities)
        assertEquals(listOf("audio"), ModelEntry("p", base, ModelOverrides(inputModalities = listOf("audio_input"))).model.inputModalities)
        assertNull(base.copy(provider = "Custom").effectiveInputModalities)
        assertEquals(listOf("text"), base.copy(inputModalities = listOf("text")).effectiveInputModalities)
    }
}
