package com.openminis.app.agent.jobs

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.ui.chat.PendingUserQuestion
import com.openminis.app.ui.chat.UserQuestionOption
import com.openminis.app.ui.chat.UserQuestionPrompt
import com.openminis.app.ui.chat.UserQuestionKind
import com.openminis.app.ui.chat.UserQuestionSelection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal object UserQuestionTool {
    const val NAME = "request_user_input"
    const val ALIAS = "ask_user"
    const val LEGACY_ALIAS = "ask_question"

    fun definition() = AgentToolDefinition(
        name = NAME,
        description = "Ask the user a question and wait for their answer before continuing. Use for choices or missing information that materially affect the task. Do not use in unattended or sub-agent runs.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "Short title in the user's language."),
            "questions" to AgentToolParam("string", "JSON array of 1-3 questions. Each has a unique id, question, optional header, options [{label,description}], and allow_other (default true). Empty options allow free text. type=confirmation offers Allow/Cancel when no options are given. Missing/unknown types are inferred from the fields."),
            "timeout_seconds" to AgentToolParam("integer", "Time to wait for the whole question batch, 1-3600 seconds (default 300). A timeout or cancellation does not grant permission."),
        ),
        required = listOf("questions"),
    )

    fun parse(id: String, argsJson: String): PendingUserQuestion {
        val args = JSONObject(argsJson)
        val raw = args.optJSONArray("questions") ?: args.optString("questions", "").takeIf { it.isNotBlank() }?.let { JSONArray(it) }
            ?: JSONArray().put(args)
        require(raw.length() in 1..3) { "Provide 1-3 questions." }
        val questions = (0 until raw.length()).map { index ->
            val question = raw.getJSONObject(index)
            val text = question.optString("question", "").trim()
            require(text.isNotBlank()) { "Each question needs question text." }
            val options = question.optJSONArray("options") ?: JSONArray()
            require(options.length() <= 10) { "Provide at most 10 options." }
            val explicitChoices = (0 until options.length()).map { optionIndex ->
                val value = options.get(optionIndex)
                val label = if (value is JSONObject) value.optString("label", "").trim() else value.toString().trim()
                require(label.isNotBlank()) { "Each option needs a label." }
                UserQuestionOption(label, (value as? JSONObject)?.optString("description", "").orEmpty())
            }
            val isConfirmation = explicitChoices.isEmpty() && (
                question.optString("type").lowercase() in setOf("confirmation", "confirm", "approval", "permission", "boolean") ||
                    question.optBoolean("confirm", false)
                )
            val choices = if (isConfirmation) listOf(UserQuestionOption("Allow"), UserQuestionOption("Cancel")) else explicitChoices
            UserQuestionPrompt(
                id = question.optString("id", "").trim().ifBlank { "question_${index + 1}" },
                question = text, header = question.optString("header", ""), options = choices,
                allowOther = !isConfirmation && question.optBoolean("allow_other", question.optBoolean("allowOther", true)),
                kind = when {
                    isConfirmation -> UserQuestionKind.CONFIRMATION
                    choices.isNotEmpty() -> UserQuestionKind.CHOICE
                    else -> UserQuestionKind.TEXT
                },
            )
        }
        require(questions.map { it.id }.distinct().size == questions.size) { "Question ids must be unique." }
        return PendingUserQuestion(
            id, questions, title = args.optString("tool_title", ""),
            timeoutMs = args.optLong("timeout_seconds", 300L).coerceIn(1L, 3600L) * 1000L,
        )
    }
}

/** One visible question at a time, with cancellation tied to its tool coroutine. */
internal class UserQuestionBroker(
    private val nowMs: () -> Long = android.os.SystemClock::elapsedRealtime,
    private val onChanged: (PendingUserQuestion?) -> Unit = {},
) {
    private val slot = Mutex()
    private val stateLock = Any()
    private val _pending = MutableStateFlow<PendingUserQuestion?>(null)
    val pending = _pending.asStateFlow()
    private var reply: CompletableDeferred<Map<String, String>>? = null

    suspend fun ask(request: PendingUserQuestion): Map<String, String> = slot.withLock {
        val response = CompletableDeferred<Map<String, String>>()
        val visible = request.copy(
            instanceId = UUID.randomUUID().toString(), expiresAtElapsedMs = nowMs() + request.timeoutMs,
            answers = emptyMap(), selections = emptyMap(), freeTextDrafts = emptyMap(),
        )
        synchronized(stateLock) { reply = response; publish(visible) }
        try { withTimeoutOrNull(request.timeoutMs) { response.await() } ?: throw UserQuestionExpiredException() }
        finally {
            synchronized(stateLock) {
                if (reply === response) { reply = null; publish(null) }
            }
        }
    }

    fun answer(id: String, answers: Map<String, String>, instanceId: String? = null): Boolean = synchronized(stateLock) {
        val request = liveRequest(id, instanceId) ?: return false
        val validated = linkedMapOf<String, String>()
        for (question in request.questions) {
            val answer = answers[question.id]?.trim()?.takeIf { it.isNotBlank() } ?: return false
            if (!accepts(question, answer)) return false
            if (request.answers[question.id]?.let { it != answer } == true) return false
            validated[question.id] = answer
        }
        val accepted = reply?.complete(validated) == true
        if (accepted) { reply = null; publish(null) }
        accepted
    }

    fun select(id: String, instanceId: String, questionId: String, value: String, asOther: Boolean = false): Boolean = synchronized(stateLock) {
        val request = liveRequest(id, instanceId) ?: return false
        val question = request.currentQuestion?.takeIf { it.id == questionId } ?: return false
        if (asOther && question.options.isNotEmpty() && !question.allowOther) return false
        if (!asOther && question.options.isNotEmpty() && question.options.none { it.label == value }) return false
        publish(request.copy(
            selections = request.selections + (questionId to UserQuestionSelection(value, asOther)),
            freeTextDrafts = if (asOther) request.freeTextDrafts + (questionId to value) else request.freeTextDrafts,
        ))
        true
    }

    fun submitSelected(id: String, instanceId: String, questionId: String): Boolean = synchronized(stateLock) {
        val request = liveRequest(id, instanceId) ?: return false
        val selection = request.selections[questionId] ?: return false
        answerQuestion(id, instanceId, questionId, selection.value)
    }

    /** Notification buttons and the card advance the same batch, one question at a time. */
    fun answerQuestion(id: String, instanceId: String, questionId: String, value: String): Boolean = synchronized(stateLock) {
        val request = liveRequest(id, instanceId) ?: return false
        val question = request.currentQuestion?.takeIf { it.id == questionId } ?: return false
        val valueTrimmed = value.trim()
        if (valueTrimmed.isBlank() || !accepts(question, valueTrimmed)) return false
        val answers = request.answers + (questionId to valueTrimmed)
        if (answers.size == request.questions.size) {
            val accepted = reply?.complete(answers) == true
            if (accepted) { reply = null; publish(null) }
            accepted
        } else {
            publish(request.copy(answers = answers, selections = request.selections + (questionId to UserQuestionSelection(valueTrimmed))))
            true
        }
    }

    fun dismiss(id: String, instanceId: String): Boolean = synchronized(stateLock) {
        liveRequest(id, instanceId) ?: return false
        val accepted = reply?.completeExceptionally(UserQuestionDismissedException()) == true
        if (accepted) { reply = null; publish(null) }
        accepted
    }

    fun cancel() = synchronized(stateLock) { reply?.cancel(); reply = null; publish(null) }

    private fun liveRequest(id: String, instanceId: String?): PendingUserQuestion? {
        val request = _pending.value ?: return null
        if (request.id != id || (instanceId != null && request.instanceId != instanceId)) return null
        if (request.remainingMs(nowMs()) == 0L) {
            reply?.completeExceptionally(UserQuestionExpiredException())
            reply = null
            publish(null)
            return null
        }
        return request
    }

    private fun accepts(question: UserQuestionPrompt, answer: String): Boolean =
        question.options.isEmpty() || question.allowOther || question.options.any { it.label == answer }

    private fun publish(request: PendingUserQuestion?) {
        _pending.value = request
        onChanged(request)
    }
}

internal class UserQuestionExpiredException : Exception("The user did not answer before the question expired. No permission was granted.")
internal class UserQuestionDismissedException : Exception("The user cancelled the question. No permission was granted.")
