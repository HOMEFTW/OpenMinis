package com.openminis.app.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class SessionMail(
    val id: String,
    val source: String,
    val target: String,
    val text: String,
    val fromAgent: Boolean,
    val createdAt: Long,
    val status: String = "queued",
    val reply: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("source", source).put("target", target).put("text", text)
        .put("fromAgent", fromAgent).put("createdAt", createdAt).put("status", status).put("reply", reply)
}

/** Atomic persistence happens before publishing changes. No automatic replay of interrupted runs. */
class SessionMailbox(initial: String?, private val persist: (String) -> Unit) {
    companion object {
        const val MAX_TEXT = 16_000
        const val MAX_PENDING = 20
        const val MAX_RECORDS = 1_000
    }

    private val root = initial?.let(::JSONObject) ?: JSONObject()
    private var enabled = root.optJSONArray("enabled")?.let { arr ->
        (0 until arr.length()).map { arr.getString(it) }.toSet()
    }.orEmpty()
    private val _messages = MutableStateFlow(root.optJSONArray("messages")?.let { arr ->
        (0 until arr.length()).map { index ->
            val obj = arr.getJSONObject(index)
            SessionMail(obj.getString("id"), obj.getString("source"), obj.getString("target"),
                obj.getString("text"), obj.getBoolean("fromAgent"), obj.getLong("createdAt"),
                obj.getString("status"), obj.optString("reply"))
        }
    }.orEmpty())
    val messages = _messages.asStateFlow()
    private val _allowed = MutableStateFlow(enabled)
    val allowed = _allowed.asStateFlow()

    fun forSession(sessionId: String, messageId: String? = null): List<SessionMail> = _messages.value.filter {
        (it.source == sessionId || it.target == sessionId) && (messageId == null || it.id == messageId)
    }

    @Synchronized
    private fun save(records: List<SessionMail>, allowed: Set<String> = enabled) {
        persist(JSONObject().put("enabled", JSONArray(allowed.toList()))
            .put("messages", JSONArray(records.map { it.toJson() })).toString())
        enabled = allowed
        _allowed.value = allowed
        _messages.value = records
    }

    @Synchronized
    fun setAllowed(sessionId: String, value: Boolean) {
        save(_messages.value, if (value) enabled + sessionId else enabled - sessionId)
    }

    @Synchronized
    fun enqueue(source: String, target: String, text: String, fromAgent: Boolean): SessionMail {
        require(source.isNotBlank() && target.isNotBlank() && source != target) { "Invalid target session" }
        require(text.isNotBlank() && text.length <= MAX_TEXT) { "Message must contain 1–$MAX_TEXT characters" }
        require(!fromAgent || target in enabled) { "Target has not enabled AI messages" }
        require(_messages.value.count { it.status in setOf("queued", "running") } < MAX_PENDING) { "Mailbox queue is full" }
        require(_messages.value.size < MAX_RECORDS) { "Mailbox is full; delete finished records first" }
        val mail = SessionMail(UUID.randomUUID().toString(), source, target, text, fromAgent, System.currentTimeMillis())
        save(_messages.value + mail)
        return mail
    }

    @Synchronized
    fun claim(id: String): SessionMail? {
        val mail = _messages.value.find { it.id == id && it.status == "queued" } ?: return null
        if (mail.fromAgent && mail.target !in enabled) {
            finish(id, "failed", "Target disabled AI messages")
            return null
        }
        val running = mail.copy(status = "running")
        save(_messages.value.map { if (it.id == id) running else it })
        return running
    }

    /** Admission lost a race with local input before dispatch; safe to queue again. */
    @Synchronized
    fun defer(id: String) {
        save(_messages.value.map { if (it.id == id && it.status == "running") it.copy(status = "queued") else it })
    }

    @Synchronized
    fun finish(id: String, status: String, reply: String) {
        require(status in setOf("completed", "failed", "interrupted"))
        val savedReply = if (reply.length > 32_000) {
            reply.take(31_900) + "\n[Reply truncated / 回复已截断：请在目标会话查看完整内容]"
        } else reply
        save(_messages.value.map {
            if (it.id == id && it.status in setOf("queued", "running")) it.copy(status = status, reply = savedReply) else it
        })
    }

    @Synchronized
    fun remove(id: String, sessionId: String) {
        val mail = _messages.value.find { it.id == id } ?: return
        require(sessionId == mail.source || sessionId == mail.target) { "Not a participant" }
        require(mail.status != "running") { "Cannot remove a running message" }
        save(_messages.value.filterNot { it.id == id })
    }

    @Synchronized
    fun recover() {
        if (_messages.value.none { it.status == "running" }) return
        save(_messages.value.map {
            if (it.status == "running") it.copy(status = "interrupted", reply = "App stopped before completion; check the target conversation before sending again.") else it
        })
    }
}
