package com.openminis.app.speech

import org.junit.Assert.*
import org.junit.Test

class VoiceOutputVolumeTest {
    @Test fun volumeIsIndependentOfMuteAndEnableAndBoundedForPlayback() {
        val previous = VoiceOutputState.volume.value
        val muted = VoiceOutputState.isMuted.value
        val enabled = VoiceOutputState.isEnabled.value
        try {
            VoiceOutputState.setVolume(0.35f, persist = false)
            assertEquals(0.35f, VoiceOutputState.volume.value, 0f)
            VoiceOutputState.setVolume(0f)
            assertEquals(0f, VoiceOutputState.volume.value, 0f)
            assertEquals(muted, VoiceOutputState.isMuted.value)
            assertEquals(enabled, VoiceOutputState.isEnabled.value)
            VoiceOutputState.setVolume(5f)
            assertEquals(2f, VoiceOutputState.volume.value, 0f)
            VoiceOutputState.setVolume(-1f)
            assertEquals(0f, VoiceOutputState.volume.value, 0f)
            VoiceOutputState.setVolume(Float.NaN)
            assertEquals(1f, VoiceOutputState.volume.value, 0f)
            VoiceOutputState.setVolume(Float.POSITIVE_INFINITY)
            assertEquals(1f, VoiceOutputState.volume.value, 0f)
        } finally { VoiceOutputState.setVolume(previous, persist = false) }
    }
}
