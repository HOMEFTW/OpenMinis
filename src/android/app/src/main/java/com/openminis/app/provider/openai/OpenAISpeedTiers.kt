package com.openminis.app.provider.openai

import com.openminis.app.data.model.LLMModel
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Provider capability metadata, matching build34's models-list parser. */
internal object OpenAISpeedTiers {
    fun read(obj: JSONObject): List<String>? {
        val tiers = obj.optJSONArray("service_tiers")
        val additional = obj.optJSONArray("additional_speed_tiers")
        if (tiers == null && additional == null) return null
        val result = linkedSetOf<String>()
        tiers?.forEachValue { raw ->
            val value = if (raw is JSONObject) raw.opt("id") as? String else raw as? String
            value?.normalized()?.let(result::add)
        }
        additional?.forEachValue { raw -> (raw as? String)?.normalized()?.let(result::add) }
        return result.toList()
    }

    /** The provider-export format uses the same property name as serialized LLMModel. */
    fun readSaved(obj: JSONObject): List<String>? = obj.optJSONArray("serviceTiers")?.let { values ->
        val result = linkedSetOf<String>()
        values.forEachValue { raw -> (raw as? String)?.normalized()?.let(result::add) }
        result.toList()
    }

    fun supportsUltrafast(model: LLMModel): Boolean =
        model.id == "gpt-6-astra" ||
            model.supportedServiceTiers.orEmpty().any { it.normalized() == "ultrafast" }

    private fun String.normalized(): String? = trim().lowercase(Locale.ROOT).takeIf { it.isNotEmpty() }

    private inline fun JSONArray.forEachValue(block: (Any?) -> Unit) {
        for (index in 0 until length()) block(opt(index))
    }
}
