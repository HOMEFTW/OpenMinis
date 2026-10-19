package com.openminis.app.ui.chat

/** IDs belong to one stream attempt; start, delta and completion share the same allocation. */
internal class ToolStreamAttemptIds {
    private val used = mutableSetOf<String>()
    private val starts = mutableMapOf<String, MutableList<String>>()
    private val completed = mutableMapOf<String, Int>()

    private fun allocate(raw: String): String {
        // Responses carries call_id + item_id in this internal ID. Wire limits
        // belong to the provider encoder, where the two identifiers are split.
        val base = raw.ifBlank { "call" }
        var result = base
        var occurrence = 2
        while (!used.add(result)) {
            val suffix = "-${occurrence++}"
            result = base + suffix
        }
        return result
    }

    fun start(raw: String): String = allocate(raw).also {
        starts.getOrPut(raw) { mutableListOf() }.add(it)
    }

    fun input(raw: String): String = starts[raw]?.lastOrNull() ?: raw

    fun complete(raw: String): String {
        val index = completed[raw] ?: 0
        completed[raw] = index + 1
        return starts[raw]?.getOrNull(index) ?: start(raw)
    }

    fun reset() {
        used.clear()
        starts.clear()
        completed.clear()
    }
}

/** Discard only the unsuccessful attempt; prior accepted turns stay intact. */
internal fun rollbackToolStreamAttempt(blocks: MutableList<AssistantBlock>, turnStart: Int) {
    blocks.subList(turnStart.coerceIn(0, blocks.size), blocks.size).clear()
}

/** Inserting the banner changes the boundary of the next attempt. */
internal fun replaceFallbackBanner(blocks: MutableList<AssistantBlock>, banner: AssistantBlock): Int {
    blocks.removeAll(::isModelFallbackNotice)
    blocks.add(0, banner)
    return blocks.size
}

internal fun toolTurnMustDiscardCalls(finishReason: String?, providerDiscard: Boolean): Boolean =
    providerDiscard || finishReason.isNullOrBlank() || finishReason in setOf("length", "max_tokens", "max_output_tokens")

/** JSONObject.NULL differs between Android and the desktop org.json implementation. */
internal fun org.json.JSONObject.optionalNonBlankString(key: String): String? =
    (opt(key) as? String)?.takeIf { it.isNotBlank() && it != "null" }
