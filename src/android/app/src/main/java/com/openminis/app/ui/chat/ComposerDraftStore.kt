package com.openminis.app.ui.chat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import com.openminis.app.data.storage.SessionStorageMaintenanceGate
import com.openminis.app.data.storage.SessionStorageReferences
import com.openminis.app.sandbox.SessionWorkspaceRegistry

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
    private fun reservationIds(ids: Set<String>, draft: ComposerDraft): Set<String> =
        (ids + SessionStorageReferences.idsInText(draft.text) +
            draft.attachments.flatMap { SessionStorageReferences.idsInText(it.uri) }).let {
            it + it.map(SessionWorkspaceRegistry::owner)
        }
    fun list(): Map<String, ComposerDraft> = prefs.all.mapNotNull { (id, raw) -> ComposerDraft.decode(raw as? String)?.let { id to it } }.toMap()
    fun load(id: String): ComposerDraft? = ComposerDraft.decode(prefs.getString(id, null))
    fun save(id: String, draft: ComposerDraft) = SessionStorageMaintenanceGate.shared.withSynchronousActivity(
        reservationIds(setOf(id), draft),
    ) {
        val editor = prefs.edit()
        if (draft.text.isEmpty() && draft.attachments.isEmpty()) editor.remove(id)
        else editor.putString(id, draft.encode())
        editor.apply()
    }
    fun saveConfirmed(id: String, draft: ComposerDraft) = SessionStorageMaintenanceGate.shared.withSynchronousActivity(
        reservationIds(setOf(id), draft),
    ) {
        val editor = prefs.edit()
        if (draft.text.isEmpty() && draft.attachments.isEmpty()) editor.remove(id)
        else editor.putString(id, draft.encode())
        check(editor.commit()) { "Cannot persist the shared composer draft" }
    }
    fun move(from: String, to: String, latest: ComposerDraft) = SessionStorageMaintenanceGate.shared.withSynchronousActivity(
        reservationIds(setOf(from, to), latest),
    ) {
        val editor = prefs.edit().remove(from)
        if (latest.text.isEmpty() && latest.attachments.isEmpty()) editor.remove(to)
        else editor.putString(to, latest.encode())
        editor.apply()
    }
    fun has(id: String): Boolean = load(id)?.let { it.text.isNotEmpty() || it.attachments.isNotEmpty() } == true

    fun protectedStorageIds(): Set<String> = buildSet {
        prefs.all.forEach { (id, raw) ->
            require(SessionStorageReferences.isIdentifier(id)) { "Invalid saved draft identifier" }
            val obj = JSONObject(requireNotNull(raw as? String) { "Unreadable saved draft" })
            require(!obj.has("attachments") || obj.opt("attachments") is JSONArray) { "Unreadable draft attachments" }
            val draft = requireNotNull(ComposerDraft.decode(raw as? String)) { "Unreadable saved draft" }
            add(id)
            addAll(SessionStorageReferences.idsInText(draft.text))
            draft.attachments.forEach { addAll(SessionStorageReferences.idsInText(it.uri)) }
        }
    }
}
