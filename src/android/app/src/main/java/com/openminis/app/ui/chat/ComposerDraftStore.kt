package com.openminis.app.ui.chat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal data class DraftAttachment(val id: String, val name: String, val uri: String, val mime: String, val kind: String)
internal data class ComposerDraft(val text: String, val attachments: List<DraftAttachment>, val editingMessageId: String? = null) {
    fun encode(): String = JSONObject().put("text", text).put("editing", editingMessageId)
        .put("attachments", JSONArray().apply {
            attachments.forEach { a -> put(JSONObject().put("id", a.id).put("name", a.name)
                .put("uri", a.uri).put("mime", a.mime).put("kind", a.kind)) }
        }).toString()
    companion object {
        fun decode(raw: String?): ComposerDraft? = runCatching {
            if (raw == null) return null
            val obj = JSONObject(raw)
            val array = obj.optJSONArray("attachments") ?: JSONArray()
            ComposerDraft(obj.getString("text"), (0 until array.length()).map { i ->
                val a = array.getJSONObject(i)
                DraftAttachment(a.getString("id"), a.getString("name"), a.getString("uri"), a.getString("mime"), a.getString("kind"))
            }, obj.optString("editing").takeIf { it.isNotBlank() && it != "null" })
        }.getOrNull()
    }
}

internal class ComposerDraftStore(private val prefs: android.content.SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences("composer_drafts", Context.MODE_PRIVATE))
    fun list(): Map<String, ComposerDraft> = prefs.all.mapNotNull { (id, raw) -> ComposerDraft.decode(raw as? String)?.let { id to it } }.toMap()
    fun load(id: String): ComposerDraft? = ComposerDraft.decode(prefs.getString(id, null))
    fun save(id: String, draft: ComposerDraft) {
        val editor = prefs.edit()
        if (draft.text.isEmpty() && draft.attachments.isEmpty()) editor.remove(id)
        else editor.putString(id, draft.encode())
        editor.apply()
    }
    fun move(from: String, to: String, latest: ComposerDraft) {
        val editor = prefs.edit().remove(from)
        if (latest.text.isEmpty() && latest.attachments.isEmpty()) editor.remove(to)
        else editor.putString(to, latest.encode())
        editor.apply()
    }
    fun has(id: String): Boolean = load(id)?.let { it.text.isNotEmpty() || it.attachments.isNotEmpty() } == true
}
