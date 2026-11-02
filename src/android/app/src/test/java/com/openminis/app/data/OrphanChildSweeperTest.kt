package com.openminis.app.data

import com.openminis.app.data.session.OrphanChildSweeper
import com.openminis.app.data.session.SessionDeleter
import com.openminis.app.data.storage.SessionStorageMaintenanceGate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class OrphanChildSweeperTest {
    @Test fun protectionIsCheckedUnderTheClaimAndNewlyProtectedTreesAreSpared() = runBlocking {
        val gate = SessionStorageMaintenanceGate()
        gate.withOrphanMaintenance { maintenance ->
            var deleted = false
            val result = OrphanChildSweeper.deleteClaimedTree(maintenance, listOf("root"), isProtected = {
                assertTrue(runCatching { gate.reserve(setOf("root")) }.isFailure)
                true
            }, delete = { deleted = true; SessionDeleter.Report(listOf("root"), 0, 0) })
            assertNull(result)
            assertFalse(deleted)
            gate.reserve(setOf("root")).close()
        }
    }

    @Test fun vmEntryDuringTheFreshProtectionCheckInvalidatesDeletion() = runBlocking {
        val gate = SessionStorageMaintenanceGate()
        var lease: java.io.Closeable? = null
        try {
            gate.withOrphanMaintenance { maintenance ->
                val result = OrphanChildSweeper.deleteClaimedTree(maintenance, listOf("root"), isProtected = {
                    lease = gate.reserveForViewModel("root")
                    false
                }, delete = { claim ->
                    assertFalse(claim.beginCommit(listOf("root")))
                    null
                })
                assertNull(result)
            }
        } finally { lease?.close() }
        assertTrue(gate.reservedSessionIds().isEmpty())
    }

    @Test fun cleanupRequiresAnOldChildWhoseParentIsMissingAndWhichIsNotProtected() {
        assertTrue(OrphanChildSweeper.eligible("parent", 99, 100, false, false))
        assertFalse(OrphanChildSweeper.eligible(null, 99, 100, false, false))
        assertFalse(OrphanChildSweeper.eligible("", 99, 100, false, false))
        assertFalse(OrphanChildSweeper.eligible("parent", 100, 100, false, false))
        assertFalse(OrphanChildSweeper.eligible("parent", 99, 100, true, false))
        assertFalse(OrphanChildSweeper.eligible("parent", 99, 100, false, true))
        assertEquals(86_400_000L, OrphanChildSweeper.GRACE_MS)
    }
}
