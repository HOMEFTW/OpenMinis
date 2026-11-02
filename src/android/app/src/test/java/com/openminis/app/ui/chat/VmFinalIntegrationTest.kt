package com.openminis.app.ui.chat

import com.openminis.app.agent.jobs.SubAgentTermination
import com.openminis.app.agent.jobs.SubAgentTerminationStore
import com.openminis.app.data.db.MessageEntity
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VmFinalIntegrationTest {
    @Test fun `late queued or failed callback outcomes never undo actual delivery`() {
        val store = SubAgentTerminationStore(null)
        store.begin("child", "job", "parent", 1L)
        store.end(store.record("child")!!.copy(reason = SubAgentTermination.Reason.COMPLETED,
            delivery = SubAgentTermination.Delivery.PENDING))
        ChatViewModel.noteSubAgentDelivery("job", SubAgentTermination.Delivery.QUEUED, store = store)
        assertEquals(SubAgentTermination.Delivery.QUEUED, store.record("child")!!.delivery)
        ChatViewModel.noteSubAgentDelivery("job", SubAgentTermination.Delivery.DELIVERED, store = store)
        ChatViewModel.noteSubAgentDelivery("job", SubAgentTermination.Delivery.QUEUED, store = store)
        ChatViewModel.noteSubAgentDelivery("job", SubAgentTermination.Delivery.FAILED, "late admission failure", store)
        assertEquals(SubAgentTermination.Delivery.DELIVERED, store.record("child")!!.delivery)
        assertNull(store.record("child")!!.deliveryDetail)
        store.begin("child", "resumed-job", "parent", 2L)
        ChatViewModel.noteSubAgentDelivery("job", SubAgentTermination.Delivery.FAILED, "old job", store)
        assertEquals("resumed-job", store.record("child")!!.jobId)
        assertNull(store.record("child")!!.delivery)
    }

    @Test fun `summary must free half the needed calibrated space to justify a model call`() {
        assertTrue(ChatViewModel.summaryWouldFreeLittleFor(100, 2000, 1000, 1.0))
        assertFalse(ChatViewModel.summaryWouldFreeLittleFor(500, 2000, 1000, 1.0))
        assertFalse(ChatViewModel.summaryWouldFreeLittleFor(300, 2000, 1000, 2.0))
        assertTrue(ChatViewModel.summaryWouldFreeLittleFor(-100, 2000, 1000, 1.0))
        assertFalse(ChatViewModel.summaryWouldFreeLittleFor(0, 1000, 1000, 1.0))
    }

    private fun call(id: String) = JSONArray().put(JSONObject().put("type", "toolUse").put("value", JSONObject()
        .put("toolUseId", id).put("name", "shell_execute").put("input", "{}"))).toString()

    @Test fun `stale cancellation appends once and skips calls behind a newer user row`() = runBlocking {
        val rows = mutableListOf(call("old"), call("current"))
        var appended = 0
        suspend fun persist(ids: List<String>): MessageEntity? = ChatViewModel.persistCancelledToolResultsFor(
            ids.map { it to "shell_execute" }, loadParts = { rows.toList() }, append = { results ->
                appended++
                val json = serializeToolResults(results) { error("no image") }
                rows += json
                MessageEntity("result$appended", "session", "user", json, createdAt = 0, sortOrder = appended)
            })
        assertNull(persist(listOf("old")))
        val saved = persist(listOf("current", "current"))!!
        val result = JSONArray(saved.partsJson).getJSONObject(0).getJSONObject("value")
        assertEquals(1, JSONArray(saved.partsJson).length())
        assertFalse(result.getBoolean("success"))
        assertTrue(result.getString("output").startsWith(ChatViewModel.CANCELLED_MARKER))
        assertNull(persist(listOf("current")))
        assertEquals(1, appended)
        rows += call("later")
        rows += """[{"type":"text","value":"new user message"}]"""
        assertNull(persist(listOf("later")))
        assertEquals(1, appended)
    }
}
