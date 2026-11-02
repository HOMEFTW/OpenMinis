package com.openminis.app.data.session

import android.content.Context
import com.openminis.app.agent.jobs.AgentJobRegistry
import com.openminis.app.backup.backupActivityLock
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.storage.SessionStorageMaintenanceGate
import com.openminis.app.logging.AppLogger
import com.openminis.app.scheduled.ScheduledTaskManager
import com.openminis.app.scheduled.ScheduledTargetMode
import com.openminis.app.service.SessionActivityTracker
import com.openminis.app.ui.chat.ChatViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock

/** Stable 1.15 orphan-child cleanup, serialized with the fork's restore and file maintenance. */
object OrphanChildSweeper {
    internal const val GRACE_MS = 24L * 60 * 60 * 1000
    data class Report(val examined: Int, val deleted: Int, val spared: Int, val bytesFreed: Long)

    internal fun eligible(parentId: String?, updatedAt: Long, cutoff: Long, parentExists: Boolean, protected: Boolean): Boolean =
        !parentId.isNullOrBlank() && updatedAt < cutoff && !parentExists && !protected

    /** The protected snapshot is read only after producers have been excluded. */
    internal suspend fun deleteClaimedTree(
        maintenance: SessionStorageMaintenanceGate.Maintenance,
        ids: List<String>,
        isProtected: suspend () -> Boolean,
        delete: suspend (SessionStorageMaintenanceGate.Maintenance.TreeClaim) -> SessionDeleter.Report?,
    ): SessionDeleter.Report? {
        val claim = maintenance.claimTreeIfUnreserved(ids.toSet()) ?: return null
        try {
            if (isProtected()) return null
            return delete(claim)
        } finally { claim.close() }
    }

    private suspend fun isProtected(context: Context, ids: List<String>): Boolean {
        val scheduled = ScheduledTaskManager(context).list().filter { it.enabled }.flatMap { task ->
            listOfNotNull(task.creatorSessionId, when (val target = task.targetMode) {
                is ScheduledTargetMode.AppendToSession -> target.sessionId
                is ScheduledTargetMode.RerunMessage -> target.sessionId
                is ScheduledTargetMode.ChildOfCurrent -> target.sessionId
                ScheduledTargetMode.NewSession -> null
            })
        }.toSet()
        return withContext(Dispatchers.Main) {
            ids.any { id ->
                id in scheduled || id in SessionActivityTracker.presentSessions.value ||
                    SessionActivityTracker.isProcessing(id) || ChatViewModelStore.existing(id) != null ||
                    id in com.openminis.app.agent.jobs.UserQuestionRegistry.pending.value ||
                    com.openminis.app.agent.jobs.UserQuestionRegistry.recoveredResults.value.any { it.sessionId == id } ||
                    AgentJobRegistry.jobForSession(id)?.isActive == true
            }
        }
    }

    suspend fun sweep(context: Context, repo: ChatRepository): Report = withContext(Dispatchers.IO) {
        backupActivityLock.withLock {
            val gate = SessionStorageMaintenanceGate.shared
            gate.withOrphanMaintenance { maintenance ->
                val cutoff = System.currentTimeMillis() - GRACE_MS
                val candidates = repo.dao.orphanChildSessionIds(cutoff)
                var deleted = 0
                var spared = 0
                var bytes = 0L
                for (id in candidates) {
                    val row = repo.getSession(id)
                    if (row == null || !eligible(row.parentSessionId, row.updatedAt, cutoff,
                            row.parentSessionId?.let { repo.getSession(it) } != null, protected = false)) {
                        spared++
                        continue
                    }
                    val ids = SessionTree.subtreeDeepestFirst(id) { repo.dao.childSessionIds(it) }
                    val result = deleteClaimedTree(
                        maintenance, ids,
                        isProtected = { isProtected(context, ids) },
                        delete = { claim -> SessionDeleter.deleteClaimedOrphanTree(context, repo, id, ids, cutoff, claim) },
                    )
                    if (result == null) spared++ else {
                        deleted += result.deletedIds.size
                        bytes += result.bytesFreed
                    }
                }
                AppLogger.info("OrphanSweep", "examined=${candidates.size} deleted=$deleted spared=$spared freed=$bytes")
                Report(candidates.size, deleted, spared, bytes)
            }
        }
    }
}
