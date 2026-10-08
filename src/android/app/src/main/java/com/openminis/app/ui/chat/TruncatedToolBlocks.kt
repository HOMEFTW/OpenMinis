package com.openminis.app.ui.chat

/** Prune previews before building durable turn parts; never leave phantom tool calls in history. */
internal fun discardTruncatedToolBlocks(blocks: MutableList<AssistantBlock>, turnStartIndex: Int) {
    for (index in blocks.lastIndex downTo turnStartIndex.coerceAtLeast(0)) {
        if (blocks[index].kind == "tool_use") blocks.removeAt(index)
    }
}
