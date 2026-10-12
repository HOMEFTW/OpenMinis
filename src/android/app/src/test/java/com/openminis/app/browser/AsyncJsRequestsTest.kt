package com.openminis.app.browser

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AsyncJsRequestsTest {
    @Test fun `timed out callback cannot complete the next script or fetch`() = runTest {
        val requests = AsyncJsRequests()
        var firstToken = ""
        val first = async { requests.await(30_000) { firstToken = it } }
        runCurrent()
        advanceTimeBy(30_001)
        runCurrent()
        assertNull(first.await())
        var secondToken = ""
        val second = async { requests.await(60_000) { secondToken = it } }
        runCurrent()
        requests.complete(firstToken, "late first result")
        runCurrent()
        assertFalse(second.isCompleted)
        requests.complete(secondToken, "fetch result")
        assertEquals("fetch result", second.await())
    }

    @Test fun `cancelled or failed evaluation releases only its own callback`() = runTest {
        val requests = AsyncJsRequests()
        var oldToken = ""
        val old = async { requests.await(60_000) { oldToken = it } }
        runCurrent()
        old.cancelAndJoin()
        var nextToken = ""
        val next = async { requests.await(60_000) { nextToken = it } }
        runCurrent()
        requests.complete(oldToken, "late cancelled result")
        assertFalse(next.isCompleted)
        requests.complete(nextToken, "ok")
        assertEquals("ok", next.await())
        try {
            requests.await(1) { throw IllegalStateException("evaluation failed") }
            fail("expected evaluation failure")
        } catch (_: IllegalStateException) { }
    }

    @Test fun `out of order callbacks and duplicates keep their identity`() = runTest {
        val requests = AsyncJsRequests()
        var aToken = ""
        var bToken = ""
        val a = async { requests.await(1000) { aToken = it } }
        val b = async { requests.await(1000) { bToken = it } }
        runCurrent()
        requests.complete(bToken, "B")
        requests.complete(bToken, "duplicate B")
        requests.complete(aToken, "A")
        assertEquals("A", a.await())
        assertEquals("B", b.await())
    }

    @Test fun `closed or reclaimed tab finishes all outstanding requests`() = runTest {
        val requests = AsyncJsRequests()
        val a = async { requests.await(1000) {} }
        val b = async { requests.await(1000) {} }
        runCurrent()
        requests.failAll("closed")
        assertEquals("closed", a.await())
        assertEquals("closed", b.await())
    }
}
