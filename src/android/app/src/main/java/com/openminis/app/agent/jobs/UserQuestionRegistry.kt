package com.openminis.app.agent.jobs

import com.openminis.app.ui.chat.PendingUserQuestion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** In-process routing only: a notification cannot revive or answer a lost agent run. */
internal object UserQuestionRegistry {
    private data class Entry(val request: PendingUserQuestion, val broker: UserQuestionBroker)
    private val lock = Any()
    private val entries = linkedMapOf<String, Entry>()
    private val _pending = MutableStateFlow<Map<String, PendingUserQuestion>>(emptyMap())
    val pending = _pending.asStateFlow()

    fun update(sessionId: String, request: PendingUserQuestion?, broker: UserQuestionBroker) = synchronized(lock) {
        entries.entries.removeAll { it.value.broker === broker }
        if (request != null && sessionId.isNotBlank()) entries[sessionId] = Entry(request, broker)
        _pending.value = entries.mapValues { it.value.request }
    }

    fun answer(sessionId: String, instanceId: String, questionId: String, value: String): Boolean {
        // Do not hold the registry lock while entering the broker: its change callback returns here.
        val entry = synchronized(lock) { entries[sessionId]?.takeIf { it.request.instanceId == instanceId } } ?: return false
        return entry.broker.answerQuestion(entry.request.id, instanceId, questionId, value)
    }
}
