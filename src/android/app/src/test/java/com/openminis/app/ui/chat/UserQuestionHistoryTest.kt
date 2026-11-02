package com.openminis.app.ui.chat

import org.junit.Assert.*
import org.junit.Test

class UserQuestionHistoryTest {
    private val block = AssistantBlock(id = "ask", kind = "tool_use", toolName = "user_ask",
        toolArgs = """{"questions":[{"id":"q","question":"Choose"}]}""",
        content = """{"answers":{"q":"Answer","foreign":"Ignore"}}""", toolStatus = ToolBlockStatus.SUCCESS)

    @Test fun historyUsesOnlyAnswersForThisQuestionAndNeverReopensPendingCards() {
        assertEquals(mapOf("q" to "Answer"), UserQuestionHistory.parse(block)!!.answers)
        assertNull(UserQuestionHistory.parse(block.copy(toolStatus = ToolBlockStatus.RUNNING)))
        assertTrue(UserQuestionHistory.parse(block.copy(toolStatus = ToolBlockStatus.CANCELLED, content = "Error: cancelled"))!!.answers.isEmpty())
        assertNull(UserQuestionHistory.parse(block.copy(content = """{"results":[{"status":"deferred"}]}""")))
        assertNull(UserQuestionHistory.parse(block.copy(toolName = "file_read")))
        assertNull(UserQuestionHistory.parse(block.copy(content = "x".repeat(100_000))))
    }
}
