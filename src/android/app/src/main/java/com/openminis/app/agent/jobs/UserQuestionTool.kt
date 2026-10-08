package com.openminis.app.agent.jobs

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.ui.chat.PendingUserQuestion
import com.openminis.app.ui.chat.UserQuestionOption
import com.openminis.app.ui.chat.UserQuestionPrompt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

internal object UserQuestionTool {
    const val NAME = "request_user_input"
    const val ALIAS = "ask_user"

    fun definition() = AgentToolDefinition(
        name = NAME,
        description = "Ask the user a question and wait for their answer before continuing. Use for choices or missing information that materially affect the task. Do not use in unattended or sub-agent runs.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "Short title in the user's language."),
            "questions" to AgentToolParam("string", "JSON array of 1-3 questions. Each has a unique id, question, optional header, options [{label,description}], and allow_other (default true). An empty options array allows free text."),
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
            val choices = (0 until options.length()).map { optionIndex ->
                val value = options.get(optionIndex)
                val label = if (value is JSONObject) value.optString("label", "").trim() else value.toString().trim()
                require(label.isNotBlank()) { "Each option needs a label." }
                UserQuestionOption(label, (value as? JSONObject)?.optString("description", "").orEmpty())
            }
            UserQuestionPrompt(
                id = question.optString("id", "").trim().ifBlank { "question_${index + 1}" },
                question = text, header = question.optString("header", ""), options = choices,
                allowOther = question.optBoolean("allow_other", question.optBoolean("allowOther", true)),
            )
        }
        require(questions.map { it.id }.distinct().size == questions.size) { "Question ids must be unique." }
        return PendingUserQuestion(id, questions)
    }
}

/** One visible question at a time, with cancellation tied to its tool coroutine. */
internal class UserQuestionBroker {
    private val slot = Mutex()
    private val stateLock = Any()
    private val _pending = MutableStateFlow<PendingUserQuestion?>(null)
    val pending = _pending.asStateFlow()
    private var reply: CompletableDeferred<Map<String, String>>? = null

    suspend fun ask(request: PendingUserQuestion): Map<String, String> = slot.withLock {
        val response = CompletableDeferred<Map<String, String>>()
        synchronized(stateLock) { reply = response; _pending.value = request }
        try { response.await() }
        finally {
            synchronized(stateLock) {
                if (reply === response) { reply = null; _pending.value = null }
            }
        }
    }

    fun answer(id: String, answers: Map<String, String>): Boolean = synchronized(stateLock) {
        val request = _pending.value ?: return false
        if (id != request.id) return false
        val validated = linkedMapOf<String, String>()
        for (question in request.questions) {
            val answer = answers[question.id]?.trim()?.takeIf { it.isNotBlank() } ?: return false
            if (question.options.isNotEmpty() && !question.allowOther && question.options.none { it.label == answer }) return false
            validated[question.id] = answer
        }
        val accepted = reply?.complete(validated) == true
        if (accepted) { _pending.value = null; reply = null }
        accepted
    }

    fun cancel() = synchronized(stateLock) { reply?.cancel(); reply = null; _pending.value = null }
}
