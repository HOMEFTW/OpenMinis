package com.openminis.app.data.repository

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ProviderConfig

/** Explicit deletion bypasses the absent-from-catalog grace period. */
internal fun removeModelEntryReferences(config: ProviderConfig, entryIds: Set<String>) {
    config.modelEntries.removeAll { it.id in entryIds }
    config.modelGroups.forEach { it.memberEntryIds.removeAll { id -> id in entryIds } }
    config.agentLoopModelEntryIds.removeAll { it in entryIds }
}

internal fun modelsAfterDeletionPolicy(models: List<LLMModel>, deletedIds: Set<String>, manualRefresh: Boolean): List<LLMModel> =
    models.distinctBy { it.id }.filter { manualRefresh || it.id !in deletedIds }
