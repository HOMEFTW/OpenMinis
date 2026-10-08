package com.openminis.app.sandbox

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SessionCommandRegistryTest {
    @Test fun stoppingOneSessionCancelsItsRunningAndWaitingCommandsOnly() = runTest {
        val registry = SessionCommandRegistry()
        val blocker = CompletableDeferred<Unit>()
        val jobs = listOf("a", "a", "ab").map { id -> launch { registry.run(id) { blocker.await() } } }
        testScheduler.runCurrent()
        assertEquals(3, registry.activeCount)
        registry.stop("a")
        testScheduler.runCurrent()
        assertTrue(jobs[0].isCancelled)
        assertTrue(jobs[1].isCancelled)
        assertTrue(jobs[2].isActive)
        assertEquals(1, registry.activeCount)
        registry.stop()
        jobs.forEach { it.join() }
        assertEquals(0, registry.activeCount)
        assertEquals(42, registry.run("a") { 42 })
    }

    @Test fun failureAndCallerCancellationLeaveNoJobs() = runTest {
        val registry = SessionCommandRegistry()
        runCatching { registry.run("a") { error("failure") } }
        assertEquals(0, registry.activeCount)
        val job = launch { registry.run("a") { awaitCancellation() } }
        testScheduler.runCurrent()
        job.cancelAndJoin()
        assertEquals(0, registry.activeCount)
    }
}
