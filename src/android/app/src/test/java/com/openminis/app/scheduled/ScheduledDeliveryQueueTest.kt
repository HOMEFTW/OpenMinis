package com.openminis.app.scheduled

import org.junit.Assert.*
import org.junit.Test

class ScheduledDeliveryQueueTest {
    @Test fun `pending callback includes restored deferred and running work until durable finish`() {
        var disk: String? = null
        var pending = emptySet<String>()
        var fail = false
        val observe: (List<ScheduledDelivery>) -> Unit = { pending = it.map { d -> d.executionId }.toSet() }
        val queue = ScheduledDeliveryQueue(null, onChanged = observe) {
            if (fail) error("disk full")
            disk = it
        }
        queue.enqueue(delivery("one"))
        assertEquals(setOf("one"), pending)
        queue.claim("one")
        assertEquals(setOf("one"), pending)
        val restored = ScheduledDeliveryQueue(disk, onChanged = observe) {}
        assertEquals(setOf("one"), pending)
        restored.defer("one")
        assertEquals(setOf("one"), pending)
        fail = true
        assertTrue(runCatching { queue.finish("one") }.isFailure)
        assertEquals(setOf("one"), pending)
        fail = false
        queue.finish("one")
        assertTrue(pending.isEmpty())
    }

    private fun delivery(id: String, session: String = "parent") = ScheduledDelivery(
        ScheduledTask(label = "Test", timeOfDayHour = 9, timeOfDayMinute = 0,
            repeatMode = ScheduledRepeatMode.ONCE, prompt = "Work",
            targetMode = ScheduledTargetMode.ChildOfCurrent("parent")),
        session, id, 123L, true,
    )

    @Test fun `restart keeps claimed work marked running rather than replayable`() {
        var disk = ""
        val queue = ScheduledDeliveryQueue(null) { disk = it }
        queue.enqueue(delivery("one"))
        queue.claim("one")
        val restored = ScheduledDeliveryQueue(disk) {}
        assertTrue(restored.deliveries.value.single().running)
        assertNull(restored.claim("one"))
        assertEquals(ScheduledTargetMode.ChildOfCurrent("parent"), restored.deliveries.value.single().task.targetMode)
    }

    @Test fun `deduplicates fires but admits independent runs into the same VM queue`() {
        val queue = ScheduledDeliveryQueue(null) {}
        queue.enqueue(delivery("one"))
        queue.enqueue(delivery("one"))
        queue.enqueue(delivery("two"))
        queue.enqueue(delivery("other", "other"))
        assertEquals(3, queue.deliveries.value.size)
        assertNotNull(queue.claim("one"))
        assertNotNull(queue.claim("two"))
        assertNotNull(queue.claim("other"))
        queue.finish("one")
        assertNull(queue.claim("two"))
    }

    @Test fun `replacement keeps a durable cancelled tombstone and never replaces executing fire`() {
        var disk = ""
        val queue = ScheduledDeliveryQueue(null) { disk = it }
        val first = delivery("first")
        queue.enqueue(first)
        queue.claim("first")
        queue.enqueue(first.copy(executionId = "pending"))
        queue.enqueue(first.copy(executionId = "latest"))
        val restored = ScheduledDeliveryQueue(disk) {}
        assertTrue(restored.deliveries.value.first { it.executionId == "first" }.running)
        assertFalse(restored.deliveries.value.first { it.executionId == "first" }.superseded)
        assertTrue(restored.deliveries.value.first { it.executionId == "pending" }.superseded)
        assertNull(restored.claim("pending"))
        assertNotNull(restored.claim("latest"))
        restored.finish("pending")
        assertEquals(setOf("first", "latest"), restored.deliveries.value.map { it.executionId }.toSet())
    }

    @Test fun `replacement is scoped to task and target and atomic on persistence failure`() {
        var fail = false
        val queue = ScheduledDeliveryQueue(null) { if (fail) error("disk full") }
        val first = delivery("first")
        queue.enqueue(first)
        queue.enqueue(first.copy(executionId = "different-target", sessionId = "elsewhere"))
        fail = true
        assertTrue(runCatching { queue.enqueue(first.copy(executionId = "replacement")) }.isFailure)
        assertEquals(2, queue.deliveries.value.size)
        assertTrue(queue.deliveries.value.none { it.superseded })
    }

    @Test fun `failed persistence never publishes admission`() {
        var fail = false
        val queue = ScheduledDeliveryQueue(null) { if (fail) error("disk full") }
        queue.enqueue(delivery("one"))
        fail = true
        assertTrue(runCatching { queue.claim("one") }.isFailure)
        assertFalse(queue.deliveries.value.single().running)
    }

    @Test fun `child delivery describes callback to the parent`() {
        val target = ScheduledTargetDelivery.Destination.Child("parent", "Parent")
        assertEquals("child-of-current", ScheduledTargetDelivery.summary(target, false)["target"])
        assertTrue(ScheduledTargetDelivery.sentence(target, false).contains("returns to that parent"))
    }
}
