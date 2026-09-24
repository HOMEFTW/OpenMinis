package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import org.json.JSONArray
import org.json.JSONObject

/** Media refs stay top-level so existing restore, sync and media cleanup can find them. */
internal fun serializeToolResults(
    results: List<AgentContentPart.ToolResult>,
    saveImage: (AgentContentPart.ToolResult) -> JSONObject,
): String {
    val parts = JSONArray()
    for (result in results) {
        parts.put(JSONObject().put("type", "toolResult").put("value", JSONObject()
            .put("toolUseId", result.id)
            .put("name", result.name)
            .put("output", result.content)
            .put("success", !result.isError)
            .put("snapshot", JSONObject().put("type", "text")
                .put("text", result.content.lines().takeLast(30).joinToString("\n")))))
        if (result.imageData != null) {
            val reference = saveImage(result)
            reference.getJSONObject("value").put("toolUseId", result.id)
            parts.put(reference)
        }
    }
    return parts.toString()
}
