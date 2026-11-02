package com.openminis.app.agent.jobs

import android.content.Context
import com.openminis.app.ui.chat.PendingUserQuestion
import com.openminis.app.ui.chat.UserQuestionKind
import com.openminis.app.ui.chat.UserQuestionOption
import com.openminis.app.ui.chat.UserQuestionPrompt
import com.openminis.app.ui.chat.UserQuestionSelection
import org.json.JSONArray
import org.json.JSONObject

internal enum class UserQuestionOutcome { ANSWERED, EXPIRED, DISMISSED, CANCELLED }

/** Terminal answers remain on disk until their tool_result is durably written. */
internal data class UserQuestionRecord(
    val sessionId: String,
    val request: PendingUserQuestion,
    val outcome: UserQuestionOutcome? = null,
    val assistantPartsJson: String? = null,
) {
    val resumesRun: Boolean get() = outcome == UserQuestionOutcome.ANSWERED || (!request.deferred && outcome == UserQuestionOutcome.EXPIRED)
    val output: String get() = when (outcome) {
        UserQuestionOutcome.ANSWERED -> JSONObject().put("answers", JSONObject(request.answers)).toString()
        UserQuestionOutcome.EXPIRED -> "Error: ${UserQuestionExpiredException().message}"
        UserQuestionOutcome.DISMISSED -> "Error: ${UserQuestionDismissedException().message}"
        UserQuestionOutcome.CANCELLED -> "Error: The user stopped the question. No permission was granted."
        null -> error("Question has not been resolved")
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("sessionId", sessionId)
        put("id", request.id)
        put("instanceId", request.instanceId)
        put("title", request.title)
        put("timeoutMs", request.timeoutMs)
        put("expiresAtWallMs", request.expiresAtWallMs)
        put("createdAtWallMs", request.createdAtWallMs)
        put("deferred", request.deferred)
        put("displayedIndex", request.displayedIndex)
        put("questions", JSONArray().apply {
            request.questions.forEach { q -> put(JSONObject().apply {
                put("id", q.id); put("question", q.question); put("header", q.header)
                put("kind", q.kind.name); put("allowOther", q.allowOther)
                put("options", JSONArray().apply { q.options.forEach { o ->
                    put(JSONObject().put("label", o.label).put("description", o.description))
                } })
            }) }
        })
        put("answers", JSONObject(request.answers))
        put("selections", JSONObject().apply { request.selections.forEach { (id, s) ->
            put(id, JSONObject().put("value", s.value).put("isOther", s.isOther))
        } })
        put("freeTextDrafts", JSONObject(request.freeTextDrafts))
        outcome?.let { put("outcome", it.name) }
        assistantPartsJson?.let { put("assistantPartsJson", it) }
    }

    companion object {
        fun fromJson(json: JSONObject, nowElapsedMs: Long, nowWallMs: Long): UserQuestionRecord {
            fun strings(key: String): Map<String, String> = json.optJSONObject(key)?.let { o ->
                o.keys().asSequence().associateWith { o.getString(it) }
            }.orEmpty()
            val raw = json.getJSONArray("questions")
            require(raw.length() in 1..4)
            val questions = (0 until raw.length()).map { i ->
                val q = raw.getJSONObject(i)
                val options = q.getJSONArray("options")
                require(options.length() <= 10)
                UserQuestionPrompt(q.getString("id"), q.getString("question"), q.optString("header"),
                    (0 until options.length()).map { n -> options.getJSONObject(n).let {
                        UserQuestionOption(it.getString("label"), it.optString("description"))
                    } }, q.getBoolean("allowOther"), UserQuestionKind.valueOf(q.getString("kind")))
            }
            require(questions.all { it.id.isNotBlank() && it.question.isNotBlank() })
            require(questions.map { it.id }.distinct().size == questions.size)
            val timeout = json.getLong("timeoutMs").also { require(it == 0L || it in 1_000L..86_400_000L) }
            val deferred = json.optBoolean("deferred")
            val deadline = json.getLong("expiresAtWallMs").also { require(it > 0L || (timeout == 0L && !deferred && it == 0L)) }
            val selections = json.optJSONObject("selections")?.let { o -> o.keys().asSequence().associateWith { id ->
                o.getJSONObject(id).let { UserQuestionSelection(it.getString("value"), it.optBoolean("isOther")) }
            } }.orEmpty()
            val request = PendingUserQuestion(
                id = json.getString("id"), questions = questions, title = json.optString("title"), timeoutMs = timeout,
                instanceId = json.getString("instanceId"), expiresAtElapsedMs = nowElapsedMs + (deadline - nowWallMs).coerceAtLeast(0L),
                answers = strings("answers"), selections = selections, freeTextDrafts = strings("freeTextDrafts"), expiresAtWallMs = deadline,
                deferred = deferred, createdAtWallMs = json.optLong("createdAtWallMs"),
                displayedIndex = json.optInt("displayedIndex", -1),
            )
            require(request.id.isNotBlank() && request.instanceId.isNotBlank())
            val validIds = questions.map { it.id }.toSet()
            require(request.answers.keys.all { it in validIds })
            require(request.answers.all { (id, answer) -> answer.isNotBlank() && questions.first { it.id == id }.let {
                it.options.isEmpty() || it.allowOther || it.options.any { option -> option.label == answer }
            } })
            val outcome = if (json.has("outcome")) UserQuestionOutcome.valueOf(json.getString("outcome")) else null
            require(outcome != UserQuestionOutcome.ANSWERED || request.answers.keys == validIds)
            require(outcome != null || request.currentQuestion != null)
            return UserQuestionRecord(json.getString("sessionId").also { require(it.isNotBlank() && !it.startsWith("draft")) },
                request, outcome, json.optString("assistantPartsJson").takeIf { it.isNotBlank() })
        }
    }
}

internal interface UserQuestionRecords {
    fun all(nowElapsedMs: Long, nowWallMs: Long): List<UserQuestionRecord>
    fun save(record: UserQuestionRecord)
    fun remove(instanceId: String)
}

internal class UserQuestionRecordStore(context: Context) : UserQuestionRecords {
    private val prefs = context.applicationContext.getSharedPreferences("minis_user_questions", Context.MODE_PRIVATE)

    override fun all(nowElapsedMs: Long, nowWallMs: Long): List<UserQuestionRecord> = prefs.all.mapNotNull { (key, value) ->
        runCatching { UserQuestionRecord.fromJson(JSONObject(value as String), nowElapsedMs, nowWallMs)
            .takeIf { it.request.instanceId == key } }.getOrNull()
    }

    override fun save(record: UserQuestionRecord) {
        check(prefs.edit().putString(record.request.instanceId, record.toJson().toString()).commit()) { "Question persistence failed" }
    }

    override fun remove(instanceId: String) {
        check(prefs.edit().remove(instanceId).commit()) { "Question persistence failed" }
    }
}
