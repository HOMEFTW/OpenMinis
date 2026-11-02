package com.openminis.app.agent.jobs

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UserQuestionStable115Test {
    private class Records : UserQuestionRecords {
        val data = linkedMapOf<String, String>()
        override fun all(nowElapsedMs: Long, nowWallMs: Long) = data.values.map {
            UserQuestionRecord.fromJson(JSONObject(it), nowElapsedMs, nowWallMs)
        }
        override fun save(record: UserQuestionRecord) { data[record.request.instanceId] = record.toJson().toString() }
        override fun remove(instanceId: String) { data.remove(instanceId) }
    }

    private class Transcript : UserQuestionTranscript {
        val data = mutableListOf<Pair<String, String>>()
        var failAfterAnswer = false
        var rowsGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        val rowsEntered = kotlinx.coroutines.CompletableDeferred<Unit>()
        override suspend fun sessionExists(sessionId: String) = true
        override suspend fun rows(sessionId: String): List<String> {
            rowsGate?.let { gate -> rowsGate = null; rowsEntered.complete(Unit); gate.await() }
            return data.map { it.second }
        }
        override suspend fun append(sessionId: String, role: String, partsJson: String) {
            data += role to partsJson
            if (failAfterAnswer && partsJson.contains("[User question ")) {
                failAfterAnswer = false
                error("simulated restart after committed answer")
            }
        }
        override suspend fun replace(sessionId: String, previousPartsJson: String, partsJson: String) {
            val i = data.indexOfFirst { it.second == previousPartsJson }
            check(i >= 0)
            data[i] = data[i].first to partsJson
        }
    }

    private fun request() = UserQuestionTool.parse("call", """{"questions":[{"id":"q","question":"Choose","options":["A","B"]}]}""")
    private fun snapshot(): String = JSONArray().put(JSONObject().put("type", "toolUse").put("value", JSONObject()
        .put("toolUseId", "call").put("name", "user_ask").put("input", "{}"))).toString()

    @Before fun reset() = UserQuestionRegistry.resetForTest()
    @After fun cleanup() = UserQuestionRegistry.resetForTest()

    @Test fun `structured schema decodes question and option JSON strings recursively`() {
        val q = JSONObject().put("id", "q").put("question", "Pick").put("options", """[{"label":"A"},{"label":"B"}]""")
        val args = JSONObject().put("questions", JSONArray().put(q).toString())
        val parsed = UserQuestionTool.parse("call", args.toString())
        assertEquals(listOf("A", "B"), parsed.questions.single().options.map { it.label })
        val properties = UserQuestionTool.definition().toAnthropicJson().getJSONObject("input_schema").getJSONObject("properties")
        assertEquals("array", properties.getJSONObject("questions").getString("type"))
        assertEquals("user_ask", UserQuestionTool.definition().name)
        assertEquals("request_user_input", UserQuestionTool.definition(UserQuestionTool.NAME).name)
    }

    @Test fun `settings timeout zero means infinite and legacy seconds still override`() {
        assertEquals(0L, UserQuestionTool.parse("call", """{"question":"Explain"}""", 0).timeoutMs)
        assertEquals(120_000L, UserQuestionTool.parse("call", """{"question":"Explain"}""", 2).timeoutMs)
        assertEquals(3_000L, UserQuestionTool.parse("call", """{"question":"Explain","timeout_seconds":3}""", 0).timeoutMs)
    }

    @Test fun `infinite wait has no deadline and survives recovery`() = runTest {
        val broker = UserQuestionBroker(nowMs = { testScheduler.currentTime })
        val result = async { broker.ask(request().copy(timeoutMs = 0L)) }
        runCurrent()
        val pending = broker.pending.value!!
        assertFalse(pending.hasCountdown)
        advanceTimeBy(86_400_000L)
        broker.expire()
        assertFalse(result.isCompleted)
        val decoded = UserQuestionRecord.fromJson(UserQuestionRecord("session", pending).toJson(), 99L, 100L).request
        assertEquals(Long.MAX_VALUE, decoded.remainingMs(Long.MAX_VALUE))
        assertTrue(broker.answerQuestion("call", pending.instanceId, "q", "A"))
        assertEquals("A", result.await()["q"])
    }

    @Test fun `typing and navigation renew the actual coroutine timeout`() = runTest {
        val broker = UserQuestionBroker(nowMs = { testScheduler.currentTime }, wallNowMs = { testScheduler.currentTime + 10_000 })
        val result = async { broker.ask(request().copy(timeoutMs = 1000L)) }
        runCurrent()
        val token = broker.pending.value!!.instanceId
        advanceTimeBy(800)
        assertTrue(broker.select("call", token, "q", "draft", true))
        assertEquals(11_800L, broker.pending.value!!.expiresAtWallMs)
        advanceTimeBy(800)
        assertTrue(broker.goTo("call", token, 0))
        advanceTimeBy(800)
        runCurrent()
        assertFalse(result.isCompleted)
        assertTrue(broker.answerQuestion("call", token, "q", "A"))
        result.await()
    }

    @Test fun `previous question can be edited and unsubmitted questions are still required`() = runTest {
        val broker = UserQuestionBroker(nowMs = { testScheduler.currentTime })
        val batch = UserQuestionTool.parse("call", """{"questions":[{"id":"a","question":"One"},{"id":"b","question":"Two"},{"id":"c","question":"Three"}]}""")
        val result = async { broker.ask(batch) }
        runCurrent()
        val token = broker.pending.value!!.instanceId
        assertTrue(broker.answerQuestion("call", token, "a", "first"))
        assertTrue(broker.goTo("call", token, 0))
        assertTrue(broker.answerQuestion("call", token, "a", "revised"))
        assertTrue(broker.goTo("call", token, 2))
        assertTrue(broker.answerQuestion("call", token, "c", "third"))
        assertFalse(result.isCompleted)
        assertEquals(1, broker.pending.value!!.currentIndex)
        assertTrue(broker.answerQuestion("call", token, "b", "second"))
        assertEquals(mapOf("a" to "revised", "b" to "second", "c" to "third"), result.await())
    }

    @Test fun `deferred card returns immediately and initial result does not acknowledge an early answer`() = runTest {
        val records = Records()
        UserQuestionRegistry.install(records, { testScheduler.currentTime }, { 10_000L })
        lateinit var broker: UserQuestionBroker
        broker = UserQuestionBroker(nowMs = { testScheduler.currentTime }, wallNowMs = { 10_000L },
            onChanged = { UserQuestionRegistry.update("session", it, broker, snapshot()) })
        broker.defer(request())
        val pending = UserQuestionRegistry.pending.value.getValue("session")
        assertTrue(pending.deferred)
        assertFalse(pending.hasCountdown)
        assertTrue(UserQuestionTool.isDeferredOutput(UserQuestionTool.deferredOutput(1)))
        assertTrue(UserQuestionRegistry.answer("session", pending.instanceId, "q", "B"))
        UserQuestionRegistry.acknowledgeToolResult("session", "call", UserQuestionTool.deferredOutput(1))
        assertEquals(1, records.data.size)
        val transcript = Transcript().apply {
            data += "assistant" to snapshot()
            data += "user" to """[{"type":"toolResult","value":{"toolUseId":"call","name":"user_ask","output":${JSONObject.quote(UserQuestionTool.deferredOutput(1))},"success":true}}]"""
        }
        assertTrue(UserQuestionRecovery.deliver("session", transcript))
        assertEquals(3, transcript.data.size)
        assertTrue(transcript.data[1].second.contains("answers"))
        assertTrue(transcript.data.last().second.contains("earlier question card"))
        assertFalse(UserQuestionRecovery.deliver("session", transcript))
        assertTrue(records.data.isEmpty())
    }

    @Test fun `deferred answer killed after append is not duplicated or lost on recovery`() = runTest {
        val records = Records()
        val pending = request().copy(instanceId = "deferred", deferred = true, timeoutMs = 0L,
            expiresAtWallMs = 3_610_000L, answers = mapOf("q" to "A"))
        records.save(UserQuestionRecord("session", pending, UserQuestionOutcome.ANSWERED, snapshot()))
        UserQuestionRegistry.install(records, { 0L }, { 12_000L })
        val transcript = Transcript().apply { failAfterAnswer = true }
        assertTrue(runCatching { UserQuestionRecovery.deliver("session", transcript) }.isFailure)
        assertTrue(records.data.isNotEmpty())
        assertTrue(UserQuestionRecovery.deliver("session", transcript))
        assertEquals(1, transcript.data.count { it.second.contains("[User question deferred]") })
        assertTrue(records.data.isEmpty())
    }

    @Test fun `deferred cards expire after one hour without starting a run or renewing their lifetime`() = runTest {
        val records = Records()
        UserQuestionRegistry.install(records, { testScheduler.currentTime }, { 10_000L })
        lateinit var broker: UserQuestionBroker
        broker = UserQuestionBroker(nowMs = { testScheduler.currentTime }, wallNowMs = { testScheduler.currentTime + 10_000 },
            onChanged = { UserQuestionRegistry.update("session", it, broker, snapshot()) })
        broker.defer(request())
        val pending = broker.pending.value!!
        advanceTimeBy(UserQuestionTool.DEFERRED_LIFETIME_MS - 1000)
        assertTrue(broker.select("call", pending.instanceId, "q", "draft", true))
        assertEquals(pending.expiresAtWallMs, broker.pending.value!!.expiresAtWallMs)
        advanceTimeBy(1000)
        UserQuestionRegistry.expire()
        assertNull(broker.pending.value)
        assertEquals(UserQuestionOutcome.EXPIRED, UserQuestionRegistry.recoveredResults.value.single().outcome)
        assertFalse(UserQuestionRecovery.deliver("session", Transcript()))
    }

    @Test fun `stop during a suspended recovery read wins before answer delivery`() = runTest {
        val records = Records()
        records.save(UserQuestionRecord("session", request().copy(instanceId = "deferred", deferred = true,
            timeoutMs = 0L, expiresAtWallMs = 3_610_000L, answers = mapOf("q" to "A")), UserQuestionOutcome.ANSWERED, snapshot()))
        UserQuestionRegistry.install(records, { 0L }, { 12_000L })
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val transcript = Transcript().apply { rowsGate = gate }
        val result = async { UserQuestionRecovery.deliver("session", transcript) }
        transcript.rowsEntered.await()
        UserQuestionRegistry.cancelSession("session")
        gate.complete(Unit)
        assertFalse(result.await())
        assertFalse(transcript.data.any { it.second.contains("[User question ") })
        assertTrue(transcript.data.any { it.second.contains("user stopped") })
    }

    @Test fun `stop cancels a deferred answer already waiting for delivery`() = runTest {
        val records = Records()
        val pending = request().copy(instanceId = "deferred", deferred = true, timeoutMs = 0L,
            expiresAtWallMs = 3_610_000L, answers = mapOf("q" to "A"))
        records.save(UserQuestionRecord("session", pending, UserQuestionOutcome.ANSWERED, snapshot()))
        UserQuestionRegistry.install(records, { 0L }, { 12_000L })
        UserQuestionRegistry.cancelSession("session")
        val transcript = Transcript()
        assertFalse(UserQuestionRecovery.deliver("session", transcript))
        assertFalse(transcript.data.any { it.second.contains("[User question ") })
    }
}
