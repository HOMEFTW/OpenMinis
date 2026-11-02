package com.openminis.app.service

import org.junit.Assert.*
import org.junit.Test

class ScheduledSessionStateTest {
    @Test fun `scheduled countdown overrides completion but a live run takes precedence`() {
        assertEquals(AgentPhase.SCHEDULED_WAIT, resolveAgentPhase(true, "stale-tool", true, false, isScheduledWaiting = true))
        assertEquals(AgentPhase.TOOL, resolveAgentPhase(false, "shell_execute", false, true, isScheduledWaiting = true))
        assertEquals(AgentPhase.GENERATING, resolveAgentPhase(false, null, false, true, isScheduledWaiting = true))
        assertEquals(AgentPhase.WAITING, resolveAgentPhase(true, null, false, false, isWaitingForAnswer = true, isScheduledWaiting = true))
    }

    @Test fun `earliest timed wait wins and any live session hides scheduled waiting`() {
        val event = ScheduledSessionWait("event", "a", "Event", null)
        val later = ScheduledSessionWait("later", "b", "Later", 300_000L)
        val soon = ScheduledSessionWait("soon", "c", "Soon", 100_000L, "2/30")
        assertEquals(soon, nextScheduledWait(listOf(event, later, soon), emptySet()))
        assertNull(nextScheduledWait(listOf(soon), setOf("running")))
        assertEquals(event, nextScheduledWait(listOf(event), emptySet()))
        assertEquals("Next run · 2/30", scheduledWaitStatus("Next run", soon.runCounter))
        assertEquals("Next run", scheduledWaitStatus("Next run", null))
    }

    @Test fun `system clock counts down to next fire and never renders an overdue negative timer`() {
        assertEquals(AgentNotificationClock(true, 90_000L, true, true),
            agentNotificationClock(AgentPhase.SCHEDULED_WAIT, 1, 2, 90_000L, 30_000L))
        for (deadline in listOf(null, 29_000L, 30_000L)) {
            assertEquals(AgentNotificationClock(false, 30_000L, false),
                agentNotificationClock(AgentPhase.SCHEDULED_WAIT, 1, 2, deadline, 30_000L))
        }
    }

    @Test fun `completed running and presence clocks keep their respective semantics`() {
        assertEquals(AgentNotificationClock(true, 2, false), agentNotificationClock(AgentPhase.COMPLETED, 1, 2, null, 3))
        assertEquals(AgentNotificationClock(true, 1, true), agentNotificationClock(AgentPhase.TOOL, 1, 2, null, 3))
        assertEquals(AgentNotificationClock(false, 3, false), agentNotificationClock(AgentPhase.IDLE, 1, 2, null, 3))
        assertEquals(AgentNotificationClock(false, 3, false), agentNotificationClock(AgentPhase.QUEUED, 1, 2, null, 3))
    }
}
