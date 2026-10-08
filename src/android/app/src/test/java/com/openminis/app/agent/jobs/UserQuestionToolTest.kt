package com.openminis.app.agent.jobs

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
}
