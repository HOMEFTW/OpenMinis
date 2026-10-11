package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import org.json.JSONArray
import org.json.JSONObject

/** Shared wire shape for the persisted turn and its live session-list preview. */
internal fun serializeAssistantParts(
    parts: List<AgentContentPart>,
    toolBlockMeta: Map<String, AssistantBlock> = emptyMap(),
    deepSeekReplay: String? = null,
    anthropicReplay: String? = null,
): String {
    val array = JSONArray()
    for (part in parts) {
        val encoded = when (part) {
            is AgentContentPart.Text -> JSONObject().put("type", "text").put("value", part.text)
            is AgentContentPart.ToolUse -> {
                if (part.name.isBlank()) continue
                val meta = toolBlockMeta[part.id]
                JSONObject().put("type", "toolUse").put("value", JSONObject()
                    .put("toolUseId", part.id)
                    .put("name", part.name)
                    .put("input", part.input.toString())
                    .put("description", meta?.toolTitle.orEmpty())
                    .put("pageURL", meta?.browserURL.orEmpty())
                    .put("imageFilePath", meta?.imageFilePath.orEmpty())
                    .put("thoughtSignature", part.thoughtSignature ?: JSONObject.NULL)
                    .put("isOffloadedArgument", part.isOffloadedArgument))
            }
            // Results and images have their own persistence paths.
            else -> continue
        }
        array.put(encoded)
    }
    if (deepSeekReplay != null) {
        array.put(JSONObject().put("type", "deepSeekReplay").put("value", deepSeekReplay))
    }
    if (anthropicReplay != null) {
        array.put(JSONObject().put("type", "anthropicReplay").put("value", anthropicReplay))
    }
    return array.toString()
}
