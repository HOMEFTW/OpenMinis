package com.openminis.app.provider

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.model.normalizeModalityName

/** Shared eligibility for the UI and request builders; provider support still determines the actual tier. */
internal object FastModeSupport {
    private fun isGptModelId(id: String): Boolean =
        Regex("^gpt-\\d", RegexOption.IGNORE_CASE).containsMatchIn(id.substringAfterLast('/'))

    fun supportsGptRoute(type: ProviderType): Boolean =
        type == ProviderType.openAI || type == ProviderType.openAIResponses

    fun isGptTextModel(model: LLMModel): Boolean =
        isGptModelId(model.id) &&
            (model.outputModalities?.any { it.normalizeModalityName() == "text" } != false)

    /** Keep GPT controls discoverable even when the current route cannot request Fast. */
    fun shouldShowControl(modelId: String?, available: Boolean): Boolean =
        available || (modelId?.let(::isGptModelId) == true)

    fun isAvailable(type: ProviderType, model: LLMModel): Boolean =
        type == ProviderType.xAI || (supportsGptRoute(type) && isGptTextModel(model))
}
