package com.openminis.app.offload

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.openminis.app.util.MemorySharedPreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OffloadPermissionTimeoutTest {
    @Before fun setup() {
        val prefs = MemorySharedPreferences()
        OffloadPermissionManager.init(object : ContextWrapper(null) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
        })
        OffloadPermissionManager.setLevel("clipboard", OffloadPermissionManager.PermissionLevel.ASK_ONCE)
    }

    @Test fun timeoutDeniesWithoutAllowingAnOldDialogToApproveTheNextRequest() = runTest {
        val first = async { OffloadPermissionManager.checkPermission("clipboard", "Copy", "timeout-first") }
        runCurrent()
        val expired = requireNotNull(OffloadPermissionManager.pendingRequest.value)
        advanceTimeBy(OffloadPermissionManager.TOOL_DIALOG_TIMEOUT_MS)
        runCurrent()
        assertFalse(first.await())
        assertNull(OffloadPermissionManager.pendingRequest.value)

        val second = async { OffloadPermissionManager.checkPermission("clipboard", "Copy", "timeout-second") }
        runCurrent()
        val active = requireNotNull(OffloadPermissionManager.pendingRequest.value)
        OffloadPermissionManager.respondToRequest(OffloadPermissionManager.Response.ALLOW_SESSION, expired.requestId)
        runCurrent()
        assertFalse(second.isCompleted)
        assertEquals(active.requestId, OffloadPermissionManager.pendingRequest.value?.requestId)
        OffloadPermissionManager.respondToRequest(OffloadPermissionManager.Response.ALLOW_ONCE, active.requestId)
        assertTrue(second.await())
        assertNull(OffloadPermissionManager.pendingRequest.value)
    }

    @Test fun concurrentRequestsStayQueuedAndCancellationDoesNotEraseTheNextDialog() = runTest {
        val first = async { OffloadPermissionManager.checkPermission("clipboard", "Copy", "queue-first") }
        val second = async { OffloadPermissionManager.checkPermission("clipboard", "Copy", "queue-second") }
        runCurrent()
        assertEquals("queue-first", OffloadPermissionManager.pendingRequest.value?.sessionId)
        first.cancelAndJoin()
        runCurrent()
        val active = requireNotNull(OffloadPermissionManager.pendingRequest.value)
        assertEquals("queue-second", active.sessionId)
        OffloadPermissionManager.respondToRequest(OffloadPermissionManager.Response.DENY_SESSION, active.requestId)
        assertFalse(second.await())
        assertFalse(OffloadPermissionManager.checkPermission("clipboard", "Copy", "queue-second"))
        OffloadPermissionManager.clearSessionGrants("queue-second")
    }

    @Test fun fiveMinuteDeadlineIncludesTimeWaitingBehindAnotherDialog() = runTest {
        val first = async { OffloadPermissionManager.checkPermission("clipboard", "Copy", "total-first") }
        val second = async { OffloadPermissionManager.checkPermission("clipboard", "Copy", "total-second") }
        runCurrent()
        advanceTimeBy(OffloadPermissionManager.TOOL_DIALOG_TIMEOUT_MS)
        runCurrent()
        assertTrue(first.isCompleted)
        assertTrue(second.isCompleted)
        assertFalse(first.await())
        assertFalse(second.await())
        assertNull(OffloadPermissionManager.pendingRequest.value)
    }
}
