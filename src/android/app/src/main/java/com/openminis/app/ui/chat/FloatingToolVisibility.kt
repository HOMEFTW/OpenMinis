package com.openminis.app.ui.chat

/** Shared by the bar and ChatScreen's list/reserved-space gates; finished tools stay visible. */
internal fun isFloatingToolVisible(block: AssistantBlock): Boolean =
    block.toolStatus != null && block.kind != "thinking" && block.kind != "info"
