package com.openminis.app.speech

import android.media.audiofx.LoudnessEnhancer
import kotlin.math.log10
import kotlin.math.roundToInt

internal fun boundedSpeechVolume(value: Float): Float = if (value.isFinite()) value.coerceIn(0f, 2f) else 1f

internal data class SpeechPlaybackGain(val volume: Float, val boostMillibels: Int)

internal fun speechPlaybackGain(value: Float): SpeechPlaybackGain {
    val gain = boundedSpeechVolume(value)
    return SpeechPlaybackGain(gain.coerceAtMost(1f), if (gain > 1f) (2000 * log10(gain.toDouble())).roundToInt() else 0)
}

/** Android's player volume stops at 1.0; the session effect supplies a limited boost above it. */
internal class SpeechVolumeBoost {
    private var sessionId = 0
    private var enhancer: LoudnessEnhancer? = null

    fun apply(value: Float, audioSessionId: Int): Float {
        val gain = speechPlaybackGain(value)
        if (sessionId != audioSessionId) {
            release()
            sessionId = audioSessionId
        }
        if (gain.boostMillibels > 0 && enhancer == null && audioSessionId > 0) {
            enhancer = runCatching { LoudnessEnhancer(audioSessionId) }.getOrNull()
        }
        runCatching {
            enhancer?.setTargetGain(gain.boostMillibels)
            enhancer?.enabled = gain.boostMillibels > 0
        }
        return gain.volume
    }

    fun release() {
        runCatching { enhancer?.release() }
        enhancer = null
        sessionId = 0
    }
}
