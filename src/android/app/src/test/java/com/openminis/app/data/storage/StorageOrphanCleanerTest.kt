package com.openminis.app.data.storage

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Test

class StorageOrphanCleanerTest {
    private data class Target(override val sessionId: String, override val media: Boolean = false,
                              override val bytes: Long = 999L) : StorageOrphanFiles.Target

    private class Files : StorageOrphanFiles {
        val deleted = mutableListOf<String>()
        var candidates = listOf(Target("orphan"))
        var deletion: suspend (StorageOrphanFiles.Target, (Long) -> Unit) -> Boolean = { target, counted ->
            deleted += target.sessionId
            counted(12)
            true
        }
        override suspend fun scan(protection: StorageProtection) = StorageOrphanPlan(candidates.filterNot {
            protection.protects(it.sessionId, it.media)
        })
        override suspend fun delete(target: StorageOrphanFiles.Target, onFileDeleted: (Long) -> Unit) =
            deletion(target, onFileDeleted)
    }

    @Test fun savedOwnersMessagesDraftsAndAllNewDraftIdsArePreserved() = runBlocking {
        val files = Files().apply { candidates = listOf(Target("saved"), Target("lost-row-message"),
            Target("draft-source"), Target("__new__not-in-prefs"), Target("orphan")) }
        val cleaner = StorageOrphanCleaner(files, { StorageProtection(setOf("saved", "lost-row-message", "draft-source")) },
            SessionStorageMaintenanceGate(), Mutex())
        val plan = cleaner.scan()
        assertEquals(listOf("orphan"), plan.targets.map { it.sessionId })
        assertEquals(StorageOrphanResult(cleaned = 1, freed = 12), cleaner.clean(plan))
    }

    @Test fun freshOwnerAfterConfirmationPreventsDeletion() = runBlocking {
        val files = Files()
        var owners = emptySet<String>()
        val cleaner = StorageOrphanCleaner(files, { StorageProtection(owners) }, SessionStorageMaintenanceGate(), Mutex())
        val plan = cleaner.scan()
        owners = setOf("orphan")
        assertEquals(StorageOrphanResult(kept = 1), cleaner.clean(plan))
        assertTrue(files.deleted.isEmpty())
    }

    @Test fun lateReservationIsKeptAndUnconfirmedTargetsAreNeverAdded() = runBlocking {
        val files = Files()
        val gate = SessionStorageMaintenanceGate()
        val cleaner = StorageOrphanCleaner(files, { StorageProtection(emptySet()) }, gate, Mutex())
        val plan = cleaner.scan()
        files.candidates += Target("unconfirmed")
        gate.reserve(setOf("orphan")).use {
            assertEquals(StorageOrphanResult(kept = 1), cleaner.clean(plan))
        }
        assertTrue(files.deleted.isEmpty())
        assertEquals(StorageOrphanResult(cleaned = 1, freed = 12), cleaner.clean(plan))
        assertEquals(listOf("orphan"), files.deleted)
    }

    @Test fun claimPrecedesFreshSnapshotAndBlocksOnlyItsProducer() = runBlocking {
        val files = Files()
        val gate = SessionStorageMaintenanceGate()
        var loads = 0
        val cleaner = StorageOrphanCleaner(files, {
            if (++loads > 1) {
                assertTrue(runCatching { gate.reserve(setOf("orphan")) }.exceptionOrNull() is StorageMaintenanceBusyException)
                gate.reserve(setOf("other")).close()
            }
            StorageProtection(emptySet())
        }, gate, Mutex())
        assertEquals(1, cleaner.clean(cleaner.scan()).cleaned)
        gate.reserve(setOf("orphan")).close()
    }

    @Test fun unknownMediaReferencesKeepEveryMediaTargetButAllowWorkspace() = runBlocking {
        val files = Files().apply { candidates = listOf(Target("media-owner", true), Target("orphan")) }
        var ids = emptySet<String>()
        val cleaner = StorageOrphanCleaner(files, { StorageProtection(ids) }, SessionStorageMaintenanceGate(), Mutex())
        val plan = cleaner.scan()
        ids = setOf(SessionStorageReferences.ALL_MEDIA)
        assertEquals(StorageOrphanResult(cleaned = 1, freed = 12, kept = 1), cleaner.clean(plan))
        assertEquals(listOf("orphan"), files.deleted)
    }

    @Test fun unreadableFreshReferencesAbortRemainingTargetsWithoutEmptyFallback() = runBlocking {
        val files = Files().apply { candidates += Target("second") }
        var broken = false
        val cleaner = StorageOrphanCleaner(files, {
            if (broken) throw IOException("Cannot read owners")
            StorageProtection(emptySet())
        }, SessionStorageMaintenanceGate(), Mutex())
        val plan = cleaner.scan()
        broken = true
        assertEquals(StorageOrphanResult(failed = 2), cleaner.clean(plan))
        assertTrue(files.deleted.isEmpty())
    }

    @Test fun identityChangesAreKeptAndPartialFailuresCountOnlySuccessfulUnlinks() = runBlocking {
        val files = Files().apply {
            candidates = listOf(Target("replaced"), Target("partial"), Target("complete"))
            deletion = { target, counted -> when (target.sessionId) {
                "replaced" -> false
                "partial" -> { counted(3); throw IOException("Cannot remove second file") }
                else -> { counted(5); true }
            } }
        }
        val cleaner = StorageOrphanCleaner(files, { StorageProtection(emptySet()) }, SessionStorageMaintenanceGate(), Mutex())
        assertEquals(StorageOrphanResult(cleaned = 1, freed = 8, kept = 1, failed = 1), cleaner.clean(cleaner.scan()))
    }

    @Test fun cancellationKeepsPartialCountsAndReleasesClaimMaintenanceAndBackupLock() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val files = Files().apply {
            candidates += Target("untouched")
            deletion = { _, counted -> counted(7); entered.complete(Unit); awaitCancellation() }
        }
        val gate = SessionStorageMaintenanceGate()
        val backup = Mutex()
        val cleaner = StorageOrphanCleaner(files, { StorageProtection(emptySet()) }, gate, backup)
        val plan = cleaner.scan()
        var cancelled: StorageOrphanCancelled? = null
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            try { cleaner.clean(plan) } catch (e: StorageOrphanCancelled) { cancelled = e; throw e }
        }
        entered.await()
        job.cancelAndJoin()
        assertEquals(StorageOrphanResult(freed = 7, kept = 1, failed = 1), cancelled?.result)
        assertFalse(backup.isLocked)
        gate.reserve(setOf("orphan")).close()
        gate.withOrphanMaintenance { it.claimIfUnreserved("orphan")!!.close() }
    }

    @Test fun importLockIsAcquiredBeforeMaintenanceAndCancellationWhileWaitingLeaksNothing() = runBlocking {
        val files = Files()
        val gate = SessionStorageMaintenanceGate()
        val backup = Mutex(locked = true)
        val cleaner = StorageOrphanCleaner(files, { StorageProtection(emptySet()) }, gate, backup)
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { cleaner.scan() }
        assertFalse(waiting.isCompleted)
        gate.withOrphanMaintenance { it.claimIfUnreserved("other")!!.close() }
        waiting.cancelAndJoin()
        backup.unlock()
        assertEquals(1, cleaner.scan().targets.size)
    }
}
