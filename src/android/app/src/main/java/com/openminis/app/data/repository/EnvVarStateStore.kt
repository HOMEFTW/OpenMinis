package com.openminis.app.data.repository

import org.json.JSONObject
import java.io.File

/**
 * Enabled / disabled state for environment variables, in `env-vars-state.json`,
 * not in `env-vars.json`. Older builds rewrite the metadata file with only
 * id / key / note / createdAt, so a field stored there would be dropped and
 * every disabled variable would turn back on. Those builds never open this file.
 *
 * Indexed by entry id. Absent means enabled. An unreadable file is left in
 * place so a later save cannot turn every variable back on.
 */
class EnvVarStateStore(private val dir: File) {

    data class State(val key: String, val enabled: Boolean, val enabledUpdatedAt: Long)

    private val file: File get() = File(dir, FILE_NAME)
    private var states: MutableMap<String, State> = LinkedHashMap()

    var unreadable: Boolean = false
        private set

    init {
        load()
    }

    fun isEnabled(id: String): Boolean = states[id]?.enabled ?: true

    fun state(id: String): State? = states[id]

    fun setEnabled(id: String, key: String, enabled: Boolean, now: Long = System.currentTimeMillis()) {
        val previous = states[id]
        val stamp = if (previous != null && now <= previous.enabledUpdatedAt) previous.enabledUpdatedAt + 1 else now
        states[id] = State(key, enabled, stamp)
        save()
    }

    fun renamed(id: String, newKey: String) {
        val current = states[id] ?: return
        if (current.key == newKey) return
        states[id] = current.copy(key = newKey)
        save()
    }

    fun remove(id: String) {
        if (states.remove(id) != null) save()
    }

    fun prune(liveIds: Set<String>) {
        if (unreadable) return
        val before = states.size
        states.keys.retainAll(liveIds)
        if (states.size != before) save()
    }

    private fun load() {
        val current = file
        if (!current.exists()) return
        try {
            val root = JSONObject(current.readText())
            val obj = root.optJSONObject("states") ?: JSONObject()
            val loaded = LinkedHashMap<String, State>()
            for (id in obj.keys()) {
                val row = obj.optJSONObject(id) ?: continue
                loaded[id] = State(
                    key = row.optString("key", ""),
                    enabled = row.optBoolean("enabled", true),
                    enabledUpdatedAt = row.optLong("enabledUpdatedAt", 0L),
                )
            }
            states = loaded
        } catch (_: Exception) {
            unreadable = true
        }
    }

    private fun save() {
        if (unreadable) return
        val obj = JSONObject()
        for ((id, row) in states) {
            obj.put(id, JSONObject().apply {
                put("key", row.key)
                put("enabled", row.enabled)
                put("enabledUpdatedAt", row.enabledUpdatedAt)
            })
        }
        val root = JSONObject().apply {
            put("version", 1)
            put("states", obj)
        }
        val tmp = File(dir, "$FILE_NAME.tmp")
        tmp.writeText(root.toString())
        if (!tmp.renameTo(file)) {
            file.writeText(root.toString())
            tmp.delete()
        }
    }

    companion object {
        const val FILE_NAME = "env-vars-state.json"
    }
}
