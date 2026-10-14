package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.json.JSONObject

/** Detect a serialized call in DeepSeek reasoning; never execute the text. */
internal object MisplacedToolRecovery {
    const val MAX_RETRIES = 2
    private const val MAX_SCAN_CHARS = 131_072
    const val REMINDER = "<system-reminder>The previous response put a tool call in the thinking text, " +
        "so no tool was executed. If the task needs that tool, re-issue it through the actual tool-call channel " +
        "with all required arguments. Do not put serialized calls inside reasoning or assume they ran. " +
        "Otherwise give the user a final answer.</system-reminder>"

    enum class Action { NONE, RETRY, RESUME }

    fun isDeepSeekModel(modelId: String): Boolean =
        Regex("(^|[/:])deepseek(?:[-_.]|$)", RegexOption.IGNORE_CASE).containsMatchIn(modelId)

    fun action(
        isDeepSeek: Boolean,
        reasoning: String?,
        availableTools: Set<String>,
        hasToolCalls: Boolean,
        finishReason: String?,
        retries: Int,
    ): Action {
        if (!isDeepSeek || hasToolCalls || availableTools.isEmpty() || reasoning.isNullOrBlank()) return Action.NONE
        // Output-limit and broken-stream recovery have their own semantics.
        if (finishReason !in setOf("stop", "end_turn", "tool_calls", "tool_use")) return Action.NONE
        if (!containsCall(reasoning.takeLast(MAX_SCAN_CHARS), availableTools)) return Action.NONE
        return if (retries < MAX_RETRIES) Action.RETRY else Action.RESUME
    }

    private fun containsCall(text: String, tools: Set<String>): Boolean {
        val dsmlStart = text.lastIndexOf("<|DSML|function_calls>")
        if (dsmlStart >= 0) {
            val end = text.indexOf("</|DSML|function_calls>", dsmlStart)
            if (end >= 0) {
                val block = text.substring(dsmlStart, end)
                val invokes = Regex("""<\|DSML\|invoke\s+name\s*=\s*["']([^"'<>\r\n]{1,256})["']""")
                if (invokes.findAll(block).any { it.groupValues[1] in tools } && block.contains("</|DSML|invoke>")) return true
            }
        }
        val tokenStart = text.lastIndexOf("<｜tool▁calls▁begin｜>")
        if (tokenStart >= 0) {
            val end = text.indexOf("<｜tool▁calls▁end｜>", tokenStart)
            if (end >= 0) {
                val block = text.substring(tokenStart, end)
                val names = Regex("""<｜tool▁call▁begin｜>function<｜tool▁sep｜>([^\s<>]{1,256})""")
                if (names.findAll(block).any { it.groupValues[1] in tools } && block.contains("<｜tool▁call▁end｜>")) return true
            }
        }
        for (match in Regex("<tool_call>([\\s\\S]*?)</tool_call>").findAll(text)) {
            if (isKnownJsonCall(match.groupValues[1].trim().removeSurrounding("```json", "```"), tools)) return true
        }
        // OpenAI-shaped call JSON is accepted only as an entire object (or a
        // fenced object), so merely discussing JSON fields is not a trigger.
        val json = text.trim().removeSurrounding("```json", "```").trim()
        val objectValue = runCatching { JSONObject(json) }.getOrNull() ?: return false
        val calls = objectValue.optJSONArray("tool_calls") ?: return false
        return (0 until calls.length()).any { index ->
            val call = calls.optJSONObject(index) ?: return@any false
            call.optString("type") == "function" && call.optJSONObject("function")?.let {
                it.optString("name") in tools && it.has("arguments")
            } == true
        }
    }

    private fun isKnownJsonCall(json: String, tools: Set<String>): Boolean {
        val call = runCatching { JSONObject(json) }.getOrNull() ?: return false
        return call.optString("name") in tools && (call.has("arguments") || call.has("input"))
    }

    /** Keep earlier signed/tool history unchanged; nudge the same user turn. */
    fun retryHistory(history: List<LLMMessage>): List<LLMMessage> {
        val failed = history.lastOrNull() ?: return history
        if (failed.role != LLMMessage.Role.ASSISTANT || failed.contentParts.any { it is AgentContentPart.ToolUse }) return history
        val result = history.dropLast(1).toMutableList()
        val tail = result.lastOrNull()
        if (tail?.role == LLMMessage.Role.USER) {
            val alreadyNudged = tail.content.startsWith(REMINDER) || tail.contentParts.any { it is AgentContentPart.Text && it.text == REMINDER }
            if (!alreadyNudged) result[result.lastIndex] = prependContextSummary(tail, REMINDER)
        } else {
            result.add(LLMMessage(LLMMessage.Role.USER, REMINDER))
        }
        return result
    }
}
