package com.openminis.app.service

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SessionActivityTrackerTest {
    @Test fun handoffCanTransferToDurableQueueWithoutStoppingTheService() {
        val handoff = "test-handoff"
        val queue = "test-queue"
        try {
            SessionActivityTracker.setPendingWork(handoff, setOf("delivery"))
            assertTrue(SessionActivityTracker.shouldRunServiceNow())
            SessionActivityTracker.setPendingWork(queue, setOf("delivery"))
            SessionActivityTracker.setPendingWork(handoff, emptySet())
            assertEquals(setOf("delivery"), SessionActivityTracker.pendingWork.value)
            SessionActivityTracker.setActive("scheduled-test")
            SessionActivityTracker.setPendingWork(queue, emptySet())
            assertTrue(SessionActivityTracker.shouldRunServiceNow())
            SessionActivityTracker.setInactive("scheduled-test")
            assertFalse(SessionActivityTracker.shouldRunServiceNow())
        } finally {
            SessionActivityTracker.setInactive("scheduled-test")
            SessionActivityTracker.setPendingWork(handoff, emptySet())
            SessionActivityTracker.setPendingWork(queue, emptySet())
        }
    }

    @Test fun simultaneousStartsAndFinishesRetainEveryActiveSession() {
        val workers = Executors.newFixedThreadPool(8)
        try {
            repeat(30) { round ->
                val ids = (0 until 8).map { "activity-$round-$it" }
                try {
                    val start = CountDownLatch(1)
                    val starts = ids.map { id ->
                        workers.submit { start.await(); SessionActivityTracker.setActive(id) }
                    }
                    start.countDown()
                    starts.forEach { it.get(10, TimeUnit.SECONDS) }
                    assertTrue(SessionActivityTracker.activeSessions.value.containsAll(ids))
                    val finish = CountDownLatch(1)
                    val finishes = ids.map { id ->
                        workers.submit { finish.await(); SessionActivityTracker.setInactive(id) }
                    }
                    finish.countDown()
                    finishes.forEach { it.get(10, TimeUnit.SECONDS) }
                    assertTrue(ids.none { it in SessionActivityTracker.activeSessions.value })
                } finally {
                    ids.forEach(SessionActivityTracker::setInactive)
                }
            }
        } finally {
            workers.shutdownNow()
        }
    }
}
