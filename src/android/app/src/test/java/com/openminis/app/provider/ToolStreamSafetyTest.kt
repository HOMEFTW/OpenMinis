package com.openminis.app.provider

import com.openminis.app.data.model.*
import com.openminis.app.provider.anthropic.AnthropicProvider
import com.openminis.app.provider.gemini.GeminiProvider
import com.openminis.app.provider.openai.OpenAIProvider
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ToolStreamSafetyTest {
    private data class Outcome(val chunks: List<LLMStreamChunk>, val error: Throwable?)
    private fun sse(vararg events: String) = events.joinToString("") { "data: $it\n\n" }
    private fun stream(protocol: String, body: String): Outcome {
        val server = MockWebServer()
        server.start()
        return try {
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body))
            val url = server.url("/v1").toString().trimEnd('/')
            val provider: LLMProvider = when (protocol) {
                "anthropic" -> AnthropicProvider("fixture", LLMModel.claudeHaiku45, basePath = url)
                "gemini" -> GeminiProvider("fixture", LLMModel.gemini25Flash, basePath = url)
                else -> OpenAIProvider("fixture", LLMModel.gpt4oMini, basePath = url, useResponsesAPI = protocol == "responses")
            }
            val chunks = mutableListOf<LLMStreamChunk>()
            val error = runCatching { runBlocking {
                provider.streamMessageClamped(listOf(LLMMessage(LLMMessage.Role.USER, "fixture")),
                    null, 1024, null, emptyList(), emptyList(), ThinkingLevel.OFF).collect { chunks.add(it) }
            } }.exceptionOrNull()
            Outcome(chunks, error)
        } finally { server.shutdown() }
    }
    private fun chatCall(args: String) = JSONObject().put("choices", org.json.JSONArray().put(
        JSONObject().put("delta", JSONObject().put("tool_calls", org.json.JSONArray().put(
            JSONObject().put("index", 0).put("id", "call_a").put("function",
                JSONObject().put("name", "read_file").put("arguments", args))))))).toString()
    private val chatFinish = """{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}"""
    private val aStart = """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"call_a","name":"read_file","input":{}}}"""
    private fun aArgs(args: String) = JSONObject().put("type", "content_block_delta").put("index", 0)
        .put("delta", JSONObject().put("type", "input_json_delta").put("partial_json", args)).toString()
    private val aStop = """{"type":"content_block_stop","index":0}"""
    private val aFinish = """{"type":"message_delta","delta":{"stop_reason":"tool_use"}}"""
    private val aEnd = """{"type":"message_stop"}"""
    private val gCall = """{"candidates":[{"content":{"parts":[{"functionCall":{"name":"read_file","args":{}}}]}}]}"""
    private val gEnd = """{"candidates":[{"finishReason":"STOP"}]}"""
    private val rStart = """{"type":"response.output_item.added","item":{"type":"function_call","id":"fc_a","call_id":"call_a","name":"read_file"}}"""
    private val rDone = """{"type":"response.output_item.done","item":{"type":"function_call","id":"fc_a","call_id":"call_a","name":"read_file","arguments":"{}"}}"""
    private val rEnd = """{"type":"response.completed","response":{"status":"completed"}}"""

    @Test fun `EOF and item completion never authorize a tool`() {
        listOf("chat" to sse(chatCall("{}")), "responses" to sse(rStart, rDone),
            "anthropic" to sse(aStart, aArgs("{}"), aStop, aFinish), "gemini" to sse(gCall)).forEach { (protocol, body) ->
            val out = stream(protocol, body)
            assertTrue("$protocol emitted executable tool", out.chunks.none { it is LLMStreamChunk.ToolCallComplete })
            assertTrue("$protocol emitted successful finish", out.chunks.none { it is LLMStreamChunk.Finished })
        }
    }
    @Test fun `complete streams emit validated tools before Finished`() {
        listOf("chat" to sse(chatCall("{}"), chatFinish, "[DONE]"), "responses" to sse(rStart, rDone, rEnd),
            "anthropic" to sse(aStart, aArgs("{}"), aStop, aFinish, aEnd), "gemini" to sse(gCall, gEnd)).forEach { (protocol, body) ->
            val out = stream(protocol, body)
            assertNull("$protocol failed: ${out.error}", out.error)
            assertEquals(protocol, 1, out.chunks.filterIsInstance<LLMStreamChunk.ToolCallComplete>().size)
            assertTrue(protocol, out.chunks.last() is LLMStreamChunk.Finished)
        }
    }
    @Test fun `incomplete or trailing JSON is rejected rather than empty arguments`() {
        listOf("{\"path\":\"partial", "{} trailing", "[]").forEach { args ->
            listOf("chat" to sse(chatCall(args), chatFinish, "[DONE]"),
                "anthropic" to sse(aStart, aArgs(args), aStop, aFinish, aEnd)).forEach { (protocol, body) ->
                val out = stream(protocol, body)
                assertNotNull("$protocol accepted $args", out.error)
                assertTrue(out.chunks.none { it is LLMStreamChunk.ToolCallComplete })
            }
        }
    }
    @Test fun `Responses and Gemini reject malformed executable arguments`() {
        listOf("{\"path\":\"partial", "{} trailing", "[]", "{\"command\":bad}").forEach { args ->
            val done = JSONObject(rDone).apply { getJSONObject("item").put("arguments", args) }.toString()
            val out = stream("responses", sse(rStart, done, rEnd))
            assertTrue(out.error is LLMError.DecodingError)
            assertTrue(out.chunks.none { it is LLMStreamChunk.ToolCallComplete })
        }
        val out = stream("gemini", sse(gCall.replace("\"args\":{}", "\"args\":[]"), gEnd))
        assertTrue(out.error is LLMError.DecodingError)
        assertTrue(out.chunks.none { it is LLMStreamChunk.ToolCallComplete })
    }
    @Test fun `one invalid parallel input rejects the entire batch`() {
        val first = JSONObject(chatCall("{}")).getJSONArray("choices").getJSONObject(0).getJSONObject("delta").getJSONArray("tool_calls").getJSONObject(0)
        val second = JSONObject(first.toString()).put("index", 1).put("id", "call_b").apply { getJSONObject("function").put("arguments", "{} trailing") }
        val calls = JSONObject(chatCall("{}")).apply { getJSONArray("choices").getJSONObject(0).getJSONObject("delta").getJSONArray("tool_calls").put(second) }
        val out = stream("chat", sse(calls.toString(), chatFinish, "[DONE]"))
        assertTrue(out.error is LLMError.DecodingError)
        assertTrue(out.chunks.none { it is LLMStreamChunk.ToolCallComplete })
    }
    @Test fun `strict tool parser rejects unquoted and nonstandard literals recursively`() {
        listOf("bad", "NaN", "Infinity", "-Infinity", "01", "+1", ".5", "1.", "0x1", "undefined").forEach { literal ->
            assertTrue("accepted $literal", runCatching { parseToolInputObject("{\"nested\":[{\"value\":$literal}]}") }.exceptionOrNull() is LLMError.DecodingError)
        }
        assertTrue(runCatching { parseToolInputObject("{key:1}") }.isFailure)
        assertEquals(-1250.0, parseToolInputObject("{\"value\":-1.25E+3}").getDouble("value"), 0.0)
        assertEquals(3, parseToolInputObject("{\"values\":[true,false,null]}").getJSONArray("values").length())
    }
    @Test fun `output limit discards every pending tool`() {
        listOf("chat" to sse(chatCall("{}"), chatFinish.replace("tool_calls", "length"), "[DONE]"),
            "anthropic" to sse(aStart, aArgs("{}"), aStop, aFinish.replace("tool_use", "max_tokens"), aEnd),
            "gemini" to sse(gCall, gEnd.replace("STOP", "MAX_TOKENS"))).forEach { (protocol, body) ->
            val out = stream(protocol, body)
            assertNull(out.error)
            assertTrue(protocol, out.chunks.none { it is LLMStreamChunk.ToolCallComplete })
            assertTrue(protocol, out.chunks.filterIsInstance<LLMStreamChunk.Finished>().single().discardToolCalls)
        }
    }
    @Test fun `stream errors retain classification and discard pending tools`() {
        val scenarios = listOf(
            Triple("responses", sse(rStart, rDone, """{"type":"error","code":"invalid_api_key","message":"fixture"}"""), LLMError.InvalidApiKey::class.java),
            Triple("responses", sse(rStart, rDone, """{"type":"response.failed","response":{"error":{"code":"rate_limit_exceeded","message":"fixture"}}}"""), LLMError.RateLimited::class.java),
            Triple("gemini", sse(gCall, """{"error":{"code":429,"status":"RESOURCE_EXHAUSTED","message":"fixture"}}"""), LLMError.RateLimited::class.java),
            Triple("gemini", sse(gCall, """{"error":{"code":500,"message":"fixture"}}"""), LLMError.TransientError::class.java))
        scenarios.forEach { (protocol, body, category) ->
            val out = stream(protocol, body)
            assertTrue("$protocol wrong error ${out.error}", category.isInstance(out.error))
            assertTrue(out.chunks.none { it is LLMStreamChunk.ToolCallComplete || it is LLMStreamChunk.Finished })
        }
    }
    @Test fun `standard SSE without spaces and with multiline data preserves calls`() {
        val chat = chatCall("{}").replace("\"delta\":", "\"delta\":\ndata: ")
        listOf("chat" to sse(chat, chatFinish, "[DONE]"), "responses" to sse(rStart, rDone, rEnd),
            "anthropic" to sse(aStart, aArgs("{}"), aStop, aFinish, aEnd), "gemini" to sse(gCall, gEnd)).forEach { (protocol, body) ->
            val out = stream(protocol, ": heartbeat\n\n" + body.replace("data: ", "data:"))
            assertNull("$protocol failed ${out.error}", out.error)
            assertEquals(protocol, 1, out.chunks.filterIsInstance<LLMStreamChunk.ToolCallComplete>().size)
        }
    }
    @Test fun `EOF frame compatibility requires real message completion`() {
        listOf("chat" to sse(chatCall("{}")), "responses" to sse(rStart, rDone),
            "anthropic" to sse(aStart, aArgs("{}"), aStop, aFinish), "gemini" to sse(gCall)).forEach { (protocol, body) ->
            val out = stream(protocol, body.trimEnd() + "\n")
            assertTrue(protocol, out.chunks.none { it is LLMStreamChunk.ToolCallComplete || it is LLMStreamChunk.Finished })
        }
        listOf("chat" to sse(chatCall("{}"), chatFinish), "responses" to sse(rStart, rDone, rEnd),
            "anthropic" to sse(aStart, aArgs("{}"), aStop, aFinish, aEnd), "gemini" to sse(gCall, gEnd)).forEach { (protocol, body) ->
            val out = stream(protocol, body.trimEnd() + "\n")
            assertNull(out.error)
            assertEquals(protocol, 1, out.chunks.filterIsInstance<LLMStreamChunk.ToolCallComplete>().size)
        }
    }
    @Test fun `chat IDs remain unique capped and paired after natural suffix collision`() {
        val provider = OpenAIProvider("fixture", LLMModel.gpt4oMini)
        val ids = listOf("call_x", "call_x", "call_x-2", "a".repeat(64), "a".repeat(64))
        val history = ids.flatMap { id -> listOf(
            LLMMessage(LLMMessage.Role.ASSISTANT, "", contentParts = listOf(AgentContentPart.ToolUse(id, "read_file", JSONObject()))),
            LLMMessage(LLMMessage.Role.USER, "", contentParts = listOf(AgentContentPart.ToolResult(id, "read_file", "ok")))) }
        val messages = provider.buildRequestBody(history, null, 1024, false, null, emptyList()).getJSONArray("messages")
        val calls = (0 until messages.length()).mapNotNull { messages.getJSONObject(it).optJSONArray("tool_calls")?.getJSONObject(0)?.getString("id") }
        val results = (0 until messages.length()).mapNotNull { messages.getJSONObject(it).optString("tool_call_id").takeIf { id -> id.isNotEmpty() } }
        assertEquals(calls.size, calls.toSet().size)
        assertTrue(calls.all { it.length <= 64 })
        assertEquals(calls, results)
    }
    @Test fun `parallel result images follow all tool messages`() {
        val provider = OpenAIProvider("fixture", LLMModel.gpt4oMini.copy(inputModalities = listOf("text", "image")))
        val history = listOf(
            LLMMessage(LLMMessage.Role.ASSISTANT, "", contentParts = listOf(AgentContentPart.ToolUse("a", "read_image", JSONObject()), AgentContentPart.ToolUse("b", "read_file", JSONObject()))),
            LLMMessage(LLMMessage.Role.USER, "", contentParts = listOf(AgentContentPart.ToolResult("a", "read_image", "image", imageData = byteArrayOf(1, 2), imageMimeType = "image/png"), AgentContentPart.ToolResult("b", "read_file", "ok"))))
        val messages = provider.buildRequestBody(history, null, 1024, false, null, emptyList()).getJSONArray("messages")
        assertEquals(listOf("assistant", "tool", "tool", "user"), (0 until messages.length()).map { messages.getJSONObject(it).getString("role") })
    }
    @Test fun `Responses keeps composite halves independent and capped duplicate pairs unique`() {
        val provider = OpenAIProvider("fixture", LLMModel.gpt4oMini, useResponsesAPI = true)
        val call = "call_" + "c".repeat(50)
        val item = "fc_" + "i".repeat(50)
        val ids = listOf("$call|$item", "$call|$item", "$call-2|$item-2",
            "call_" + "p".repeat(65) + "x|fc_long_x", "call_" + "p".repeat(65) + "y|fc_long_y")
        val history = ids.flatMap { id -> listOf(
            LLMMessage(LLMMessage.Role.ASSISTANT, "", contentParts = listOf(AgentContentPart.ToolUse(id, "read_file", JSONObject()))),
            LLMMessage(LLMMessage.Role.USER, "", contentParts = listOf(AgentContentPart.ToolResult(id, "read_file", "ok")))) }
        val input = provider.buildResponsesAPIBody(history, null, 1024, false, emptyList(), emptyList(), ThinkingLevel.OFF).getJSONArray("input")
        val calls = (0 until input.length()).map { input.getJSONObject(it) }.filter { it.optString("type") == "function_call" }
        val outputs = (0 until input.length()).map { input.getJSONObject(it) }.filter { it.optString("type") == "function_call_output" }
        assertEquals(call, calls[0].getString("call_id"))
        assertEquals(item, calls[0].getString("id"))
        assertEquals(ids.size, calls.map { it.getString("call_id") }.toSet().size)
        assertEquals(ids.size, calls.map { it.getString("id") }.toSet().size)
        assertTrue(calls.all { it.getString("call_id").length <= 64 && it.getString("id").length <= 64 })
        assertEquals(calls.map { it.getString("call_id") }, outputs.map { it.getString("call_id") })
    }
}
