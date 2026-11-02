package com.openminis.app.agent.jobs

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AgentJobRegistryRaceTest {
    @Before fun reset() = AgentJobRegistry.resetForTest()

    private fun reserve(session: String? = null, then: AgentJobThen = AgentJobThen.None, parent: String = "parent") = AgentJobRegistry.tryRegisterChild(
        title = "task", origin = AgentJobOrigin.TOOL,
        target = AgentJobTarget.ChildOfCurrent(parent, "tool"), prompt = "do it",
        then = then, runSessionId = session,
    )

    @Test fun `concurrent reservations cannot exceed three pending slots`() {
        val executor = Executors.newFixedThreadPool(8)
        val gate = CountDownLatch(1)
        try {
            val attempts = (0 until 8).map { executor.submit<Boolean> { gate.await(); reserve() != null } }
            gate.countDown()
            assertEquals(3, attempts.count { it.get(5, TimeUnit.SECONDS) })
            assertEquals(3, AgentJobRegistry.runningChildJobCount)
        } finally { executor.shutdownNow() }
    }

    @Test fun `same child cannot be resumed twice and cancelled pending job cannot revive`() {
        val job = requireNotNull(reserve("child"))
        assertNull(reserve("child"))
        AgentJobRegistry.cancel(job.id, "stopped during setup")
        assertFalse(AgentJobRegistry.markRunning(job.id, "child"))
        assertEquals(AgentJobState.CANCELLED, AgentJobRegistry.job(job.id)?.state)
    }

    @Test fun `each of six parents can reserve three children without starving the others`() {
        repeat(6) { p ->
            val parent = "parent-$p"
            repeat(3) { assertNotNull(reserve(parent = parent)) }
            assertNull(reserve(parent = parent))
            assertEquals(3, AgentJobRegistry.runningChildJobCount(parent))
            assertFalse(AgentJobRegistry.canStartChildJob(parent))
        }
        assertEquals(18, AgentJobRegistry.runningChildJobCount)
    }

    @Test fun `a saturated parent and its full backlog do not block another parent's queue`() {
        repeat(3) { assertNotNull(reserve()) }
        repeat(AgentJobRegistry.MAX_QUEUED_DELEGATIONS) { i ->
            assertTrue(AgentJobRegistry.enqueueDelegation(AgentJobRegistry.QueuedDelegation("parent", "{}", "a-$i")))
        }
        assertFalse(AgentJobRegistry.enqueueDelegation(AgentJobRegistry.QueuedDelegation("parent", "{}", "overflow")))
        assertTrue(AgentJobRegistry.enqueueDelegation(AgentJobRegistry.QueuedDelegation("other", "{}", "b")))
        val started = mutableListOf<String>()
        AgentJobRegistry.registerQueuedStarter("parent") { started += it.toolUseId; reserve() != null }
        AgentJobRegistry.registerQueuedStarter("other") { started += it.toolUseId; reserve(parent = "other") != null }
        AgentJobRegistry.drainIfStalled()
        assertEquals(listOf("b"), started)
        assertEquals(10, AgentJobRegistry.queuedCount("parent"))
        assertEquals(0, AgentJobRegistry.queuedCount("other"))
        assertEquals(1, AgentJobRegistry.runningChildJobCount("other"))
    }

    @Test fun `batch stop drops queue before freed slot could start it`() {
        val jobs = (0 until 3).map { requireNotNull(reserve()) }
        var starts = 0
        AgentJobRegistry.registerQueuedStarter("parent") { starts++; true }
        AgentJobRegistry.enqueueDelegation(AgentJobRegistry.QueuedDelegation("parent", "{}", "queued"))
        AgentJobRegistry.cancelAll("parent", "stop")
        assertEquals(0, starts)
        assertEquals(0, AgentJobRegistry.queuedCount("parent"))
        assertTrue(jobs.all { AgentJobRegistry.job(it.id)?.state == AgentJobState.CANCELLED })
    }

    @Test fun `callback waits for suspend final persistence and tab release`() = runBlocking {
        val job = requireNotNull(reserve("child", AgentJobThen.FollowUpParent(null)))
        val entered = CompletableDeferred<Unit>()
        val releasePersistence = CompletableDeferred<Unit>()
        val dispatched = CompletableDeferred<Unit>()
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        AgentJobRegistry.registerTabRelease(job.id) { events += "tabs" }
        AgentJobRegistry.setCompletionHookAsync(job.id) {
            entered.complete(Unit)
            releasePersistence.await()
            events += "persisted"
        }
        AgentJobRegistry.followUpDispatcher = { _, _, _ -> events += "callback"; dispatched.complete(Unit) }
        AgentJobRegistry.finish(job.id, AgentJobState.DONE, "done")
        withTimeout(5_000) { entered.await() }
        assertFalse(dispatched.isCompleted)
        releasePersistence.complete(Unit)
        withTimeout(5_000) { dispatched.await() }
        assertEquals(listOf("tabs", "persisted", "callback"), events.toList())
    }
}
