package com.openminis.app.data.repository

import org.json.JSONArray
import org.json.JSONObject

/** Bounded transcript pages with a cursor that can resume inside a long message. */
internal object SessionHistoryPage {
    const val MAX_CHARS = 32_000
    const val MAX_ROWS = 50
    private const val CHUNK_CHARS = 1_000

    fun build(
        rows: List<MessagePageItem>,
        offset: Int,
        textOffset: Int,
        total: Int,
        maxChars: Int = MAX_CHARS,
    ): JSONObject {
        require(offset in 0..total && textOffset >= 0) { "Invalid history cursor" }
        require(maxChars in 2..MAX_CHARS) { "Invalid history text budget" }
        require(rows.isNotEmpty() || textOffset == 0) { "Message no longer exists at this history cursor" }
        val messages = JSONArray()
        var remaining = maxChars
        var nextOffset = offset
        var nextTextOffset = 0
        for ((index, row) in rows.withIndex()) {
            var start = if (index == 0) textOffset else 0
            require(start <= row.text.length) { "Message changed; read this offset again with text_offset 0" }
            require(start == 0 || start == row.text.length ||
                !Character.isHighSurrogate(row.text[start - 1]) || !Character.isLowSurrogate(row.text[start])) {
                "text_offset must not split a Unicode character"
            }
            nextOffset = offset + index
            if (row.text.isNotBlank() && start < row.text.length) {
                // Short JSON text fields remain line-readable after the tool result is offloaded.
                while (start < row.text.length && remaining > 0) {
                    var end = start + minOf(row.text.length - start, remaining, CHUNK_CHARS)
                    if (end > start && end < row.text.length &&
                        Character.isHighSurrogate(row.text[end - 1]) && Character.isLowSurrogate(row.text[end])
                    ) end--
                    if (end == start) break
                    messages.put(JSONObject()
                        .put("message_id", row.messageId)
                        .put("role", row.role)
                        .put("created_at", row.createdAt)
                        .put("offset", nextOffset)
                        .put("text_offset", start)
                        .put("text_length", row.text.length)
                        .put("text", row.text.substring(start, end))
                        .put("truncated", end < row.text.length))
                    remaining -= end - start
                    start = end
                }
                if (start < row.text.length) {
                    nextTextOffset = start
                    break
                }
            }
            nextOffset++
            if (remaining == 0) break
        }
        // No rows can remain at a cursor beyond the current database page.
        val hasMore = nextTextOffset > 0 || (rows.isNotEmpty() && nextOffset < total)
        return JSONObject()
            .put("offset", offset)
            .put("text_offset", textOffset)
            .put("total", total)
            .put("messages", messages)
            .put("count", messages.length())
            .put("has_more", hasMore)
            .put("next_offset", nextOffset)
            .put("next_text_offset", nextTextOffset)
    }
}
