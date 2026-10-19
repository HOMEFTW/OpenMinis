package com.openminis.app.speech

import org.junit.Assert.*
import org.junit.Test

class SpeechVolumeBoostTest {
    @Test fun ordinaryVolumeUsesPlayerAttenuationWithoutAnEffect() {
        assertEquals(SpeechPlaybackGain(0f, 0), speechPlaybackGain(0f))
        assertEquals(SpeechPlaybackGain(0.35f, 0), speechPlaybackGain(0.35f))
        assertEquals(SpeechPlaybackGain(1f, 0), speechPlaybackGain(1f))
    }

    @Test fun doubleVolumeUsesSixDecibelBoostWhilePlayerVolumeRemainsValid() {
        val gain = speechPlaybackGain(2f)
        assertEquals(1f, gain.volume, 0f)
        assertEquals(602, gain.boostMillibels)
        assertEquals(gain, speechPlaybackGain(10f))
        assertEquals(SpeechPlaybackGain(1f, 0), speechPlaybackGain(Float.NaN))
    }
}
