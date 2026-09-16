package com.openminis.app.provider

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.openai.OpenAIModelsApi
import com.openminis.app.provider.openai.OpenAIProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AstraImage25Test {
    private val efforts = listOf("low", "medium", "high", "xhigh", "max")
    private val levels = listOf(ThinkingLevel.LOW, ThinkingLevel.MEDIUM, ThinkingLevel.HIGH, ThinkingLevel.XHIGH, ThinkingLevel.MAX)
    private val messages = listOf(LLMMessage(LLMMessage.Role.USER, "Hello"))

    @Test fun modelIdentificationDoesNotChangeOtherModels() {
        assertTrue(LLMModel.isGPT6AstraId("openai/gpt-6-astra"))
        assertTrue(LLMModel.isGPTImage25Id("gpt-image-2.5-flare-2026-09-08"))
        for (id in listOf("gpt-6-astra-fake", "gpt-image-2", "gpt-image-2.5", "gpt-image-2.5-flare-fake", "gpt-5.5")) {
            assertFalse(LLMModel.isGPT6AstraId(id))
            assertFalse(LLMModel.isGPTImage25Id(id))
        }
        assertEquals(null, LLMModel.gpt4oMini.reasoningEffortValues)
    }

    @Test fun astraCatalogAndDiscoveredSnapshotsExposeAllFiveEfforts() {
        val builtin = LLMModel.allOpenAI.first { it.id == "gpt-6-astra" }
        val oauth = OpenAIModelsApi.fetchModelsOAuth().first { it.id == builtin.id }
        val snapshot = LLMModel("gpt-6-astra-2026-09-08", "Astra", "Custom")
        for (model in listOf(builtin, oauth, snapshot)) {
            assertEquals(true, model.supportsReasoning)
            assertEquals(efforts, model.reasoningEffortValues)
            assertEquals(levels, model.selectableThinkingLevels)
            assertEquals(ThinkingLevel.MAX, model.catalogMaxThinkingLevel)
        }
    }

    @Test fun astraBodiesPreserveEffortsAndNeverSendUnsupportedOffOrTemperature() {
        val provider = OpenAIProvider(apiKey = "test", model = LLMModel("gpt-6-astra", "Astra", "OpenAI"))
        for ((level, effort) in (levels + ThinkingLevel.ULTRA + ThinkingLevel.OFF).zip(efforts + "max" + "low")) {
            val chat = provider.buildRequestBody(messages, null, 1024, true, 0.7, emptyList(), thinkingLevel = level)
            assertEquals(effort, chat.getString("reasoning_effort"))
            assertFalse(chat.has("temperature"))
            val responses = provider.buildResponsesAPIBody(messages, null, 1024, true, emptyList(), thinkingLevel = level)
            assertEquals(effort, responses.getJSONObject("reasoning").getString("effort"))
        }
    }

    @Test fun image25ModelsArePureImageGeneratorsAndUseImagesAPIWithoutResponseFormat() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            for (variant in listOf("flare", "sunburst")) {
                val id = "gpt-image-2.5-$variant"
                val model = LLMModel.allOpenAI.first { it.id == id }
                assertEquals(listOf("image"), model.outputModalities)
                assertEquals(listOf("text", "image"), model.inputModalities)
                assertEquals(false, model.supportsReasoning)
                val provider = OpenAIProvider("test", model, server.url("/").toString().trimEnd('/'))
                server.enqueue(MockResponse().setBody("""{"data":[]}"""))
                provider.generateImage("a cat", quality = "max")
                val request = server.takeRequest()
                assertEquals("/images/generations", request.path)
                val body = JSONObject(request.body.readUtf8())
                assertEquals(id, body.getString("model"))
                assertEquals("max", body.getString("quality"))
                assertEquals("1024x1024", body.getString("size"))
                assertEquals(1, body.getInt("n"))
                assertFalse(body.has("response_format"))
                server.enqueue(MockResponse().setBody("""{"data":[]}"""))
                provider.editImage("a blue cat", listOf(LLMMessage.ImagePart(byteArrayOf(1, 2, 3), "image/png")))
                val edit = server.takeRequest()
                assertEquals("/images/edits", edit.path)
                val editBody = edit.body.readUtf8()
                assertTrue(editBody.contains(id) && !editBody.contains("response_format"))
                assertTrue(editBody.contains("1024x1024"))
                assertTrue(editBody.contains("high"))
                server.enqueue(MockResponse().setBody("""{"data":[]}"""))
                provider.generateImage("a cat")
                val defaults = JSONObject(server.takeRequest().body.readUtf8())
                assertEquals("high", defaults.getString("quality"))
                assertEquals("1024x1024", defaults.getString("size"))
                assertEquals(1, defaults.getInt("n"))
                server.enqueue(MockResponse().setBody("""{"data":[]}"""))
                provider.generateImage("a cat", n = 2, size = "1536x1024", quality = "low")
                val overrides = JSONObject(server.takeRequest().body.readUtf8())
                assertEquals("1536x1024", overrides.getString("size"))
                assertEquals("low", overrides.getString("quality"))
                assertEquals(2, overrides.getInt("n"))
            }
        } finally { server.shutdown() }
    }

    @Test fun discoveredModelsRetainCapabilitiesWithoutModelsDevEntries() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"data":[{"id":"gpt-6-astra"},{"id":"gpt-image-2.5-flare"},{"id":"gpt-image-2.5-sunburst-2026-09-08"}]}"""))
            val models = OpenAIModelsApi.fetchModels("test", server.url("/").toString(), forceRefresh = true)
            assertEquals(efforts, models.first().reasoningEffortValues)
            assertEquals(true, models.first().supportsReasoning)
            for (model in models.drop(1)) {
                assertEquals(listOf("image"), model.outputModalities)
                assertEquals(listOf("text", "image"), model.inputModalities)
            }
        } finally { server.shutdown() }
    }

    @Test fun astraDefaultsToResponsesEndpoint() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "data: {\"type\":\"response.output_text.delta\",\"delta\":\"ok\"}\n\n" +
                "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[]}}\n\n"
            ))
            val provider = OpenAIProvider("test", LLMModel.gpt6Astra, server.url("/").toString().trimEnd('/'))
            assertEquals("ok", provider.sendMessage(messages, null, 1024).text)
            val request = server.takeRequest()
            assertEquals("/responses", request.path)
            val body = JSONObject(request.body.readUtf8())
            assertEquals("gpt-6-astra", body.getString("model"))
            assertEquals("low", body.getJSONObject("reasoning").getString("effort"))
        } finally { server.shutdown() }
    }

    @Test fun codexImage25SelectsExactToolModelAndPreservesReferences() {
        for (model in listOf(LLMModel.gptImage25Flare, LLMModel.gptImage25Sunburst)) {
            val provider = OpenAIProvider(oauthTokenProvider = { "test" }, model = model)
            val body = provider.buildCodexImageBody(messages, listOf(LLMMessage.ImagePart(byteArrayOf(1), "image/png")))
            assertEquals("gpt-6-astra", body.getString("model"))
            assertEquals(model.id, body.getJSONArray("tools").getJSONObject(0).getString("model"))
            assertEquals("input_image", body.getJSONArray("input").getJSONObject(0).getJSONArray("content").getJSONObject(1).getString("type"))
        }
        val old = OpenAIProvider(oauthTokenProvider = { "test" }, model = LLMModel("gpt-image-2", "Image 2", "OpenAI"))
            .buildCodexImageBody(messages)
        assertEquals("gpt-5.5", old.getString("model"))
        assertFalse(old.getJSONArray("tools").getJSONObject(0).has("model"))
    }

    @Test
    fun codexImageDoesNotRestoreLegacyImagesAfterAllStructuredImagesAreElided() {
        val message = LLMMessage(
            role = LLMMessage.Role.USER,
            content = "prompt",
            imageParts = listOf(LLMMessage.ImagePart(ByteArray(4), "image/jpeg")),
            contentParts = listOf(AgentContentPart.ImageData(ByteArray(4), "image/png")),
        )
        val budgeted = budgetProviderRequest(
            messages = listOf(message),
            imageParts = emptyList(),
            maxRequestBytes = 0L,
        )
        assertTrue(
            budgeted.messages.single().contentParts.none { it is AgentContentPart.ImageData },
        )
        val body = OpenAIProvider(
            oauthTokenProvider = { "test" },
            model = LLMModel.gptImage25Flare,
        ).buildCodexImageBody(budgeted.messages, budgeted.imageParts)

        assertEquals(0, codexInputImages(body).size)
    }

    @Test
    fun codexImageIgnoresLegacyImagesWhenStructuredContentIsTextOnly() {
        val budgeted = budgetProviderRequest(
            messages = listOf(
                LLMMessage(
                    role = LLMMessage.Role.USER,
                    content = "prompt",
                    imageParts = listOf(LLMMessage.ImagePart(byteArrayOf(1), "image/jpeg")),
                    contentParts = listOf(AgentContentPart.Text("prompt")),
                ),
            ),
            imageParts = emptyList(),
        )
        val body = OpenAIProvider(
            oauthTokenProvider = { "test" },
            model = LLMModel.gptImage25Flare,
        ).buildCodexImageBody(budgeted.messages, budgeted.imageParts)

        assertEquals(0, codexInputImages(body).size)
    }

    private fun codexInputImages(body: JSONObject): List<JSONObject> {
        val content = body.getJSONArray("input").getJSONObject(0).optJSONArray("content")
            ?: return emptyList()
        return (0 until content.length())
            .map { content.getJSONObject(it) }
            .filter { it.optString("type") == "input_image" }
    }
}
