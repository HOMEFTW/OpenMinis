package com.openminis.app.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FallbackPulseGateTest {
    @Test fun reopeningAfterAFallbackDoesNotReplayThePulse() {
        assertFalse(shouldPulse(current = 3, baseline = 3))
    }

    @Test fun onlyNewFallbacksOnThisVisitPulse() {
        assertFalse(shouldPulse(current = 0, baseline = 0))
        assertFalse(shouldPulse(current = 2, baseline = 3))
        assertTrue(shouldPulse(current = 4, baseline = 3))
    }
}
