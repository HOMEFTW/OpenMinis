package com.openminis.app.ui.settings

import android.content.Context
import androidx.sqlite.db.SimpleSQLiteQuery
import com.openminis.app.agent.jobs.AgentJobRegistry
import com.openminis.app.agent.jobs.AgentJobTarget
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.storage.SessionStorageReferences
import com.openminis.app.data.storage.SessionStorageOwners
import com.openminis.app.data.storage.SessionStorageMessageReferences
import com.openminis.app.data.storage.StorageProtection
import com.openminis.app.sandbox.SessionWorkspaceRegistry
import com.openminis.app.service.SessionActivityTracker
import com.openminis.app.ui.chat.ComposerDraftStore

/** Errors propagate: incomplete owner, draft or reference data never permits cleanup. */
internal class StorageOrphanProtection(private val context: Context, private val repo: ChatRepository) {
    suspend fun load(): StorageProtection {
        val sessions = repo.dao.listSessions()
        val parents = sessions.associate { it.id to it.parentSessionId }
        val ids = buildSet {
            addAll(parents.keys)
            addAll(repo.dao.listSessionIds())
            // Even messages whose session row was lost still own their original directory.
            addAll(repo.dao.messageSessionIds())
            addAll(SessionStorageMessageReferences.collect { sql, args ->
                repo.dao.runPartsJsonQueryRaw(SimpleSQLiteQuery(sql, args)).map { it.partsJson }
            })
            addAll(ComposerDraftStore(context).protectedStorageIds())
            context.getSharedPreferences("draft_resource_migrations", Context.MODE_PRIVATE).all.forEach { (to, raw) ->
                val from = requireNotNull(raw as? String) { "Unreadable pending draft migration" }
                require(SessionStorageReferences.isIdentifier(to) && SessionStorageReferences.isIdentifier(from) &&
                    from.startsWith("__new__")) { "Invalid pending draft migration" }
                add(to)
                add(from)
            }
            val active = SessionActivityTracker.activeSessions.value + SessionActivityTracker.presentSessions.value
            addAll(active)
            active.forEach { add(SessionWorkspaceRegistry.owner(it)) }
            AgentJobRegistry.jobs.value.values.filter { it.isActive }.forEach { job ->
                val target = when (val value = job.target) {
                    AgentJobTarget.New -> null
                    is AgentJobTarget.FollowUp -> value.sessionId
                    is AgentJobTarget.Rerun -> value.sessionId
                    is AgentJobTarget.ChildOfCurrent -> value.parentSessionId
                }
                listOfNotNull(job.runSessionId, target).forEach {
                    add(it)
                    add(SessionWorkspaceRegistry.owner(it))
                }
            }
        }
        require(ids.all(SessionStorageReferences::isIdentifier)) { "Untrusted saved session identifier" }
        return StorageProtection(SessionStorageOwners.collect(ids,
            parentOf = { if (parents.containsKey(it)) parents[it] else repo.getSession(it)?.parentSessionId },
            workspaceOwner = SessionWorkspaceRegistry::owner,
        ))
    }
}
