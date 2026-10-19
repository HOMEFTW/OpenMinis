package com.openminis.app.ui.chat

import com.openminis.app.logging.LogRedactor

/** The producer supplies the complete chronological failure/switch trail in content. */
internal fun isModelFallbackNotice(block: AssistantBlock): Boolean = block.kind == "info" &&
    (block.toolName == "model_fallback" || block.id.startsWith("fallback_info_"))

internal fun modelFallbackNoticeLines(block: AssistantBlock): List<String> =
    LogRedactor.redact(block.content).lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
