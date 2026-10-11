package com.openminis.app.data.repository

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MessagePartsStorageTest {
    @Test fun oversizedResultsKeepPairingStatusAndMediaReferences() {
        val parts = JSONArray()
            .put(JSONObject().put("type", "toolResult").put("value", JSONObject()
                .put("toolUseId", "call_1").put("name", "shell_execute")
                .put("success", false).put("output", "中\\\"".repeat(250_000))
                .put("snapshot", JSONObject().put("type", "text").put("text", "x".repeat(250_000)))))
            .put(JSONObject().put("type", "mediaRef").put("value", JSONObject()
                .put("relativePath", "session/image.png").put("toolUseId", "call_1")))
        val capped = ChatRepository.buildTruncatedPartsJson(parts.toString())
        assertTrue(capped.length <= ChatRepository.MAX_MESSAGE_PARTS_JSON_LENGTH)
        val saved = JSONArray(capped)
        assertEquals("toolResult", saved.getJSONObject(0).getString("type"))
        val value = saved.getJSONObject(0).getJSONObject("value")
        assertEquals("call_1", value.getString("toolUseId"))
        assertFalse(value.getBoolean("success"))
        assertTrue(value.getString("output").contains("truncated"))
        assertEquals("session/image.png", saved.getJSONObject(1).getJSONObject("value").getString("relativePath"))
    }

    @Test fun oversizedArgumentsStayJsonAndKeepCallIdentity() {
        val input = JSONObject().put("command", "x".repeat(700_000)).put("timeout", 30).toString()
        val raw = JSONArray().put(JSONObject().put("type", "toolUse").put("value", JSONObject()
            .put("toolUseId", "call_2").put("name", "shell_execute").put("input", input)
            .put("thoughtSignature", JSONObject.NULL).put("isOffloadedArgument", false))).toString()
        val saved = JSONArray(ChatRepository.buildTruncatedPartsJson(raw)).getJSONObject(0)
        assertEquals("toolUse", saved.getString("type"))
        val value = saved.getJSONObject("value")
        assertEquals("call_2", value.getString("toolUseId"))
        assertTrue(value.isNull("thoughtSignature"))
        assertEquals(30, JSONObject(value.getString("input")).getInt("timeout"))
        val savedInput = JSONObject(value.getString("input"))
        assertTrue(savedInput.getBoolean("_minisHistoryTruncated"))
        assertEquals(input.length, savedInput.getInt("_minisOriginalInputChars"))
        assertTrue(savedInput.getString("_minisHistoryNotice").contains("must not be executed"))
    }

    @Test fun malformedOversizedPartsAreRejectedInsteadOfConvertedToPlainText() {
        assertTrue(runCatching { ChatRepository.buildTruncatedPartsJson("[" + "x".repeat(600_000)) }.isFailure)
    }

    @Test fun signedReplayAndUnknownPartFieldsSurvivePayloadTrimming() {
        val replay = """[{"type":"thinking","thinking":"original","signature":"signed"}]"""
        val parts = JSONArray()
            .put(JSONObject().put("type", "futurePart").put("value", JSONObject()
                .put("flag", true).put("count", 7).put("body", "x".repeat(700_000))))
            .put(JSONObject().put("type", "anthropicReplay").put("value", replay))
        val saved = JSONArray(boundedMessagePartsJson(parts.toString()))
        assertEquals("futurePart", saved.getJSONObject(0).getString("type"))
        assertTrue(saved.getJSONObject(0).getJSONObject("value").getBoolean("flag"))
        assertEquals(7, saved.getJSONObject(0).getJSONObject("value").getInt("count"))
        assertEquals(replay, saved.getJSONObject(1).getString("value"))
    }

    @Test fun largeSignedPayloadsRemainExactForChunkedStorage() {
        val parts = JSONArray().put(JSONObject().put("type", "anthropicReplay").put("value", "x".repeat(600_000)))
        assertEquals(parts.toString(), boundedMessagePartsJson(parts.toString()))
        for (replayType in listOf("anthropicReplay", "deepSeekReplay")) {
            val raw = JSONArray().put(JSONObject().put("type", "text").put("value", "x".repeat(600_000)))
                .put(JSONObject().put("type", replayType).put("value", "{\"version\":1}"))
                .toString()
            assertEquals(raw, boundedMessagePartsJson(raw))
        }
        val gemini = JSONArray().put(JSONObject().put("type", "toolUse").put("value", JSONObject()
            .put("toolUseId", "signed-call").put("name", "file_write")
            .put("input", JSONObject().put("content", "x".repeat(600_000)).toString())
            .put("thoughtSignature", "signed=="))).toString()
        assertEquals(gemini, boundedMessagePartsJson(gemini))
    }
}
