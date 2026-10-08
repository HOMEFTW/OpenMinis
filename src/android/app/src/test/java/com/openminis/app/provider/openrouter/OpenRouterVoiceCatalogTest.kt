package com.openminis.app.provider.openrouter

import com.openminis.app.data.model.*
import org.junit.Assert.*
import org.junit.Test

class OpenRouterVoiceCatalogTest {
    private fun model(id: String) = LLMModel(id, id, "OpenRouter",
        inputModalities = listOf("text", "audio"), outputModalities = listOf("text", "audio"))

    @Test fun `dedicated catalogs decide voice roles instead of chat modalities`() {
        val merged = OpenRouterModelsApi.mergeVoiceCatalogs(
            listOf(model("vendor/chat"), model("openai/gpt-audio")),
            listOf(model("vendor/speech")), listOf(model("vendor/transcribe")),
        ).associateBy { it.id }
        assertFalse(merged.getValue("vendor/chat").isVoiceInputCandidate)
        assertFalse(merged.getValue("vendor/chat").isVoiceOutputCandidate)
        assertTrue(merged.getValue("openai/gpt-audio").isVoiceInputCandidate)
        assertTrue(merged.getValue("openai/gpt-audio").isVoiceOutputCandidate)
        assertEquals(VoiceRole.TTS, merged.getValue("vendor/speech").voiceRole)
        assertFalse(merged.getValue("vendor/speech").isVoiceInputCandidate)
        assertEquals(VoiceRole.STT, merged.getValue("vendor/transcribe").voiceRole)
        assertFalse(merged.getValue("vendor/transcribe").isVoiceOutputCandidate)
    }

    @Test fun `dedicated catalog overrides duplicate chat id`() {
        val merged = OpenRouterModelsApi.mergeVoiceCatalogs(listOf(model("same")), listOf(model("same")), emptyList())
        assertEquals(1, merged.size)
        assertEquals(VoiceRole.TTS, merged.single().voiceRole)
    }
}
