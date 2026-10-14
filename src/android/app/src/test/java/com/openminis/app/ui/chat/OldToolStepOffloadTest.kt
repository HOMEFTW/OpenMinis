package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class OldToolStepOffloadTest {
    private fun step(id: String): List<LLMMessage> = listOf(
        LLMMessage(LLMMessage.Role.ASSISTANT, "working on $id", dbMessageId = "assistant-$id",
            reasoningContent = "old reasoning", contentParts = listOf(
                AgentContentPart.Text("explanation ".repeat(100)),
                AgentContentPart.ToolUse(id, "file_read", JSONObject().put("path", "/var/minis/$id.txt")),
            )),
        LLMMessage(LLMMessage.Role.USER, "", dbMessageId = "result-$id", contentParts = listOf(
            AgentContentPart.ToolResult(id, "file_read", "result 中文🙂 ".repeat(500)),
        )),
    )

    private val recent = List(4) { LLMMessage(LLMMessage.Role.ASSISTANT, "recent-$it", reasoningContent = "recent thinking") }
    private fun count(message: LLMMessage): Int = message.content.length + message.reasoningContent.orEmpty().length +
        message.contentParts.sumOf { part -> when (part) {
            is AgentContentPart.Text -> part.text.length
            is AgentContentPart.ToolUse -> part.input.toString().length
            is AgentContentPart.ToolResult -> part.content.length
            is AgentContentPart.ImageData -> part.data.size
        } }

    @Test fun oldestCompletedStepIsReplacedAsAPairWithReadableFullContentAndStableRowIds() {
        val old = step("one")
        val history = old + step("two") + recent
        var saved = ""
        val collapsed = offloadOldToolSteps(history, 1, ::count) { saved = it; "/var/minis/offloads/tools/step.txt" }
        assertTrue(collapsed[0].content.contains("file_read"))
        assertTrue(collapsed[0].content.contains("/var/minis/offloads/tools/step.txt"))
        assertEquals("", collapsed[0].reasoningContent)
        assertEquals("one", collapsed[0].contentParts.filterIsInstance<AgentContentPart.ToolUse>().single().id)
        assertEquals("one", (collapsed[1].contentParts.single() as AgentContentPart.ToolResult).id)
        assertEquals("", collapsed[1].content)
        assertEquals(history.map { it.role to it.dbMessageId }, collapsed.map { it.role to it.dbMessageId })
        assertEquals(history.drop(2), collapsed.drop(2))
        assertEquals(old, history.take(2)) // Original transcript was not changed.
        val messages = JSONObject(saved).getJSONArray("messages")
        assertEquals("old reasoning", messages.getJSONObject(0).getString("reasoning_content"))
        assertEquals("/var/minis/one.txt", messages.getJSONObject(0).getJSONArray("parts").getJSONObject(1).getJSONObject("input").getString("path"))
        assertEquals((old[1].contentParts.single() as AgentContentPart.ToolResult).content,
            messages.getJSONObject(1).getJSONArray("parts").getJSONObject(0).getString("content"))
    }

    @Test fun parallelCallsAreOffloadedTogetherAndErrorsAndSavedImageReferencesRemainReadable() {
        val old = step("one")
        val use = old[0].copy(contentParts = old[0].contentParts + AgentContentPart.ToolUse("two", "write", JSONObject(), isOffloadedArgument = true))
        val result = old[1].copy(contentParts = old[1].contentParts + AgentContentPart.ToolResult(
            "two", "write", "failed", isError = true, imageMimeType = "image/png", imageLinuxPath = "/var/minis/saved.png",
        ))
        var snapshot = ""
        val collapsed = offloadOldToolSteps(listOf(use, result) + recent, 1, ::count) { snapshot = it; "/saved" }
        assertTrue((collapsed[1].contentParts.last() as AgentContentPart.ToolResult).content.contains("reported an error"))
        val messages = JSONObject(snapshot).getJSONArray("messages")
        assertTrue(messages.getJSONObject(0).getJSONArray("parts").getJSONObject(2).getBoolean("is_offloaded_argument"))
        val savedResult = messages.getJSONObject(1).getJSONArray("parts").getJSONObject(1)
        assertTrue(savedResult.getBoolean("is_error"))
        assertEquals("/var/minis/saved.png", savedResult.getString("image_linux_path"))
    }

    @Test fun incompleteDuplicateMixedHumanAndSignedStepsStayUntouched() {
        val old = step("one")
        val use = old[0].contentParts.filterIsInstance<AgentContentPart.ToolUse>().single()
        val result = old[1].contentParts.single() as AgentContentPart.ToolResult
        val protected = listOf(
            listOf(old[0], old[1].copy(contentParts = listOf(result.copy(id = "different")))),
            listOf(old[0].copy(contentParts = listOf(use, use)), old[1]),
            listOf(old[0], old[1].copy(contentParts = listOf(result, result))),
            listOf(old[0], old[1].copy(content = "new human instruction")),
            listOf(old[0], old[1].copy(contentParts = listOf(result, AgentContentPart.Text("new human instruction")))),
            listOf(old[0].copy(deepSeekReplay = "opaque replay"), old[1]),
            listOf(old[0].copy(anthropicReplay = "opaque replay"), old[1]),
            listOf(old[0].copy(contentParts = listOf(use.copy(thoughtSignature = "signed"))), old[1]),
        )
        for (pair in protected) {
            val history = pair + recent
            assertEquals(history, offloadOldToolSteps(history, Int.MAX_VALUE, ::count) { fail("Protected step must not be written"); null })
        }
    }

    @Test fun inlineImagesAndAudioStayInTheirOriginalExchange() {
        val old = step("one")
        val result = old[1].contentParts.single() as AgentContentPart.ToolResult
        val variants = listOf(
            listOf(old[0].copy(imageParts = listOf(LLMMessage.ImagePart(byteArrayOf(1), "image/png"))), old[1]),
            listOf(old[0].copy(audioParts = listOf(LLMMessage.AudioPart("wav", "AA=="))), old[1]),
            listOf(old[0], old[1].copy(contentParts = listOf(result.copy(imageData = byteArrayOf(1))))),
            listOf(old[0].copy(contentParts = old[0].contentParts + AgentContentPart.ImageData(byteArrayOf(1), "image/png")), old[1]),
        )
        for (pair in variants) {
            val history = pair + recent
            assertEquals(history, offloadOldToolSteps(history, 1, ::count) { fail("Media must not be dropped"); null })
        }
    }

    @Test fun latestFourMessagesAndPartiallyProtectedPairsAreNeverCollapsed() {
        val history = listOf(LLMMessage(LLMMessage.Role.USER, "question")) + step("protected") + recent.take(3)
        assertEquals(history, offloadOldToolSteps(history, 1, ::count) { fail("Pair crosses protected tail"); null })
        val tail = step("tail")
        assertEquals(tail, offloadOldToolSteps(tail, 1, ::count) { fail("Tail must stay intact"); null })
    }

    @Test fun missingOrFailedPersistenceAndNonShrinkingStepsKeepOriginalContent() {
        val history = step("one") + recent
        assertEquals(history, offloadOldToolSteps(history, 1, ::count) { null })
        assertEquals(history, offloadOldToolSteps(history, 1, ::count) { " " })
        assertEquals(history, offloadOldToolSteps(history, 1, ::count) { throw IOException("disk full") })
        assertEquals(history, offloadOldToolSteps(history, 1, { 1 }) { "/saved" })
        assertEquals(history, offloadOldToolSteps(history, 0, ::count) { fail("No pressure"); null })
    }

    @Test fun alreadyCollapsedStepsAreNotWrittenAgain() {
        val history = step("one") + recent
        val collapsed = offloadOldToolSteps(history, 1, ::count) { "/saved" }
        assertEquals(collapsed, offloadOldToolSteps(collapsed, 1, ::count) { fail("Already collapsed"); null })
    }

    @Test fun compactKeepsRealHumanTurnBoundariesAndAllToolPairsAfterShrinkingSeveralSteps() {
        val human = LLMMessage(LLMMessage.Role.USER, "Keep the original task instructions", dbMessageId = "human")
        val history = listOf(human) + step("one") + step("two") + step("three") + recent
        val collapsed = offloadOldToolSteps(history, Int.MAX_VALUE, ::count) { "/saved" }
        val humanBoundaries = collapsed.filter {
            it.role == LLMMessage.Role.USER && it.contentParts.none { part -> part is AgentContentPart.ToolResult }
        }
        assertEquals(listOf(human), humanBoundaries)
        val calls = collapsed.flatMap { it.contentParts.filterIsInstance<AgentContentPart.ToolUse>() }.map { it.id }
        val results = collapsed.flatMap { it.contentParts.filterIsInstance<AgentContentPart.ToolResult>() }.map { it.id }
        assertEquals(listOf("one", "two", "three"), calls)
        assertEquals(calls, results)
    }

    @Test fun historyOutsideTheActualSendViewCannotConsumeTheReleaseBudget() {
        val hidden = step("summarized")
        val active = step("active")
        val history = hidden + active + recent
        var saved = ""
        val collapsed = offloadOldToolSteps(history, 1, ::count, write = { saved = it; "/saved" }, visibleHistory = active + recent)
        assertEquals(hidden, collapsed.take(2))
        assertTrue(collapsed[2].content.contains("Full text"))
        assertTrue(saved.contains("assistant-active"))
        assertFalse(saved.contains("assistant-summarized"))
        assertTrue((active + recent).sumOf(::count) > collapsed.drop(2).sumOf(::count))
    }

    @Test(expected = CancellationException::class)
    fun cancellationWhileSavingIsNotConvertedToSuccess() {
        offloadOldToolSteps(step("one") + recent, 1, ::count) { throw CancellationException("cancelled") }
    }
}
