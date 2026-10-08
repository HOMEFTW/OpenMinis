package com.openminis.app.ui.chat

import com.openminis.app.data.model.LLMError

/**
 * Reactive hint for a provider 400 on a request that carried images.
 * This does not gate sending, select modalities or change image byte budgets.
 */
internal object ImageInputPreflight {
    fun isLikelyImageRejection(error: Throwable, sentImageCount: Int): Boolean {
        if (sentImageCount <= 0) return false
        val providerError = error as? LLMError.ProviderError ?: return false
        if (providerError.httpStatus != 400) return false
        val detail = providerError.detail.lowercase()
        return NOT_IMAGE_RELATED.none { it in detail }
    }

    // Tool pairing and prompt-size failures need their own remedy, even when
    // the same request happened to include images.
    private val NOT_IMAGE_RELATED = listOf(
        "tool output", "tool call", "tool_call", "tool result", "tool_result", "tool use", "tool_use",
        "function call", "function_call",
        "context length", "context_length", "context window", "context_window",
        "context limit", "context_limit", "context size", "context_size", "maximum context",
        "prompt is too long", "prompt too long", "input too long", "too many tokens",
        "token limit", "token_limit", "tokens exceed",
        "上下文长度", "上下文窗口", "上下文超", "超出上下文", "输入过长", "提示词过长",
        "工具调用", "工具结果",
    )
}
