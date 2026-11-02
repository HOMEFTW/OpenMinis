package com.openminis.app.forge

import android.net.Uri
import com.openminis.app.MinisApp
import com.openminis.app.debug.HeadlessChatRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

class ForgeHost(private val app: MinisApp) {
    data class Plugin(val id: String, val page: String, val title: String, val description: String)
    companion object {
        val plugins = listOf(
            Plugin("forge.canvas", "canvas", "无限画布", "连接想法、文件与图片，让 AI 协同编辑"),
            Plugin("forge.information-stats", "information-stats", "信息统计", "Token、速度、模型检测与运行历史"),
            Plugin("forge.skills", "skills", "Codeg 技能包", "四组技能与参考资源，按需启用"),
            Plugin("forge.todos", "todos", "待办清单", "个人与会话待办、计划和变更记录"),
        )
    }
    val root = File(app.filesDir, "forge")
    val canvas = ForgeCanvasStore(File(root, "canvases"))
    private val todos by lazy { ForgeTodoStore(File(root, "todos")) }
    private val skills get() = app.skillRepository.forgeSkillsService
    private val information by lazy { ForgeInformationService(app, app) }
    private val prefs = app.getSharedPreferences("forge_plugins", 0)
    private val changes = MutableSharedFlow<JSONObject>(extraBufferCapacity = 64)
    val events = changes.asSharedFlow()
    fun enabled(id: String) = plugins.any { it.id == id } && prefs.getBoolean(id, true)
    fun setEnabled(id: String, enabled: Boolean) {
        require(plugins.any { it.id == id }); check(prefs.edit().putBoolean(id, enabled).commit()) { "设置保存失败" }
        changes.tryEmit(JSONObject().put("type", "plugin-state").put("pluginId", id).put("enabled", enabled))
    }
    fun manifest(id: String): JSONObject {
        require(plugins.any { it.id == id })
        return app.assets.open("forge/plugins/$id.json").bufferedReader().use { JSONObject(it.readText()) }
    }
    fun changed(plugin: Plugin, canvasId: String? = null) {
        changes.tryEmit(JSONObject().put("type", "refresh").put("pluginId", plugin.id).put("canvasId", canvasId))
    }

    suspend fun request(plugin: Plugin, method: String, args: JSONObject, sessionId: String?, model: Boolean = false): Any? = withContext(Dispatchers.IO) {
        check(enabled(plugin.id)) { "此 Forge 插件已停用" }
        if (sessionId != null) {
            ForgeStorage.id(sessionId)
            check(app.chatRepository.getSession(sessionId) != null) { "会话已删除或尚未创建" }
        }
        if (args.has("threadId") && !args.isNull("threadId")) require(args.getString("threadId") == sessionId ||
            (plugin.page == "todos" && args.getString("threadId") == "personal")) { "不能访问其他会话" }
        val result: Any? = when (method) {
            "bootstrap" -> JSONObject().put("threadId", sessionId ?: JSONObject.NULL).put("canvases", if (plugin.page == "canvas") canvas.list() else JSONArray())
                .put("panel", JSONObject().put("page", plugin.page).put("mode", "expanded"))
            "mode" -> JSONObject().put("mode", "expanded")
            else -> when (plugin.page) {
                "canvas" -> when (method.removePrefix("canvas_")) {
                    "list" -> canvas.list()
                    "read" -> canvas.read(args.getString("canvasId"))
                    "create" -> canvas.create(args, if (model) "model" else "user")
                    "apply" -> {
                        require(args.has("expectedRevision") && args.get("expectedRevision") is Number && args.getDouble("expectedRevision") == args.getLong("expectedRevision").toDouble()) { "expectedRevision is required" }
                        canvas.apply(args.getString("canvasId"), args.getLong("expectedRevision"), args.getJSONArray("operations"), if (model) "model" else "user", args.optString("message"))
                    }
                    "delete" -> { require(!model) { "模型不能删除整张画布" }; canvas.delete(args.getString("canvasId"), args.getLong("expectedRevision")) }
                    "restore" -> { require(!model); canvas.restore(args.getString("restoreToken")) }
                    "asset" -> imageData(args.getString("asset"))
                    "attach" -> {
                        require(model && sessionId != null)
                        val path = args.getString("filePath")
                        val file = requireNotNull(com.openminis.app.sandbox.PRootKernel.resolveSessionHostPath(sessionId, path, app)).canonicalFile
                        val allowed = File(app.filesDir, "minis-sessions/$sessionId").canonicalFile
                        require(file.toPath().startsWith(allowed.toPath()) && file.isFile) { "仅允许附加当前会话的文件" }
                        file.inputStream().use { importAttachment(it, file.name, args.getString("kind")) }
                    }
                    else -> error("Unknown canvas method: $method")
                }
                "todos" -> {
                    if (model) require(method in setOf("todo_read", "todo_apply", "todo_list")) { "Unsupported model todo method" }
                    if (model) todos.modelRequest(method, args, sessionId) else todos.request(method, args, sessionId)
                }
                "skills" -> {
                    if (model) require(method in setOf("skill_list", "skill_read", "skill_files")) { "模型只能读取已启用的技能" }
                    val safeArgs = JSONObject(args.toString())
                    if (model) safeArgs.put("requireEnabled", true).put("enabledOnly", true)
                    skills.request(method, safeArgs, sessionId)
                }
                "information-stats" -> {
                    require(!model) { "信息统计由用户在面板中操作" }
                    if (sessionId != null) withContext(Dispatchers.Main.immediate) {
                        HeadlessChatRunner.existingViewModel(sessionId)?.syncForgeInformationSelection()
                    }
                    information.request(method, args, sessionId)
                }
                else -> error("Unknown plugin")
            }
        }
        if (method in setOf("create", "apply", "delete", "restore", "canvas_create", "canvas_apply", "todo_apply", "todos_apply", "skills_set")) {
            changed(plugin, if (plugin.page == "canvas") (result as? JSONObject)?.optString("id")?.takeIf { it.isNotEmpty() } else null)
        }
        result
    }

    suspend fun sdk(plugin: Plugin, method: String, args: JSONObject, sessionId: String?): Any? {
        check(enabled(plugin.id)) { "此 Forge 插件已停用" }
        val manifest = manifest(plugin.id)
        ForgeApiAccess.denial(manifest, method, args.optString("surface").takeIf { it.isNotBlank() })?.let { error(it) }
        val permissions = ForgeApiAccess.strings(manifest.optJSONArray("permissions"))
        val context = JSONObject().put("threadId", if ("conversation.read" in permissions) sessionId ?: JSONObject.NULL else JSONObject.NULL)
            .put("apiVersion", 2).put("forgeVersion", "0.3.2").put("codexVersion", "").put("host", "openminis-android")
        return when (method) {
            "context.get" -> context
            "metadata.get" -> manifest
            "capabilities.get" -> JSONObject(context.toString()).put("permissions", manifest.getJSONArray("permissions"))
                .put("capabilities", manifest.optJSONArray("capabilities") ?: JSONArray())
                .put("available", JSONObject(ForgeApiAccess.available)).put("denied", JSONObject().apply {
                    for (m in ForgeApiAccess.methods.keys + listOf("dsh.request", "devtools.eval", "diagnostics.collect")) ForgeApiAccess.denial(manifest, m)?.let { put(m, it) }
                }).put("slots", JSONArray()).put("surfaces", JSONArray(listOf("plugin.menu", "conversation.menu", "screen")))
                .put("events", JSONArray(listOf("ready", "context.changed", "settings.changed", "dispose", "output.changed")))
            "settings.get" -> JSONObject().put("enabled", enabled(plugin.id))
            "storage.get", "storage.set", "storage.delete" -> withContext(Dispatchers.IO) { storage(plugin.id, method, args) }
            "composer.read", "composer.set", "composer.insert" -> withContext(Dispatchers.Main.immediate) {
                val vm = requireNotNull(sessionId?.let(HeadlessChatRunner::existingViewModel)) { "请从已打开的会话进入" }
                val text = vm.inputText.value
                if (method != "composer.read") {
                    require(args.has("expectedText") && args.getString("expectedText") == text) { "草稿已变化，重新读取后再修改" }
                    val next = if (method == "composer.insert") text + args.getString("text") else args.getString("text")
                    require(next.length <= 100000); vm.setInputText(next)
                }
                JSONObject().put("text", vm.inputText.value)
            }
            "conversation.title" -> {
                require(sessionId != null); val title = args.getString("title").trim(); require(title.isNotBlank() && title.length <= 200)
                require(app.chatRepository.getSession(sessionId) != null)
                app.chatRepository.updateSessionTitle(sessionId, title)
                JSONObject().put("threadId", sessionId).put("title", title)
            }
            "output.append", "output.clear", "output.open" -> withContext(Dispatchers.IO) {
                require(sessionId != null)
                val file = File(root, "output/${plugin.id}/${ForgeStorage.id(sessionId)}.json")
                synchronized(this@ForgeHost) {
                    val doc = if (file.exists()) ForgeStorage.read(file, 1024 * 1024) else JSONObject().put("items", JSONArray())
                    if (method == "output.clear") doc.put("items", JSONArray())
                    if (method == "output.append") {
                        val text = args.getString("text"); require(text.length <= 64000)
                        val items = doc.getJSONArray("items"); require(items.length() < 100)
                        items.put(JSONObject().put("text", text).put("title", args.optString("title", plugin.title)).put("level", args.optString("level", "info")))
                    }
                    if (method != "output.open") ForgeStorage.write(file, doc, 1024 * 1024)
                    doc
                }
            }
            "events.emit" -> {
                val name = args.getString("name")
                val declared = manifest.optJSONObject("contributes")?.optJSONArray("events") ?: JSONArray()
                require(name.startsWith("plugin.${plugin.id}.") && (0 until declared.length()).any { declared.optString(it) == name || declared.optJSONObject(it)?.optString("name") == name }) { "事件未声明" }
                require(args.toString().toByteArray().size <= 65536)
                changes.tryEmit(JSONObject().put("type", "sdk-event").put("name", name).put("value", args.opt("value")).put("sourcePluginId", plugin.id).put("threadId", sessionId))
                JSONObject().put("accepted", true).put("name", name)
            }
            else -> error("此方法需要面板上下文: $method")
        }
    }

    @Synchronized private fun storage(pluginId: String, method: String, args: JSONObject): Any? {
        val key = args.getString("key"); require(key.length in 1..200)
        val file = File(root, "storage/$pluginId.json")
        val doc = if (file.exists()) ForgeStorage.read(file, 1024 * 1024) else JSONObject()
        if (method == "storage.get") return doc.opt(key)
        if (method == "storage.delete") doc.remove(key) else {
            val value = args.get("value"); require(JSONObject().put("value", value).toString().toByteArray().size <= 65536); doc.put(key, value)
        }
        ForgeStorage.write(file, doc, 1024 * 1024); return true
    }

    fun importAttachment(input: java.io.InputStream, filename: String, kind: String): JSONObject {
        require(kind in setOf("image", "file"))
        val out = java.io.ByteArrayOutputStream(); val chunk = ByteArray(8192)
        while (true) { val n = input.read(chunk); if (n < 0) break; require(out.size() + n <= 15 * 1024 * 1024) { "附件不能超过 15 MiB" }; out.write(chunk, 0, n) }
        val bytes = out.toByteArray(); val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val ext = if (kind == "image") imageExtension(bytes) else filename.substringAfterLast('.', "").lowercase()
            .takeIf { it.matches(Regex("[a-z0-9]{1,12}")) } ?: "bin"
        val file = File(root, "assets/$hash.$ext"); file.parentFile!!.mkdirs()
        if (!file.exists()) {
            val tmp = File(file.parentFile, ".${java.util.UUID.randomUUID()}.tmp")
            try { java.io.FileOutputStream(tmp).use { it.write(bytes); it.fd.sync() }; java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
            finally { tmp.delete() }
        }
        return JSONObject().put("title", filename.take(300)).apply { if (kind == "image") put("asset", file.name) else put("filePath", file.absolutePath) }
    }
    private fun imageExtension(bytes: ByteArray): String {
        fun prefix(vararg values: Int) = bytes.size >= values.size && values.indices.all { bytes[it].toInt() and 255 == values[it] }
        val ext = when {
            prefix(137,80,78,71,13,10,26,10) -> "png"
            prefix(255,216,255) -> "jpg"
            bytes.size >= 6 && String(bytes, 0, 6, Charsets.US_ASCII) in setOf("GIF87a", "GIF89a") -> "gif"
            bytes.size >= 12 && String(bytes,0,4,Charsets.US_ASCII)=="RIFF" && String(bytes,8,4,Charsets.US_ASCII)=="WEBP" -> "webp"
            else -> error("仅支持有效的 PNG/JPEG/GIF/WebP 图片")
        }
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        require(opts.outWidth > 0 && opts.outHeight > 0 && opts.outWidth.toLong() * opts.outHeight <= 40_000_000) { "图片无效或尺寸过大" }
        return ext
    }
    private fun imageData(asset: String): String {
        require(asset.matches(Regex("[a-f0-9]{64}\\.(png|jpg|webp|gif)")))
        val file = File(root, "assets/$asset"); require(file.isFile && file.length() <= 15 * 1024 * 1024)
        val type = if (file.extension == "jpg") "jpeg" else file.extension
        return "data:image/$type;base64," + android.util.Base64.encodeToString(file.readBytes(), android.util.Base64.NO_WRAP)
    }
    fun attachmentFile(path: String): File {
        val file = File(path).canonicalFile
        require(file.toPath().startsWith(File(root, "assets").canonicalFile.toPath()) && file.isFile) { "文件不属于 Forge 附件" }
        return file
    }
}
