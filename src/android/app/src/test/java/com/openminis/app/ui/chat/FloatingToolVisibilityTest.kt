package com.openminis.app.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingToolVisibilityTest {
    @Test fun `active and completed tool statuses all reserve a visible bar`() {
        for (status in ToolBlockStatus.entries) {
            assertTrue(status.name, isFloatingToolVisible(AssistantBlock("tool", "tool_use", toolStatus = status)))
        }
    }

    @Test fun `thinking and informational blocks never reserve tool space even with a status`() {
        for (kind in listOf("thinking", "info")) {
            for (status in ToolBlockStatus.entries) {
                assertFalse(isFloatingToolVisible(AssistantBlock("note", kind, toolStatus = status)))
            }
        }
    }

    @Test fun `an unstarted tool does not leave an empty bar`() {
        assertFalse(isFloatingToolVisible(AssistantBlock("tool", "tool_use", toolName = "shell_execute")))
    }

    @Test fun `legacy blocks carrying a tool status keep existing visibility semantics`() {
        assertTrue(isFloatingToolVisible(AssistantBlock("legacy", "text", toolStatus = ToolBlockStatus.SUCCESS)))
    }
}
