package com.openminis.app.forge

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/** Source-compatible Forge todo documents. Schedules are metadata, not an AI scheduler. */
class ForgeTodoStore(root: File) {
    private val storage = ForgeLocalFiles(root)

    fun request(method: String, args: JSONObject, sessionId: String?): Any? = dispatch(method, args, sessionId, "user")

    fun modelRequest(method: String, args: JSONObject, sessionId: String?): Any? = dispatch(method, args, sessionId, "model")

    private fun dispatch(method: String, args: JSONObject, sessionId: String?, actor: String): JSONObject {
        require(method == "todo_read" || method == "todo_apply") { "Unknown todo method" }
        val target = forgeSession(args, sessionId)
        val threadId = target ?: "personal"
        val key = ForgeLocalFiles.digest((if (target == null) "personal" else "conversation:$target").toByteArray()) + ".json"
        return synchronized(storage.lock) {
            val doc = if (storage.file(key).exists()) JSONObject(storage.read(key, MAX_BYTES).toString(Charsets.UTF_8))
                else JSONObject().put("schemaVersion", 1).put("threadId", threadId).put("revision", 0)
                    .put("items", JSONArray()).put("updatedAt", JSONObject.NULL)
            require(doc.optInt("schemaVersion") == 1 && doc.optString("threadId") == threadId) { "Invalid todo document" }
            if (method == "todo_read") return@synchronized doc
            val expected = forgeInteger(args, "expectedRevision")
            val current = forgeInteger(doc, "revision")
            require(expected == current) { "Revision conflict: expected $expected, current $current. Read and reconcile." }
            require(current < 9_007_199_254_740_991L) { "Revision limit exceeded" }
            val operations = args.optJSONArray("operations") ?: throw IllegalArgumentException("Require 1-100 todo operations")
            require(operations.length() in 1..100) { "Require 1-100 todo operations" }
            val array = doc.getJSONArray("items")
            val items = (0 until array.length()).map { array.getJSONObject(it) }.toMutableList()
            for (i in 0 until operations.length()) apply(items, operations.getJSONObject(i), actor)
            require(items.size <= 1000) { "Todo limit exceeded" }
            items.sortWith(compareBy<JSONObject> { it.getLong("sortOrder") }.thenBy { it.getString("createdAt") })
            doc.put("items", JSONArray(items)).put("revision", current + 1).put("updatedAt", now()).put("lastSource", actor)
            storage.write(key, doc.toString().toByteArray(Charsets.UTF_8), MAX_BYTES)
            doc
        }
    }

    private fun apply(items: MutableList<JSONObject>, op: JSONObject, actor: String) {
        when (val operation = op.optString("op")) {
            "add" -> {
                val id = if (op.has("id")) text(op.opt("id"), 80, "todo ID") else UUID.randomUUID().toString()
                require(ID.matches(id) && items.none { it.getString("id") == id }) { "Invalid or duplicate todo ID" }
                val status = if (op.has("status")) status(op.opt("status")) else "pending"
                val stamp = now()
                val item = JSONObject().put("id", id).put("title", title(op.opt("title")))
                    .put("notes", text(op.opt("notes") ?: "", 10000, "notes")).put("status", status)
                    .put("createdAt", stamp).put("updatedAt", stamp)
                    .put("sortOrder", (items.maxOfOrNull { it.getLong("sortOrder") } ?: -1L) + 1)
                    .put("scheduledAt", iso(op.opt("scheduledAt"))).put("deferredUntil", iso(op.opt("deferredUntil")))
                    .put("attempts", 0).put("agent", agent(op.opt("agent"))).put("events", JSONArray())
                record(item, actor, "created", "创建待办")
                items.add(item)
            }
            "restore" -> {
                val original = op.optJSONObject("item") ?: throw IllegalArgumentException("Invalid restored todo")
                require(original.keys().asSequence().all { it in ITEM_KEYS }) { "Invalid restored todo" }
                val restored = JSONObject(original.toString())
                val id = text(restored.opt("id"), 80, "todo ID")
                require(ID.matches(id) && items.none { it.getString("id") == id }) { "Invalid or duplicate todo ID" }
                restored.put("title", title(restored.opt("title"))).put("notes", text(restored.opt("notes") ?: "", 10000, "notes"))
                restored.put("status", status(restored.opt("status")))
                require(iso(restored.opt("createdAt")) != JSONObject.NULL) { "Invalid todo creation time" }
                restored.put("updatedAt", iso(restored.opt("updatedAt") ?: restored.get("createdAt")))
                val index = forgeInteger(op, "index", items.size.toLong()).coerceAtMost(items.size.toLong()).toInt()
                restored.put("sortOrder", forgeInteger(restored, "sortOrder", index.toLong()))
                    .put("attempts", forgeInteger(restored, "attempts", 0)).put("agent", agent(restored.opt("agent")))
                    .put("scheduledAt", iso(restored.opt("scheduledAt"))).put("deferredUntil", iso(restored.opt("deferredUntil")))
                val events = restored.optJSONArray("events") ?: JSONArray()
                require(events.length() <= 100) { "Todo event limit exceeded" }
                for (i in 0 until events.length()) {
                    val event = events.getJSONObject(i)
                    require(iso(event.opt("at")) != JSONObject.NULL) { "Invalid todo event time" }
                    text(event.opt("actor"), 120, "event actor"); text(event.opt("action"), 120, "event action")
                    status(event.opt("status"))
                    if (event.has("message")) text(event.opt("message"), 500, "event message")
                    require(event.keys().asSequence().all { it in EVENT_KEYS }) { "Invalid todo event" }
                }
                restored.put("events", events)
                items.add(index, restored)
            }
            "reorder" -> {
                val order = op.optJSONArray("order") ?: throw IllegalArgumentException("Invalid todo order")
                val ids = (0 until order.length()).map { text(order.opt(it), 80, "todo ID") }
                require(ids.size == items.size && ids.toSet().size == ids.size && ids.toSet() == items.map { it.getString("id") }.toSet()) { "Invalid todo order" }
                val byId = items.associateBy { it.getString("id") }
                items.clear()
                ids.forEachIndexed { index, id -> items.add(byId.getValue(id).also { it.put("sortOrder", index); record(it, actor, "reordered", "调整顺序") }) }
            }
            "update", "remove", "retry", "requeue", "cancel", "defer", "schedule" -> {
                val id = text(op.opt("id"), 80, "todo ID")
                val item = items.find { it.getString("id") == id } ?: throw IllegalArgumentException("Todo not found")
                when (operation) {
                    "remove" -> items.remove(item)
                    "update" -> {
                        if (op.has("title")) item.put("title", title(op.opt("title")))
                        if (op.has("notes")) item.put("notes", text(op.opt("notes"), 10000, "notes"))
                        if (op.has("status")) item.put("status", status(op.opt("status")))
                        if (op.has("scheduledAt")) item.put("scheduledAt", iso(op.opt("scheduledAt")))
                        if (op.has("deferredUntil")) item.put("deferredUntil", iso(op.opt("deferredUntil")))
                        if (op.has("agent")) item.put("agent", agent(op.opt("agent")))
                        record(item, actor, "updated", if (op.has("message")) text(op.opt("message"), 500, "message") else "更新待办")
                    }
                    "retry", "requeue" -> {
                        item.put("status", if (operation == "retry") "queued" else "todo")
                            .put("attempts", forgeInteger(item, "attempts", 0, 1_000_000) + 1).put("deferredUntil", JSONObject.NULL)
                        record(item, actor, operation, if (operation == "retry") "重试待办" else "重新排队")
                    }
                    "cancel" -> { item.put("status", "canceled"); record(item, actor, "canceled", "取消待办") }
                    "defer" -> { item.put("deferredUntil", iso(op.opt("until"))); record(item, actor, "deferred", "延后处理") }
                    "schedule" -> { item.put("scheduledAt", iso(op.opt("scheduledAt"))); record(item, actor, "scheduled", "调整计划时间") }
                }
            }
            else -> throw IllegalArgumentException("Unknown todo operation")
        }
    }

    private fun record(item: JSONObject, actor: String, action: String, message: String) {
        val stamp = now()
        item.put("updatedAt", stamp)
        val events = item.getJSONArray("events")
        events.put(JSONObject().put("at", stamp).put("actor", actor).put("action", action).put("status", item.getString("status")).put("message", message))
        while (events.length() > 100) events.remove(0)
    }

    private fun title(value: Any?): String = text(value, 500, "title").trim().also { require(it.isNotEmpty()) { "Title is required" } }
    private fun text(value: Any?, max: Int, label: String): String = (value as? String)?.also { require(it.length <= max) { "Invalid $label" } }
        ?: throw IllegalArgumentException("Invalid $label")
    private fun status(value: Any?): String = (value as? String)?.also { require(it in STATUSES) { "Invalid todo status" } }
        ?: throw IllegalArgumentException("Invalid todo status")
    private fun agent(value: Any?): Any = if (value == null || value == JSONObject.NULL) JSONObject.NULL else text(value, 120, "agent")
    private fun iso(value: Any?): Any {
        if (value == null || value == JSONObject.NULL || value == "") return JSONObject.NULL
        val date = text(value, 50, "time")
        require(runCatching { OffsetDateTime.parse(date) }.isSuccess) { "Invalid todo time" }
        return date
    }
    private fun now() = Instant.now().toString()

    companion object {
        private const val MAX_BYTES = 8 * 1024 * 1024
        private val ID = Regex("[A-Za-z0-9_-]{1,80}")
        val STATUSES = setOf("pending", "todo", "queued", "in_progress", "preparing", "running", "awaiting_input", "review", "merging", "blocked", "failed", "completed", "done", "canceled")
        fun columnFor(status: String): String = when (status) {
            "in_progress", "preparing", "running" -> "in_progress"
            "awaiting_input", "review", "merging", "blocked", "failed" -> "attention"
            "completed", "done", "canceled" -> "done"
            else -> "todo"
        }
        private val ITEM_KEYS = setOf("id", "title", "notes", "status", "createdAt", "updatedAt", "sortOrder", "scheduledAt", "deferredUntil", "attempts", "agent", "events")
        private val EVENT_KEYS = setOf("at", "actor", "action", "status", "message")
    }
}
