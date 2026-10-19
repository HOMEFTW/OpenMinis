package com.openminis.app.provider

import com.openminis.app.data.model.*
import com.openminis.app.provider.anthropic.AnthropicReplay
import com.openminis.app.provider.deepseek.DeepSeekMessages
import com.openminis.app.provider.openai.OpenAIProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class Build34ToolCompatibilityTest {
    private fun call(id: String) = AgentContentPart.ToolUse(id, "shell", JSONObject().put("cmd", "pwd"), "opaque+/|==")
    private fun result(id: String) = AgentContentPart.ToolResult(id, "shell", "done")
    private fun assistant(vararg ids: String) = LLMMessage(LLMMessage.Role.ASSISTANT, "", contentParts = ids.map(::call))
    private fun user(vararg ids: String) = LLMMessage(LLMMessage.Role.USER, "", contentParts = ids.map(::result))
    private fun calls(messages: List<LLMMessage>) = messages.flatMap { it.contentParts.filterIsInstance<AgentContentPart.ToolUse>() }
    private fun results(messages: List<LLMMessage>) = messages.flatMap { it.contentParts.filterIsInstance<AgentContentPart.ToolResult>() }

    @Test fun collidingForeignIdsPairByOriginalIdentityEvenWhenResultsAreReordered() {
        val raw = listOf("call_x|fc_a", "call_x|fc_b", "call_x-2", "odd/id", "odd_id", "")
        val history = listOf(assistant(*raw.toTypedArray()), user(*raw.reversed().toTypedArray()))
        for (format in ToolIdCompatibility.Format.entries) {
            val wire = ToolIdCompatibility.messages(history, format)
            val ids = calls(wire).map { it.id }
            assertEquals(raw.size, ids.toSet().size)
            assertEquals(ids.reversed(), results(wire).map { it.id })
            ids.forEach { id ->
                id.split('|').forEach { assertTrue(it.matches(Regex("[A-Za-z0-9_-]{1,64}"))) }
            }
        }
        assertEquals(raw, calls(history).map { it.id })
    }

    @Test fun repeatedCallsAvoidReservedSuffixesAndKeepEachResultPaired() {
        val history = listOf(assistant("same"), user("same"), assistant("same"), user("same"),
            assistant("same-2"), user("same-2"))
        for (format in ToolIdCompatibility.Format.entries) {
            val wire = ToolIdCompatibility.messages(history, format)
            val ids = calls(wire).map { it.id }
            assertEquals(3, ids.toSet().size)
            assertEquals(ids, results(wire).map { it.id })
        }
    }

    @Test fun duplicateResultKeepsItsOwnCallInsteadOfClaimingACollidingComposite() {
        val history = listOf(assistant("call_x|fc_a", "call_x|fc_b"), user("call_x|fc_b", "call_x|fc_b", "call_x|fc_a"))
        for (format in ToolIdCompatibility.Format.entries) {
            val wire = ToolIdCompatibility.messages(history, format)
            val ids = calls(wire).map { it.id }
            assertEquals(listOf(ids[1], ids[1], ids[0]), results(wire).map { it.id })
        }
    }

    @Test fun unmatchedCompositeCannotStealAnExistingCallBySharingItsCallHalf() {
        val history = listOf(assistant("call_x|fc_a"), user("call_x|fc_missing", "call_x|fc_a"))
        for (format in ToolIdCompatibility.Format.entries) {
            val wire = ToolIdCompatibility.messages(history, format)
            assertNotEquals(calls(wire).single().id, results(wire).first().id)
            assertEquals(calls(wire).single().id, results(wire).last().id)
        }
    }

    @Test fun longCommonPrefixesAndOpaquePipesAreNotConflated() {
        val raw = listOf("x".repeat(150) + "a", "x".repeat(150) + "b", "opaque|a", "opaque|b")
        for (format in ToolIdCompatibility.Format.entries) {
            val wire = ToolIdCompatibility.messages(listOf(assistant(*raw.toTypedArray()), user(*raw.toTypedArray())), format)
            val ids = calls(wire).map { it.id }
            assertEquals(4, ids.toSet().size)
            assertEquals(ids, results(wire).map { it.id })
        }
    }

    @Test fun nativeReplayReservationsKeepForeignCallsFromTakingNativeIds() {
        val history = listOf(assistant("call_x|fc_foreign", "call_x"), user("call_x", "call_x|fc_foreign"))
        val wire = ToolIdCompatibility.messages(history, ToolIdCompatibility.Format.DEEPSEEK, setOf("call_x"))
        assertEquals("call_x", calls(wire)[1].id)
        assertNotEquals("call_x", calls(wire)[0].id)
        assertEquals(calls(wire).map { it.id }.reversed(), results(wire).map { it.id })
    }

    @Test fun openAiRequestFormatsSendMatchingUniqueIdsWithoutMutatingHistory() {
        val ids = arrayOf("call_shared|fc_a", "call_shared|fc_b", "非ASCII/ID")
        val history = listOf(assistant(*ids), user(*ids.reversedArray()))
        val provider = OpenAIProvider(apiKey = "test", model = LLMModel.gpt61Sol)
        val chat = provider.buildRequestBody(history, null, 128, false, null, emptyList()).getJSONArray("messages")
        val chatIds = chat.getJSONObject(0).getJSONArray("tool_calls").let { calls ->
            (0 until calls.length()).map { calls.getJSONObject(it).getString("id") }
        }
        assertEquals(3, chatIds.toSet().size)
        assertEquals(chatIds.reversed(), (1..3).map { chat.getJSONObject(it).getString("tool_call_id") })
        val items = provider.buildResponsesAPIBody(history, null, 128, false).getJSONArray("input")
        val callIds = (0..2).map { items.getJSONObject(it).getString("call_id") }
        assertEquals(3, callIds.toSet().size)
        assertEquals(callIds.reversed(), (3..5).map { items.getJSONObject(it).getString("call_id") })
        assertEquals(ids.toList(), calls(history).map { it.id })
        assertEquals("opaque+/|==", calls(history).first().thoughtSignature)
    }

    @Test fun deepSeekNativeIdsAndSignedBlocksSurviveAlongsideForeignHistory() {
        val model = LLMModel("deepseek-v4.1-flash", "DeepSeek", "DeepSeek")
        val native = "opaque/native|id"
        val blocks = JSONArray().put(JSONObject().put("type", "thinking").put("thinking", "reason")
            .put("signature", "opaque+/|==")).put(JSONObject().put("type", "tool_use").put("id", native)
            .put("name", "shell").put("input", JSONObject().put("cmd", "pwd")))
        val replay = DeepSeekMessages.replay(model.id, blocks)
        val signed = assistant(native).copy(reasoningContent = "reason", deepSeekReplay = replay)
        val history = listOf(assistant("call_foreign|fc_item"), user("call_foreign|fc_item"), signed, user(native))
        val wire = DeepSeekMessages.request(model, history, null, 128, null, emptyList(), emptyList(), ThinkingLevel.HIGH)
            .getJSONArray("messages")
        val content = wire.getJSONObject(2).getJSONArray("content")
        assertEquals("opaque+/|==", content.getJSONObject(0).getString("signature"))
        assertEquals(native, content.getJSONObject(1).getString("id"))
        assertEquals(native, wire.getJSONObject(3).getJSONArray("content").getJSONObject(0).getString("tool_use_id"))
        assertEquals(replay, signed.deepSeekReplay)
    }

    @Test fun anthropicReplayKeepsOpaqueThinkingRedactionAndOriginalToolId() {
        val blocks = JSONArray("""[{"type":"thinking","thinking":"reason","signature":"opaque+/|=="},
            {"type":"redacted_thinking","data":"sealed+/|=="},
            {"type":"tool_use","id":"toolu_native","name":"shell","input":{"cmd":"pwd"}}]""")
        val replay = AnthropicReplay.capture("claude", blocks)
        val signed = assistant("toolu_native").copy(reasoningContent = "reason", anthropicReplay = replay)
        val wire = ToolIdCompatibility.messages(listOf(signed, user("toolu_native")),
            ToolIdCompatibility.Format.ANTHROPIC, setOf("toolu_native"))
        val decoded = AnthropicReplay.read(wire.first(), "claude")!!
        assertEquals("opaque+/|==", decoded.getJSONObject(0).getString("signature"))
        assertEquals("sealed+/|==", decoded.getJSONObject(1).getString("data"))
        assertEquals("toolu_native", decoded.getJSONObject(2).getString("id"))
        assertEquals(replay, signed.anthropicReplay)
    }
}
