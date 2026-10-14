package com.openminis.app.data.storage

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class SessionStorageMaintenanceGateTest {
    @Test fun fallbackWriteSentinelsProtectBothLayoutsAndWaitForExistingClaims() = runBlocking {
        val gate = SessionStorageMaintenanceGate()
        val ids = setOf(SessionStorageReferences.ALL_WORKSPACES, SessionStorageReferences.ALL_MEDIA)
        gate.reserve(ids).use {
            gate.withOrphanMaintenance {
                assertNull(it.claimIfUnreserved("workspace"))
                assertNull(it.claimIfUnreserved("media", media = true))
            }
        }
        gate.withOrphanMaintenance { maintenance ->
            maintenance.claimIfUnreserved("workspace")!!.use {
                assertTrue(runCatching { gate.reserve(ids) }.exceptionOrNull() is StorageMaintenanceBusyException)
            }
        }
        gate.reserve(ids).close()
    }
    @Test fun existingAndLateReservationsBothExcludeDeletion() = runBlocking {
        val gate = SessionStorageMaintenanceGate()
        val early = gate.reserve(setOf("early"))
        gate.withOrphanMaintenance { maintenance ->
            assertNull(maintenance.claimIfUnreserved("early"))
            gate.reserve(setOf("late")).use { assertNull(maintenance.claimIfUnreserved("late")) }
            maintenance.claimIfUnreserved("late")!!.close()
        }
        early.close()
        assertTrue(gate.reservedSessionIds().isEmpty())
    }

    @Test fun asyncProducerWaitsOnlyForItsClaimAndSyncProducerFailsImmediately() = runBlocking {
        val gate = SessionStorageMaintenanceGate()
        gate.withOrphanMaintenance { maintenance ->
            val claim = maintenance.claimIfUnreserved("orphan")!!
            val producer = async(start = CoroutineStart.UNDISPATCHED) {
                gate.withSessionActivity(setOf("orphan")) { "resumed" }
            }
            assertFalse(producer.isCompleted)
            assertThrows(StorageMaintenanceBusyException::class.java) { gate.reserve(setOf("orphan")) }
            gate.reserve(setOf("another-session")).use { assertTrue("another-session" in gate.reservedSessionIds()) }
            claim.close()
            assertEquals("resumed", withTimeout(2_000) { producer.await() })
        }
        assertTrue(gate.reservedSessionIds().isEmpty())
    }

    @Test fun canceledWaitingProducerDoesNotLeakAReservation() = runBlocking {
        val gate = SessionStorageMaintenanceGate()
        gate.withOrphanMaintenance { maintenance ->
            maintenance.claimIfUnreserved("orphan")!!.use {
                val producer = launch(start = CoroutineStart.UNDISPATCHED) { gate.acquire(setOf("orphan")).close() }
                producer.cancelAndJoin()
                assertTrue(gate.reservedSessionIds().isEmpty())
            }
        }
        gate.reserve(setOf("orphan")).close()
    }

    @Test fun activityCancellationAndExceptionsReleaseNestedLeases() = runBlocking {
        val gate = SessionStorageMaintenanceGate()
        val started = CompletableDeferred<Unit>()
        val job = launch {
            gate.withSessionActivity(setOf("active")) {
                gate.reserve(setOf("active")).use { started.complete(Unit); awaitCancellation() }
            }
        }
        started.await()
        job.cancelAndJoin()
        assertTrue(gate.reservedSessionIds().isEmpty())
        assertThrows(IllegalStateException::class.java) {
            gate.withSynchronousActivity(setOf("broken")) { error("fixture") }
        }
        assertTrue(gate.reservedSessionIds().isEmpty())
    }

    @Test fun abandonedMaintenanceClaimIsReleasedOnCancellation() = runBlocking {
        val gate = SessionStorageMaintenanceGate()
        val started = CompletableDeferred<Unit>()
        val job = launch {
            gate.withOrphanMaintenance { maintenance ->
                maintenance.claimIfUnreserved("orphan")!!
                started.complete(Unit)
                awaitCancellation()
            }
        }
        started.await()
        job.cancelAndJoin()
        gate.reserve(setOf("orphan")).close()
        gate.withOrphanMaintenance { assertNotNull(it.claimIfUnreserved("orphan")) }
    }

    @Test fun draftAndUnknownMediaReservationsAreConservative() = runBlocking {
        val gate = SessionStorageMaintenanceGate()
        gate.withOrphanMaintenance { maintenance ->
            assertNull(maintenance.claimIfUnreserved("__new__draft"))
            gate.reserve(setOf(SessionStorageReferences.ALL_MEDIA)).use {
                assertNull(maintenance.claimIfUnreserved("orphan", media = true))
                maintenance.claimIfUnreserved("workspace-only")!!.close()
            }
        }
    }

    @Test fun leaseCloseIsIdempotentAndCannotReleaseSomeoneElsesLease() {
        val gate = SessionStorageMaintenanceGate()
        val first = gate.reserve(setOf("a"))
        val second = gate.reserve(setOf("a"))
        first.close(); first.close()
        assertEquals(setOf("a"), gate.reservedSessionIds())
        second.close()
        assertTrue(gate.reservedSessionIds().isEmpty())
        assertThrows(IllegalArgumentException::class.java) { gate.reserve(setOf("../escape")) }
    }
}
