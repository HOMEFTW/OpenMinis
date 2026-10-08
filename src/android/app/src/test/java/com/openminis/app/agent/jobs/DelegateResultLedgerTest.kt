package com.openminis.app.agent.jobs

import com.openminis.app.data.model.AgentContentPart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DelegateResultLedgerTest {
    private fun result(text: String) = AgentContentPart.ToolResult("tool", "subagent_task", text)

    @Test fun `fast child waits for insert which stores final result`() = runTest {
        val ledger = DelegateResultLedger()
        val finish = async { ledger.finish(result("done")) { false } }
        runCurrent()
        assertFalse(finish.isCompleted)
        var saved = ""
        ledger.persist(listOf(result("running"))) { saved = it.single().content }
        finish.await()
        assertEquals("done", saved)
        assertEquals("done", (ledger.apply(listOf(result("running"))).single() as AgentContentPart.ToolResult).content)
    }

    @Test fun `normal child rewrites existing row before returning`() = runTest {
        val ledger = DelegateResultLedger()
        var row = ""
        ledger.persist(listOf(result("running"))) { row = it.single().content }
        ledger.finish(result("done")) { row = it.content; true }
        assertEquals("done", row)
    }

    @Test fun `late running rewrite cannot replace terminal payload`() = runTest {
        val ledger = DelegateResultLedger()
        ledger.finish(result("done")) { true }
        var row = ""
        ledger.rewriteCurrent(result("running")) { row = it.content; true }
        assertEquals("done", row)
    }

    @Test fun `close cancels completion waiting for abandoned parent insert`() = runTest {
        val ledger = DelegateResultLedger()
        val finish = async { ledger.finish(result("done")) { false } }
        runCurrent()
        ledger.close()
        runCurrent()
        assertTrue(finish.isCancelled)
    }
}
