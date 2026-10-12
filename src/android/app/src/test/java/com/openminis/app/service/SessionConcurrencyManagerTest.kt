package com.openminis.app.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SessionConcurrencyManagerTest {
    @Test fun cancelledHandoffReturnsTheSlotBeforeTheWaiterRuns() = runTest {
        val holders = (0 until SessionConcurrencyManager.MAX_CONCURRENT).map { "handoff-$it" }
        try {
            holders.forEach { SessionConcurrencyManager.acquireSlot(it) }
            var entered = false
            val waiter = launch {
                SessionConcurrencyManager.acquireSlot("cancelled-waiter")
                try { entered = true } finally { SessionConcurrencyManager.releaseSlot("cancelled-waiter") }
            }
            runCurrent()
            assertEquals(listOf("cancelled-waiter"), SessionConcurrencyManager.suspendedSessions.value)
            SessionConcurrencyManager.releaseSlot(holders.first())
            waiter.cancel()
            runCurrent()
            assertFalse(entered)
            assertFalse(SessionConcurrencyManager.runningSessions.value.contains("cancelled-waiter"))
            assertTrue(SessionConcurrencyManager.suspendedSessions.value.isEmpty())
        } finally {
            holders.forEach(SessionConcurrencyManager::releaseSlot)
        }
        assertTrue(SessionConcurrencyManager.runningSessions.value.isEmpty())
    }

    @Test fun cancelledQueuedWaiterDoesNotBlockFifoAdmission() = runTest {
        val holders = (0 until SessionConcurrencyManager.MAX_CONCURRENT).map { "fifo-$it" }
        try {
            holders.forEach { SessionConcurrencyManager.acquireSlot(it) }
            val order = mutableListOf<String>()
            val waiters = listOf("first", "cancelled", "last").map { id ->
                launch {
                    SessionConcurrencyManager.acquireSlot(id)
                    try { order.add(id) } finally { SessionConcurrencyManager.releaseSlot(id) }
                }
            }
            runCurrent()
            waiters[1].cancel()
            runCurrent()
            SessionConcurrencyManager.releaseSlot(holders.first())
            runCurrent()
            waiters.forEach { it.join() }
            assertEquals(listOf("first", "last"), order)
        } finally {
            holders.forEach(SessionConcurrencyManager::releaseSlot)
        }
        assertTrue(SessionConcurrencyManager.runningSessions.value.isEmpty())
    }

    @Test fun concurrentAdmissionNeverExceedsCapacityOrLosesHolders() = runTest {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        coroutineScope {
            repeat(32) { worker ->
                launch(Dispatchers.Default) {
                    repeat(20) { turn ->
                        val id = "parallel-$worker-$turn"
                        SessionConcurrencyManager.acquireSlot(id)
                        try {
                            val count = active.incrementAndGet()
                            peak.updateAndGet { maxOf(it, count) }
                            assertTrue(SessionConcurrencyManager.runningSessions.value.contains(id))
                            yield()
                            active.decrementAndGet()
                        } finally {
                            SessionConcurrencyManager.releaseSlot(id)
                        }
                    }
                }
            }
        }
        assertTrue(peak.get() <= SessionConcurrencyManager.MAX_CONCURRENT)
        assertEquals(0, active.get())
        assertTrue(SessionConcurrencyManager.runningSessions.value.isEmpty())
        assertTrue(SessionConcurrencyManager.suspendedSessions.value.isEmpty())
    }
}
