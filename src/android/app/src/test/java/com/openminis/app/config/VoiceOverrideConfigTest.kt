package com.openminis.app.config

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.SystemVoiceEntries
import com.openminis.app.data.model.VoiceRole
import org.junit.Assert.assertThrows
import org.junit.Test

class VoiceOverrideConfigTest {
    @Test fun systemInputAndOutputCannotBeConfused() {
        Build34ConfigFields.validateVoiceOverride(SystemVoiceEntries.asrOnline, output = false)
        Build34ConfigFields.validateVoiceOverride(SystemVoiceEntries.asrOffline, output = false)
        Build34ConfigFields.validateVoiceOverride(SystemVoiceEntries.tts, output = true)
        assertThrows(ConfigError.InvalidValue::class.java) {
            Build34ConfigFields.validateVoiceOverride(SystemVoiceEntries.tts, output = false)
        }
        assertThrows(ConfigError.InvalidValue::class.java) {
            Build34ConfigFields.validateVoiceOverride(SystemVoiceEntries.asrOnline, output = true)
        }
    }

    @Test fun configuredModelMustSupportTheRequestedVoiceDirection() {
        val text = ModelEntry("provider", LLMModel("text-only", "Text", "Custom"))
        for (output in listOf(false, true)) assertThrows(ConfigError.InvalidValue::class.java) {
            Build34ConfigFields.validateVoiceOverride(text, output)
        }
        val stt = ModelEntry("provider", LLMModel("transcriber", "ASR", "Custom", voiceRole = VoiceRole.STT))
        Build34ConfigFields.validateVoiceOverride(stt, output = false)
        assertThrows(ConfigError.InvalidValue::class.java) { Build34ConfigFields.validateVoiceOverride(stt, output = true) }
    }
}
