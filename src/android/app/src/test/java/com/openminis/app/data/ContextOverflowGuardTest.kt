package com.openminis.app.data

import org.junit.Assert.*
import org.junit.Test

class ContextOverflowGuardTest {
    @Test fun onlyExplicitContextRejectionsTriggerRecovery() {
        for (status in listOf(400, 413)) {
            assertTrue(ContextOverflowGuard.isContextOverflow(status, "maximum context length exceeded"))
            assertTrue(ContextOverflowGuard.isContextOverflow(status, "input and max_tokens exceed context limit"))
            assertFalse(ContextOverflowGuard.isContextOverflow(status, "Request exceeds the maximum allowed number of bytes"))
            assertFalse(ContextOverflowGuard.isContextOverflow(status, "unknown parameter temperature"))
            assertFalse(ContextOverflowGuard.isContextOverflow(status, "request too large"))
            assertFalse(ContextOverflowGuard.isContextOverflow(status, "image exceeds the maximum dimensions"))
        }
        assertFalse(ContextOverflowGuard.isContextOverflow(500, "maximum context length"))
        assertFalse(ContextOverflowGuard.isContextOverflow(null, "too many tokens"))
    }

    @Test fun hardOverflowAllowsCompactionWhenTheNativeTierSupportsSummaries() {
        assertEquals(ContextPolicy.CheckResult.NEEDS_COMPACT, ContextPolicy.forContextWindow(32000).check(33000, 32000))
        assertEquals(ContextPolicy.CheckResult.NEEDS_COMPACT, ContextPolicy.forContextWindow(128000).check(130000, 128000))
    }
}
