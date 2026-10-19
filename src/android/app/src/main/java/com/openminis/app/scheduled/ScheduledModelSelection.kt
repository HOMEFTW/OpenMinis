package com.openminis.app.scheduled

import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelGroup
import org.json.JSONObject

internal data class ScheduledModelSelection(val modelId: String?, val binding: String) {
    companion object {
        fun resolve(raw: String, entries: List<ModelEntry>, groups: List<ModelGroup>): ScheduledModelSelection {
            entries.firstOrNull { it.id == raw }?.let { return entry(it) }
            groups.firstOrNull { it.id == raw }?.let {
                require(it.memberEntryIds.any { id -> entries.any { entry -> entry.id == id } }) { "The selected model group is empty." }
                return ScheduledModelSelection(null, JSONObject().put("type", "group").put("groupId", it.id).toString())
            }
            val matching = entries.filter { it.model.id == raw }
            require(matching.isNotEmpty()) { "Unknown model '$raw'. Use a configured model ID, model entry ID or group ID." }
            require(matching.size == 1) { "Model '$raw' belongs to multiple entries. Use its model entry ID to select the intended provider." }
            return entry(matching.single())
        }

        private fun entry(value: ModelEntry) = ScheduledModelSelection(
            value.model.id, JSONObject().put("type", "entry").put("entryId", value.id).toString(),
        )
    }
}
