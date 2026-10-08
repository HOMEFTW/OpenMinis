package com.openminis.app.ui.chat

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

class QueuedRunIsolationTest {
    @Test fun `cancelling inserted job resumes its waiting parent without overlapping work`() = runTest {
        val ready = CompletableDeferred<Job>()
        val events = mutableListOf<String>()
        val parent = launch {
            events += "parent-paused"
            val completed = QueuedRunIsolation.run({ ready.complete(it) }) {
                events += "child-start"
                try { awaitCancellation() } finally { events += "child-end" }
            }
            assertFalse(completed)
            events += "parent-resumed"
        }
        val child = ready.await()
        testScheduler.runCurrent()
        child.cancel()
        parent.join()
        assertFalse(parent.isCancelled)
        assertEquals(listOf("parent-paused", "child-start", "child-end", "parent-resumed"), events)
    }

    @Test fun `global cancellation cancels inserted job and never resumes parent`() = runTest {
        val ready = CompletableDeferred<Job>()
        var resumed = false
        val parent = launch {
            QueuedRunIsolation.run({ ready.complete(it) }) { awaitCancellation() }
            resumed = true
        }
        val child = ready.await()
        parent.cancel()
        parent.join()
        assertTrue(child.isCancelled)
        assertFalse(resumed)
    }

    @Test fun `inserted failure can be handled without cancelling parent and success is awaited`() = runTest {
        var caught = false
        try { QueuedRunIsolation.run({}) { error("tool failed") } }
        catch (_: IllegalStateException) { caught = true }
        assertTrue(caught)
        var finished = false
        assertTrue(QueuedRunIsolation.run({}) { finished = true })
        assertTrue(finished)
    }
}
