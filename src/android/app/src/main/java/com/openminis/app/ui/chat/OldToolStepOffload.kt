package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.ContextOffload
import org.json.JSONArray
import org.json.JSONObject

/**
 * Shrink completed, unsigned tool exchanges oldest first after ordinary
 * output offload. Keep row ids, roles and tool pairs so compact-marker boundaries
 * remain stable. The stored transcript is untouched; full steps are readable
 * from the saved file. Human messages and the latest four messages stay intact.
 */
internal fun offloadOldToolSteps(
    history: List<LLMMessage>,
    tokensToFree: Int,
    countTokens: (LLMMessage) -> Int,
    visibleHistory: List<LLMMessage> = history,
    write: (String) -> String?,
): List<LLMMessage> {
    if (tokensToFree <= 0) return history
    val end = (history.size - 4).coerceAtLeast(0)
    val result = history.toMutableList()
    var remaining = tokensToFree
    // Image budgeting may copy result bodies, but preserves ToolUse objects.
    // Match the actual outgoing calls, so already summarized history cannot
    // consume the release budget for the current request.
    val visibleCalls = visibleHistory.flatMap { it.contentParts.filterIsInstance<AgentContentPart.ToolUse>() }.toSet()
    val visibleResults = visibleHistory.flatMap { it.contentParts.filterIsInstance<AgentContentPart.ToolResult>() }.map { it.id }.toSet()
    var index = 0
    while (index + 1 < end && remaining > 0) {
        val assistant = history[index]
        val response = history[index + 1]
        if (!isCompleteOffloadableStep(assistant, response)) { index++; continue }
        val tools = assistant.contentParts.filterIsInstance<AgentContentPart.ToolUse>()
        if (!tools.all { it in visibleCalls && it.id in visibleResults }) { index += 2; continue }
        val path = try {
            write(oldToolStepJson(assistant, response))?.takeIf { it.isNotBlank() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null // Failed persistence must never replace the original exchange.
        }
        if (path != null) {
            val notice = "${ContextOffload.OFFLOADED_PREFIX} Older ${tools.map { it.name }.distinct().joinToString(", ")} step. Full text, arguments and results saved at $path; read with file_read when needed."
            val calls = tools.map { it.copy(input = JSONObject().put(SAVED_STEP_KEY, path), isOffloadedArgument = true) }
            val results = response.contentParts.filterIsInstance<AgentContentPart.ToolResult>().map {
                it.copy(content = "${ContextOffload.OFFLOADED_PREFIX} ${if (it.isError) "Tool reported an error. " else ""}Full step saved at $path; read with file_read when needed.")
            }
            // A ToolResult must stay a ToolResult: compact's walk-back excludes
            // it from human turn boundaries, and providers require the pair.
            val collapsedAssistant = assistant.copy(content = notice, contentParts = listOf(AgentContentPart.Text(notice)) + calls, reasoningContent = assistant.reasoningContent?.let { "" })
            val collapsedResponse = response.copy(contentParts = results)
            val freed = countTokens(assistant) + countTokens(response) - countTokens(collapsedAssistant) - countTokens(collapsedResponse)
            if (freed > 0) {
                result[index] = collapsedAssistant
                result[index + 1] = collapsedResponse
                remaining -= freed
            }
        }
        index += 2
    }
    return result
}

private fun isCompleteOffloadableStep(assistant: LLMMessage, response: LLMMessage): Boolean {
    if (assistant.role != LLMMessage.Role.ASSISTANT || response.role != LLMMessage.Role.USER) return false
    if (assistant.hasSignedToolReplay() || response.hasSignedToolReplay()) return false
    if (assistant.imageParts.isNotEmpty() || response.imageParts.isNotEmpty() ||
        assistant.audioParts.isNotEmpty() || response.audioParts.isNotEmpty() || response.content.isNotBlank()) return false
    if (assistant.contentParts.any { it !is AgentContentPart.Text && it !is AgentContentPart.ToolUse }) return false
    if (response.contentParts.isEmpty() || response.contentParts.any { it !is AgentContentPart.ToolResult || it.imageData != null }) return false
    val calls = assistant.contentParts.filterIsInstance<AgentContentPart.ToolUse>()
    val results = response.contentParts.filterIsInstance<AgentContentPart.ToolResult>()
    if (calls.isEmpty() || calls.any { !it.thoughtSignature.isNullOrBlank() }) return false
    if (calls.all { it.isOffloadedArgument && it.input.has(SAVED_STEP_KEY) }) return false
    val ids = calls.map { it.id }
    val resultIds = results.map { it.id }
    return ids.distinct().size == ids.size && resultIds.distinct().size == resultIds.size && ids.toSet() == resultIds.toSet()
}

private const val SAVED_STEP_KEY = "_minis_saved_tool_step"

private fun oldToolStepJson(assistant: LLMMessage, response: LLMMessage): String {
    val messages = JSONArray()
    for (message in listOf(assistant, response)) {
        val parts = JSONArray()
        for (part in message.contentParts) {
            parts.put(when (part) {
                is AgentContentPart.Text -> JSONObject().put("type", "text").put("text", part.text)
                is AgentContentPart.ToolUse -> JSONObject().put("type", "tool_use").put("id", part.id).put("name", part.name)
                    .put("input", part.input).put("is_offloaded_argument", part.isOffloadedArgument)
                is AgentContentPart.ToolResult -> JSONObject().put("type", "tool_result").put("id", part.id).put("name", part.name)
                    .put("content", part.content).put("is_error", part.isError)
                    .put("image_mime_type", part.imageMimeType ?: JSONObject.NULL)
                    .put("image_linux_path", part.imageLinuxPath ?: JSONObject.NULL)
                is AgentContentPart.ImageData -> error("Image steps must be offloaded before collapse")
            })
        }
        messages.put(JSONObject().put("role", message.role.value).put("text", message.content)
            .put("db_message_id", message.dbMessageId ?: JSONObject.NULL)
            .put("reasoning_content", message.reasoningContent ?: JSONObject.NULL).put("parts", parts))
    }
    return JSONObject().put("messages", messages).toString()
}
