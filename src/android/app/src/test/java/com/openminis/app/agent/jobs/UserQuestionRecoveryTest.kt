package com.openminis.app.agent.jobs

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UserQuestionRecoveryTest {
    private class Records : UserQuestionRecords {
        val json = linkedMapOf<String, String>()
        override fun all(nowElapsedMs: Long, nowWallMs: Long) = json.values.map {
            UserQuestionRecord.fromJson(JSONObject(it), nowElapsedMs, nowWallMs)
        }
        override fun save(record: UserQuestionRecord) { json[record.request.instanceId] = record.toJson().toString() }
        override fun remove(instanceId: String) { json.remove(instanceId) }
        fun copy() = Records().also { it.json.putAll(json) }
    }

    private class Transcript : UserQuestionTranscript {
        var exists = true
        var failAfterAppend = false
        val data = mutableListOf<Pair<String, String>>()
        override suspend fun sessionExists(sessionId: String) = exists
        override suspend fun rows(sessionId: String) = data.map { it.second }
        override suspend fun append(sessionId: String, role: String, partsJson: String) {
            data += role to partsJson
            if (failAfterAppend && role == "user") { failAfterAppend = false; error("simulated death after DB commit") }
        }
    }

    @Before fun reset() = UserQuestionRegistry.resetForTest()
    @After fun cleanup() = UserQuestionRegistry.resetForTest()

    private fun request() = UserQuestionTool.parse("call", """{"questions":[{"id":"q1","question":"Choose","options":["A","B"]},{"id":"q2","question":"Explain"}]}""")

    private fun snapshot(vararg toolIds: String): String = JSONArray().apply { toolIds.forEach { id ->
        put(JSONObject().put("type", "toolUse").put("value", JSONObject().put("toolUseId", id).put("name", "request_user_input").put("input", "{}")))
    } }.toString()

    @Test fun `reboot retains instance partial answers draft and original wall deadline`() = runTest {
        val records = Records()
        UserQuestionRegistry.install(records, { testScheduler.currentTime }, { 10_000L })
        lateinit var broker: UserQuestionBroker
        broker = UserQuestionBroker(nowMs = { testScheduler.currentTime }, wallNowMs = { 10_000L },
            onChanged = { UserQuestionRegistry.update("session", it, broker, snapshot("call")) })
        val wait = async { broker.ask(request()) }
        runCurrent()
        val original = broker.pending.value!!
        assertTrue(UserQuestionRegistry.answer("session", original.instanceId, "q1", "B"))
        assertTrue(UserQuestionRegistry.select("session", original.instanceId, "q2", "unfinished draft", true))
        val crash = records.copy()
        broker.cancel(); wait.join()
        UserQuestionRegistry.resetForTest()
        UserQuestionRegistry.install(crash, { 50L }, { 12_000L })
        val restored = UserQuestionRegistry.pending.value.getValue("session")
        assertEquals(original.instanceId, restored.instanceId)
        assertEquals(original.expiresAtWallMs, restored.expiresAtWallMs)
        assertEquals(298_000L, restored.remainingMs(50L))
        assertEquals(mapOf("q1" to "B"), restored.answers)
        assertEquals("unfinished draft", restored.freeTextDrafts["q2"])
        assertEquals("q2", restored.currentQuestion!!.id)
        assertFalse(UserQuestionRegistry.answer("other-session", restored.instanceId, "q2", "wrong"))
        assertFalse(UserQuestionRegistry.answer("session", "stale-instance", "q2", "wrong"))
        assertTrue(UserQuestionRegistry.answer("session", restored.instanceId, "q2", "final answer"))
        assertTrue(UserQuestionRegistry.pending.value.isEmpty())
        assertFalse(UserQuestionRegistry.answer("session", restored.instanceId, "q2", "duplicate"))
        val completed = UserQuestionRegistry.recoveredResults.value.single()
        assertEquals("B", JSONObject(completed.output).getJSONObject("answers").getString("q1"))
        assertEquals("final answer", JSONObject(completed.output).getJSONObject("answers").getString("q2"))
        assertEquals(snapshot("call"), completed.assistantPartsJson)
        assertTrue(crash.json.isNotEmpty())
    }

    private fun completedRecords(outcome: UserQuestionOutcome = UserQuestionOutcome.ANSWERED, assistant: String? = snapshot("call")): Records = Records().also { records ->
        records.save(UserQuestionRecord("session", request().copy(instanceId = "instance", expiresAtWallMs = 310_000L,
            expiresAtElapsedMs = 300_000L, answers = mapOf("q1" to "A", "q2" to "Details")), outcome, assistant))
    }

    @Test fun `resolved answer survives a second restart and delivery is idempotent after DB commit`() = runTest {
        val records = completedRecords(assistant = snapshot("call", "interrupted-sibling"))
        UserQuestionRegistry.install(records, { 10L }, { 12_000L })
        val transcript = Transcript().apply { failAfterAppend = true }
        assertTrue(runCatching { UserQuestionRecovery.deliver("session", transcript) }.isFailure)
        assertEquals(2, transcript.data.size)
        assertTrue(records.json.isNotEmpty())
        UserQuestionRegistry.resetForTest()
        UserQuestionRegistry.install(records, { 20L }, { 13_000L })
        assertFalse(UserQuestionRecovery.deliver("session", transcript))
        assertEquals(2, transcript.data.size)
        val results = JSONArray(transcript.data.last().second)
        assertEquals(2, results.length())
        assertTrue(results.getJSONObject(0).getJSONObject("value").getBoolean("success"))
        assertFalse(results.getJSONObject(1).getJSONObject("value").getBoolean("success"))
        assertTrue(records.json.isEmpty())
        assertFalse(UserQuestionRecovery.deliver("session", transcript))
    }

    @Test fun `expired restoration never answers or grants approval and still requests a recovery turn`() = runTest {
        val records = Records()
        records.save(UserQuestionRecord("session", request().copy(instanceId = "expired", expiresAtWallMs = 11_000L),
            assistantPartsJson = snapshot("call")))
        UserQuestionRegistry.install(records, { 5L }, { 12_000L })
        assertTrue(UserQuestionRegistry.pending.value.isEmpty())
        assertFalse(UserQuestionRegistry.answer("session", "expired", "q1", "A"))
        val result = UserQuestionRegistry.recoveredResults.value.single()
        assertEquals(UserQuestionOutcome.EXPIRED, result.outcome)
        assertTrue(result.output.contains("No permission was granted"))
        val transcript = Transcript()
        assertTrue(UserQuestionRecovery.deliver("session", transcript))
        assertFalse(JSONArray(transcript.data.last().second).getJSONObject(0).getJSONObject("value").getBoolean("success"))
    }

    @Test fun `stop and dismissal restore as denied without waking a run`() = runTest {
        for (outcome in listOf(UserQuestionOutcome.CANCELLED, UserQuestionOutcome.DISMISSED)) {
            UserQuestionRegistry.resetForTest()
            UserQuestionRegistry.install(completedRecords(outcome), { 0L }, { 12_000L })
            assertFalse(UserQuestionRecovery.deliver("session", Transcript()))
        }
    }

    @Test fun `stop closes a restored card even though the new VM owns no waiting coroutine`() = runTest {
        val records = Records()
        records.save(UserQuestionRecord("session", request().copy(instanceId = "waiting", expiresAtWallMs = 310_000L),
            assistantPartsJson = snapshot("call")))
        UserQuestionRegistry.install(records, { 0L }, { 12_000L })
        assertTrue(UserQuestionRegistry.pending.value.containsKey("session"))
        UserQuestionRegistry.cancelSession("session")
        assertTrue(UserQuestionRegistry.pending.value.isEmpty())
        assertFalse(UserQuestionRegistry.answer("session", "waiting", "q1", "A"))
        assertEquals(UserQuestionOutcome.CANCELLED, UserQuestionRegistry.recoveredResults.value.single().outcome)
        assertFalse(UserQuestionRecovery.deliver("session", Transcript()))
    }

    @Test fun `deleted sessions are discarded and missing snapshots preserve answers as plain user text`() = runTest {
        val records = completedRecords()
        UserQuestionRegistry.install(records, { 0L }, { 12_000L })
        val deleted = Transcript().apply { exists = false }
        assertFalse(UserQuestionRecovery.deliver("session", deleted))
        assertTrue(deleted.data.isEmpty())
        assertTrue(records.json.isEmpty())
        UserQuestionRegistry.resetForTest()
        UserQuestionRegistry.install(completedRecords(assistant = null), { 0L }, { 12_000L })
        val oldFork = Transcript()
        assertTrue(UserQuestionRecovery.deliver("session", oldFork))
        assertEquals("text", JSONArray(oldFork.data.single().second).getJSONObject(0).getString("type"))
        assertTrue(oldFork.data.single().second.contains("Details"))
    }

    @Test fun `stop wins over a saved answer awaiting transcript delivery`() = runTest {
        val records = completedRecords()
        UserQuestionRegistry.install(records, { 0L }, { 12_000L })
        assertTrue(UserQuestionRegistry.pending.value.isEmpty())
        UserQuestionRegistry.cancelSession("session")
        assertEquals(UserQuestionOutcome.CANCELLED, UserQuestionRegistry.recoveredResults.value.single().outcome)
        val transcript = Transcript()
        assertFalse(UserQuestionRecovery.deliver("session", transcript))
        assertFalse(JSONArray(transcript.data.last().second).getJSONObject(0).getJSONObject("value").getBoolean("success"))
        assertTrue(records.json.isEmpty())
    }
}
