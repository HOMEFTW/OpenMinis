package com.openminis.app.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openminis.app.data.db.MessageEntity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

/** Runs the production class initializer with Android's ICU, not the desktop JVM regex engine. */
@RunWith(AndroidJUnit4::class)
class LegacyAssistantPartsInstrumentedTest {
    @Test
    fun ordinaryHistoryReadsInitializeRecoveryWithoutCrashing() {
        for (raw in listOf("[]", """[{"type":"text","value":"Existing conversation"}]""")) {
            // Session-list previews and message-page reads both initialize this class.
            assertEquals(raw, readablePartsJson(raw))
            val row = MessageEntity("row", "session", "assistant", raw, 123L, sortOrder = 0)
            assertSame(row, row.withReadableAssistantParts())
        }
    }

    @Test
    fun legacyToolReadsRecoverOnlyTheMissingBraceAndPreserveArguments() {
        val command = "echo \"test\"; cat /tmp/\$f.md | tail -n 30"
        val input = JSONObject().put("command", command).toString()
        for (offloaded in listOf(false, true)) {
            val value = JSONObject()
                .put("toolUseId", "call_1")
                .put("name", "shell_execute")
                .put("input", input)
                .put("description", "Read test file")
                .put("pageURL", "")
                .put("imageFilePath", "")
                .put("thoughtSignature", JSONObject.NULL)
            val fragment = """{"type":"toolUse","value":${value.toString().dropLast(1)},"isOffloadedArgument" : $offloaded }"""
            val raw = "[$fragment]"
            val expected = "[$fragment}]"
            val repaired = readablePartsJson(raw)
            assertEquals(expected, repaired)
            assertEquals(expected, readablePartsJson(repaired))
            val restored = JSONArray(repaired).getJSONObject(0).getJSONObject("value")
            assertEquals(input, restored.getString("input"))
            assertEquals(offloaded, restored.getBoolean("isOffloadedArgument"))

            val row = MessageEntity("row", "session", "assistant", raw, 123L, sortOrder = 0)
            assertEquals(row.copy(partsJson = expected), row.withReadableAssistantParts())
            assertEquals(raw + " trailing", readablePartsJson(raw + " trailing"))
        }
    }
}
