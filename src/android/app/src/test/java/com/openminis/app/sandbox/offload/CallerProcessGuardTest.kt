package com.openminis.app.sandbox.offload

import org.junit.Assert.*
import org.junit.Test

class CallerProcessGuardTest {
    private fun stat(start: Long, state: String = "S") =
        "42 (command with ) parentheses) $state " + List(18) { "0" }.joinToString(" ") + " $start 0"

    @Test fun exitAndPidReuseDoNotKeepARecordingAlive() {
        var current: String? = stat(100)
        val guard = CallerProcessGuard(42) { current }
        assertTrue(guard.isAlive())
        current = stat(100, "Z")
        assertFalse(guard.isAlive())
        current = stat(200)
        assertFalse(guard.isAlive())
        current = null
        assertFalse(guard.isAlive())
    }

    @Test fun unverifiableCallerIsNeverTreatedAsLive() {
        assertFalse(CallerProcessGuard(42) { null }.isAlive())
        assertFalse(CallerProcessGuard(42) { "invalid" }.isAlive())
    }
}
