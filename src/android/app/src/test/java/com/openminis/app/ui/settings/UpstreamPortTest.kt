package com.openminis.app.ui.settings

import com.openminis.app.data.ContextOffload
import org.junit.Assert.*
import org.junit.Test

class UpstreamPortTest {
    private val stub = ContextOffload.stub(100, 400, "/var/minis/offloads/tools/read_123.txt")
    private fun wrapped(path: String = "/var/minis/offloads/tools/read_123.txt", suffix: String = "") =
        "[$path | 400 bytes | 2 lines | showing 1-2 of 2$suffix]\n$stub"

    @Test fun rawStubIsSkipped() = assertTrue(ContextOffload.isOffloadReadback(stub))
    @Test fun wrappedStubIsSkipped() = assertTrue(ContextOffload.isOffloadReadback(wrapped()))
    @Test fun truncatedHeaderIsRecognized() = assertTrue(ContextOffload.isOffloadReadback(wrapped(suffix = " | truncated at 200 chars, next_offset=3")))
    @Test fun crlfHeaderIsRecognized() = assertTrue(ContextOffload.isOffloadReadback(wrapped().replace("]\n", "]\r\n")))
    @Test fun ordinaryFileContainingStubIsNotSkipped() = assertFalse(ContextOffload.isOffloadReadback(wrapped("/var/minis/workspace/read.txt")))
    @Test fun traversalHeaderIsNotSkipped() = assertFalse(ContextOffload.isOffloadReadback(wrapped("/var/minis/offloads/tools/../read.txt")))
    @Test fun unrelatedMentionIsNotSkipped() = assertFalse(ContextOffload.isOffloadReadback("See /var/minis/offloads/tools/read.txt\n$stub"))
    @Test fun originalOffloadPayloadRemainsEligible() = assertFalse(ContextOffload.isOffloadReadback(wrapped().substringBefore('\n') + "\nreal content"))
    @Test fun laterMentionIsNotSkipped() = assertFalse(ContextOffload.isOffloadReadback(wrapped().substringBefore('\n') + "\noriginal content\n$stub"))

    @Test fun mcpCommandQuotesShellMetacharacters() {
        assertEquals("minis-mcp-cli refresh 'a'\"'\"'; \$HOME `id`'", mcpCheckCommand("a'; \$HOME `id`"))
    }
    @Test fun mcpRejectsNul() { assertTrue(runCatching { mcpCheckCommand("a\u0000b") }.isFailure) }
    @Test fun mcpAcceptsZeroTools() { assertEquals(0, parseMcpCheck("s", """{"server":"s","count":0,"tools":[]}""", 0)) }
    @Test fun mcpAcceptsValidTools() { assertEquals(1, parseMcpCheck("s", """{"server":"s","count":1,"tools":[{"name":"read"}]}""", 0)) }
    @Test fun mcpRejectsNonzeroExitDespiteValidJson() { assertTrue(runCatching { parseMcpCheck("s", """{"server":"s","count":0,"tools":[]}""", 1) }.isFailure) }
    @Test fun mcpRejectsFalseSuccessEnvelopes() {
        listOf(
            """{"server":"s","count":0}""",
            """{"server":"other","count":0,"tools":[]}""",
            """{"server":"s","count":1,"tools":[]}""",
            """{"server":"s","count":"0","tools":[]}""",
            """{"server":"s","count":-1,"tools":[]}""",
            """{"server":"s","count":0,"tools":[],"error":"failed"}""",
            """{"server":"s","count":1,"tools":[{}]}""",
            "not json",
        ).forEach { assertTrue(it, runCatching { parseMcpCheck("s", it, 0) }.isFailure) }
    }

    @Test fun dateWindowIncludesBoundaryAndExcludesFuture() {
        val now = 30L * 86_400_000
        assertTrue(usageInDateRange(now - 7L * 86_400_000, now, 7))
        assertFalse(usageInDateRange(now - 7L * 86_400_000 - 1, now, 7))
        assertFalse(usageInDateRange(now + 1, now, 7))
        assertTrue(usageInDateRange(0, now, 0))
    }
    @Test fun searchMatchesModelAndProviderCaseInsensitively() {
        assertTrue(usageMatchesSearch("gpt-6-astra", "Astra", "OpenAI", " GPT-6 "))
        assertTrue(usageMatchesSearch("gpt-6-astra", "Astra", "OpenAI", "openai"))
        assertFalse(usageMatchesSearch("gpt-6-astra", "Astra", "OpenAI", "anthropic"))
    }
}
