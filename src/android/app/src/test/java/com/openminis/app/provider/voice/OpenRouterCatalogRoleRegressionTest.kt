package com.openminis.app.provider.voice

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.VoiceRole
import com.openminis.app.data.model.isVoiceInputCandidate
import com.openminis.app.data.model.isVoiceOutputCandidate
import org.junit.Assert.*
import org.junit.Test

class OpenRouterCatalogRoleRegressionTest {
    @Test fun catalogAsrWithoutWhisperInItsNameUsesTranscriptions() {
        val model = LLMModel("qwen/qwen3-asr", "Qwen ASR", "OpenRouter", voiceRole = VoiceRole.STT)
        assertFalse(OpenRouterVoiceProvider.routesAsrThroughChat(model))
        assertTrue(model.isVoiceInputCandidate)
        assertFalse(model.isVoiceOutputCandidate)
    }

    @Test fun speechCatalogRoutesToSpeechAndForwardsVendorVoice() {
        val model = LLMModel("fish-audio/s2-pro", "Fish", "OpenRouter", voiceRole = VoiceRole.TTS)
        assertEquals(OpenRouterVoiceProvider.TtsRoute.SPEECH_ENDPOINT, OpenRouterVoiceProvider.ttsRoute(model.id, model))
        val provider = OpenRouterVoiceProvider("test", "https://openrouter.ai/api", "test-key")
        val request = provider.buildVoiceOutputRequest(VoiceOutputRequest("hello", model.id, "reference-id", resolvedModel = model))
        assertEquals("/api/v1/audio/speech", request.url.encodedPath)
        val buffer = okio.Buffer()
        request.body!!.writeTo(buffer)
        assertEquals("reference-id", org.json.JSONObject(buffer.readUtf8()).getString("voice"))
        assertTrue(model.isVoiceOutputCandidate)
    }

    @Test fun chatAudioAllowlistWorksAndOtherChatAudioIsNotMistakenForSpeech() {
        val chatAudio = LLMModel("openai/gpt-audio-mini", "Audio", "OpenRouter", voiceRole = VoiceRole.NONE)
        assertEquals(OpenRouterVoiceProvider.TtsRoute.CHAT_AUDIO, OpenRouterVoiceProvider.ttsRoute(chatAudio.id, chatAudio))
        assertTrue(chatAudio.isVoiceInputCandidate)
        assertTrue(chatAudio.isVoiceOutputCandidate)
        val music = LLMModel("google/lyria", "Music", "OpenRouter", outputModalities = listOf("audio"), voiceRole = VoiceRole.NONE)
        assertEquals(OpenRouterVoiceProvider.TtsRoute.UNSUPPORTED, OpenRouterVoiceProvider.ttsRoute(music.id, music))
        assertFalse(music.isVoiceOutputCandidate)
    }

    @Test fun routeError401KeepsVendorMessageAndCredentialError401IsAuth() {
        assertFalse(OpenRouterVoiceProvider.isAuthFailureBody(401, "Model is unavailable on this route".toByteArray()))
        assertTrue(OpenRouterVoiceProvider.isAuthFailureBody(401, "Invalid API key".toByteArray()))
    }
}
