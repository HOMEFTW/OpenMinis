package com.openminis.app.sandbox

import org.junit.Assert.*
import org.junit.Test

class TracerAssertionTest {
    @Test fun compatibilityModeMustNotReplayTheSameFailingCommand() {
        assertFalse(SeccompFallbackPolicy.shouldRetryWithoutSeccomp(139, 10, false, true))
        assertFalse(SeccompFallbackPolicy.shouldRetryWithoutSeccomp(1, 10, true, false))
    }

    @Test fun onlyKnownTracerAssertionSelectsCompatibility() {
        assertTrue(SeccompFallbackPolicy.isTracerAssertion(
            "./tracee/event.c:568: int handle_tracee_event(Tracee *, int): assertion \"tracee->restart_how != PTRACE_CONT\" failed",
        ))
        assertFalse(SeccompFallbackPolicy.isTracerAssertion("Segmentation fault (core dumped)"))
        assertFalse(SeccompFallbackPolicy.isTracerAssertion("app.c:10 assertion failed"))
        assertFalse(SeccompFallbackPolicy.isTracerAssertion("tracee/event.c: tracee->restart_how != PTRACE_CONT"))
    }
}
