package com.openminis.app.provider.openai

import org.json.JSONObject

internal data class OpenAIModelLimits(val context: Int?, val output: Int?) {
    companion object {
        fun read(model: JSONObject): OpenAIModelLimits {
            fun positive(value: Any?): Int? = value?.toString()?.toLongOrNull()
                ?.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt()
            fun first(vararg values: Any?): Int? = values.firstNotNullOfOrNull(::positive)
            val limits = model.optJSONObject("limits")
            val limit = model.optJSONObject("limit")
            val top = model.optJSONObject("top_provider")
            return OpenAIModelLimits(
                context = first(model.opt("context_length"), model.opt("context_window"),
                    model.opt("max_context_length"), model.opt("max_model_len"),
                    top?.opt("context_length"), limit?.opt("context"), limits?.opt("context_length")),
                output = first(model.opt("max_output_tokens"), model.opt("max_completion_tokens"),
                    top?.opt("max_completion_tokens"), limit?.opt("output"), limits?.opt("max_output_tokens"),
                    model.opt("max_tokens")),
            )
        }
    }
}
