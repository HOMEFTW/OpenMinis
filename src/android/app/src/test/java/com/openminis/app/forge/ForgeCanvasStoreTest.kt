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

class ForgeCanvasStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private fun node(id: String) = JSONObject("""{"id":"$id","type":"text","x":0,"y":0,"width":200,"height":100,"data":{"text":"hello"}}""")
    private fun add(id: String) = JSONObject().put("op", "upsert_node").put("node", node(id))
    @Test fun `stale writer cannot erase a newer persisted edit`() {
        val root = temp.newFolder(); val store = ForgeCanvasStore(root); val id = store.create(JSONObject().put("title", "测试")).getString("id")
        store.apply(id, 0, JSONArray().put(add("a")), "model")
        assertThrows(IllegalStateException::class.java) { store.apply(id, 0, JSONArray().put(add("b"))) }
        val actual = ForgeCanvasStore(root).read(id)
        assertEquals(1, actual.getLong("revision")); assertEquals("a", actual.getJSONArray("nodes").getJSONObject(0).getString("id"))
    }
    @Test fun `invalid last operation rolls back whole batch`() {
        val store = ForgeCanvasStore(temp.newFolder()); val id = store.create(JSONObject()).getString("id")
        assertThrows(Exception::class.java) { store.apply(id, 0, JSONArray().put(add("a")).put(JSONObject("""{"op":"upsert_edge","edge":{"id":"edge","source":"a","target":"missing"}}"""))) }
        assertEquals(0, store.read(id).getJSONArray("nodes").length()); assertEquals(0, store.read(id).getLong("revision"))
    }
    @Test fun `removal cleans edges and group membership and trash survives reopening`() {
        val root = temp.newFolder(); val store = ForgeCanvasStore(root); val id = store.create(JSONObject()).getString("id")
        val group = node("g").put("type", "group").put("data", JSONObject().put("members", JSONArray().put("a")))
        store.apply(id, 0, JSONArray().put(add("a")).put(add("b")).put(JSONObject().put("op", "upsert_node").put("node", group)))
        store.apply(id, 1, JSONArray().put(JSONObject("""{"op":"upsert_edge","edge":{"id":"e","source":"a","target":"b"}}""")))
        val after = store.apply(id, 2, JSONArray().put(JSONObject("""{"op":"remove_node","id":"a"}""")))
        assertEquals(0, after.getJSONArray("edges").length())
        assertEquals(0, after.getJSONArray("nodes").getJSONObject(1).getJSONObject("data").getJSONArray("members").length())
        val token = store.delete(id, 3).getString("restoreToken"); assertEquals(0, store.list().length())
        assertEquals(id, ForgeCanvasStore(root).restore(token).getJSONObject("canvas").getString("id"))
    }
    @Test fun `only one simultaneous writer can consume a revision`() {
        val store = ForgeCanvasStore(temp.newFolder()); val id = store.create(JSONObject()).getString("id")
        val pool = Executors.newFixedThreadPool(4); val start = CountDownLatch(1)
        try {
            val futures = (1..4).map { i -> pool.submit<Boolean> { start.await(); runCatching { store.apply(id, 0, JSONArray().put(add("n$i"))) }.isSuccess } }
            start.countDown(); assertEquals(1, futures.count { it.get(5, TimeUnit.SECONDS) }); assertEquals(1, store.read(id).getJSONArray("nodes").length())
        } finally { pool.shutdownNow() }
    }
    @Test fun `paths and unsupported data are refused without changing disk`() {
        val store = ForgeCanvasStore(temp.newFolder())
        assertThrows(IllegalArgumentException::class.java) { store.read("../private") }
        val id = store.create(JSONObject()).getString("id")
        val malicious = node("a").apply { getJSONObject("data").put("html", "<script>bad</script>") }
        assertThrows(IllegalArgumentException::class.java) { store.apply(id, 0, JSONArray().put(JSONObject().put("op", "upsert_node").put("node", malicious))) }
        assertEquals(0, store.read(id).getJSONArray("nodes").length())
    }
}
