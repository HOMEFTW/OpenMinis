package com.openminis.app.agent.jobs

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.data.model.AgentSchemaNode
import com.openminis.app.ui.chat.PendingUserQuestion
import com.openminis.app.ui.chat.UserQuestionOption
import com.openminis.app.ui.chat.UserQuestionPrompt
import com.openminis.app.ui.chat.UserQuestionKind
import com.openminis.app.ui.chat.UserQuestionSelection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
    const val STABLE_NAME = "user_ask"
    const val DEFERRED_LIFETIME_MS = 60 * 60_000L
    const val DEFERRED_NOTE = "The card is shown; the answer will arrive as the user's next message. End your turn now."

    fun definition(name: String = STABLE_NAME) = AgentToolDefinition(
        name = name,
        description = "Ask the user for choices or missing information that materially affect the task. An interactive run waits for the answer. In an unattended run the card remains open, the tool returns deferred immediately, and the answer arrives as the user's next message. After deferred, end the turn. A timeout or cancellation never grants permission.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "Short title in the user's language."),
            "questions" to AgentToolParam("array", "1-4 questions. Empty options allow free text; confirm requires approval for the exact action.",
                schema = AgentSchemaNode.Arr(minItems = 1, maxItems = 4, items = AgentSchemaNode.Obj(properties = listOf(
                    "id" to AgentSchemaNode.Str("Unique question id."),
                    "type" to AgentSchemaNode.Str(enumValues = listOf("options", "confirm")),
                    "question" to AgentSchemaNode.Str("Question or exact action requiring approval."),
                    "header" to AgentSchemaNode.Str("Short heading."),
                    "impact" to AgentSchemaNode.Str("Consequences of the action."),
                    "options" to AgentSchemaNode.Arr(items = AgentSchemaNode.Obj(properties = listOf(
                        "label" to AgentSchemaNode.Str(), "description" to AgentSchemaNode.Str(),
                    ), required = listOf("label")), maxItems = 10),
                    "allow_other" to AgentSchemaNode.Bool("Allow a free text answer (default true)."),
                ), required = listOf("question")))),
            "timeout_seconds" to AgentToolParam("integer", "Optional legacy override in seconds, 0-3600; 0 waits indefinitely. Otherwise the user settings decide. Interactions renew the countdown. A timeout or cancellation does not grant permission."),
        ),
        required = listOf("questions"),
    )

    fun parse(id: String, argsJson: String, timeoutMinutes: Int = UserQuestionSettings.DEFAULT_TIMEOUT_MINUTES): PendingUserQuestion {
        val args = definition().coerceNestedJsonStrings(JSONObject(argsJson))
        val raw = args.optJSONArray("questions") ?: args.optString("questions", "").takeIf { it.isNotBlank() }?.let { JSONArray(it) }
            ?: JSONArray().put(args)
        require(raw.length() in 1..4) { "Provide 1-4 questions." }
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
                question = listOf(text, question.optString("impact").trim()).filter { it.isNotBlank() }.joinToString("\n"),
                header = question.optString("header", ""), options = choices,
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
            timeoutMs = if (args.has("timeout_seconds")) args.optLong("timeout_seconds", 300L).coerceIn(0L, 3600L) * 1000L
                else timeoutMinutes.coerceIn(0, UserQuestionSettings.MAX_TIMEOUT_MINUTES) * 60_000L,
        )
    }

    fun deferredOutput(questionCount: Int): String = JSONObject().put("note", DEFERRED_NOTE)
        .put("results", JSONArray().apply { repeat(questionCount) { put(JSONObject().put("i", it).put("status", "deferred")) } }).toString()

    fun isDeferredOutput(output: String): Boolean = runCatching {
        val results = JSONObject(output).getJSONArray("results")
        results.length() > 0 && (0 until results.length()).all { results.getJSONObject(it).optString("status") == "deferred" }
    }.getOrDefault(false)
}

/** One visible question at a time, with cancellation tied to its tool coroutine. */
internal class UserQuestionBroker(
    private val nowMs: () -> Long = android.os.SystemClock::elapsedRealtime,
    private val onChanged: (PendingUserQuestion?) -> Unit = {},
    private val wallNowMs: () -> Long = System::currentTimeMillis,
) {
    private val slot = Mutex()
    private val stateLock = Any()
    private val _pending = MutableStateFlow<PendingUserQuestion?>(null)
    val pending = _pending.asStateFlow()
    private var reply: CompletableDeferred<Map<String, String>>? = null
    private var recovered = false
    internal var onResolved: ((PendingUserQuestion, UserQuestionOutcome) -> Unit)? = null

    private fun visible(request: PendingUserQuestion): PendingUserQuestion {
        val lifetime = if (request.deferred) UserQuestionTool.DEFERRED_LIFETIME_MS else request.timeoutMs
        return request.copy(instanceId = UUID.randomUUID().toString(),
            expiresAtElapsedMs = if (lifetime > 0) nowMs() + lifetime else 0L,
            expiresAtWallMs = if (lifetime > 0) wallNowMs() + lifetime else 0L,
            createdAtWallMs = wallNowMs(), displayedIndex = 0,
            answers = emptyMap(), selections = emptyMap(), freeTextDrafts = emptyMap())
    }

    /** No suspended tool owns a deferred card; its durable answer is delivered later. */
    fun defer(request: PendingUserQuestion) = synchronized(stateLock) {
        _pending.value?.let { resolve(it, UserQuestionOutcome.DISMISSED) }
        check(reply == null)
        recovered = true
        publish(visible(request.copy(deferred = true, timeoutMs = 0L)))
    }

    suspend fun ask(request: PendingUserQuestion): Map<String, String> = slot.withLock {
        val response = CompletableDeferred<Map<String, String>>()
        val visible = visible(request)
        try {
            synchronized(stateLock) { reply = response; recovered = false; publish(visible) }
            while (!response.isCompleted) {
                val current = _pending.value ?: break
                if (!current.hasCountdown) return@withLock response.await()
                val changed = withTimeoutOrNull(current.remainingMs(nowMs()).coerceAtLeast(1L)) {
                    _pending.first { it == null || it.expiresAtElapsedMs != current.expiresAtElapsedMs }
                    true
                } ?: false
                if (!changed) synchronized(stateLock) {
                    _pending.value?.takeIf { reply === response && it.expiresAtElapsedMs == current.expiresAtElapsedMs }
                        ?.let { resolve(it, UserQuestionOutcome.EXPIRED) }
                }
            }
            response.await()
        }
        finally {
            synchronized(stateLock) {
                if (reply === response) {
                    _pending.value?.let { resolve(it, UserQuestionOutcome.CANCELLED) }
                    reply = null
                }
            }
            response.cancel()
        }
    }

    /** A restored card owns no coroutine. Its result stays durable for transcript recovery. */
    internal fun restore(request: PendingUserQuestion) = synchronized(stateLock) {
        check(reply == null && _pending.value == null)
        recovered = true
        publish(request)
        expire()
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
        resolve(request.copy(answers = validated), UserQuestionOutcome.ANSWERED)
    }

    fun select(id: String, instanceId: String, questionId: String, value: String, asOther: Boolean = false): Boolean = synchronized(stateLock) {
        val request = liveRequest(id, instanceId) ?: return false
        val question = request.currentQuestion?.takeIf { it.id == questionId } ?: return false
        if (asOther && question.options.isNotEmpty() && !question.allowOther) return false
        if (!asOther && question.options.isNotEmpty() && question.options.none { it.label == value }) return false
        publish(renew(request.copy(
            selections = request.selections + (questionId to UserQuestionSelection(value, asOther)),
            freeTextDrafts = if (asOther) request.freeTextDrafts + (questionId to value) else request.freeTextDrafts,
        )))
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
            resolve(request.copy(answers = answers), UserQuestionOutcome.ANSWERED)
        } else {
            val next = (request.currentIndex + 1).takeIf { it in request.questions.indices }
                ?: request.questions.indexOfFirst { it.id !in answers }
            publish(renew(request.copy(answers = answers, displayedIndex = next,
                selections = request.selections + (questionId to UserQuestionSelection(valueTrimmed)))))
            true
        }
    }

    fun dismiss(id: String, instanceId: String): Boolean = synchronized(stateLock) {
        val request = liveRequest(id, instanceId) ?: return false
        resolve(request, UserQuestionOutcome.DISMISSED)
    }

    fun goTo(id: String, instanceId: String, index: Int): Boolean = synchronized(stateLock) {
        val request = liveRequest(id, instanceId) ?: return false
        if (index !in request.questions.indices) return false
        publish(renew(request.copy(displayedIndex = index)))
        true
    }

    fun noteInteraction(id: String, instanceId: String): Boolean = synchronized(stateLock) {
        val request = liveRequest(id, instanceId) ?: return false
        publish(renew(request))
        true
    }

    private fun renew(request: PendingUserQuestion): PendingUserQuestion = if (!request.hasCountdown) request
        else request.copy(expiresAtElapsedMs = nowMs() + request.timeoutMs, expiresAtWallMs = wallNowMs() + request.timeoutMs)

    fun cancel() = synchronized(stateLock) {
        _pending.value?.let { resolve(it, UserQuestionOutcome.CANCELLED) }
    }

    internal fun expire() = synchronized(stateLock) {
        _pending.value?.takeIf { it.remainingMs(nowMs()) == 0L }?.let { resolve(it, UserQuestionOutcome.EXPIRED) }
    }

    private fun resolve(request: PendingUserQuestion, outcome: UserQuestionOutcome): Boolean {
        val response = reply
        if (response == null && !recovered) return false
        // Persist before unblocking a live tool or clearing a recovered card.
        onResolved?.invoke(request, outcome)
        when (outcome) {
            UserQuestionOutcome.ANSWERED -> response?.complete(request.answers)
            UserQuestionOutcome.EXPIRED -> response?.completeExceptionally(UserQuestionExpiredException())
            UserQuestionOutcome.DISMISSED -> response?.completeExceptionally(UserQuestionDismissedException())
            UserQuestionOutcome.CANCELLED -> response?.cancel()
        }
        reply = null
        recovered = false
        publish(null)
        return true
    }

    private fun liveRequest(id: String, instanceId: String?): PendingUserQuestion? {
        val request = _pending.value ?: return null
        if (request.id != id || (instanceId != null && request.instanceId != instanceId)) return null
        if (request.remainingMs(nowMs()) == 0L) {
            resolve(request, UserQuestionOutcome.EXPIRED)
            return null
        }
        return request
    }

    private fun accepts(question: UserQuestionPrompt, answer: String): Boolean =
        question.options.isEmpty() || question.allowOther || question.options.any { it.label == answer }

    private fun publish(request: PendingUserQuestion?) {
        onChanged(request)
        _pending.value = request
    }
}

internal class UserQuestionExpiredException : Exception("The user did not answer before the question expired. No permission was granted.")
internal class UserQuestionDismissedException : Exception("The user cancelled the question. No permission was granted.")
