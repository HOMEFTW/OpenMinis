package com.openminis.app.scheduled

import com.openminis.app.util.MemorySharedPreferences
import java.util.Calendar
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ScheduledRunNowTest {
    private fun task() = ScheduledTask(id = "task", label = "Task", timeOfDayHour = 9, timeOfDayMinute = 0,
        repeatMode = ScheduledRepeatMode.ONCE, prompt = "work", triggerKind = ScheduledTriggerKind.AFTER,
        delaySec = 120, anchorMs = 0, creatorSessionId = "creator")

    @Test fun `manual interval fire consumes exactly one count reanchors and stops at the finite limit`() {
        val interval = task().copy(triggerKind = ScheduledTriggerKind.INTERVAL, intervalSec = 120, maxFires = 2)
        val first = ScheduledTaskManager.runNowTransition(interval, 10_000)!!
        assertTrue(first.enabled)
        assertEquals(1, first.triggeredCount)
        assertEquals(130_000L, first.nextTriggerMs(10_000))
        val final = ScheduledTaskManager.runNowTransition(first, 20_000)!!
        assertFalse(final.enabled)
        assertEquals(2, final.triggeredCount)
        assertNull(final.nextTriggerMs(20_000))
        assertNull(ScheduledTaskManager.runNowTransition(final, 30_000))
    }

    @Test fun `after and fork event tasks consume their single pending fire`() {
        val after = ScheduledTaskManager.runNowTransition(task(), 1)!!
        assertFalse(after.enabled); assertEquals(1, after.triggeredCount)
        val event = task().copy(triggerKind = ScheduledTriggerKind.ON_COMPLETION, onCompletionOf = "upstream")
        val fired = ScheduledTaskManager.runNowTransition(event, 1)!!
        assertFalse(fired.enabled); assertEquals(1, fired.triggeredCount)
        assertNull(ScheduledTaskManager.runNowTransition(fired, 2))
    }

    @Test fun `calendar run now consumes the upcoming slot and suppresses an already delivered old alarm`() {
        val now = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 2, 8, 0, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val daily = task().copy(triggerKind = ScheduledTriggerKind.CALENDAR, repeatMode = ScheduledRepeatMode.DAILY)
        val originalSlot = daily.nextTriggerMs(now)!!
        val claimed = ScheduledTaskManager.runNowTransition(daily, now)!!
        assertEquals(originalSlot, claimed.runNowSlotMs)
        assertTrue(claimed.nextTriggerMs(now)!! > originalSlot)
        assertTrue(ScheduledTaskManager.calendarSlotTakenByRunNow(claimed, originalSlot + 1_000))
        assertFalse(ScheduledTaskManager.calendarSlotTakenByRunNow(claimed, originalSlot + 24 * 3_600_000L))
        assertFalse(ScheduledTaskManager.runNowTransition(daily.copy(repeatMode = ScheduledRepeatMode.ONCE), now)!!.enabled)
    }

    @Test fun `a stale request cannot take the following interval slot after an alarm won the race`() {
        val original = task().copy(triggerKind = ScheduledTriggerKind.INTERVAL, intervalSec = 120, maxFires = 5)
        val alarm = ScheduledTaskManager.afterAlarmFire(original, 120_000)!!.first
        assertFalse(ScheduledTaskManager.samePendingRun(alarm, original, 120_001))
        assertFalse(ScheduledTaskManager.samePendingRun(original.copy(enabled = false), original, 0))
    }

    @Test fun `concurrent store claims spend one slot and preserve the original history`() {
        val store = ScheduledTaskStore(MemorySharedPreferences())
        val original = task().copy(triggerKind = ScheduledTriggerKind.INTERVAL, intervalSec = 120, maxFires = 5,
            runHistory = listOf(ScheduledRun(1, "chat", "previous", true)), fireCount = 1)
        store.upsert(original)
        val executor = Executors.newFixedThreadPool(8)
        val gate = CountDownLatch(1)
        try {
            val attempts = (0 until 8).map { executor.submit<Boolean> {
                gate.await()
                store.update(original.id) { current ->
                    if (ScheduledTaskManager.samePendingRun(current, original, 10_000)) ScheduledTaskManager.runNowTransition(current, 10_000)
                    else null
                } != null
            } }
            gate.countDown()
            assertEquals(1, attempts.count { it.get(5, TimeUnit.SECONDS) })
            val stored = store.get(original.id)!!
            assertEquals(1, stored.triggeredCount)
            assertEquals(original.runHistory, stored.runHistory)
            assertEquals(original.fireCount, stored.fireCount)
        } finally { executor.shutdownNow() }
    }

    @Test fun `creator and consumed slot survive persistence and editor updates`() {
        val original = task().copy(runNowSlotMs = 1234)
        assertEquals(original, ScheduledTask.fromJson(JSONObject(original.toJson().toString())))
        val edited = ScheduledTaskManager.mergeBookkeeping(task().copy(creatorSessionId = null), original)
        assertEquals("creator", edited.creatorSessionId)
        assertEquals(1234L, edited.runNowSlotMs)
    }

    @Test fun `Stop atomically invalidates an already admitted last fire and stays effective after reenabling`() {
        val store = ScheduledTaskStore(MemorySharedPreferences())
        val admitted = task().copy(enabled = false, triggeredCount = 1,
            runHistory = listOf(ScheduledRun(1, "chat", "previous", true)))
        store.upsert(admitted)
        store.upsert(task().copy(id = "other", creatorSessionId = "other"))
        store.stopCreatedBy(setOf("creator"))
        val stopped = store.get(admitted.id)!!
        assertFalse(stopped.enabled)
        assertEquals(admitted.stopGeneration + 1, stopped.stopGeneration)
        assertEquals(admitted.runHistory, stopped.runHistory)
        assertTrue(store.get("other")!!.enabled)
        assertFalse(ScheduledTaskManager.samePendingRun(stopped, admitted, 1))
        val reenabled = stopped.copy(enabled = true)
        assertFalse(ScheduledTaskManager.samePendingRun(reenabled, admitted.copy(enabled = true), 1))
        assertEquals(stopped.stopGeneration, ScheduledTask.fromJson(stopped.toJson()).stopGeneration)
    }
}
