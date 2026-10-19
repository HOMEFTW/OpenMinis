package com.openminis.app.speech

/** A recognition take may switch system services once, never bounce between them. */
internal class SystemRecognitionFallback {
    private var used = false

    fun reset() { used = false }
    fun disable() { used = true }

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
