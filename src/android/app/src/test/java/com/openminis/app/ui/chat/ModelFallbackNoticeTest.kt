package com.openminis.app.ui.chat

import org.junit.Assert.*
import org.junit.Test

class ModelFallbackNoticeTest {
    @Test fun onlyModelFallbackMarkersUseTheOrangeCard() {
        assertTrue(isModelFallbackNotice(AssistantBlock("fallback_info_1", "info")))
        assertTrue(isModelFallbackNotice(AssistantBlock("new", "info", toolName = "model_fallback")))
        assertFalse(isModelFallbackNotice(AssistantBlock("compact", "info", toolName = "compact")))
        assertFalse(isModelFallbackNotice(AssistantBlock("fallback_info_1", "text")))
    }

    @Test fun keepsEveryFailureAndSwitchInOrderIncludingRepeatedAttempts() {
        val lines = listOf("A: HTTP 503", "Switched A → B", "B: rate limited", "Switched B → A", "A: HTTP 503")
        assertEquals(lines, modelFallbackNoticeLines(AssistantBlock("fallback_info_1", "info", content = lines.joinToString("\n"))))
    }

    @Test fun removesCredentialValuesBeforeRendering() {
        val block = AssistantBlock("fallback_info_1", "info", content = "A: HTTP 401\nAuthorization: Bearer sk-private\nSwitched to B")
        val text = modelFallbackNoticeLines(block).joinToString("\n")
        assertFalse(text.contains("sk-private"))
        assertTrue(text.contains("HTTP 401"))
        assertTrue(text.contains("Switched to B"))
    }
}
