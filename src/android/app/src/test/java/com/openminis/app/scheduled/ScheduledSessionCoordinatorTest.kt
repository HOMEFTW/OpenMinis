package com.openminis.app.scheduled

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScheduledSessionCoordinatorTest {
    private fun task() = ScheduledTask(id = "task", label = "Task", timeOfDayHour = 9, timeOfDayMinute = 0,
        repeatMode = ScheduledRepeatMode.ONCE, prompt = "work", targetMode = ScheduledTargetMode.AppendToSession("chat"),
        triggerKind = ScheduledTriggerKind.AFTER, delaySec = 120, anchorMs = 0, creatorSessionId = "chat")
    private fun input(tasks: List<ScheduledTask>, lead: Long = 60_000L, running: Map<String, ScheduledTask> = emptyMap()) =
        ScheduledSessionCoordinator.Inputs(tasks, running, emptyMap(), lead)

    @Test fun `only a follow up into the creator chat receives a countdown hold`() {
        val original = task()
        val cases = listOf(original.copy(id = "creatorless", creatorSessionId = null),
            original.copy(id = "other", creatorSessionId = "other"),
            original.copy(id = "new", targetMode = ScheduledTargetMode.NewSession),
            original.copy(id = "child", targetMode = ScheduledTargetMode.ChildOfCurrent("chat")),
            original.copy(id = "rerun", targetMode = ScheduledTargetMode.RerunMessage("chat", "message")), original)
        val state = ScheduledSessionCoordinator.derive(input(cases), 60_000)
        assertEquals(setOf("chat"), state.sessions)
        assertEquals(listOf("task"), state.waits.map { it.taskId })
        assertTrue(ScheduledSessionCoordinator.derive(input(cases), 59_999).sessions.isEmpty())
        assertTrue(ScheduledSessionCoordinator.derive(input(cases, lead = 0), 60_000).sessions.isEmpty())
        assertEquals(setOf("chat"), ScheduledSessionCoordinator.derive(input(cases, lead = Long.MAX_VALUE), 0).sessions)
    }

    @Test fun `lead window is entered by a timer without any task or preference write`() = runTest {
        val source = MutableStateFlow(input(listOf(task())))
        val states = mutableListOf<ScheduledSessionCoordinator.State>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            ScheduledSessionCoordinator.observe(source) { testScheduler.currentTime }.collect { states += it }
        }
        assertTrue(states.last().sessions.isEmpty())
        advanceTimeBy(60_001)
        runCurrent()
        assertEquals(setOf("chat"), states.last().sessions)
        source.value = source.value.copy(leadMs = 0)
        runCurrent()
        assertTrue(states.last().sessions.isEmpty())
    }

    @Test fun `the last disabled fire holds its creator until durable delivery finishes even with countdown off`() {
        val last = task().copy(enabled = false, triggeredCount = 1)
        val running = input(listOf(last), lead = 0, running = mapOf("execution" to last))
        assertEquals(setOf("chat"), ScheduledSessionCoordinator.derive(running, 150_000).sessions)
        assertTrue(ScheduledSessionCoordinator.derive(running, 150_000).waits.isEmpty())
        assertTrue(ScheduledSessionCoordinator.derive(running.copy(running = emptyMap()), 150_000).sessions.isEmpty())
        assertTrue(ScheduledSessionCoordinator.derive(running.copy(tasks = emptyList()), 150_000).sessions.isEmpty())
    }

    @Test fun `on completion waits hold only while their upstream still exists and is unfinished`() {
        val upstream = task().copy(id = "upstream", creatorSessionId = null)
        val dependent = task().copy(id = "dependent", triggerKind = ScheduledTriggerKind.ON_COMPLETION, onCompletionOf = upstream.id)
        assertEquals(setOf("chat"), ScheduledSessionCoordinator.derive(input(listOf(upstream, dependent)), 0).sessions)
        assertTrue(ScheduledSessionCoordinator.derive(input(listOf(upstream.copy(enabled = false), dependent)), 0).sessions.isEmpty())
        assertTrue(ScheduledSessionCoordinator.derive(input(listOf(dependent)), 0).sessions.isEmpty())
        assertEquals(setOf("chat"), ScheduledSessionCoordinator.derive(input(listOf(upstream.copy(enabled = false), dependent),
            running = mapOf("run" to upstream)), 0).sessions)
    }

    @Test fun `interval counter is based on admitted fires instead of completed history`() {
        val interval = task().copy(triggerKind = ScheduledTriggerKind.INTERVAL, intervalSec = 120, maxFires = 30, triggeredCount = 1)
        val wait = ScheduledSessionCoordinator.derive(input(listOf(interval)), 60_000).waits.single()
        assertEquals("2/30", wait.runCounter)
        assertEquals(120_000L, wait.nextRunWallMs)
    }

    @Test fun `queue cancellation is durable and affects only the selected creator`() {
        var saved = ""
        val queue = ScheduledDeliveryQueue(null) { saved = it }
        queue.enqueue(ScheduledDelivery(task(), "chat", "mine", 1, true))
        queue.enqueue(ScheduledDelivery(task().copy(id = "other", creatorSessionId = "other"), "chat", "other", 2, true))
        queue.cancelCreatedBy(setOf("chat"))
        val restored = ScheduledDeliveryQueue(saved) { }
        assertNull(restored.claim("mine"))
        assertNotNull(restored.claim("other"))
        assertTrue(restored.deliveries.value.single { it.executionId == "mine" }.superseded)
    }

    @Test fun `manual handoffs reserve admission while automatic interval fires retain fork overlap semantics`() {
        val task = task().copy(id = "handoff-test")
        try {
            assertTrue(ScheduledSessionCoordinator.beginHandoff("manual-a", task, manual = true))
            assertFalse(ScheduledSessionCoordinator.beginHandoff("manual-b", task, manual = true))
            assertTrue(ScheduledSessionCoordinator.beginHandoff("alarm", task, manual = false))
            assertTrue(ScheduledSessionCoordinator.isTaskRunning(task.id))
        } finally {
            listOf("manual-a", "manual-b", "alarm").forEach(ScheduledSessionCoordinator::endHandoff)
        }
        assertFalse(ScheduledSessionCoordinator.isTaskRunning(task.id))
    }
}
