package com.openminis.app.provider.deepseek

import com.openminis.app.data.model.*
import com.openminis.app.provider.ProviderFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class DeepSeekProviderTest {
    private val model = LLMModel("deepseek-v4.1-flash", "DeepSeek", "DeepSeek", maxOutputTokens = 384_000,
        inputModalities = listOf("text", "image"))
    private val messages = listOf(LLMMessage(LLMMessage.Role.USER, "Hello"))
    private fun body(history: List<LLMMessage> = messages, level: ThinkingLevel = ThinkingLevel.HIGH,
                     images: List<LLMMessage.ImagePart> = emptyList()) =
        DeepSeekMessages.request(model, history, "Be helpful", 1000, null, images, emptyList(), level)

    private fun sse(vararg events: String) = events.joinToString("") { "event: ignored\ndata:$it\n\n" }
    private val start = """{"type":"message_start","message":{"usage":{"input_tokens":12,"cache_read_input_tokens":8}}}"""
    private val textStart = """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":"Hi"}}"""
    private val textStop = """{"type":"content_block_stop","index":0}"""
    private val finish = """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":7}}"""
    private val stop = """{"type":"message_stop"}"""
    private fun response(text: String = sse(start, textStart, textStop, finish, stop)) =
        MockResponse().setHeader("Content-Type", "text/event-stream").setBody(text)

    @Test fun directHttpUsesOfficialMessagesAuthenticationAndCumulativeUsage() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(response())
            val instance = ProviderInstance("native", "DeepSeek", ProviderType.deepSeek, ProviderCredential.apiKey,
                customBaseURL = server.url("/anthropic/").toString(), appendV1Suffix = true,
                useResponsesAPI = true, azureMode = true)
            val provider = ProviderFactory.create(instance, "test-key", model)
            assertTrue(provider is DeepSeekProvider)
            val result = provider.sendMessage(messages, "system", 2000, thinkingLevel = ThinkingLevel.MAX)
            assertEquals("Hi", result.text)
            assertEquals("end_turn", result.stopReason)
            assertEquals(12, result.usage!!.inputTokens)
            assertEquals(7, result.usage!!.outputTokens)
            assertEquals(20, result.usage!!.latestContextTokens)
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("/anthropic/v1/messages", request.path)
            assertEquals("test-key", request.getHeader("x-api-key"))
            assertEquals("2023-06-01", request.getHeader("anthropic-version"))
            assertNull(request.getHeader("Authorization"))
            assertNull(request.getHeader("anthropic-beta"))
            val json = JSONObject(request.body.readUtf8())
            assertEquals("max", json.getJSONObject("output_config").getString("effort"))
            assertFalse(json.has("reasoning_effort"))
            assertFalse(json.getJSONObject("thinking").has("budget_tokens"))
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun endpointDoesNotDuplicateV1AndRejectsEmbeddedCredentials() {
        assertEquals("https://api.deepseek.com/anthropic/v1/messages", DeepSeekProvider.messagesEndpoint(null))
        assertEquals("https://host.test/anthropic/v1/messages", DeepSeekProvider.messagesEndpoint("https://host.test/anthropic/v1/"))
        assertThrows(IllegalArgumentException::class.java) { DeepSeekProvider.messagesEndpoint("https://key@host.test") }
        assertThrows(IllegalArgumentException::class.java) { DeepSeekProvider.messagesEndpoint("https://host.test?key=secret") }
    }

    @Test fun effortsAreDeepSeekNativeAndOffOmitsEffort() {
        listOf(ThinkingLevel.LOW to "low", ThinkingLevel.HIGH to "high", ThinkingLevel.MAX to "max").forEach { (level, effort) ->
            assertEquals(effort, body(level = level).getJSONObject("output_config").getString("effort"))
        }
        val off = body(level = ThinkingLevel.OFF)
        assertEquals("disabled", off.getJSONObject("thinking").getString("type"))
        assertFalse(off.has("output_config"))
    }

    @Test fun thinkingAndToolSignaturesSurviveNativeReplayButNotHistoryEdits() {
        val parser = DeepSeekStream(model.id)
        val events = listOf(start,
            """{"type":"content_block_start","index":3,"content_block":{"type":"thinking","thinking":"Plan"}}""",
            """{"type":"content_block_delta","index":3,"delta":{"type":"thinking_delta","thinking":" carefully"}}""",
            """{"type":"content_block_delta","index":3,"delta":{"type":"signature_delta","signature":"sig"}}""",
            """{"type":"content_block_stop","index":3}""",
            """{"type":"content_block_start","index":5,"content_block":{"type":"tool_use","id":"c1","name":"shell","input":{}}}""",
            """{"type":"content_block_delta","index":5,"delta":{"type":"input_json_delta","partial_json":"{\"cmd\":\"pwd\"}"}}""",
            """{"type":"content_block_stop","index":5}""",
            """{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":5}}""", stop)
        val chunks = events.flatMap { parser.accept(JSONObject(it)) }
        val replay = chunks.filterIsInstance<LLMStreamChunk.ReasoningContent>().single()
        val tool = chunks.filterIsInstance<LLMStreamChunk.ToolCallComplete>().single()
        assertEquals("Plan carefully", replay.content)
        assertEquals("pwd", tool.args.getString("cmd"))
        val history = LLMMessage(LLMMessage.Role.ASSISTANT, "",
            contentParts = listOf(AgentContentPart.ToolUse(tool.id, tool.name, tool.args)),
            reasoningContent = replay.content, deepSeekReplay = replay.deepSeekReplay)
        val decoded = DeepSeekMessages.readReplay(history, model.id)!!
        assertEquals("sig", decoded.getJSONObject(0).getString("signature"))
        assertFalse(replay.deepSeekReplay!!.contains("Plan carefully"))
        assertNull(DeepSeekMessages.readReplay(history, "other-model"))
        assertNull(DeepSeekMessages.readReplay(history.copy(reasoningContent = "summary"), model.id))
        assertNull(DeepSeekMessages.readReplay(history.copy(contentParts = listOf(AgentContentPart.ToolUse("c1", "shell", JSONObject().put("cmd", "offloaded")))), model.id))
        val result = LLMMessage(LLMMessage.Role.USER, "", contentParts = listOf(AgentContentPart.ToolResult("c1", "shell", "done")))
        val sent = body(messages + history + result).getJSONArray("messages")
        assertEquals("sig", sent.getJSONObject(1).getJSONArray("content").getJSONObject(0).getString("signature"))
        assertEquals("tool_result", sent.getJSONObject(2).getJSONArray("content").getJSONObject(0).getString("type"))
    }

    @Test fun imageBlocksAndToolResultsUseMessagesFormat() {
        val bytes = byteArrayOf(1, 2, 3)
        val sent = body(images = listOf(LLMMessage.ImagePart(bytes, "image/png"))).getJSONArray("messages")
        val image = sent.getJSONObject(0).getJSONArray("content").getJSONObject(1)
        assertEquals("base64", image.getJSONObject("source").getString("type"))
        assertEquals("AQID", image.getJSONObject("source").getString("data"))
        val call = LLMMessage(LLMMessage.Role.ASSISTANT, "", contentParts = listOf(AgentContentPart.ToolUse("x", "read_image", JSONObject())))
        val result = LLMMessage(LLMMessage.Role.USER, "", contentParts = listOf(AgentContentPart.Text("next"),
            AgentContentPart.ToolResult("x", "read_image", "image", imageData = bytes, imageMimeType = "image/png")))
        val content = body(messages + call + result).getJSONArray("messages").getJSONObject(2).getJSONArray("content")
        assertEquals("tool_result", content.getJSONObject(0).getString("type"))
        assertEquals("image", content.getJSONObject(0).getJSONArray("content").getJSONObject(1).getString("type"))
        assertThrows(LLMError.ProviderError::class.java) { body(messages + result) }
    }

    @Test fun replayRestoresInterleavedOrderFromGroupedAndReloadedHistory() {
        val blocks = JSONArray("""[
            {"type":"thinking","thinking":"first","signature":"s1"},
            {"type":"text","text":"before"},
            {"type":"tool_use","id":"c1","name":"shell","input":{"b":2,"a":1}},
            {"type":"thinking","thinking":"second","signature":"s2"},
            {"type":"text","text":"after"}
        ]""")
        val persisted = JSONObject().put("type", "deepSeekReplay").put("value", DeepSeekMessages.replay(model.id, blocks)).toString()
        val history = LLMMessage(LLMMessage.Role.ASSISTANT, "beforeafter", reasoningContent = "firstsecond",
            contentParts = listOf(AgentContentPart.Text("beforeafter"),
                AgentContentPart.ToolUse("c1", "shell", JSONObject("""{"a":1,"b":2}"""))),
            deepSeekReplay = JSONObject(persisted).getString("value"))
        val replay = DeepSeekMessages.readReplay(history, model.id)!!
        assertEquals(listOf("thinking", "text", "tool_use", "thinking", "text"),
            (0 until replay.length()).map { replay.getJSONObject(it).getString("type") })
        assertEquals("s2", replay.getJSONObject(3).getString("signature"))
        assertEquals("after", replay.getJSONObject(4).getString("text"))
        assertNull(DeepSeekMessages.readReplay(history.copy(contentParts = listOf(AgentContentPart.Text("edited"))), model.id))
    }

    @Test fun streamTruncationAndInBandErrorsNeverFinishSuccessfully() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(response(sse(start, textStart, textStop, finish)))
            server.enqueue(response(sse(start, """{"type":"error","error":{"type":"overloaded_error","message":"busy"}}""")))
            repeat(2) {
                val chunks = mutableListOf<LLMStreamChunk>()
                try {
                    DeepSeekProvider("key", model, server.url("/").toString()).streamMessage(messages, null, 100).toList(chunks)
                    fail("Expected stream failure")
                } catch (_: LLMError.TransientError) { }
                assertTrue(chunks.none { it is LLMStreamChunk.Finished })
            }
        }
    }

    @Test fun malformedToolJsonDoesNotBecomeAnEmptyToolCall() {
        val parser = DeepSeekStream(model.id)
        listOf(start,
            """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"x","name":"shell","input":{}}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{"}}""",
            textStop, """{"type":"message_delta","delta":{"stop_reason":"tool_use"}}""").forEach {
            assertTrue(parser.accept(JSONObject(it)).none { chunk -> chunk is LLMStreamChunk.ToolCallComplete })
        }
        assertThrows(LLMError.DecodingError::class.java) { parser.accept(JSONObject(stop)) }
    }

    @Test fun completeAndPartialToolsAreBothDiscardedOnOutputLimit() {
        for (input in listOf("{\"cmd\":\"pwd\"}", "{")) {
            val parser = DeepSeekStream(model.id)
            val inputDelta = JSONObject().put("type", "content_block_delta").put("index", 1)
                .put("delta", JSONObject().put("type", "input_json_delta").put("partial_json", input))
            val chunks = listOf(JSONObject(start), JSONObject(textStart), JSONObject(textStop),
                JSONObject("""{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"x","name":"shell","input":{}}}"""),
                inputDelta, JSONObject("""{"type":"content_block_stop","index":1}"""),
                JSONObject("""{"type":"message_delta","delta":{"stop_reason":"max_tokens"}}"""), JSONObject(stop))
                .flatMap(parser::accept)
            assertTrue(chunks.none { it is LLMStreamChunk.ToolCallComplete })
            assertTrue(chunks.filterIsInstance<LLMStreamChunk.Finished>().single().discardToolCalls)
            val replay = chunks.filterIsInstance<LLMStreamChunk.ReasoningContent>().single()
            assertFalse(replay.deepSeekReplay!!.contains("tool_use"))
            val history = LLMMessage(LLMMessage.Role.ASSISTANT, "Hi", reasoningContent = replay.content, deepSeekReplay = replay.deepSeekReplay)
            assertEquals(1, DeepSeekMessages.readReplay(history, model.id)!!.length())
            // Continuing after truncation sends no unresolved tool call.
            assertEquals(3, body(messages + history + LLMMessage(LLMMessage.Role.USER, "continue")).getJSONArray("messages").length())
        }
    }

    @Test fun strictToolJsonRejectsTrailingGarbageAndNonObjects() {
        for (input in listOf("{\"cmd\":\"pwd\"}garbage", "{} {}", "[]", "{cmd:'pwd'}")) {
            val parser = DeepSeekStream(model.id)
            parser.accept(JSONObject(start))
            parser.accept(JSONObject("""{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"x","name":"shell","input":{}}}"""))
            parser.accept(JSONObject().put("type", "content_block_delta").put("index", 0)
                .put("delta", JSONObject().put("type", "input_json_delta").put("partial_json", input)))
            parser.accept(JSONObject(textStop))
            parser.accept(JSONObject("""{"type":"message_delta","delta":{"stop_reason":"tool_use"}}"""))
            assertThrows(input, LLMError.DecodingError::class.java) { parser.accept(JSONObject(stop)) }
        }
    }

    @Test fun cancellationClosesBlockedHttpRead() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val job = launch(Dispatchers.Default) {
                DeepSeekProvider("key", model, server.url("/").toString()).streamMessage(messages, null, 100).toList()
            }
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(3, TimeUnit.SECONDS) })
            withTimeout(3000) { job.cancelAndJoin() }
        }
    }

    @Test fun httpErrorsAreClassifiedWithoutProtocolFallback() = runBlocking {
        MockWebServer().use { server ->
            val expected = listOf(401 to LLMError.InvalidApiKey::class.java, 429 to LLMError.RateLimited::class.java,
                503 to LLMError.TransientError::class.java, 400 to LLMError.ProviderError::class.java)
            expected.forEach { (status, errorClass) ->
                server.enqueue(MockResponse().setResponseCode(status).setBody("""{"error":{"message":"rejected"}}"""))
                try {
                    DeepSeekProvider("key", model, server.url("/").toString()).sendMessage(messages, null, 100)
                    fail("Expected HTTP error")
                } catch (error: LLMError) { assertTrue(errorClass.isInstance(error)) }
            }
            assertEquals(expected.size, server.requestCount)
        }
    }

    @Test fun multilineSseAndCumulativeUsageUpdatesAreAccepted() = runBlocking {
        MockWebServer().use { server ->
            val multiline = "data: {\ndata: \"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":4,\"cache_creation_input_tokens\":3}}}\n\n"
            server.enqueue(response(multiline + sse(textStart, textStop,
                """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"input_tokens":6,"output_tokens":2}}""", stop)))
            val result = DeepSeekProvider("key", model, server.url("/").toString()).sendMessage(messages, null, 100)
            assertEquals(9, result.usage!!.latestContextTokens)
            assertEquals(6, result.usage!!.inputTokens)
            assertEquals(3, result.usage!!.cacheCreationInputTokens)
        }
    }

    @Test fun heartbeatsDoNotResetTheProgressTimeout() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(response(": ping\n\n".repeat(100)).throttleBody(8, 100, TimeUnit.MILLISECONDS))
            withTimeout(5000) {
                try {
                    DeepSeekProvider("key", model, server.url("/").toString(), responseTimeoutSeconds = 1)
                        .streamMessage(messages, null, 100).toList()
                    fail("Expected idle timeout")
                } catch (error: LLMError.NetworkError) {
                    assertTrue(error.cause is java.io.InterruptedIOException)
                }
            }
        }
    }

    @Test fun redirectNeverForwardsTheApiKey() = runBlocking {
        MockWebServer().use { source -> MockWebServer().use { destination ->
            source.enqueue(MockResponse().setResponseCode(307).setHeader("Location", destination.url("/capture")))
            try {
                DeepSeekProvider("private-key", model, source.url("/").toString()).sendMessage(messages, null, 100)
                fail("Redirect must fail")
            } catch (_: LLMError.ProviderError) { }
            assertEquals(0, destination.requestCount)
        } }
    }
}
