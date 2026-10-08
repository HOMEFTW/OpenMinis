package com.openminis.app.ui.chat

import com.openminis.app.scheduled.ScheduledTaskMarker
import org.junit.Assert.*
import org.junit.Test

class QueuedPromptBatchingTest {
    private fun scheduled(id: String, task: String = "task") = QueuedPrompt(
        id, ScheduledTaskMarker(task, "Task", null, "Do work", firedAtMs = 42).xml,
        runId = "scheduled-$id",
    )
    private val isUser: (QueuedPrompt) -> Boolean = { it.runId?.startsWith("user-") == true }

    @Test fun `tool boundary skips ordinary mail and selects exactly one scheduled run`() {
        val mail = QueuedPrompt("mail", "callback", runId = "mail-run")
        val fire = scheduled("fire")
        val user = QueuedPrompt("user", "question", runId = "user-run")
        assertEquals(listOf(fire), QueuedPromptBatching.nextInsertBatch(listOf(mail, fire, user), isUser))
        assertEquals(listOf(mail), QueuedPromptBatching.nextDrainBatch(listOf(mail, fire, user)))
    }

    @Test fun `replacement is only the same scheduled task and never user authored marker`() {
        val old = scheduled("old")
        val user = old.copy(id = "user", runId = "user-run")
        val other = scheduled("other", "other-task")
        assertEquals(listOf("old"), QueuedPromptBatching.supersededBy(listOf(old, user, other), "task", isUser))
        assertNull(QueuedPromptBatching.scheduledTaskId(user, isUser))
    }

    @Test fun `insertion envelope preserves task identity and fire timestamp`() {
        val original = scheduled("fire")
        val inserted = ScheduledTaskMarker.parse(QueuedPromptBatching.insertedEnvelope(original.text))!!
        assertTrue(inserted.insertedMidTask)
        assertEquals("task", inserted.taskId)
        assertEquals(42L, inserted.firedAtMs)
        assertEquals("Do work", inserted.prompt)
    }
}
