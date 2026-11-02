package com.openminis.app.ui.chat

/** The rows of the final text block; coordinates are resolved only when a tap arrives. */
internal fun usageTapTargets(messages: List<ChatMessage>, rows: List<FlatChatItem>): Map<String, UsageTapBandRow> {
    val endings = messages.filter { it.role == "assistant" }.associateBy { it.id }
    val candidates = rows.mapNotNull { row ->
        val id = when (row) {
            is FlatChatItem.AssistantMarkdownBlock -> row.messageId
            is FlatChatItem.AssistantText -> row.messageId
            is FlatChatItem.AssistantLegacyContent -> row.messageId
            else -> return@mapNotNull null
        }.substringBefore('#')
        val message = endings[id] ?: return@mapNotNull null
        val last = message.toolBlocks.lastOrNull()
        val eligible = when (row) {
            is FlatChatItem.AssistantMarkdownBlock -> last?.kind == "text" && last.content.isNotEmpty() && row.parentBlockId == last.id
            is FlatChatItem.AssistantText -> last?.kind == "text" && last.content.isNotEmpty() && row.block.id == last.id
            is FlatChatItem.AssistantLegacyContent -> message.toolBlocks.none { it.kind == "text" } && message.content.isNotEmpty()
            else -> false
        }
        if (eligible) row.key to id else null
    }
    val lastRows = candidates.groupBy({ it.second }, { it.first }).mapValues { it.value.last() }
    return candidates.associate { (key, id) -> key to UsageTapBandRow(id, lastRows[id] == key) }
}
