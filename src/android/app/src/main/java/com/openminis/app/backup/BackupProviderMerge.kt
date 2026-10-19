package com.openminis.app.backup

import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.ProviderConfig
import java.util.Locale

/** Backup settings win by id; device-only records and references survive. */
object BackupProviderMerge {
    data class Result(
        val config: ProviderConfig,
        val imported: Int,
        val updated: Int,
        val skipped: Int,
        val hasChanges: Boolean,
        val groupIdRemapping: Map<String, String>,
    )

    fun merge(local: ProviderConfig, incoming: ProviderConfig): Result {
        var imported = 0
        var updated = 0
        var skipped = 0
        fun <T> mergeRecords(localItems: List<T>, backupItems: List<T>, id: (T) -> String): MutableList<T> {
            val existing = localItems.associateBy(id)
            val remote = backupItems.distinctBy(id)
            remote.forEach {
                val old = existing[id(it)]
                when {
                    old == null -> imported++
                    old != it -> updated++
                    else -> skipped++
                }
            }
            val ids = remote.map(id).toSet()
            return (remote + localItems.filter { id(it) !in ids }).toMutableList()
        }
        val instances = mergeRecords(local.instances, incoming.instances) { it.id }
            .map { it.copy() }.toMutableList()
        val entries = mergeRecords(local.modelEntries, incoming.modelEntries) { it.id }
        val incomingEntryIds = incoming.modelEntries.map { it.id }.toSet()
        val incomingGroups = incoming.modelGroups.distinctBy { it.id }
        val incomingById = incomingGroups.associateBy { it.id }
        val localById = local.modelGroups.associateBy { it.id }
        fun nameKey(group: ModelGroup) = group.name.trim().lowercase(Locale.ROOT)

        // Apply same-id properties before matching names. A backup that swaps
        // two group names must not accidentally merge those two groups.
        val effective = local.modelGroups.map { old ->
            incomingById[old.id]?.let { remote ->
                remote.copy(memberEntryIds = (
                    remote.memberEntryIds + old.memberEntryIds.filter { it !in incomingEntryIds }
                ).distinct().toMutableList())
            } ?: old.copy(memberEntryIds = old.memberEntryIds.toMutableList())
        }
        val aliases = local.modelGroups.associate { it.id to it.id }.toMutableMap()
        val mergedGroups = effective.associateBy { it.id }.toMutableMap()
        val placed = mutableSetOf<String>()
        for (remote in incomingGroups) {
            val key = nameKey(remote)
            val existing = if (remote.id in localById) mergedGroups[remote.id] else {
                mergedGroups.values.firstOrNull { key.isNotEmpty() && nameKey(it) == key }
            }
            val canonicalId = existing?.id ?: remote.id
            aliases[remote.id] = canonicalId
            // Never remove another device group with the same name: live
            // sessions may still reference its id. Only the incoming alias is
            // merged into the selected stable local identity.
            val properties = incomingById[canonicalId] ?:
                if (canonicalId in placed) existing!! else remote
            val members = (properties.memberEntryIds + remote.memberEntryIds + existing?.memberEntryIds.orEmpty())
                .distinct().toMutableList()
            mergedGroups[canonicalId] = properties.copy(id = canonicalId, memberEntryIds = members)
            placed.add(canonicalId)
        }
        val groupOrder = (incomingGroups.map { aliases.getValue(it.id) } +
            local.modelGroups.map { aliases.getValue(it.id) }).distinct()
        val groups = groupOrder.map { mergedGroups.getValue(it) }.toMutableList()
        val restoredGroupIds = incomingGroups.map { aliases.getValue(it.id) }.distinct()
        for (id in restoredGroupIds) {
            val group = mergedGroups.getValue(id)
            val old = localById[id]
            when {
                old == null -> imported++
                old != group -> updated++
                else -> skipped++
            }
        }
        fun groupId(id: String?): String? = id?.let { aliases[it] ?: it }
        val incomingGroupIds = incomingGroups.map { it.id }.toSet()
        val agentEntries = (incoming.agentLoopModelEntryIds +
            local.agentLoopModelEntryIds.filter { it !in incomingEntryIds }).distinct().toMutableList()
        val agentGroups = (incoming.agentLoopGroupIds +
            local.agentLoopGroupIds.filter { it !in incomingGroupIds })
            .map { groupId(it)!! }.distinct().toMutableList()
        val primary = groupId(incoming.defaultPrimaryGroupId ?: local.defaultPrimaryGroupId)
        val sub = groupId(incoming.defaultSubGroupId ?: local.defaultSubGroupId)
        val voiceIn = groupId(incoming.voiceInputGroupId ?: local.voiceInputGroupId)
        val voiceOut = groupId(incoming.voiceOutputGroupId ?: local.voiceOutputGroupId)
        val vision = groupId(incoming.visionGroupId ?: local.visionGroupId)
        // Older iOS packages omit device bindings. A present backup binding
        // wins; an omitted one retains the device binding, remapped if merged.
        val settingsChanged = primary != local.defaultPrimaryGroupId || sub != local.defaultSubGroupId ||
            voiceIn != local.voiceInputGroupId || voiceOut != local.voiceOutputGroupId ||
            vision != local.visionGroupId || agentEntries != local.agentLoopModelEntryIds ||
            agentGroups != local.agentLoopGroupIds
        val orderChanged = instances.map { it.id } != local.instances.map { it.id } ||
            entries.map { it.id } != local.modelEntries.map { it.id } ||
            groups.map { it.id } != local.modelGroups.map { it.id }
        // A settings/order-only restore is a real update. Don't invent an
        // inserted provider merely because its position or binding changed.
        if ((settingsChanged || orderChanged) && imported == 0 && updated == 0) updated++
        val agents = local.subAgents.map { it.copy(modelGroupId = groupId(it.modelGroupId)) }.toMutableList()
        val merged = local.copy(
            instances = instances, modelEntries = entries, modelGroups = groups,
            defaultPrimaryGroupId = primary, defaultSubGroupId = sub,
            voiceInputGroupId = voiceIn, voiceOutputGroupId = voiceOut, visionGroupId = vision,
            agentLoopModelEntryIds = agentEntries, agentLoopGroupIds = agentGroups, subAgents = agents,
        )
        return Result(
            merged, imported, updated, skipped,
            imported > 0 || updated > 0 || settingsChanged || orderChanged || agents != local.subAgents,
            aliases.filter { (from, to) -> from != to },
        )
    }
}
