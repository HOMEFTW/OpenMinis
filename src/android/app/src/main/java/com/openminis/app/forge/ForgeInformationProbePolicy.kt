package com.openminis.app.forge

import org.json.JSONObject

/** Existing OpenAI providers merge arbitrary entry body params LAST. Permit only numeric sampling
 * and reasoning knobs, so an entry cannot reintroduce tools, history or a previous response ID.
 * Entries are never edited. Unsupported overrides require manual samples instead of a paid request.
 */
internal object ForgeInformationProbePolicy {
    private val parameters = setOf("temperature", "top_p", "seed", "reasoning", "reasoning_effort", "thinking",
        "enable_thinking", "thinking_budget", "frequency_penalty", "presence_penalty", "repetition_penalty",
        "min_p", "top_k", "verbosity", "stream_options", "service_tier")

    fun unavailableReason(textOutput: Boolean, imageToolRoute: Boolean, extraBody: JSONObject?): String? {
        if (!textOutput || imageToolRoute) return "当前模型不能用于无工具数字采样，请使用文字模型或粘贴已有样本"
        if (extraBody != null && extraBody.keys().asSequence().any { it !in parameters })
            return "当前自定义请求体含数字采样以外的字段，无法保证隔离；可粘贴已有数字样本分析"
        return null
    }
}
