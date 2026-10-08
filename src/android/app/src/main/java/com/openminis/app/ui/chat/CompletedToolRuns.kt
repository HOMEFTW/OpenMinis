package com.openminis.app.ui.chat

internal data class ToolFoldCandidate(val messageId: String, val canFold: Boolean)

/** Only adjacent completed tools in the same assistant turn share a group. */
internal fun completedToolRunRanges(items: List<ToolFoldCandidate>): List<IntRange> {
    val ranges = mutableListOf<IntRange>()
    var start = 0
    while (start < items.size) {
        if (!items[start].canFold) {
            start++
            continue
        }
        var end = start + 1
        while (end < items.size && items[end].canFold &&
            items[end].messageId == items[start].messageId
        ) end++
        if (end - start >= 3) ranges += start until end
        start = end
    }
    return ranges
}
