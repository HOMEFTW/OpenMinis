package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRunStateTest {

    @Test
    fun `accepted run is running until it completes`() {
        assertEquals(
            ChatRunStatus.Running,
            ChatRunStatePolicy.statusFor(accepted = true),
        )
        assertEquals(
            ChatRunStatus.Completed,
            ChatRunStatePolicy.statusFor(accepted = true, completed = true),
        )
    }

    @Test
    fun `rejected admission is not started`() {
        assertEquals(
            ChatRunStatus.NotStarted,
            ChatRunStatePolicy.statusFor(accepted = false),
        )
    }

    @Test
    fun `queued run stays distinct from a running run`() {
        assertEquals(
            ChatRunStatus.Queued,
            ChatRunStatePolicy.statusFor(accepted = true, queued = true),
        )
        assertFalse(ChatRunStatus.Queued.isTerminal)
        assertTrue(ChatRunStatus.Completed.isTerminal)
    }

    @Test
    fun `terminal failures keep their specific outcome`() {
        assertEquals(
            ChatRunStatus.Error,
            ChatRunStatePolicy.statusFor(accepted = true, error = true),
        )
        assertEquals(
            ChatRunStatus.Cancelled,
            ChatRunStatePolicy.statusFor(accepted = true, cancelled = true),
        )
        assertEquals(
            ChatRunStatus.Timeout,
            ChatRunStatePolicy.statusFor(accepted = true, timedOut = true),
        )
        assertEquals(
            ChatRunStatus.BudgetExceeded,
            ChatRunStatePolicy.statusFor(accepted = true, budgetExceeded = true),
        )
    }

    @Test
    fun `only completed counts as success`() {
        assertTrue(ChatRunStatus.Completed.isSuccess)
        assertFalse(ChatRunStatus.Error.isSuccess)
        assertFalse(ChatRunStatus.Cancelled.isSuccess)
        assertFalse(ChatRunStatus.Timeout.isSuccess)
        assertFalse(ChatRunStatus.BudgetExceeded.isSuccess)
        assertFalse(ChatRunStatus.NotStarted.isSuccess)
    }

    @Test
    fun `late completion cannot overwrite a cancelled run`() {
        val running = ChatRunState(
            runId = "run-a",
            status = ChatRunStatus.Running,
            startedAtMs = 10L,
        )
        val cancelled = ChatRunStatePolicy.transition(
            current = running,
            requested = ChatRunStatus.Cancelled,
            error = "user stopped",
            nowMs = 20L,
        )
        val lateCompletion = ChatRunStatePolicy.transition(
            current = cancelled,
            requested = ChatRunStatus.Completed,
            nowMs = 30L,
        )

        assertEquals(ChatRunStatus.Cancelled, lateCompletion.status)
        assertEquals("user stopped", lateCompletion.error)
        assertEquals(20L, lateCompletion.finishedAtMs)
    }

    @Test
    fun `late completion cannot overwrite a budget stop`() {
        val budgetExceeded = ChatRunStatePolicy.transition(
            current = ChatRunState("run-a", ChatRunStatus.Running),
            requested = ChatRunStatus.BudgetExceeded,
            error = "budget",
            nowMs = 20L,
        )

        val lateCompletion = ChatRunStatePolicy.transition(
            current = budgetExceeded,
            requested = ChatRunStatus.Completed,
            nowMs = 30L,
        )

        assertEquals(ChatRunStatus.BudgetExceeded, lateCompletion.status)
        assertEquals("budget", lateCompletion.error)
    }

    @Test
    fun `a completed run stays completed when a later queued run fails`() {
        val aCompleted = ChatRunStatePolicy.transition(
            current = ChatRunState("run-a", ChatRunStatus.Running),
            requested = ChatRunStatus.Completed,
            nowMs = 20L,
        )
        val bFailed = ChatRunStatePolicy.transition(
            current = ChatRunState("run-b", ChatRunStatus.Running),
            requested = ChatRunStatus.Error,
            error = "queued failure",
            nowMs = 30L,
        )

        // Models independent drain batches: B's failure must not be applied
        // to A after A has already reached its terminal outcome.
        val aAfterLateFailure = ChatRunStatePolicy.transition(
            current = aCompleted,
            requested = bFailed.status,
            error = bFailed.error,
            nowMs = 30L,
        )

        assertEquals(ChatRunStatus.Completed, aAfterLateFailure.status)
        assertEquals(ChatRunStatus.Error, bFailed.status)
    }

    @Test
    fun `only the queued run without an assistant id claims the next bubble`() {
        val states = mapOf(
            "run-a" to ChatRunState(
                runId = "run-a",
                status = ChatRunStatus.Running,
                assistantMessageId = "assistant-a",
            ),
            "run-b" to ChatRunState(
                runId = "run-b",
                status = ChatRunStatus.Queued,
            ),
        )

        assertEquals(
            listOf("run-b"),
            ChatRunStatePolicy.runsNeedingAssistantBinding(listOf("run-a", "run-b"), states),
        )
    }
}
