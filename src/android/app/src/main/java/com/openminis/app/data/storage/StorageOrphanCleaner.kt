package com.openminis.app.data.storage

import com.openminis.app.backup.backupActivityLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class StorageProtection(val sessionIds: Set<String>) {
    fun protects(id: String, media: Boolean): Boolean =
        id.startsWith("__new__") || id in sessionIds ||
            ((if (media) SessionStorageReferences.ALL_MEDIA else SessionStorageReferences.ALL_WORKSPACES) in sessionIds)
}

internal interface StorageOrphanFiles {
    interface Target {
        val sessionId: String
        val media: Boolean
        val bytes: Long
    }

    suspend fun scan(protection: StorageProtection): StorageOrphanPlan

    /** False means the confirmed identity changed; callbacks count only successful unlinks. */
    suspend fun delete(target: Target, onFileDeleted: (Long) -> Unit): Boolean
}

internal data class StorageOrphanPlan(
    val targets: List<StorageOrphanFiles.Target>,
    val kept: Int = 0,
) {
    val bytes: Long get() = targets.sumOf { it.bytes }
}

internal data class StorageOrphanResult(
    val cleaned: Int = 0,
    val freed: Long = 0,
    val kept: Int = 0,
    val failed: Int = 0,
)

internal class StorageOrphanCancelled(val result: StorageOrphanResult, cause: CancellationException) :
    CancellationException("Session cleanup cancelled") {
    init { initCause(cause) }
}

/** The UI supplies a confirmed plan; cleanup never discovers additional deletion targets. */
internal class StorageOrphanCleaner(
    private val files: StorageOrphanFiles,
    private val loadProtection: suspend () -> StorageProtection,
    private val gate: SessionStorageMaintenanceGate = SessionStorageMaintenanceGate.shared,
    private val backupLock: Mutex = backupActivityLock,
) {
    suspend fun scan(): StorageOrphanPlan = backupLock.withLock {
        gate.withOrphanMaintenance {
            files.scan(loadProtection().withReservations())
        }
    }

    suspend fun clean(plan: StorageOrphanPlan): StorageOrphanResult {
        var result = StorageOrphanResult(kept = plan.kept)
        var handled = 0
        try {
            // Same order as import/export. No producer holds the monitor during file IO.
            backupLock.withLock {
                gate.withOrphanMaintenance { maintenance ->
                    for (target in plan.targets) {
                        currentCoroutineContext().ensureActive()
                        val claim = maintenance.claimIfUnreserved(target.sessionId, target.media)
                        if (claim == null) {
                            result = result.copy(kept = result.kept + 1)
                            handled++
                            continue
                        }
                        claim.use {
                            val protection = try {
                                loadProtection().withReservations()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                // An unreadable owner/reference snapshot cannot become empty.
                                result = result.copy(failed = result.failed + plan.targets.size - handled)
                                handled = plan.targets.size
                                return@withOrphanMaintenance
                            }
                            if (protection.protects(target.sessionId, target.media)) {
                                result = result.copy(kept = result.kept + 1)
                                handled++
                                return@use
                            }
                            try {
                                if (files.delete(target) { size -> result = result.copy(freed = result.freed + size) }) {
                                    result = result.copy(cleaned = result.cleaned + 1)
                                } else {
                                    result = result.copy(kept = result.kept + 1)
                                }
                            } catch (e: CancellationException) {
                                result = result.copy(failed = result.failed + 1)
                                handled++
                                throw e
                            } catch (_: Exception) {
                                result = result.copy(failed = result.failed + 1)
                            }
                            handled++
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw StorageOrphanCancelled(
                result.copy(kept = result.kept + plan.targets.size - handled), e,
            )
        }
        return result
    }

    private fun StorageProtection.withReservations() = copy(sessionIds = sessionIds + gate.reservedSessionIds())
}
