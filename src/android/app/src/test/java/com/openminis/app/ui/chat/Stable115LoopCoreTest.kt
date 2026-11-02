package com.openminis.app.ui.chat

import com.openminis.app.agent.jobs.AgentCallback
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.tools.BatchToolOrder
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class Stable115LoopCoreTest {
    private val offloader = ContextTrimPlanner.Offloader { _, id, _ -> "/var/minis/offloads/$id.txt" }
    private val measure: (List<LLMMessage>) -> Int = { rows -> rows.sumOf { row ->
        row.content.length + row.reasoningContent.orEmpty().length + row.contentParts.sumOf { part -> when (part) {
            is AgentContentPart.Text -> part.text.length
            is AgentContentPart.ToolUse -> part.input.toString().length
            is AgentContentPart.ToolResult -> part.content.length
            else -> 0
        } }
    } }

    private fun history(count: Int, reasoning: Int = 0, output: Int = 50): List<LLMMessage> =
        listOf(LLMMessage(LLMMessage.Role.USER, "task")) + (0 until count).flatMap { i -> listOf(
            LLMMessage(LLMMessage.Role.ASSISTANT, "", reasoningContent = "r".repeat(reasoning), deepSeekReplay = "signed-$i",
                contentParts = listOf(AgentContentPart.ToolUse("call$i", "file_read", JSONObject().put("path", "file$i")))),
            LLMMessage(LLMMessage.Role.USER, "", contentParts = listOf(AgentContentPart.ToolResult("call$i", "file_read", "x".repeat(output)))),
        ) }

    @Test fun `only readers wait for preceding writers in their own batch`() {
        val names = listOf("file_read", "file_write", "browser_use", "shell_execute", "read_image", "file_edit", "file_read")
        assertEquals(emptyList<Int>(), BatchToolOrder.writersBefore(0, names))
        assertEquals(emptyList<Int>(), BatchToolOrder.writersBefore(3, names))
        assertEquals(listOf(1, 3), BatchToolOrder.writersBefore(4, names))
        assertEquals(listOf(1, 3, 5), BatchToolOrder.writersBefore(6, names))
    }

    private fun callback(id: String, kind: AgentCallback.Kind = AgentCallback.Kind.FINISHED) = QueuedPrompt(id,
        AgentCallback(kind, "job-$id", null, "Research", "done", body = "result").xml, runId = "background-$id")

    @Test fun `callback reminders cover only new completed programmatic callbacks`() {
        val finished = callback("done")
        val queue = listOf(finished, callback("progress", AgentCallback.Kind.PROGRESS),
            callback("user").copy(runId = "human"), QueuedPrompt("rpc", "ordinary RPC", runId = "background-rpc"))
        val isUser: (QueuedPrompt) -> Boolean = { it.runId == "human" }
        val plan = QueuedCallbackReminder.plan(queue, emptySet(), isUser)!!
        assertEquals(listOf("done"), plan.promptIds)
        assertTrue(plan.text.contains("job-done"))
        assertNull(QueuedCallbackReminder.plan(queue, plan.promptIds.toSet(), isUser))
        assertEquals(listOf("later"), QueuedCallbackReminder.plan(queue + callback("later"), plan.promptIds.toSet(), isUser)!!.promptIds)
    }

    @Test fun `reminder injection preserves tool result shape and leaves source history intact`() {
        val result = AgentContentPart.ToolResult("call", "shell_execute", "output")
        val source = listOf(LLMMessage(LLMMessage.Role.USER, "", contentParts = listOf(result)))
        val injected = QueuedCallbackReminder.inject(source, "reminder")!!
        assertEquals("output", result.content)
        assertTrue((injected.single().contentParts.single() as AgentContentPart.ToolResult).content.endsWith("reminder"))
        assertNull(QueuedCallbackReminder.inject(listOf(LLMMessage(LLMMessage.Role.ASSISTANT, "reply")), "reminder"))
    }

    @Test fun `reasoning ladder preserves newest reasoning and invalidates only changed signed replay`() {
        val source = history(6, reasoning = 5000)
        val plan = ContextTrimPlanner.plan(source, measure(source) - 12_000,
            ContextTrimPlanner.Options(stripReasoning = true, reasoningFloor = 1), measure, offloader)
        assertTrue(plan.after < plan.before)
        val changed = plan.messages.filter { it.role == LLMMessage.Role.ASSISTANT && it.reasoningContent == "" }
        assertTrue(changed.isNotEmpty())
        assertTrue(changed.all { it.deepSeekReplay == null })
        assertEquals(5000, plan.messages.last { it.role == LLMMessage.Role.ASSISTANT }.reasoningContent!!.length)
        assertEquals("signed-0", source[1].deepSeekReplay)
        assertEquals(5000, source[1].reasoningContent!!.length)
    }

    @Test fun `output ladder offloads old results and protects recent iterations`() {
        val source = history(6, output = 20_000)
        val plan = ContextTrimPlanner.plan(source, measure(source) - 30_000,
            ContextTrimPlanner.Options(stripReasoning = false, keepRecentIterations = 2), measure, offloader)
        assertTrue(plan.after < plan.before)
        assertTrue(plan.steps.any { it.startsWith("L2L3") })
        assertEquals(source.takeLast(4), plan.messages.takeLast(4))
    }

    @Test fun `frozen request trim reapplies without changing the accepted prefix`() {
        val source = history(6, reasoning = 5000)
        val plan = ContextTrimPlanner.plan(source, measure(source) - 10_000,
            ContextTrimPlanner.Options(stripReasoning = true), measure, offloader)
        val grown = source + history(1).drop(1).map { row -> row.copy(contentParts = row.contentParts.map { part -> when (part) {
            is AgentContentPart.ToolUse -> part.copy(id = "new")
            is AgentContentPart.ToolResult -> part.copy(id = "new")
            else -> part
        } }) }
        assertEquals(plan.messages, ContextTrimPlanner.apply(grown, plan.state, offloader)!!.take(source.size))
        assertFalse(ContextTrimPlanner.planFitsWindow(128_000, 1_000_000))
        assertFalse(ContextTrimPlanner.planFitsMarker("old", "new"))
    }

    @Test fun `cancelled generation cannot publish after a new generation begins`() {
        val guard = LiveTurnGuard()
        val old = guard.begin(); guard.markAssistant(old, "old")
        guard.cancel()
        val next = guard.begin(); guard.markAssistant(next, "next")
        guard.end(old)
        assertFalse(guard.isLive(old))
        assertTrue(guard.isLive(next))
        assertFalse(guard.acceptsStreamingWrite("old"))
        assertTrue(guard.acceptsStreamingWrite("next"))
    }

    @Test fun `isolated scheduled insertion restores parent generation and full stop invalidates both`() {
        val guard = LiveTurnGuard()
        val parent = guard.begin(); guard.markAssistant(parent, "parent")
        val inserted = guard.begin(nested = true); guard.markAssistant(inserted, "scheduled")
        assertFalse(guard.isLive(parent))
        guard.end(inserted)
        assertTrue(guard.isLive(parent))
        val second = guard.begin(nested = true)
        guard.cancel(); guard.end(second)
        assertNull(guard.liveGeneration())
    }

    @Test fun `stopping suspended parent preserves scheduled insertion but never restores stopped parent`() {
        val guard = LiveTurnGuard()
        guard.begin()
        val inserted = guard.begin(nested = true)
        guard.cancelSuspended()
        assertTrue(guard.isLive(inserted))
        guard.end(inserted)
        assertNull(guard.liveGeneration())
    }

    @Test fun `old overlay cannot resurrect a terminal tool status`() {
        val terminal = AssistantBlock("tool", "tool_use", toolStatus = ToolBlockStatus.CANCELLED)
        val running = terminal.copy(toolStatus = ToolBlockStatus.RUNNING, content = "late output")
        assertEquals(ToolBlockStatus.CANCELLED, InFlightToolHeal.mergeOverlayBlocks(listOf(terminal), listOf(running)).single().toolStatus)
        assertEquals(InFlightToolHeal.StopAction.CANCEL_STALE_BLOCK,
            InFlightToolHeal.stopAction("old", true, listOf(running), ToolBlockStatus.RUNNING))
    }

    @Test fun `idle thinking settles flags without deleting text errors or tool history`() {
        val source = ChatMessage("reply", "assistant", "partial", isStreaming = true, isAwaitingModelResponse = true, error = "failure")
        val settled = ThinkingRowSettle.settled(listOf(source))!!.single()
        assertFalse(settled.isStreaming)
        assertFalse(settled.isAwaitingModelResponse)
        assertEquals("partial", settled.content)
        assertEquals("failure", settled.error)
    }

    @Test fun `programmatic attachments stay independent from the composer`() {
        val own = listOf("rpc.png"); val composer = listOf("draft.png")
        assertSame(own, PromptAttachments.of(false, own, composer))
        assertSame(composer, PromptAttachments.of(true, own, composer))
        assertFalse(PromptAttachments.clearsComposer(false))
    }

    @Test fun `user titles including padded placeholder names cannot be overwritten by auto title`() {
        assertTrue(SessionTitleRules.autoTitleMayReplace(null, "New Chat"))
        assertTrue(SessionTitleRules.autoTitleMayReplace("New Chat", "New Chat"))
        assertFalse(SessionTitleRules.autoTitleMayReplace(" New Chat ", "New Chat"))
        assertFalse(SessionTitleRules.autoTitleMayReplace("My title", "New Chat"))
        assertEquals("New Chat", SessionTitleRules.display(" ", "New Chat"))
    }

    @Test fun `tool duration stores seconds and restores milliseconds without inventing a value`() {
        val json = serializeToolResults(listOf(AgentContentPart.ToolResult("call", "shell_execute", "ok")), mapOf("call" to 1250L)) { error("no image") }
        val value = JSONArray(json).getJSONObject(0).getJSONObject("value")
        assertEquals(1.25, value.getJSONObject("snapshot").getDouble("duration"), 0.0)
        assertEquals(1250L, ToolDurationWire.restoreMs(value))
        assertEquals(0L, ToolDurationWire.restoreMs(JSONObject("""{"snapshot":{"duration":-1}}""")))
        assertEquals(0L, ToolDurationWire.restoreMs(JSONObject("""{"snapshot":{"duration":"NaN"}}""")))
    }
}
