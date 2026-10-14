package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MisplacedToolRecoveryTest {
    private val tools = setOf("shell_execute", "file_read")
    private val dsml = """I need to inspect files. <|DSML|function_calls><|DSML|invoke name="shell_execute"><|DSML|parameter name="command" string="true">pwd</|DSML|parameter></|DSML|invoke></|DSML|function_calls>"""
    private fun action(reasoning: String = dsml, retries: Int = 0, finish: String? = "end_turn", hasCalls: Boolean = false, deepSeek: Boolean = true) =
        MisplacedToolRecovery.action(deepSeek, reasoning, tools, hasCalls, finish, retries)

    @Test fun recognizesNativeAndNamespacedDeepSeekModels() {
        for (id in listOf("deepseek-v4.1-flash", "code-buddy/deepseek-v4.1-flash", "relay:DeepSeek-R1", "deepseek")) {
            assertTrue(id, MisplacedToolRecovery.isDeepSeekModel(id))
        }
        for (id in listOf("gpt-6.1-sol", "claude-sonnet", "notdeepseek-model")) assertFalse(MisplacedToolRecovery.isDeepSeekModel(id))
    }

    @Test fun retriesSerializedToolMarkersWithoutExecutingTheirArguments() {
        assertEquals(MisplacedToolRecovery.Action.RETRY, action())
        val tokens = "<｜tool▁calls▁begin｜><｜tool▁call▁begin｜>function<｜tool▁sep｜>shell_execute\n```json\n{\"command\":\"echo 中文🙂\"}\n```<｜tool▁call▁end｜><｜tool▁calls▁end｜>"
        assertEquals(MisplacedToolRecovery.Action.RETRY, action(tokens))
        assertEquals(MisplacedToolRecovery.Action.RETRY, action("""<tool_call>{"name":"file_read","arguments":{"path":"/var/minis/test"}}</tool_call>"""))
        assertEquals(MisplacedToolRecovery.Action.RETRY, action("""{"tool_calls":[{"type":"function","function":{"name":"shell_execute","arguments":"{\"command\":\"pwd\"}"}}]}"""))
    }

    @Test fun visibleReplyDoesNotMakeAThinkingToolCallFinishTheTask() {
        // The caller checks this before its ordinary visible-text completion.
        assertEquals(MisplacedToolRecovery.Action.RETRY, action(dsml))
        val history = listOf(LLMMessage(LLMMessage.Role.USER, "inspect"), LLMMessage(LLMMessage.Role.ASSISTANT, "I will inspect it", reasoningContent = dsml))
        assertTrue(MisplacedToolRecovery.retryHistory(history).last().content.contains(MisplacedToolRecovery.REMINDER))
        assertEquals(LLMMessage.Role.USER, MisplacedToolRecovery.retryHistory(history).last().role)
    }

    @Test fun recoveryHasItsOwnFiniteBudgetAndThenOffersResume() {
        assertEquals(MisplacedToolRecovery.Action.RETRY, action(retries = 0))
        assertEquals(MisplacedToolRecovery.Action.RETRY, action(retries = 1))
        assertEquals(MisplacedToolRecovery.Action.RESUME, action(retries = MisplacedToolRecovery.MAX_RETRIES))
        assertEquals(MisplacedToolRecovery.Action.NONE, action("normal thought", retries = MisplacedToolRecovery.MAX_RETRIES))
    }

    @Test fun normalToolDiscussionUnknownToolsAndIncompleteExamplesDoNotTriggerRecovery() {
        for (reasoning in listOf(
            "I can use shell_execute to run pwd.",
            "The JSON key is \"tool_calls\" and the name is \"shell_execute\".",
            """{"name":"shell_execute","arguments":{"command":"pwd"}}""",
            """<tool_call>{"name":"unknown_tool","arguments":{}}</tool_call>""",
            """<tool_call>{"name":"file_read"}</tool_call>""",
            dsml.substringBefore("</|DSML|function_calls>"),
        )) assertEquals(reasoning, MisplacedToolRecovery.Action.NONE, action(reasoning))
        assertEquals(MisplacedToolRecovery.Action.NONE, action(dsml, deepSeek = false))
        assertEquals(MisplacedToolRecovery.Action.NONE, MisplacedToolRecovery.action(true, dsml, emptySet(), false, "end_turn", 0))
    }

    @Test fun completedFormalCallsOutputLimitAndDisconnectedStreamsKeepTheirExistingRecovery() {
        assertEquals(MisplacedToolRecovery.Action.NONE, action(hasCalls = true))
        for (finish in listOf(null, "length", "max_tokens", "max_output_tokens", "content_filter")) {
            assertEquals(MisplacedToolRecovery.Action.NONE, action(finish = finish))
        }
    }

    @Test fun retryPreservesEarlierSignedToolPairAndUserBoundary() {
        val input = JSONObject().put("path", "/var/minis/test")
        val assistant = LLMMessage(LLMMessage.Role.ASSISTANT, "", deepSeekReplay = "signed replay",
            contentParts = listOf(AgentContentPart.ToolUse("call", "file_read", input)))
        val toolResult = AgentContentPart.ToolResult("call", "file_read", "original tool result")
        val response = LLMMessage(LLMMessage.Role.USER, "", dbMessageId = "result-row", contentParts = listOf(toolResult))
        val failed = LLMMessage(LLMMessage.Role.ASSISTANT, "", reasoningContent = dsml)
        val history = listOf(assistant, response, failed)
        val retried = MisplacedToolRecovery.retryHistory(history)
        assertEquals(assistant, retried[0])
        assertEquals("result-row", retried[1].dbMessageId)
        assertEquals(toolResult, retried[1].contentParts.first())
        assertTrue(retried[1].contentParts.contains(AgentContentPart.Text(MisplacedToolRecovery.REMINDER)))
        assertEquals(history, listOf(assistant, response, failed))
        assertEquals(retried, MisplacedToolRecovery.retryHistory(retried + failed))
    }

    @Test fun noFormalToolCallIsEverRemovedByTheRetryHelper() {
        val history = listOf(LLMMessage(LLMMessage.Role.ASSISTANT, "", contentParts = listOf(
            AgentContentPart.ToolUse("call", "file_read", JSONObject()),
        )))
        assertEquals(history, MisplacedToolRecovery.retryHistory(history))
    }

    @Test fun scanningIsBoundedForVeryLongReasoning() {
        assertEquals(MisplacedToolRecovery.Action.NONE, action(dsml + "padding ".repeat(20_000)))
        assertEquals(MisplacedToolRecovery.Action.RETRY, action("padding ".repeat(20_000) + dsml))
    }
}
