package com.openminis.app.agent.jobs

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import com.openminis.app.ui.chat.UserQuestionSelection
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UserQuestionToolTest {
    private fun request(id: String = "tool") = UserQuestionTool.parse(id,
        """{"questions":[{"id":"q","question":"Choose","options":[{"label":"A"},{"label":"B"}],"allow_other":false}]}""")

    @Test fun `accepts native and encoded question arrays`() {
        assertEquals("q", request().questions.single().id)
        val encoded = org.json.JSONObject().put("questions", """[{"question":"Explain"}]""").toString()
        assertEquals("Explain", UserQuestionTool.parse("tool", encoded).questions.single().question)
    }

    @Test fun `confirmation without explicit options offers allow and cancel`() {
        val parsed = UserQuestionTool.parse("approval", """{"questions":[{"question":"Proceed?","type":"confirmation"}]}""")
        assertEquals(listOf("Allow", "Cancel"), parsed.questions.single().options.map { it.label })
        assertFalse(parsed.questions.single().allowOther)
    }

    @Test fun `missing and unknown types infer a choice or text from fields`() {
        val parsed = UserQuestionTool.parse("inferred", """{"questions":[{"question":"Pick","type":"future-choice","options":["A","B"]},{"question":"Explain","type":"future-text"}]}""")
        assertEquals(listOf("A", "B"), parsed.questions.first().options.map { it.label })
        assertTrue(parsed.questions.last().options.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `duplicate question ids are refused`() {
        UserQuestionTool.parse("tool", """{"questions":[{"id":"q","question":"One"},{"id":"q","question":"Two"}]}""")
    }

    @Test fun `stale incomplete and out of list answers do not unblock tool`() = runTest {
        val broker = UserQuestionBroker()
        val response = async { broker.ask(request()) }
        runCurrent()
        assertFalse(broker.answer("stale", mapOf("q" to "A")))
        assertFalse(broker.answer("tool", emptyMap()))
        assertFalse(broker.answer("tool", mapOf("q" to "other")))
        assertFalse(response.isCompleted)
        assertTrue(broker.answer("tool", mapOf("q" to "B")))
        assertEquals(mapOf("q" to "B"), response.await())
        assertNull(broker.pending.value)
        assertFalse(broker.answer("tool", mapOf("q" to "A")))
    }

    @Test fun `tool cancellation removes card and rejects stale answers`() = runTest {
        val broker = UserQuestionBroker()
        val response = async { broker.ask(request()) }
        runCurrent()
        broker.cancel()
        runCurrent()
        assertTrue(response.isCancelled)
        assertNull(broker.pending.value)
        assertFalse(broker.answer("tool", mapOf("q" to "A")))
    }

    @Test fun `concurrent questions are displayed one at a time`() = runTest {
        val broker = UserQuestionBroker()
        val first = async { broker.ask(request("one")) }
        val second = async { broker.ask(request("two")) }
        runCurrent()
        assertEquals("one", broker.pending.value?.id)
        assertTrue(broker.answer("one", mapOf("q" to "A")))
        first.await()
        runCurrent()
        assertEquals("two", broker.pending.value?.id)
        assertTrue(broker.answer("two", mapOf("q" to "B")))
        second.await()
        assertNull(broker.pending.value)
    }

    @Test fun `partial answers and editable picks advance one shared batch`() = runTest {
        val broker = UserQuestionBroker(nowMs = { testScheduler.currentTime })
        val batch = UserQuestionTool.parse("batch", """{"questions":[{"id":"q1","question":"Pick","options":["A","B"]},{"id":"q2","question":"Explain"}]}""")
        val result = async { broker.ask(batch) }
        runCurrent()
        val token = broker.pending.value!!.instanceId
        assertTrue(broker.select("batch", token, "q1", "A", asOther = true))
        assertEquals(UserQuestionSelection("A", true), broker.pending.value!!.selections["q1"])
        assertTrue(broker.select("batch", token, "q1", "A word ", asOther = true))
        assertEquals("A word ", broker.pending.value!!.selections["q1"]!!.value)
        assertTrue(broker.submitSelected("batch", token, "q1"))
        assertFalse(result.isCompleted)
        assertEquals(1, broker.pending.value!!.currentIndex)
        assertFalse(broker.answerQuestion("batch", token, "q1", "B"))
        assertTrue(broker.answerQuestion("batch", token, "q2", " Details "))
        assertEquals(mapOf("q1" to "A word", "q2" to "Details"), result.await())
        assertNull(broker.pending.value)
    }

    @Test fun `deadline expires without granting permission and cleans the card`() = runTest {
        val broker = UserQuestionBroker(nowMs = { testScheduler.currentTime })
        val outcome = async { runCatching { broker.ask(request().copy(timeoutMs = 1000L)) } }
        runCurrent()
        val visible = broker.pending.value!!
        assertEquals(1000L, visible.remainingMs(0))
        advanceTimeBy(1000)
        assertFalse(broker.answerQuestion("tool", visible.instanceId, "q", "A"))
        runCurrent()
        assertTrue(outcome.await().exceptionOrNull() is UserQuestionExpiredException)
        assertNull(broker.pending.value)
    }

    @Test fun `other draft survives choosing an option and can be selected again`() = runTest {
        val broker = UserQuestionBroker(nowMs = { testScheduler.currentTime })
        val batch = request().copy(questions = request().questions.map { it.copy(allowOther = true) })
        val result = async { broker.ask(batch) }
        runCurrent()
        val token = broker.pending.value!!.instanceId
        assertTrue(broker.select("tool", token, "q", "my draft", asOther = true))
        assertTrue(broker.select("tool", token, "q", "A"))
        assertEquals("my draft", broker.pending.value!!.freeTextDrafts["q"])
        assertEquals(UserQuestionSelection("A"), broker.pending.value!!.selections["q"])
        assertTrue(broker.select("tool", token, "q", broker.pending.value!!.freeTextDrafts["q"]!!, asOther = true))
        assertTrue(broker.submitSelected("tool", token, "q"))
        assertEquals(mapOf("q" to "my draft"), result.await())
    }

    @Test fun `same tool id on a new request rejects the old notification token`() = runTest {
        val broker = UserQuestionBroker(nowMs = { testScheduler.currentTime })
        val first = async { broker.ask(request()) }
        runCurrent()
        val oldToken = broker.pending.value!!.instanceId
        assertTrue(broker.answerQuestion("tool", oldToken, "q", "A"))
        first.await()
        val second = async { broker.ask(request()) }
        runCurrent()
        val newToken = broker.pending.value!!.instanceId
        assertNotEquals(oldToken, newToken)
        assertFalse(broker.answerQuestion("tool", oldToken, "q", "B"))
        assertFalse(second.isCompleted)
        assertTrue(broker.answerQuestion("tool", newToken, "q", "B"))
        second.await()
    }

    @Test fun `dismissal returns a denied result and cannot answer later`() = runTest {
        val broker = UserQuestionBroker(nowMs = { testScheduler.currentTime })
        val outcome = async { runCatching { broker.ask(request()) } }
        runCurrent()
        val token = broker.pending.value!!.instanceId
        assertTrue(broker.dismiss("tool", token))
        assertTrue(outcome.await().exceptionOrNull() is UserQuestionDismissedException)
        assertFalse(broker.answerQuestion("tool", token, "q", "A"))
        assertNull(broker.pending.value)
    }

    @Test fun `registry rejects cross session and stale replies while routing concurrent requests`() = runTest {
        lateinit var a: UserQuestionBroker
        lateinit var b: UserQuestionBroker
        a = UserQuestionBroker(nowMs = { testScheduler.currentTime }, onChanged = { UserQuestionRegistry.update("test-a", it, a) })
        b = UserQuestionBroker(nowMs = { testScheduler.currentTime }, onChanged = { UserQuestionRegistry.update("test-b", it, b) })
        val resultA = async { a.ask(request()) }
        val resultB = async { b.ask(request()) }
        try {
            runCurrent()
            val tokenA = a.pending.value!!.instanceId
            val tokenB = b.pending.value!!.instanceId
            assertFalse(UserQuestionRegistry.answer("test-b", tokenA, "q", "A"))
            assertFalse(UserQuestionRegistry.answer("test-a", tokenA, "other-question", "A"))
            assertTrue(UserQuestionRegistry.answer("test-b", tokenB, "q", "B"))
            assertEquals(mapOf("q" to "B"), resultB.await())
            assertTrue(UserQuestionRegistry.pending.value.containsKey("test-a"))
            assertFalse(UserQuestionRegistry.pending.value.containsKey("test-b"))
            assertTrue(UserQuestionRegistry.answer("test-a", tokenA, "q", "A"))
            resultA.await()
            assertFalse(UserQuestionRegistry.answer("test-a", tokenA, "q", "A"))
        } finally { a.cancel(); b.cancel() }
    }
}
