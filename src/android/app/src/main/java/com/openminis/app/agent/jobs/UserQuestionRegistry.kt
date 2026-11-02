package com.openminis.app.agent.jobs

import android.content.Context
import com.openminis.app.ui.chat.PendingUserQuestion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Live routing and durable orphaned cards share the fork's existing broker. */
internal object UserQuestionRegistry {
    private data class Entry(val request: PendingUserQuestion, val broker: UserQuestionBroker)
    private val lock = Any()
    private val entries = linkedMapOf<String, Entry>()
    private val records = linkedMapOf<String, UserQuestionRecord>()
    private val recoveredInstances = mutableSetOf<String>()
    private var store: UserQuestionRecords? = null
    private val _pending = MutableStateFlow<Map<String, PendingUserQuestion>>(emptyMap())
    val pending = _pending.asStateFlow()
    private val _recoveredResults = MutableStateFlow<List<UserQuestionRecord>>(emptyList())
    val recoveredResults = _recoveredResults.asStateFlow()

    fun initialize(context: Context) = synchronized(lock) {
        if (store == null) install(UserQuestionRecordStore(context))
    }

    /** On cold start, keep the original instance id so existing PendingIntents still route. */
    internal fun install(
        persistence: UserQuestionRecords,
        nowElapsedMs: () -> Long = android.os.SystemClock::elapsedRealtime,
        nowWallMs: () -> Long = System::currentTimeMillis,
    ) = synchronized(lock) {
        check(store == null)
        val loaded = persistence.all(nowElapsedMs(), nowWallMs())
        store = persistence
        loaded.forEach { r ->
            if (records[r.request.instanceId] == null) {
                records[r.request.instanceId] = r
                recoveredInstances.add(r.request.instanceId)
            }
        }
        // Service creation can follow the first live publish; flush those snapshots too.
        records.values.filter { it.request.instanceId !in recoveredInstances }.forEach(persistence::save)
        loaded.filter { it.outcome == null && it.sessionId !in entries }.forEach { r ->
            lateinit var broker: UserQuestionBroker
            broker = UserQuestionBroker(nowMs = nowElapsedMs, wallNowMs = nowWallMs,
                onChanged = { update(r.sessionId, it, broker) })
            bind(r.sessionId, broker)
            broker.restore(r.request)
        }
        publishResults()
    }

    /** Optional snapshot must include the assistant's toolUse, saved before the card appears. */
    fun update(sessionId: String, request: PendingUserQuestion?, broker: UserQuestionBroker, assistantPartsJson: String? = null) = synchronized(lock) {
        if (request != null && sessionId.isNotBlank()) {
            bind(sessionId, broker)
            if (!sessionId.startsWith("draft")) {
                val previous = records[request.instanceId]
                val record = UserQuestionRecord(sessionId, request, assistantPartsJson = assistantPartsJson ?: previous?.assistantPartsJson)
                store?.save(record)
                records[request.instanceId] = record
                if (request.deferred) recoveredInstances.add(request.instanceId)
            }
        }
        entries.entries.removeAll { it.value.broker === broker }
        if (request != null && sessionId.isNotBlank()) entries[sessionId] = Entry(request, broker)
        _pending.value = entries.mapValues { it.value.request }
    }

    private fun bind(sessionId: String, broker: UserQuestionBroker) {
        broker.onResolved = { request, outcome -> synchronized(lock) {
            records[request.instanceId]?.let { previous ->
                val record = previous.copy(sessionId = sessionId, request = request, outcome = outcome)
                store?.save(record)
                records[request.instanceId] = record
                publishResults()
            }
        } }
    }

    private fun entry(sessionId: String, instanceId: String): Entry? = synchronized(lock) {
        entries[sessionId]?.takeIf { it.request.instanceId == instanceId }
    }

    fun answer(sessionId: String, instanceId: String, questionId: String, value: String): Boolean {
        // Release the registry lock before entering a broker: its callbacks return here.
        val entry = entry(sessionId, instanceId) ?: return false
        return entry.broker.answerQuestion(entry.request.id, instanceId, questionId, value)
    }

    fun select(sessionId: String, instanceId: String, questionId: String, value: String, asOther: Boolean): Boolean {
        val entry = entry(sessionId, instanceId) ?: return false
        return entry.broker.select(entry.request.id, instanceId, questionId, value, asOther)
    }

    fun submitSelected(sessionId: String, instanceId: String, questionId: String): Boolean {
        val entry = entry(sessionId, instanceId) ?: return false
        return entry.broker.submitSelected(entry.request.id, instanceId, questionId)
    }

    fun dismiss(sessionId: String, instanceId: String): Boolean {
        val entry = entry(sessionId, instanceId) ?: return false
        return entry.broker.dismiss(entry.request.id, instanceId)
    }

    /** The instance id remains stable across process recovery and card navigation. */
    fun goTo(instanceId: String, index: Int): Boolean {
        val entry = synchronized(lock) { entries.values.firstOrNull { it.request.instanceId == instanceId } } ?: return false
        return entry.broker.goTo(entry.request.id, instanceId, index)
    }

    fun noteInteraction(instanceId: String): Boolean {
        val entry = synchronized(lock) { entries.values.firstOrNull { it.request.instanceId == instanceId } } ?: return false
        return entry.broker.noteInteraction(entry.request.id, instanceId)
    }

    /** Stop also closes a recovered broker that the VM itself does not own. */
    fun cancelSession(sessionId: String) {
        val broker = synchronized(lock) { entries[sessionId]?.broker }
        broker?.cancel()
        synchronized(lock) {
            // An answer can already be durable while its DB delivery is queued.
            // Stop wins that window too; it must not wake the run afterwards.
            records.values.filter { it.sessionId == sessionId && it.outcome != UserQuestionOutcome.CANCELLED }.toList().forEach {
                val cancelled = it.copy(outcome = UserQuestionOutcome.CANCELLED)
                store?.save(cancelled)
                records[it.request.instanceId] = cancelled
                recoveredInstances.add(it.request.instanceId)
            }
            publishResults()
        }
    }

    fun expire() {
        val brokers = synchronized(lock) { entries.values.map { it.broker }.distinct() }
        brokers.forEach { it.expire() }
    }

    fun acknowledgeToolResult(sessionId: String, toolUseId: String, output: String) {
        // Initial deferred output closes the tool call while the question stays open.
        if (UserQuestionTool.isDeferredOutput(output) && synchronized(lock) {
                records.values.any { it.sessionId == sessionId && it.request.id == toolUseId && it.request.deferred }
            }) return
        acknowledge(sessionId, toolUseId)
    }

    /** Call only after the matching tool_result is committed to the chat database. */
    fun acknowledge(sessionId: String, toolUseId: String) = synchronized(lock) {
        records.values.filter { it.sessionId == sessionId && it.request.id == toolUseId && it.outcome != null }.toList().forEach {
            store?.remove(it.request.instanceId)
            records.remove(it.request.instanceId)
            recoveredInstances.remove(it.request.instanceId)
        }
        publishResults()
    }

    internal fun recordsForSession(sessionId: String): List<UserQuestionRecord> = synchronized(lock) {
        records.values.filter { it.sessionId == sessionId }
    }

    private fun publishResults() {
        _recoveredResults.value = records.values.filter { it.outcome != null && it.request.instanceId in recoveredInstances }
    }

    internal fun resetForTest() = synchronized(lock) {
        entries.clear(); records.clear(); recoveredInstances.clear(); store = null
        _pending.value = emptyMap(); _recoveredResults.value = emptyList()
    }
}
