package com.openminis.app.forge

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ForgeTodoStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val root by lazy { temporary.newFolder("forge") }
    private val store by lazy { ForgeTodoStore(root) }
    private fun args(json: String) = JSONObject(json)
    private fun read(session: String? = "chat-a", params: String = "{}") = store.request("todo_read", args(params), session) as JSONObject
    private fun apply(revision: Int, operations: String, session: String? = "chat-a", extra: String = "") = store.request(
        "todo_apply", args("{expectedRevision:$revision,operations:$operations$extra}"), session) as JSONObject
    private fun item(doc: JSONObject, index: Int = 0) = doc.getJSONArray("items").getJSONObject(index)
    private fun rejects(message: String, block: () -> Unit) {
        try { block(); fail("Expected failure: $message") } catch (error: IllegalArgumentException) { assertTrue(error.message, error.message!!.contains(message)) }
    }

    @Test fun staleWritesAndInvalidBatchesLeaveTheEntireDocumentUntouched() {
        val before = apply(0, "[{op:add,id:a,title:设计},{op:add,id:b,title:验证}]")
        rejects("Revision conflict") { apply(0, "[{op:remove,id:a}]") }
        rejects("Invalid todo status") { apply(1, "[{op:update,id:a,status:completed},{op:update,id:b,status:bad}]") }
        assertEquals(before.toString(), read().toString())
        val next = apply(1, "[{op:remove,id:a},{op:update,id:b,status:in_progress}]")
        assertEquals(2, next.getInt("revision"))
        assertEquals("b", item(next).getString("id"))
        assertEquals(next.toString(), (ForgeTodoStore(root).request("todo_read", JSONObject(), "chat-a") as JSONObject).toString())
    }

    @Test fun suppliedSelectorsNeverChooseAnotherSessionAndPersonalRemainsWritable() {
        apply(0, "[{op:add,id:a,title:私有}]")
        rejects("does not match") { read("chat-b", "{threadId:'chat-a'}") }
        rejects("does not match") { read("chat-a", "{sessionId:'chat-b'}") }
        rejects("does not match") { read("chat-a", "{threadId:personal,sessionId:'chat-a'}") }
        rejects("Open a conversation") { read(null) }
        assertEquals(0, read("chat-b").getJSONArray("items").length())
        apply(0, "[{op:add,id:p,title:个人}]", null, ",scope:personal")
        assertEquals("个人", item(read(null, "{scope:personal}")).getString("title"))
        assertEquals("个人", item(read("chat-b", "{threadId:personal}")).getString("title"))
        assertEquals("私有", item(read()).getString("title"))
    }

    @Test fun statusesSchedulingAndRetryArePersistedWithoutRunningAnything() {
        var doc = apply(0, "[{op:add,id:a,title:任务,status:failed,scheduledAt:'2026-11-03T01:00:00.000Z'}]")
        doc = apply(doc.getInt("revision"), "[{op:defer,id:a,until:'2026-11-04T01:00:00Z'}]")
        assertEquals("attention", ForgeTodoStore.columnFor(item(doc).getString("status")))
        assertEquals("2026-11-04T01:00:00Z", item(doc).getString("deferredUntil"))
        doc = apply(doc.getInt("revision"), "[{op:retry,id:a}]")
        assertEquals("queued", item(doc).getString("status"))
        assertEquals(1, item(doc).getInt("attempts"))
        assertTrue(item(doc).isNull("deferredUntil"))
        assertEquals("2026-11-03T01:00:00.000Z", item(doc).getString("scheduledAt"))
        doc = apply(doc.getInt("revision"), "[{op:cancel,id:a}]")
        assertEquals("done", ForgeTodoStore.columnFor(item(doc).getString("status")))
        doc = apply(doc.getInt("revision"), "[{op:requeue,id:a},{op:schedule,id:a,scheduledAt:null}]")
        assertEquals("todo", item(doc).getString("status"))
        assertEquals(2, item(doc).getInt("attempts"))
        assertTrue(item(doc).isNull("scheduledAt"))
        assertEquals("in_progress", ForgeTodoStore.columnFor("running"))
    }

    @Test fun deletionRestoresIdsPositionNotesAndEventsLosslessly() {
        val original = apply(0, "[{op:add,id:a,title:首项},{op:add,id:b,title:保留,notes:备注}]")
        val deleted = JSONObject(item(original, 1).toString())
        apply(1, "[{op:remove,id:b}]")
        val operation = JSONObject().put("op", "restore").put("item", deleted).put("index", 1)
        val restored = store.request("todo_apply", JSONObject().put("expectedRevision", 2).put("operations", JSONArray().put(operation)), "chat-a") as JSONObject
        assertEquals(deleted.toString(), item(restored, 1).toString())
        rejects("duplicate") { apply(3, "[{op:add,id:b,title:重复}]") }
    }

    @Test fun reorderRequiresEveryIdAndPreservesUnrelatedItems() {
        apply(0, "[{op:add,id:a,title:一},{op:add,id:b,title:二},{op:add,id:c,title:三}]")
        rejects("Invalid todo order") { apply(1, "[{op:reorder,order:[a,a,c]}]") }
        val reordered = apply(1, "[{op:reorder,order:[c,a,b]}]")
        assertEquals("c", item(reordered).getString("id"))
        assertEquals("a", item(reordered, 1).getString("id"))
    }

    @Test fun limitsAndMalformedDatesCannotCreatePartialData() {
        rejects("todo ID") { apply(0, "[{op:add,id:'../escape',title:坏路径}]") }
        rejects("Invalid todo time") { apply(0, "[{op:add,id:a,title:时间,scheduledAt:nope}]") }
        rejects("Invalid expectedRevision") { store.request("todo_apply", JSONObject().put("expectedRevision", 0.5).put("operations", JSONArray("[{op:add,title:任务}]")), "chat-a") }
        rejects("Invalid title") { store.request("todo_apply", JSONObject().put("expectedRevision", 0).put("operations", JSONArray().put(JSONObject().put("op", "add").put("title", "x".repeat(501)))), "chat-a") }
        assertEquals(0, read().getInt("revision"))
    }

    @Test fun concurrentStoreInstancesSerializeCasAndEventsHaveTrustedActor() {
        val second = ForgeTodoStore(root)
        val gate = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results = listOf(store, second).mapIndexed { index, target -> pool.submit<Boolean> {
                gate.await()
                runCatching { target.modelRequest("todo_apply", args("{expectedRevision:0,operations:[{op:add,id:id$index,title:任务}]}"), "chat-a") }.isSuccess
            } }
            gate.countDown()
            assertEquals(1, results.count { it.get(5, TimeUnit.SECONDS) })
            val event = item(read()).getJSONArray("events").getJSONObject(0)
            assertEquals("model", event.getString("actor"))
        } finally { pool.shutdownNow() }
    }
}
