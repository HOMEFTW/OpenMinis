package com.openminis.app.speech

import org.junit.Assert.*
import org.junit.Test

class SystemRecognitionFallbackTest {
    @Test fun missingOfflinePackCanRetryOnlineOnce() {
        val fallback = SystemRecognitionFallback()
        assertEquals(false, fallback.nextMode(true, RecognitionError.LANGUAGE_UNSUPPORTED, false, true))
        assertNull(fallback.nextMode(false, RecognitionError.NETWORK, true, true))
    }

    @Test fun onlineFailureCanRetryOfflineWithoutLoopingOrChangingPreference() {
        val fallback = SystemRecognitionFallback()
        assertEquals(true, fallback.nextMode(false, RecognitionError.NETWORK, true, true))
        assertNull(fallback.nextMode(true, RecognitionError.LANGUAGE_UNSUPPORTED, true, true))
        fallback.reset()
        assertEquals(true, fallback.nextMode(false, RecognitionError.LANGUAGE_UNSUPPORTED, true, true))
    }

    @Test fun permissionCancellationAndMissingServiceDoNotStartAnOfflineRetry() {
        val fallback = SystemRecognitionFallback()
        assertNull(fallback.nextMode(false, RecognitionError.PERMISSION_DENIED, true, true))
        assertNull(fallback.nextMode(false, RecognitionError.NO_MATCH, true, true))
        assertNull(fallback.nextMode(false, RecognitionError.NETWORK, true, false))
        assertNull(fallback.nextMode(false, RecognitionError.NETWORK, false, true))
    }
}
