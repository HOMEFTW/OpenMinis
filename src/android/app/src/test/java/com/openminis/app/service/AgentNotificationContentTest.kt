package com.openminis.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentNotificationContentTest {
    @Test fun staleToolDoesNotShowAnIdleChatAsRunning() {
        assertEquals(AgentPhase.IDLE, resolveAgentPhase(false, "shell_execute", false, false))
    }

    @Test fun waitingOverridesTheExecutingToolAndThinkingPhase() {
        val phase = resolveAgentPhase(false, "request_user_input", true, true, isWaitingForAnswer = true)
        assertEquals(AgentPhase.WAITING, phase)
        assertTrue(notificationShowsTimer(phase))
        assertTrue(shouldPromoteAgentNotification(phase, false))
    }

    @Test fun presenceAndQueuedWorkDoNotRunAClockOrPromoteAChip() {
        assertFalse(notificationShowsTimer(AgentPhase.IDLE))
        assertFalse(shouldPromoteAgentNotification(AgentPhase.IDLE, true))
        val queued = resolveAgentPhase(true, "shell_execute", false, false, hasQueuedWork = true)
        assertEquals(AgentPhase.QUEUED, queued)
        assertFalse(notificationShowsTimer(queued))
        assertFalse(shouldPromoteAgentNotification(queued, true))
    }

    @Test fun completedChipRequiresAnUndismissedCompletion() {
        assertFalse(shouldPromoteAgentNotification(AgentPhase.COMPLETED, false))
        assertTrue(shouldPromoteAgentNotification(AgentPhase.COMPLETED, true))
        assertEquals("0:00", chipTimerText(-1000))
        assertEquals("5:00", chipTimerText(300000))
    }
}
