package com.openminis.app.sandbox

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class TerminalSessionLifecycleTest {
    @Test fun `early exit releases running guard and old callbacks cannot stop the retry`() {
        val lifecycle = TerminalSessionLifecycle()
        val first = lifecycle.begin()!!
        lifecycle.withCurrent(first) { lifecycle.state.value = TerminalSession.State.RUNNING }
        assertNull(lifecycle.begin())
        var replacement = 0L
        assertTrue(lifecycle.withCurrent(first) {
            lifecycle.state.value = TerminalSession.State.STOPPED
            replacement = lifecycle.begin()!!
            lifecycle.withCurrent(replacement) { lifecycle.state.value = TerminalSession.State.RUNNING }
        })
        assertFalse(lifecycle.withCurrent(first) { lifecycle.state.value = TerminalSession.State.STOPPED })
        assertEquals(TerminalSession.State.RUNNING, lifecycle.state.value)
        assertEquals(replacement, lifecycle.runningToken())
    }

    @Test fun `stop during boot prevents late adoption and stop after retry invalidates the retry`() {
        val lifecycle = TerminalSessionLifecycle()
        val old = lifecycle.begin()!!
        lifecycle.stop {}
        var adopted = false
        assertFalse(lifecycle.withCurrent(old) { adopted = true })
        assertFalse(adopted)
        val replacement = lifecycle.begin()!!
        lifecycle.withCurrent(replacement) { lifecycle.state.value = TerminalSession.State.RUNNING }
        lifecycle.stop {}
        assertFalse(lifecycle.withCurrent(replacement) { fail("stopped instance still owned") })
        assertEquals(TerminalSession.State.STOPPED, lifecycle.state.value)
        assertNull(lifecycle.runningToken())
    }

    @Test fun `concurrent starts admit only one boot`() {
        val lifecycle = TerminalSessionLifecycle()
        val pool = Executors.newFixedThreadPool(8)
        try {
            val go = CountDownLatch(1)
            val starts = (1..8).map { pool.submit<Long?> { go.await(); lifecycle.begin() } }
            go.countDown()
            assertEquals(1, starts.map { it.get(2, TimeUnit.SECONDS) }.count { it != null })
            assertEquals(TerminalSession.State.BOOTING, lifecycle.state.value)
        } finally { pool.shutdownNow() }
    }
}
