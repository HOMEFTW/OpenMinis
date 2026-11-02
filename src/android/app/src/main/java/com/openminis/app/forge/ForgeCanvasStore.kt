package com.openminis.app.forge

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.UUID

/** Android port of Forge's canvas schema. UI and model writes share this CAS boundary. */
class ForgeCanvasStore(private val root: File) {
    private fun file(id: String) = File(root, "${ForgeStorage.id(id)}.json")
    @Synchronized fun read(id: String): JSONObject = validate(ForgeStorage.read(file(id)))
    @Synchronized fun list(): JSONArray = JSONArray(root.listFiles().orEmpty()
        .filter { it.name.matches(Regex("[A-Za-z0-9_-]{1,80}\\.json")) }
        .mapNotNull { runCatching { read(it.nameWithoutExtension) }.getOrNull() }
        .sortedByDescending { it.optString("updatedAt") }.map { doc ->
            val nodes = objects(doc.getJSONArray("nodes"))
            JSONObject().put("id", doc.getString("id")).put("title", doc.getString("title"))
                .put("description", doc.optString("description")).put("color", doc.optString("color", "neutral"))
                .put("revision", doc.getLong("revision")).put("updatedAt", doc.optString("updatedAt"))
                .put("nodes", nodes.size).put("groups", nodes.count { it.getString("type") == "group" })
                .put("preview", JSONArray(nodes.take(24).map { n -> JSONObject(n.toString()).apply {
                    put("color", n.getJSONObject("data").optString("color", "neutral")); remove("data")
                } }))
        })

    @Synchronized fun create(args: JSONObject, source: String = "user"): JSONObject {
        val id = UUID.randomUUID().toString()
        val now = Instant.now().toString()
        val doc = JSONObject().put("schemaVersion", 1).put("id", id).put("revision", 0)
            .put("title", args.optString("title", "新画布")).put("description", args.optString("description"))
            .put("color", args.optString("color", "neutral")).put("createdAt", now).put("updatedAt", now)
            .put("nodes", JSONArray()).put("edges", JSONArray())
            .put("lastChange", JSONObject().put("source", source).put("nodeIds", JSONArray()).put("message", "创建画布"))
        ForgeStorage.write(file(id), validate(doc))
        return doc
    }

    @Synchronized fun apply(id: String, revision: Long, operations: JSONArray, source: String = "user", message: String = ""): JSONObject {
        require(revision >= 0 && operations.length() in 1..300) { "expectedRevision and 1–300 operations are required" }
        require(source in listOf("user", "model") && message.length <= 500)
        val doc = read(id)
        check(doc.getLong("revision") == revision) { "Revision conflict: expected $revision, current ${doc.getLong("revision")}. Read and retry." }
        val nodes = objects(doc.getJSONArray("nodes")).toMutableList()
        val edges = objects(doc.getJSONArray("edges")).toMutableList()
        val changed = linkedSetOf<String>()
        fun node(nodeId: String): JSONObject = requireNotNull(nodes.find { it.getString("id") == ForgeStorage.id(nodeId) }) { "Node not found" }
        for (op in objects(operations)) {
            when (op.getString("op")) {
                "set_title" -> doc.put("title", op.getString("title"))
                "set_metadata" -> listOf("title", "description", "color").filter(op::has).forEach { doc.put(it, op.get(it)) }
                "replace_content" -> {
                    val value = op.getJSONObject("content")
                    require(value.keys().asSequence().all { it in setOf("title", "description", "color", "nodes", "edges") })
                    listOf("title", "description", "color").filter(value::has).forEach { doc.put(it, value.get(it)) }
                    changed.addAll(nodes.map { it.getString("id") })
                    nodes.clear(); nodes.addAll(objects(value.getJSONArray("nodes")))
                    edges.clear(); edges.addAll(objects(value.getJSONArray("edges")))
                    changed.addAll(nodes.map { it.getString("id") })
                }
                "upsert_node" -> {
                    val value = op.getJSONObject("node"); validateNode(value)
                    val n = value.getString("id"); val index = nodes.indexOfFirst { it.getString("id") == n }
                    if (index < 0) nodes.add(value) else nodes[index] = value
                    changed.add(n)
                }
                "update_node" -> {
                    val value = node(op.getString("id")); val patch = op.getJSONObject("patch")
                    require(patch.keys().asSequence().all { it in setOf("x", "y", "width", "height", "data") }) { "Unsupported node patch" }
                    patch.keys().forEach { key ->
                        if (key == "data") patch.getJSONObject(key).let { data -> data.keys().forEach { value.getJSONObject("data").put(it, data.get(it)) } }
                        else value.put(key, patch.get(key))
                    }
                    validateNode(value); changed.add(value.getString("id"))
                }
                "set_group_members" -> {
                    val value = node(op.getString("id")); require(value.getString("type") == "group")
                    value.getJSONObject("data").put("members", op.getJSONArray("members")); changed.add(value.getString("id"))
                }
                "remove_node" -> {
                    val n = node(op.getString("id")).getString("id")
                    nodes.removeAll { it.getString("id") == n }; edges.removeAll { it.getString("source") == n || it.getString("target") == n }
                    nodes.filter { it.getString("type") == "group" }.forEach { g ->
                        g.getJSONObject("data").optJSONArray("members")?.let { members ->
                            g.getJSONObject("data").put("members", JSONArray((0 until members.length()).map { members.getString(it) }.filter { it != n }))
                        }
                    }
                    changed.add(n)
                }
                "upsert_edge" -> {
                    val value = op.getJSONObject("edge"); val edgeId = ForgeStorage.id(value.getString("id"))
                    val index = edges.indexOfFirst { it.getString("id") == edgeId }
                    if (index < 0) edges.add(value) else edges[index] = value
                    changed.add(value.getString("source")); changed.add(value.getString("target"))
                }
                "remove_edge" -> require(edges.removeAll { it.getString("id") == op.getString("id") }) { "Edge not found" }
                else -> error("Unsupported operation")
            }
        }
        doc.put("nodes", JSONArray(nodes)).put("edges", JSONArray(edges)).put("revision", revision + 1)
            .put("updatedAt", Instant.now().toString())
            .put("lastChange", JSONObject().put("source", source).put("nodeIds", JSONArray(changed.toList())).put("message", message.ifBlank { if (source == "model") "模型更新了画布" else "已自动保存" }))
        ForgeStorage.write(file(id), validate(doc))
        return doc
    }

    @Synchronized fun delete(id: String, revision: Long): JSONObject {
        val doc = read(id)
        check(doc.getLong("revision") == revision) { "Revision conflict" }
        val token = UUID.randomUUID().toString()
        val trash = File(root, "trash/$token.json"); trash.parentFile!!.mkdirs()
        java.nio.file.Files.move(file(id).toPath(), trash.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        return JSONObject().put("restoreToken", token).put("id", id).put("title", doc.getString("title")).put("canvases", list())
    }
    @Synchronized fun restore(token: String): JSONObject {
        val trash = File(root, "trash/${ForgeStorage.id(token)}.json")
        val doc = validate(ForgeStorage.read(trash)); val target = file(doc.getString("id"))
        check(!target.exists()) { "Canvas already exists" }
        java.nio.file.Files.move(trash.toPath(), target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        return JSONObject().put("canvas", doc).put("canvases", list())
    }

    companion object {
        private val colors = setOf("neutral", "yellow", "blue", "green", "pink")
        private fun objects(array: JSONArray) = (0 until array.length()).map { array.getJSONObject(it) }
        private fun text(obj: JSONObject, key: String, max: Int) { require(obj.get(key) is String && obj.getString(key).length <= max) { "Invalid $key" } }
        private fun number(obj: JSONObject, key: String, min: Double, max: Double) {
            val n = obj.get(key); require(n is Number && n.toDouble().isFinite() && n.toDouble() in min..max) { "Invalid $key" }
        }
        fun validateNode(n: JSONObject) {
            ForgeStorage.id(n.getString("id")); require(n.getString("type") in setOf("text", "sticky", "image", "file", "group"))
            require(n.keys().asSequence().all { it in setOf("id", "type", "x", "y", "width", "height", "data") })
            number(n, "x", -1e6, 1e6); number(n, "y", -1e6, 1e6); number(n, "width", 140.0, 1600.0); number(n, "height", 90.0, 1600.0)
            val d = n.getJSONObject("data")
            require(d.keys().asSequence().all { it in setOf("title", "text", "color", "asset", "filePath", "members", "collapsed", "gridColumns", "gridRows") })
            if (d.has("title")) text(d, "title", 300)
            if (d.has("text")) text(d, "text", 100000)
            if (d.has("color")) require(d.getString("color") in colors)
            if (d.has("asset")) require(d.getString("asset").matches(Regex("[a-f0-9]{64}\\.(png|jpg|webp|gif)")))
            if (d.has("filePath")) { text(d, "filePath", 32767); require(d.getString("filePath").startsWith("/")); require(d.getString("filePath").none { it in "\r\n\u0000" }) }
            if (d.has("collapsed")) require(d.get("collapsed") is Boolean)
            if (d.has("members")) {
                val members = d.getJSONArray("members"); require(members.length() <= 1500)
                val ids = (0 until members.length()).map { ForgeStorage.id(members.getString(it)) }
                require(ids.distinct().size == ids.size) { "Duplicate group member" }
            }
            for (key in listOf("gridColumns", "gridRows")) if (d.has(key)) { number(d, key, 0.0, 50.0); require(d.getDouble(key) == d.getInt(key).toDouble()) }
        }
        fun validate(doc: JSONObject): JSONObject {
            require(doc.getInt("schemaVersion") == 1); ForgeStorage.id(doc.getString("id"))
            text(doc, "title", 200); text(doc, "description", 2000); require(doc.getString("color") in colors)
            require(doc.getLong("revision") >= 0)
            val nodes = objects(doc.getJSONArray("nodes")); val edges = objects(doc.getJSONArray("edges"))
            require(nodes.size <= 1500 && edges.size <= 5000)
            nodes.forEach(::validateNode)
            val ids = nodes.map { it.getString("id") }.toSet(); require(ids.size == nodes.size)
            nodes.filter { it.getString("type") == "group" }.forEach { node -> node.getJSONObject("data").optJSONArray("members")?.let { array ->
                require(array.length() <= 1500)
                val members = (0 until array.length()).map { ForgeStorage.id(array.getString(it)) }
                require(members.distinct().size == members.size && members.all { it in ids && it != node.getString("id") }) { "Invalid group members" }
            } }
            val edgeIds = hashSetOf<String>()
            edges.forEach { e ->
                require(edgeIds.add(ForgeStorage.id(e.getString("id"))))
                require(e.getString("source") in ids && e.getString("target") in ids && e.getString("source") != e.getString("target"))
                require(e.keys().asSequence().all { it in setOf("id", "source", "target", "label") })
                if (e.has("label")) text(e, "label", 500)
            }
            return doc
        }
    }
}
