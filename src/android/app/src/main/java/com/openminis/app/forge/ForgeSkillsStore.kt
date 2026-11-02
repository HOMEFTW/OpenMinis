package com.openminis.app.forge

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant

/** Hook into the existing SkillRepository prompt and draft-session lifecycle. */
interface ForgeSkillsProvider {
    fun promptFragment(sessionId: String): String?
    fun renameSessionOverrides(fromDraft: String, toReal: String)
    fun clearSessionOverrides(sessionId: String)
}

/** A separate registry: Codeg enablement never edits user-installed skills or their database. */
internal class ForgeSkillsStore(root: File, private val asset: (String) -> ByteArray) : ForgeSkillsProvider {
    private val storage = ForgeLocalFiles(File(ForgeLocalFiles(root).root, "skills"))
    private val catalog = JSONObject(asset("catalog.json").also { require(it.size <= MAX_STATE) { "Skill catalog limit exceeded" } }.toString(Charsets.UTF_8))
    private val skills = linkedMapOf<String, JSONObject>()
    private val resources = linkedMapOf<String, JSONObject>()

    init {
        require(catalog.getInt("schemaVersion") == 1) { "Unsupported skill catalog" }
        val files = catalog.getJSONArray("files")
        require(files.length() in 1..512) { "Skill file limit exceeded" }
        var total = 0L
        for (i in 0 until files.length()) {
            val file = files.getJSONObject(i)
            val path = ForgeLocalFiles.validPath(file.getString("path"))
            val bytes = forgeInteger(file, "bytes", maximum = MAX_FILE.toLong())
            require(DIGEST.matches(file.getString("sha256")) && resources.put(path, file) == null) { "Invalid skill file manifest" }
            total += bytes
        }
        require(total <= MAX_BUNDLE) { "Skill bundle limit exceeded" }
        val packs = catalog.getJSONArray("packs")
        val packIds = (0 until packs.length()).map { packs.getJSONObject(it).getString("id") }.toSet()
        val entries = catalog.getJSONArray("skills")
        require(entries.length() in 1..100) { "Skill count limit exceeded" }
        for (i in 0 until entries.length()) {
            val skill = entries.getJSONObject(i)
            val id = skill.getString("id")
            require(ID.matches(id) && skills.put(id, skill) == null) { "Invalid skill ID" }
            require(skill.getString("pack") in packIds) { "Unknown skill pack" }
            val directory = ForgeLocalFiles.validPath(skill.getString("directory"))
            val skillFiles = skill.getJSONArray("files")
            val paths = mutableSetOf<String>()
            for (j in 0 until skillFiles.length()) {
                val entry = skillFiles.getJSONObject(j)
                val path = ForgeLocalFiles.validPath(entry.getString("path"))
                val manifest = resources["$directory/$path"]
                require(paths.add(path) && manifest != null && manifest.getString("sha256") == entry.getString("sha256") &&
                    manifest.getLong("bytes") == entry.getLong("bytes")) { "Skill resource manifest mismatch" }
            }
            require("SKILL.md" in paths) { "Skill is missing SKILL.md" }
        }
        val inventory = catalog.getJSONObject("inventory")
        require(inventory.getInt("skills") == skills.size && inventory.getInt("files") == resources.size && inventory.getInt("packs") == packIds.size) { "Skill catalog inventory mismatch" }
        synchronized(storage.lock) {
            // Assets are immutable. An existing resource must still match the manifest.
            for ((path, file) in resources) {
                val relative = "bundle/$path"
                val bytes = if (storage.file(relative).exists()) storage.read(relative, MAX_FILE) else asset(path)
                verifyBytes(bytes, file)
                if (!storage.file(relative).exists()) storage.write(relative, bytes, MAX_FILE)
            }
            state()
        }
    }

    fun request(method: String, args: JSONObject, sessionId: String?): JSONObject = synchronized(storage.lock) {
        validateAgent(args)
        validateSessionSelectors(args, sessionId)
        when (method) {
            "skills_list", "skill_list" -> list(args, sessionId, method == "skill_list")
            "skills_files", "skill_files" -> files(find(args), args, sessionId, method == "skill_files")
            "skills_read", "skill_read" -> read(find(args), args, sessionId, method == "skill_read")
            "skills_set" -> set(args, sessionId)
            "skills_verify" -> {
                resources.forEach { (path, manifest) -> verifyBytes(storage.read("bundle/$path", MAX_FILE), manifest) }
                JSONObject(catalog.getJSONObject("inventory").toString()).put("ok", true).put("source", copy(catalog.getJSONObject("source")))
            }
            else -> throw IllegalArgumentException("Unknown skill method")
        }
    }

    private fun list(args: JSONObject, sessionId: String?, model: Boolean): JSONObject {
        val state = state()
        val scope = viewScope(args, sessionId, model)
        val session = if (scope == "session") requireSession(args, sessionId) else null
        val query = string(args, "query", "").also { require(it.length <= 1000) { "Invalid skill search" } }.lowercase()
        val pack = string(args, "pack", "")
        require(pack.isEmpty() || skills.values.any { it.getString("pack") == pack }) { "Unknown skill pack" }
        val enabledOnly = if (args.has("enabledOnly")) boolean(args, "enabledOnly") else model
        val statuses = skills.values.map { skill ->
            val result = copy(skill).apply { remove("files") }
            val global = state.getJSONObject("enabled").optBoolean(skill.getString("id"), false)
            val override = session?.let { state.getJSONObject("sessions").optJSONObject(it)?.opt(skill.getString("id")) } as? Boolean
            val enabled = override ?: global
            result.put("enabled", enabled).put("globalEnabled", global).put("sessionOverride", override ?: JSONObject.NULL)
                .put("inherited", override == null).put("enabledAgents", JSONArray(if (enabled) listOf("openminis") else emptyList<String>()))
        }
        val filtered = statuses.filter { skill ->
            (pack.isEmpty() || skill.getString("pack") == pack) && (!enabledOnly || skill.getBoolean("enabled")) &&
                (query.isEmpty() || listOf("id", "name", "description", "category").any { skill.optString(it).lowercase().contains(query) })
        }
        val packs = catalog.getJSONArray("packs")
        return JSONObject().put("schemaVersion", 1).put("revision", state.getLong("revision"))
            .put("source", copy(catalog.getJSONObject("source"))).put("inventory", copy(catalog.getJSONObject("inventory")))
            .put("packs", JSONArray((0 until packs.length()).map { i -> copy(packs.getJSONObject(i)).put("enabledCount",
                statuses.count { it.getString("pack") == packs.getJSONObject(i).getString("id") && it.getBoolean("enabled") }) }))
            .put("agents", JSONArray(listOf("openminis"))).put("agent", "openminis").put("scope", scope)
            .put("skills", JSONArray(filtered)).put("instructions", ADAPTATION)
    }

    private fun files(skill: JSONObject, args: JSONObject, sessionId: String?, model: Boolean): JSONObject {
        checkEnabled(skill.getString("id"), args, sessionId, model)
        return JSONObject().put("id", skill.getString("id")).put("directory", directory(skill))
            .put("files", JSONArray(skill.getJSONArray("files").toString())).put("source", copy(skill.getJSONObject("source")))
            .put("instructions", "路径是技能内相对路径。调用 forge 工具，method=skill_read，arguments_json 为 {id,path} 的 JSON 字符串，分页读取；资源不会自动执行。")
    }

    private fun read(skill: JSONObject, args: JSONObject, sessionId: String?, model: Boolean): JSONObject {
        checkEnabled(skill.getString("id"), args, sessionId, model)
        val path = ForgeLocalFiles.validPath(string(args, "path", "SKILL.md"))
        val entries = skill.getJSONArray("files")
        val manifest = (0 until entries.length()).map { entries.getJSONObject(it) }.find { it.getString("path") == path }
            ?: throw IllegalArgumentException("Resource is not in this skill manifest")
        val file = "bundle/${skill.getString("directory")}/$path"
        val bytes = storage.read(file, MAX_FILE)
        verifyBytes(bytes, manifest)
        val offset = forgeInteger(args, "offset", 0, Int.MAX_VALUE.toLong()).toInt()
        val limit = forgeInteger(args, "limit", 30000, 100000).toInt()
        require(limit > 0) { "Invalid skill read range" }
        val result = JSONObject().put("id", skill.getString("id")).put("name", skill.getString("name"))
            .put("pack", skill.getString("pack")).put("path", path).put("filePath", storage.file(file).absolutePath)
            .put("directory", directory(skill)).put("bytes", bytes.size).put("sha256", manifest.getString("sha256"))
            .put("source", copy(skill.getJSONObject("source"))).put("license", skill.getString("license"))
            .put("compatibility", copy(skill.getJSONObject("compatibility"))).put("instructions", ADAPTATION)
        val content = runCatching {
            require(bytes.none { it == 0.toByte() })
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        }.getOrNull()
        if (content == null) return result.put("binary", true).put("nextOffset", JSONObject.NULL)
        val start = offset.coerceAtMost(content.length)
        val end = (start.toLong() + limit).coerceAtMost(content.length.toLong()).toInt()
        return result.put("content", content.substring(start, end)).put("offset", offset).put("totalCharacters", content.length)
            .put("nextOffset", if (end < content.length) end else JSONObject.NULL)
    }

    private fun set(args: JSONObject, sessionId: String?): JSONObject {
        val skill = find(args)
        val scope = viewScope(args, sessionId, false)
        val enabled = args.opt("enabled")
        require(enabled is Boolean || (scope == "session" && enabled == JSONObject.NULL)) { "enabled must be boolean, or null to inherit" }
        val state = state()
        val revision = state.getLong("revision")
        if (args.has("expectedRevision")) require(forgeInteger(args, "expectedRevision") == revision) { "Revision conflict: read and reconcile skill settings" }
        val id = skill.getString("id")
        if (scope == "global") state.getJSONObject("enabled").put(id, enabled)
        else {
            val session = requireSession(args, sessionId)
            val sessions = state.getJSONObject("sessions")
            require(sessions.has(session) || sessions.length() < 1000) { "Skill session limit exceeded" }
            val overrides = sessions.optJSONObject(session) ?: JSONObject().also { sessions.put(session, it) }
            if (enabled == JSONObject.NULL) overrides.remove(id) else overrides.put(id, enabled)
            if (overrides.length() == 0) sessions.remove(session)
        }
        save(state)
        return list(JSONObject().put("scope", scope), sessionId, false)
    }

    private fun checkEnabled(id: String, args: JSONObject, sessionId: String?, model: Boolean) {
        // Preview aliases are host/UI methods. Model tool aliases cannot bypass enablement.
        if (!model && args.opt("requireEnabled") == false) return
        val state = state()
        val session = if (sessionId != null) requireSession(args, sessionId) else null
        require(effective(state, id, session)) { "Skill is disabled; enable it in Skill Packs first" }
    }

    private fun effective(state: JSONObject, id: String, sessionId: String?): Boolean =
        (sessionId?.let { state.getJSONObject("sessions").optJSONObject(it)?.opt(id) } as? Boolean)
            ?: state.getJSONObject("enabled").optBoolean(id, false)

    override fun promptFragment(sessionId: String): String? = synchronized(storage.lock) {
        val state = state()
        val enabled = skills.values.filter { effective(state, it.getString("id"), sessionId) }
        if (enabled.isEmpty()) null else buildString {
            append("\n<forge_codeg_skills>\n").append(ADAPTATION).append("\n")
            append("以下为当前 OpenMinis 会话已启用的 Codeg 技能。通过 forge 工具调用：method=skill_read，arguments_json=\"{\\\"id\\\":\\\"技能ID\\\"}\" 读取正文；method=skill_files 查询支持文件。arguments_json 是包含 id、path、offset、limit 的 JSON 字符串，可按需分页读取。skill_list、skill_read、skill_files 是 forge 的方法名。\n")
            for (skill in enabled) append("- ").append(skill.getString("id")).append(" | ")
                .append(skill.getString("name")).append(": ").append(skill.getString("description").take(300)).append('\n')
            append("</forge_codeg_skills>")
        }
    }

    override fun renameSessionOverrides(fromDraft: String, toReal: String) = synchronized(storage.lock) {
        if (fromDraft == toReal) return@synchronized
        val state = state()
        val sessions = state.getJSONObject("sessions")
        val from = sessions.optJSONObject(fromDraft) ?: return@synchronized
        val target = sessions.optJSONObject(toReal) ?: JSONObject()
        from.keys().forEach { id -> target.put(id, from.get(id)) }
        sessions.put(toReal, target).remove(fromDraft)
        save(state)
    }

    override fun clearSessionOverrides(sessionId: String) = synchronized(storage.lock) {
        val state = state()
        if (state.getJSONObject("sessions").has(sessionId)) { state.getJSONObject("sessions").remove(sessionId); save(state) }
    }

    private fun state(): JSONObject {
        if (!storage.file("enabled.json").exists()) return JSONObject().put("schemaVersion", 1).put("revision", 0).put("enabled", JSONObject()).put("sessions", JSONObject())
        val state = JSONObject(storage.read("enabled.json", MAX_STATE).toString(Charsets.UTF_8))
        require(state.getInt("schemaVersion") == 1) { "Invalid skill settings" }
        forgeInteger(state, "revision")
        fun validate(values: JSONObject) { values.keys().forEach { require(it in skills && values.opt(it) is Boolean) { "Invalid enabled skill list" } } }
        validate(state.getJSONObject("enabled"))
        val sessions = state.getJSONObject("sessions")
        require(sessions.length() <= 1000) { "Skill session limit exceeded" }
        sessions.keys().forEach { require(it.length in 1..256) { "Invalid session ID" }; validate(sessions.getJSONObject(it)) }
        return state
    }

    private fun save(state: JSONObject) {
        val revision = forgeInteger(state, "revision")
        require(revision < 9_007_199_254_740_991L) { "Skill revision limit exceeded" }
        state.put("revision", revision + 1).put("updatedAt", Instant.now().toString())
        storage.write("enabled.json", state.toString().toByteArray(Charsets.UTF_8), MAX_STATE)
    }

    private fun viewScope(args: JSONObject, sessionId: String?, model: Boolean): String {
        if (model) return if (sessionId == null) "global" else "session"
        return when (args.opt("scope")) {
            null, "global" -> "global"
            "session", "conversation" -> "session"
            else -> throw IllegalArgumentException("Invalid skill scope")
        }
    }

    private fun validateSessionSelectors(args: JSONObject, sessionId: String?) {
        if (args.has("threadId") || args.has("sessionId") || args.opt("scope") in listOf("session", "conversation")) requireSession(args, sessionId)
    }
    private fun requireSession(args: JSONObject, sessionId: String?): String = forgeSession(args, sessionId, false)!!
    private fun validateAgent(args: JSONObject) { require(!args.has("agent") || args.opt("agent") == "openminis") { "Unsupported skill agent; Android uses OpenMinis" } }
    private fun find(args: JSONObject): JSONObject = skills[string(args, "id")] ?: throw IllegalArgumentException("Skill not found")
    private fun directory(skill: JSONObject): String = storage.file("bundle/${skill.getString("directory")}/SKILL.md").parent!!
    private fun verifyBytes(bytes: ByteArray, manifest: JSONObject) { require(bytes.size.toLong() == manifest.getLong("bytes") && ForgeLocalFiles.digest(bytes) == manifest.getString("sha256")) { "Skill resource integrity check failed" } }
    private fun copy(value: JSONObject) = JSONObject(value.toString())
    private fun string(args: JSONObject, key: String, default: String? = null): String = (if (args.has(key)) args.opt(key) else default) as? String ?: throw IllegalArgumentException("Invalid $key")
    private fun boolean(args: JSONObject, key: String): Boolean = args.opt(key) as? Boolean ?: throw IllegalArgumentException("Invalid $key")

    companion object {
        private const val MAX_FILE = 1024 * 1024
        private const val MAX_STATE = 1024 * 1024
        private const val MAX_BUNDLE = 16 * 1024 * 1024
        private val ID = Regex("[a-z0-9][a-z0-9-]{0,99}")
        private val DIGEST = Regex("[a-f0-9]{64}")
        const val ADAPTATION = "这些是 OpenMinis Android 本地管理的 Codeg 技能，默认停用，启用仅提供任务参考，不增加权限。用户及当前系统指令优先。手机智能体是 OpenMinis；Codex、Claude Code、Grok、DSH 桌面 CLI 和 Codeg 专属 API 不保证存在。只使用实际提供的工具，跨技能引用必须先核对启用清单。用 skill_list / skill_read / skill_files 读取正文与参考资源；相对路径属于对应技能目录。启用不会安装依赖、执行脚本、调用付费服务或允许对外通信。OfficeCLI、Python、uv、API 密钥等依赖须先确认实际环境。协作、提交、推送、部署仍遵循用户授权。原 UI spec/json-render 仅作格式参考，使用 Markdown、表格或当前可用画布；不要宣称已渲染不存在的界面。"
    }
}
