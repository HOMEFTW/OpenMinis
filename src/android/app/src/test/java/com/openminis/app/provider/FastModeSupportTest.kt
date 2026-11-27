package com.openminis.app.provider

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ProviderType
import org.junit.Assert.*
import org.junit.Test

class FastModeSupportTest {
    @Test fun gptControlRemainsVisibleWhenTheRouteOrCapabilitiesAreUnavailable() {
        for (model in listOf(LLMModel.gpt6Astra, LLMModel.gpt61Sol)) {
            val unavailableRoute = FastModeSupport.isAvailable(ProviderType.anthropic, model)
            assertFalse(unavailableRoute)
            assertTrue(FastModeSupport.shouldShowControl(model.id))

            val unavailableOutput = FastModeSupport.isAvailable(ProviderType.openAI,
                model.copy(outputModalities = emptyList()))
            assertFalse(unavailableOutput)
            assertTrue(FastModeSupport.shouldShowControl(model.id))
        }
    }

    @Test fun unrelatedOrUnknownModelsNeverGainAFastControl() {
        for (id in listOf(null, "grok-4", "xai/grok-4", "deepseek-flash", "claude-sonnet-5", "gpt-image-2.5-sunburst")) {
            assertFalse(FastModeSupport.shouldShowControl(id))
        }
        assertTrue(FastModeSupport.shouldShowControl("OPENAI/GPT-6.1-SOL"))
    }

    @Test fun suffixTextModalitiesRemainEligibleForGptFast() {
        for (output in listOf("text", "Text", "text_output", "TEXT_OUTPUT")) {
            val model = LLMModel.gpt61Sol.copy(outputModalities = listOf(output))
            assertTrue(output, FastModeSupport.isAvailable(ProviderType.openAI, model))
            assertTrue(output, FastModeSupport.isAvailable(ProviderType.openAIResponses, model))
        }
    }

    @Test fun gptChatAndDedicatedResponsesRoutesExposeTheSameFastOption() {
        for (type in listOf(ProviderType.openAI, ProviderType.openAIResponses)) {
            assertTrue(FastModeSupport.isAvailable(type, LLMModel.gpt61Sol))
            assertTrue(FastModeSupport.isAvailable(type, LLMModel.gpt6Luna))
            assertTrue(FastModeSupport.isAvailable(type,
                LLMModel("gpt-4.1", "GPT-4.1", "OpenAI", supportsReasoning = false)))
        }
    }

    @Test fun otherProtocolsAndNonGptModelsDoNotOfferGptFast() {
        for (type in listOf(ProviderType.openRouter, ProviderType.anthropic, ProviderType.gemini,
            ProviderType.githubCopilot, ProviderType.kimiCode, ProviderType.deepSeek, ProviderType.xAI)) {
            assertFalse(type.name, FastModeSupport.isAvailable(type, LLMModel.gpt61Sol))
        }
        assertFalse(FastModeSupport.isAvailable(ProviderType.openAI,
            LLMModel("gpt-6-sol", "Image-only override", "OpenAI", outputModalities = listOf("image"))))
        assertFalse(FastModeSupport.isAvailable(ProviderType.openAI,
            LLMModel("custom-gpt-6-sol", "Lookalike", "Custom")))
    }

    @Test fun grokIsUnavailableOnNativeAndOpenAiCompatibleRoutes() {
        for (type in listOf(ProviderType.xAI, ProviderType.openAI, ProviderType.openAIResponses)) {
            for (id in listOf("grok-4", "xai/grok-4", "openai/grok-4")) {
                assertFalse("$type / $id", FastModeSupport.isAvailable(type, LLMModel(id, "Grok 4", "xai")))
            }
        }
    }
}
