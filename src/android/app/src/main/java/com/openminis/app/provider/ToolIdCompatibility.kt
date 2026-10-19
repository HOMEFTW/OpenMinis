package com.openminis.app.provider

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import java.security.MessageDigest
import java.util.ArrayDeque

/** Translate only the outbound copy. In particular, signed replay metadata is never rewritten. */
internal object ToolIdCompatibility {
    enum class Format { CHAT, RESPONSES, ANTHROPIC, DEEPSEEK }

    fun messages(
        messages: List<LLMMessage>,
        format: Format,
        preservedIds: Set<String> = emptySet(),
    ): List<LLMMessage> {
        val calls = messages.flatMap { it.contentParts.filterIsInstance<AgentContentPart.ToolUse>() }
        if (calls.isEmpty()) return messages
        val bases = calls.map { base(it.id, format) }
        val callIds = Allocator(bases.map { it.substringBefore('|') }.toSet() + preservedIds, preservedIds)
        val itemIds = Allocator(bases.mapNotNull { it.substringAfter('|', "").takeIf(String::isNotEmpty) }.toSet())
        val pending = mutableMapOf<String, ArrayDeque<String>>()
        val answered = mutableMapOf<String, String>()
        val orphanIds = mutableMapOf<String, String>()
        return messages.map { message ->
            message.copy(contentParts = message.contentParts.map { part ->
                when (part) {
                    is AgentContentPart.ToolUse -> {
                        val id = if (part.id in preservedIds) part.id else {
                            val candidate = base(part.id, format)
                            val call = callIds.allocate(candidate.substringBefore('|'))
                            if (format == Format.RESPONSES) "$call|${itemIds.allocate(candidate.substringAfter('|'))}" else call
                        }
                        pending.getOrPut(part.id) { ArrayDeque() }.addLast(id)
                        part.copy(id = id)
                    }
                    is AgentContentPart.ToolResult -> {
                        val id = pending[part.id]?.pollFirst()?.also { answered[part.id] = it }
                            ?: answered[part.id]
                            ?: if (part.id in preservedIds) part.id else orphanIds.getOrPut(part.id) {
                                callIds.allocate("orphan_${hash(part.id).take(52)}", avoidReserved = true)
                            }
                        part.copy(id = id)
                    }
                    else -> part
                }
            })
        }
    }

    private val allowed = Regex("[A-Za-z0-9_-]{1,64}")

    private fun safe(id: String, prefix: String): String =
        if (allowed.matches(id)) id else prefix + hash(id).take(64 - prefix.length)

    private fun base(raw: String, format: Format): String {
        // Only Responses' actual composite encoding is split. An opaque id containing a pipe
        // must not lose its suffix and collide with a different originating call.
        val split = raw.indexOf('|')
        val composite = split >= 0 && raw.substring(split + 1).startsWith("fc_")
        val call = if (composite) raw.substring(0, split) else raw
        if (format != Format.RESPONSES) return safe(call, "call_")
        val callId = safe(call, "call_")
        val item = if (composite) raw.substring(split + 1) else "fc_syn_${hash(raw).take(40)}"
        return "$callId|${safe(item, "fc_")}"
    }

    private fun hash(id: String): String = MessageDigest.getInstance("SHA-256")
        .digest(id.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private class Allocator(private val reserved: Set<String>, protected: Set<String> = emptySet()) {
        private val used = protected.toMutableSet()
        fun allocate(base: String, avoidReserved: Boolean = false): String {
            var candidate = base
            var number = 2
            // A generated suffix must not consume a later call's original id.
            while (candidate in used || ((avoidReserved || candidate != base) && candidate in reserved)) {
                val suffix = "-${number++}"
                candidate = base.take(64 - suffix.length) + suffix
            }
            used.add(candidate)
            return candidate
        }
    }
}
