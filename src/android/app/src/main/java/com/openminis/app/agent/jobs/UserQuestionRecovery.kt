package com.openminis.app.agent.jobs

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** The VM supplies the chat DB adapter; recovery never starts an agent or repeats a tool. */
internal interface UserQuestionTranscript {
    suspend fun sessionExists(sessionId: String): Boolean
    suspend fun rows(sessionId: String): List<String>
    suspend fun append(sessionId: String, role: String, partsJson: String)
    suspend fun replace(sessionId: String, previousPartsJson: String, partsJson: String) {
        error("Transcript adapter must support deferred result replacement")
    }
}

internal object UserQuestionRecovery {
    private val delivery = Mutex()

    /** Write results first, acknowledge second; true means reload the transcript and resume. */
    suspend fun deliver(sessionId: String, transcript: UserQuestionTranscript): Boolean = delivery.withLock {
        val recovered = UserQuestionRegistry.recoveredResults.value.filter { it.sessionId == sessionId }
        if (recovered.isEmpty()) return@withLock false
        if (!transcript.sessionExists(sessionId)) {
            recovered.forEach { UserQuestionRegistry.acknowledge(sessionId, it.request.id) }
            return@withLock false
        }
        var resume = false
        for (saved in recovered) {
            var record = UserQuestionRegistry.recordsForSession(sessionId).firstOrNull {
                it.request.instanceId == saved.request.instanceId
            } ?: continue
            val rows = transcript.rows(sessionId).mapNotNull { runCatching { JSONArray(it) }.getOrNull() }
            record = UserQuestionRegistry.recordsForSession(sessionId).firstOrNull {
                it.request.instanceId == saved.request.instanceId
            } ?: continue
            val resultIds = rows.flatMap { ids(it, "toolResult") }.toMutableSet()
            if (record.request.id in resultIds && !record.request.deferred) {
                UserQuestionRegistry.acknowledge(sessionId, record.request.id)
                continue
            }
            val savedAssistant = rows.firstOrNull { record.request.id in ids(it, "toolUse") }
            val snapshot = record.assistantPartsJson?.let { runCatching { JSONArray(it) }.getOrNull() }
                ?.takeIf { record.request.id in ids(it, "toolUse") }
            val assistant = savedAssistant ?: snapshot
            if (assistant == null) {
                // An old fork record may lack its assistant snapshot. Preserve the
                // answer as a user message without inventing an unmatched tool_result.
                val marker = "[Recovered user question ${record.request.instanceId}]"
                val alreadyWritten = rows.any { row -> (0 until row.length()).any {
                    row.optJSONObject(it)?.let { p -> p.optString("type") == "text" && p.optString("value").startsWith(marker) } == true
                } }
                if (!alreadyWritten) transcript.append(sessionId, "user", JSONArray().put(JSONObject()
                    .put("type", "text").put("value", "$marker\n${record.output}")).toString())
                val mayResume = UserQuestionRegistry.recordsForSession(sessionId)
                    .firstOrNull { it.request.instanceId == saved.request.instanceId }?.resumesRun == true
                UserQuestionRegistry.acknowledge(sessionId, record.request.id)
                resume = resume || (!alreadyWritten && mayResume)
                continue
            }
            if (savedAssistant == null) transcript.append(sessionId, "assistant", assistant.toString())
            val siblings = UserQuestionRegistry.recordsForSession(sessionId).associateBy { it.request.id }
            val results = JSONArray()
            val delivered = mutableListOf<UserQuestionRecord>()
            val toolIds = ids(assistant, "toolUse")
            for (toolId in toolIds.filter { it !in resultIds }) {
                val sibling = siblings[toolId]
                // Another recovered question in the same assistant batch is still open.
                if (sibling != null && sibling.outcome == null) continue
                results.put(JSONObject().put("type", "toolResult").put("value", JSONObject()
                    .put("toolUseId", toolId)
                    .put("name", (0 until assistant.length()).mapNotNull { assistant.optJSONObject(it)?.optJSONObject("value") }
                        .firstOrNull { it.optString("toolUseId") == toolId }?.optString("name").orEmpty())
                    .put("output", sibling?.output ?: "Error: This tool was interrupted by an app restart. It was not run again; check any partial effects before retrying.")
                    .put("success", sibling?.outcome == UserQuestionOutcome.ANSWERED)))
                resultIds.add(toolId)
                if (sibling != null) delivered.add(sibling)
            }
            if (results.length() > 0) transcript.append(sessionId, "user", results.toString())
            if (record.request.deferred) {
                val latestRows = transcript.rows(sessionId)
                record = UserQuestionRegistry.recordsForSession(sessionId).firstOrNull {
                    it.request.instanceId == saved.request.instanceId
                } ?: continue
                for (parts in latestRows) {
                    val array = runCatching { JSONArray(parts) }.getOrNull() ?: continue
                    var changed = false
                    for (i in 0 until array.length()) {
                        val part = array.optJSONObject(i) ?: continue
                        val value = part.optJSONObject("value") ?: continue
                        if (part.optString("type") == "toolResult" && value.optString("toolUseId") == record.request.id) {
                            value.put("output", record.output).put("success", record.outcome == UserQuestionOutcome.ANSWERED)
                            changed = true
                        }
                    }
                    if (changed) transcript.replace(sessionId, parts, array.toString())
                }
                val marker = "[User question ${record.request.instanceId}]"
                fun containsAnswer(parts: String): Boolean {
                    val array = runCatching { JSONArray(parts) }.getOrNull() ?: return false
                    return (0 until array.length()).any { array.optJSONObject(it)?.let { part ->
                        part.optString("type") == "text" && part.optString("value").startsWith(marker)
                    } == true }
                }
                val alreadySent = latestRows.any(::containsAnswer)
                record = UserQuestionRegistry.recordsForSession(sessionId).firstOrNull {
                    it.request.instanceId == saved.request.instanceId
                } ?: continue
                // The deferred tool result is already closed. The real answer starts a
                // new user turn, rather than re-running the original tool or its siblings.
                if (record.outcome == UserQuestionOutcome.ANSWERED && !alreadySent) {
                    val answers = record.request.questions.joinToString("\n") { "${it.question}\n${record.request.answers[it.id].orEmpty()}" }
                    transcript.append(sessionId, "user", JSONArray().put(JSONObject().put("type", "text")
                        .put("value", "$marker\n$answers\n<system-reminder>The user's answers to your earlier question card.</system-reminder>")).toString())
                }
                val mayResume = UserQuestionRegistry.recordsForSession(sessionId)
                    .firstOrNull { it.request.instanceId == saved.request.instanceId }?.outcome == UserQuestionOutcome.ANSWERED
                UserQuestionRegistry.acknowledge(sessionId, record.request.id)
                if (toolIds.all { it in resultIds } && mayResume &&
                    (!alreadySent || latestRows.lastOrNull()?.let(::containsAnswer) == true)) resume = true
            }
            val mayResume = UserQuestionRegistry.recordsForSession(sessionId).any { current ->
                !current.request.deferred && current.resumesRun && delivered.any { it.request.instanceId == current.request.instanceId }
            }
            delivered.filterNot { it.request.deferred }.forEach { UserQuestionRegistry.acknowledge(sessionId, it.request.id) }
            // Never resume with unmatched sibling calls, or after Stop/dismissal.
            if (toolIds.all { it in resultIds } && mayResume) resume = true
        }
        resume
    }

    private fun ids(parts: JSONArray, type: String): List<String> = (0 until parts.length()).mapNotNull { index ->
        parts.optJSONObject(index)?.takeIf { it.optString("type") == type }?.optJSONObject("value")
            ?.optString("toolUseId")?.takeIf { it.isNotBlank() }
    }
}
