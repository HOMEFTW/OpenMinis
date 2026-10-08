package com.openminis.app.ui.chat

import com.openminis.app.data.db.MessageEntity

/**
 * Resolve the persisted user row behind a bubble by id/sourceDbIds.
 * Unlinked placeholders use the same visible-row rules as the chat loader.
 */
internal object BubbleRowLocator {

    enum class Via { LINKED, ORDINAL, NONE }

    class Located(val row: MessageEntity?, val via: Via)

    /** The persisted user row behind the user bubble at [index] in [messages]. */
    fun locateUserRow(messages: List<ChatMessage>, index: Int, rows: List<MessageEntity>): Located {
        val bubble = messages[index]
        val ids = HashSet<String>(bubble.sourceDbIds.size + 1).apply {
            add(bubble.id)
            addAll(bubble.sourceDbIds)
        }
        rows.firstOrNull { it.role == "user" && it.id in ids }?.let { return Located(it, Via.LINKED) }

        // Unlinked: the ordinal rule, counting the same rows the chat renders.
        val ordinal = messages.subList(0, index).count { it.role == "user" }
        var n = 0
        for (row in rows) {
            if (row.role != "user" || !isVisibleUserRow(row.partsJson)) continue
            if (n == ordinal) return Located(row, Via.ORDINAL)
            n++
        }
        return Located(null, Via.NONE)
    }

    /**
     * [T-android-bubble-anchor-drain] The bubbles that share the target's
     * persisted row: an end-of-run queue drain folds several queued prompts
     * into ONE user row but keeps one bubble per prompt, each linked to that
     * row through [ChatMessage.sourceDbIds]. Port of iOS 6c0de68f5's merged
     * group.
     *
     * The DB cut is the same for every member (they share the row), so the
     * chat must be cut as a whole group too, or it would show what the model
     * no longer has (or hide what it still has):
     *  - delete / edit remove from the group's FIRST bubble;
     *  - retry keeps through its LAST bubble.
     *
     * A bubble with no [ChatMessage.sourceDbIds] (live send, mid-loop inject,
     * steer, unpersisted placeholder) is a group of one.
     */
    fun groupSpan(messages: List<ChatMessage>, index: Int): IntRange {
        val target = messages[index]
        if (target.role != "user" || target.sourceDbIds.isEmpty()) return index..index
        val rows = target.sourceDbIds.toSet()
        var first = index
        var last = index
        messages.forEachIndexed { i, m ->
            if (m.role == "user" && (m.id in rows || m.sourceDbIds.any { it in rows })) {
                if (i < first) first = i
                if (i > last) last = i
            }
        }
        return first..last
    }

    /**
     * Where to cut so the located bubble is kept ([keepBubble], retry) or
     * removed with everything after it (delete / edit): a `sort_order` for
     * `ChatDao.deleteMessagesAfter` (`sort_order >= cutoff`), or -1 when no
     * row was found, which callers treat as "leave the DB alone".
     */
    fun cutoffFor(located: Located, keepBubble: Boolean): Int {
        val row = located.row ?: return -1
        return if (keepBubble) row.sortOrder + 1 else row.sortOrder
    }

    /**
     * Whether a user row renders a bubble, matching loadSessionMessages: it
     * has text other than a synthetic `<system-reminder>` or the
     * `<user-attached-files>` inventory, or it carries media. Tool-result-only
     * rows and the stop-continue reminder row do not.
     */
    fun isVisibleUserRow(partsJson: String): Boolean = try {
        val arr = org.json.JSONArray(partsJson)
        (0 until arr.length()).any { i ->
            val o = arr.getJSONObject(i)
            when (o.optString("type")) {
                "text" -> {
                    val v = o.optString("value", "")
                    !v.trimStart().startsWith("<system-reminder>") && stripAttachedFiles(v).isNotBlank()
                }
                "mediaRef", "image" -> true
                else -> false
            }
        }
    } catch (_: Exception) {
        true
    }

    private fun stripAttachedFiles(text: String): String {
        val start = text.indexOf("<user-attached-files>")
        if (start < 0) return text
        val endTag = "</user-attached-files>"
        val end = text.indexOf(endTag, start)
        return if (end >= 0) text.substring(0, start) + text.substring(end + endTag.length) else text.substring(0, start)
    }
}
