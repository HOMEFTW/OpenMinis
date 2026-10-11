package com.openminis.app.ui.chat

import com.openminis.app.data.model.*
import com.openminis.app.provider.anthropic.AnthropicReplay
import com.openminis.app.provider.deepseek.DeepSeekMessages
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SignedToolArgumentsTest {
    @Test fun signedTypoIsRejectedByPreflightWithoutInvalidatingReplay() {
        val tools = listOf(AgentToolDefinition("shell_execute", "Shell", mapOf(
            "command" to AgentToolParam("string", "command")), listOf("command")))
        val args = JSONObject().put("comand", "echo ok")
        val blocks = JSONArray().put(JSONObject().put("type", "thinking")
            .put("thinking", "thought").put("signature", "opaque=="))
            .put(JSONObject().put("type", "tool_use").put("id", "call_1")
                .put("name", "shell_execute").put("input", args))
        val anthropic = AnthropicReplay.capture("model", blocks)
        val deepseek = DeepSeekMessages.replay("model", blocks)
        val prepared = prepareToolArguments("shell_execute", args, null, tools, preserveExactInput = true)
        assertTrue(prepared.repairs.isEmpty())
        assertTrue(args.has("comand"))
        assertFalse(args.has("command"))
        assertNotNull(ChatViewModel.preflightValidateToolCallImpl("shell_execute", args, tools))
        val parts = listOf(AgentContentPart.ToolUse("call_1", "shell_execute", args))
        val history = LLMMessage(LLMMessage.Role.ASSISTANT, "", contentParts = parts,
            reasoningContent = "thought", anthropicReplay = anthropic, deepSeekReplay = deepseek)
        assertNotNull(AnthropicReplay.read(history, "model"))
        assertNotNull(DeepSeekMessages.readReplay(history, "model"))
        val saved = JSONArray(serializeAssistantParts(parts, deepSeekReplay = deepseek, anthropicReplay = anthropic))
        val restored = history.copy(contentParts = listOf(AgentContentPart.ToolUse("call_1", "shell_execute",
            JSONObject(saved.getJSONObject(0).getJSONObject("value").getString("input")))))
        assertNotNull(AnthropicReplay.read(restored, "model"))
        assertNotNull(DeepSeekMessages.readReplay(restored, "model"))
    }
}
