package com.openminis.app.service

import org.junit.Assert.*
import org.junit.Test

class OAuthWaitKeepAliveTest {
    @Test fun `repeated cleanup of one flow cannot release a concurrent sign in`() {
        val flows = OAuthWaitFlows(1_000)
        assertEquals(1, flows.add("a", "provider", 0))
        assertEquals(2, flows.add("b", "mcp", 0))
        assertTrue(flows.remove("a"))
        assertFalse(flows.remove("a"))
        assertEquals(1, flows.count)
        assertTrue(flows.remove("b"))
        assertNull(flows.nextDeadline())
    }

    @Test fun `abandoned sign ins expire independently at their original deadlines`() {
        val flows = OAuthWaitFlows(1_000)
        flows.add("old", "openai", 0)
        flows.add("new", "mcp", 600)
        assertEquals(1_000L, flows.nextDeadline())
        assertTrue(flows.prune(999).isEmpty())
        assertEquals(listOf("openai"), flows.prune(1_000))
        assertEquals(1_600L, flows.nextDeadline())
        assertEquals(listOf("mcp"), flows.prune(1_600))
        assertEquals(0, flows.count)
        assertNull(flows.nextDeadline())
    }

    @Test fun `refreshing one id extends its deadline without adding an extra hold`() {
        val flows = OAuthWaitFlows(1_000)
        flows.add("id", "first", 0)
        assertEquals(1, flows.add("id", "retry", 500))
        assertTrue(flows.prune(1_000).isEmpty())
        assertEquals(1_500L, flows.nextDeadline())
        assertEquals(listOf("retry"), flows.prune(1_500))
    }

    @Test fun `task removal releases every hold and later cleanup is a no op`() {
        val flows = OAuthWaitFlows(1_000)
        flows.add("a", "one", 0)
        flows.add("b", "two", 0)
        assertEquals(2, flows.clear())
        assertEquals(0, flows.clear())
        assertFalse(flows.remove("a"))
        assertEquals(0, flows.count)
    }

    @Test fun `keep alive begins and ends are balanced even before service installation`() {
        val base = OAuthWaitKeepAlive.active.value
        val a = OAuthWaitKeepAlive.begin("test-a")
        val b = OAuthWaitKeepAlive.begin("test-b")
        try {
            assertEquals(base + 2, OAuthWaitKeepAlive.active.value)
            OAuthWaitKeepAlive.end(a, "callback")
            OAuthWaitKeepAlive.end(a, "cleanup")
            assertEquals(base + 1, OAuthWaitKeepAlive.active.value)
        } finally {
            OAuthWaitKeepAlive.end(a, "test-cleanup")
            OAuthWaitKeepAlive.end(b, "test-cleanup")
        }
        assertEquals(base, OAuthWaitKeepAlive.active.value)
        assertEquals(5 * 60_000L, OAuthWaitKeepAlive.MAX_HOLD_MS)
    }
}
