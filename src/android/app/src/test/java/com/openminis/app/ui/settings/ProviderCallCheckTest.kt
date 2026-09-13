package com.openminis.app.ui.settings

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMResponse
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.LLMProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderCallCheckTest {
    @Test
    fun `textCallCheckEntries excludes hidden and image-only models`() {
        val visible = entry("visible")
        val hidden = entry("hidden", isHidden = true)
        val imageOnly = entry("image", outputModalities = listOf("image"))
        val textAndImage = entry("mixed", outputModalities = listOf("text", "image"))

        assertEquals(
            listOf("visible", "mixed"),
            textCallCheckEntries(listOf(visible, hidden, imageOnly, textAndImage))
                .map { it.model.id },
        )
    }

    @Test
    fun `textCallCheckEntries keeps null output modality as text`() {
        val explicitText = entry("explicit", outputModalities = listOf("text"))
        val emptyOutput = entry("empty", outputModalities = emptyList())
        val nullOutput = entry("null", outputModalities = null)

        assertEquals(
            listOf("explicit", "empty", "null"),
            textCallCheckEntries(listOf(explicitText, emptyOutput, nullOutput))
                .map { it.model.id },
        )
    }

    @Test
    fun `runProviderCallCheck uses selected model factory and configured key`() = runBlocking {
        val instance = instance()
        val entry = entry("selected-model")
        val fake = RecordingProvider(
            response = LLMResponse("  hello back  ", "stop", null),
        )
        var seenInstance: ProviderInstance? = null
        var seenKey: String? = null
        var seenModel: LLMModel? = null
        var now = 100L
        val result = runProviderCallCheck(
            instance = instance,
            entry = entry,
            apiKey = "configured-key",
            context = null,
            createProvider = { providerInstance, key, model, _ ->
                seenInstance = providerInstance
                seenKey = key
                seenModel = model
                fake
            },
            nowMs = {
                val value = now
                now += 45L
                value
            },
        )

        assertEquals(ProviderCallCheckResult.Success("hello back", 45L), result)
        assertEquals(instance, seenInstance)
        assertEquals("configured-key", seenKey)
        assertEquals("selected-model", seenModel?.id)
        assertEquals("Reply OK in one short sentence.", fake.lastMessage?.content)
        assertEquals(1024, fake.lastMaxTokens)
        assertEquals(ThinkingLevel.OFF, fake.lastThinkingLevel)
    }

    @Test
    fun `runProviderCallCheck passes an empty key for a keyless compatible endpoint`() = runBlocking {
        val fake = RecordingProvider(LLMResponse("ok", "stop", null))
        var seenKey: String? = null
        val result = runProviderCallCheck(
            instance = instance(customBaseURL = "http://localhost:11434"),
            entry = entry("local-model"),
            apiKey = "",
            context = null,
            createProvider = { _, key, _, _ ->
                seenKey = key
                fake
            },
            nowMs = { 10L },
        )

        assertEquals(ProviderCallCheckResult.Success("ok", 0L), result)
        assertEquals("", seenKey)
    }

    @Test
    fun `runProviderCallCheck treats an empty response as failure`() = runBlocking {
        val result = runProviderCallCheck(
            instance = instance(),
            entry = entry("selected-model"),
            apiKey = "key",
            context = null,
            createProvider = { _, _, _, _ -> RecordingProvider(LLMResponse("  \n  ", "stop", null)) },
            nowMs = { 10L },
        )

        assertEquals(
            ProviderCallCheckResult.Failure("Provider returned an empty response.", 0L),
            result,
        )
    }

    @Test
    fun `runProviderCallCheck prevents factory call when key is missing`() = runBlocking {
        var factoryCalled = false
        val result = runProviderCallCheck(
            instance = instance(),
            entry = entry("selected-model"),
            apiKey = null,
            context = null,
            createProvider = { _, _, _, _ ->
                factoryCalled = true
                error("factory must not be called")
            },
            nowMs = { 10L },
        )

        assertTrue(result is ProviderCallCheckResult.Failure)
        assertEquals("No API key configured for this provider.", (result as ProviderCallCheckResult.Failure).message)
        assertTrue(!factoryCalled)
    }

    @Test
    fun `controller rejects a second active request`() {
        val controller = ProviderCallCheckController()
        val first = controller.begin("first")

        assertNotNull(first)
        assertEquals(first, controller.activeToken)
        assertNull(controller.begin("second"))

        val completed = controller.complete(
            first!!,
            ProviderCallCheckResult.Success("ok", 1L),
        )
        assertEquals("first", completed?.modelId)
        assertNull(controller.activeToken)
    }

    @Test
    fun `controller ignores late completion after cancel`() {
        val controller = ProviderCallCheckController()
        val token = controller.begin("first")!!

        assertTrue(controller.cancel(token))
        assertNull(controller.complete(token, ProviderCallCheckResult.Success("late", 1L)))
        assertNull(controller.activeToken)
    }

    @Test
    fun `runProviderCallCheck propagates cancellation`() {
        val expected = CancellationException("cancelled")
        val provider = RecordingProvider(throwOnCall = expected)

        try {
            runBlocking {
                runProviderCallCheck(
                    instance = instance(),
                    entry = entry("selected-model"),
                    apiKey = "key",
                    context = null,
                    createProvider = { _, _, _, _ -> provider },
                    nowMs = { 10L },
                )
            }
        } catch (actual: CancellationException) {
            // Coroutine stack-trace recovery may copy the exception instance.
            assertEquals(expected.message, actual.message)
            return
        }
        throw AssertionError("Expected CancellationException")
    }

    private fun instance(customBaseURL: String? = null) = ProviderInstance(
        id = "instance",
        label = "Test provider",
        providerType = ProviderType.openAI,
        credentialType = ProviderCredential.apiKey,
        customBaseURL = customBaseURL,
    )

    private fun entry(
        id: String,
        outputModalities: List<String>? = null,
        isHidden: Boolean = false,
    ) = ModelEntry(
        providerInstanceId = "instance",
        baseModel = LLMModel(
            id = id,
            displayName = id,
            provider = "Test",
            outputModalities = outputModalities,
        ),
        isHidden = isHidden,
    )

    private class RecordingProvider(
        private val response: LLMResponse = LLMResponse("ok", "stop", null),
        private val throwOnCall: CancellationException? = null,
    ) : LLMProvider {
        override val name: String = "Test"
        override var model: LLMModel = LLMModel.gpt4oMini
        var lastMessage: LLMMessage? = null
        var lastMaxTokens: Int? = null
        var lastThinkingLevel: ThinkingLevel? = null

        override suspend fun sendMessageClamped(
            messages: List<LLMMessage>,
            systemPrompt: String?,
            maxTokens: Int,
            temperature: Double?,
            imageParts: List<LLMMessage.ImagePart>,
            tools: List<AgentToolDefinition>,
            thinkingLevel: ThinkingLevel,
        ): LLMResponse {
            throwOnCall?.let { throw it }
            lastMessage = messages.singleOrNull()
            lastMaxTokens = maxTokens
            lastThinkingLevel = thinkingLevel
            return response
        }

        override fun streamMessageClamped(
            messages: List<LLMMessage>,
            systemPrompt: String?,
            maxTokens: Int,
            temperature: Double?,
            imageParts: List<LLMMessage.ImagePart>,
            tools: List<AgentToolDefinition>,
            thinkingLevel: ThinkingLevel,
        ): Flow<LLMStreamChunk> = emptyFlow()
    }
}
