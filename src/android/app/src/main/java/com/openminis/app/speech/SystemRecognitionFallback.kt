package com.openminis.app.speech

/** A recognition take may switch system services once, never bounce between them. */
internal class SystemRecognitionFallback {
    private var used = false

    fun reset() { used = false }
    fun disable() { used = true }

    /** Apply the shared SDK error rule without reopening a stopped or productive take. */
    fun nextModeForCode(
        usingOnDevice: Boolean,
        errorCode: Int,
        allowOnlineFallback: Boolean,
        onDeviceAvailable: Boolean,
        sessionCommitted: Boolean,
        stopRequested: Boolean,
        heardSpeech: Boolean,
        hasTranscript: Boolean,
        hasReplayAudio: Boolean,
    ): Boolean? {
        if (sessionCommitted) return null
        // Saved audio may be replayed without acquiring the microphone. Without
        // it, preserve the transcript for the engine's existing salvage path.
        if (!hasReplayAudio && (stopRequested || heardSpeech || hasTranscript)) return null
        val error = RecognizerErrorCodes.classify(errorCode)
        // Keep the fork's opt-in network fallback, but do not treat quota or
        // unknown SDK codes as a disconnected service just because both map UNKNOWN.
        if (!RecognizerFallback.shouldSwitch(errorCode) && error != RecognitionError.NETWORK) return null
        return nextMode(usingOnDevice, error, allowOnlineFallback, onDeviceAvailable)
    }

    fun nextMode(usingOnDevice: Boolean, error: RecognitionError, allowOnlineFallback: Boolean,
                 onDeviceAvailable: Boolean): Boolean? {
        if (used) return null
        val retryable = error in setOf(RecognitionError.LANGUAGE_UNSUPPORTED, RecognitionError.NETWORK,
            RecognitionError.OEM_NO_SERVICE, RecognitionError.UNKNOWN)
        if (!retryable) return null
        if (usingOnDevice) {
            if (!allowOnlineFallback && error != RecognitionError.LANGUAGE_UNSUPPORTED) return null
        } else if (!allowOnlineFallback || !onDeviceAvailable) return null
        used = true
        return !usingOnDevice
    }
}
