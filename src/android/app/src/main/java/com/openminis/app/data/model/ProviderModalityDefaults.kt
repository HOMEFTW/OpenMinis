package com.openminis.app.data.model

private val visionProviders = setOf("Anthropic", "OpenAI", "OpenRouter", "xAI", "xAI (Grok)", "Kimi", "Kimi Code", "GitHub Copilot")
private val fullInputProviders = setOf("Google", "Google Gemini")

/** Explicit model lists (including empty lists) always override provider defaults. */
val LLMModel.effectiveInputModalities: List<String>?
    get() = inputModalities?.map { it.normalizeModalityName() }?.distinct() ?: when (provider) {
        in visionProviders -> listOf("text", "image", "pdf")
        in fullInputProviders -> listOf("text", "image", "pdf", "audio", "video")
        else -> null
    }

val LLMModel.effectiveOutputModalities: List<String>?
    get() = outputModalities?.map { it.normalizeModalityName() }?.distinct() ?:
        if (provider in visionProviders || provider in fullInputProviders) listOf("text") else null
