package com.openminis.app.data

import com.openminis.app.data.model.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ContextSizeMeterTest {
    private fun sample(report: Int, raw: Int = 100, model: String? = "entry:model", fixed: Int = 10) =
        ContextSizeMeter.CalibrationSample(report, raw, fixed, model)

    @Test fun reportUsesSameRequestAndSmoothingDoesNotShrinkAccurateRawCount() {
        val c = ContextSizeMeter.Calibration()
        c.seed("s", emptyList())
        c.record(sample(140))
        assertEquals(140, c.estimate("entry:model", 100))
        assertEquals(28, c.estimate("entry:model", 20)) // Compact/offload rebuilt the payload.
        c.record(sample(100))
        assertEquals(1.28, c.ratio("entry:model"), 1e-9)
        c.record(sample(1))
        assertTrue(c.estimate("entry:model", 100) >= 100)
        assertEquals(Int.MAX_VALUE, ContextSizeMeter.calibrated(Int.MAX_VALUE, 3.0))
        assertEquals(0, ContextSizeMeter.calibrated(-1, 1.4))
    }

    @Test fun persistenceReplaysSamplesAndModelsRetainTheirOwnEvidence() {
        val rows = listOf(sample(140), sample(100), sample(180, model = "other:model"))
        val saved = rows.map { s -> """{"latestContextTokens":${s.reported}${s.jsonFields()}}""" }
        val decoded = saved.mapNotNull(ContextSizeMeter::calibrationSample)
        assertEquals(rows, decoded)
        val c = ContextSizeMeter.Calibration()
        c.seed("s", decoded)
        assertEquals(1.28, c.ratio("entry:model"), 1e-9)
        assertEquals(1.8, c.ratio("other:model"), 1e-9)
        assertEquals(2.16, c.ratio("unseen:model"), 1e-9)
        assertNull(ContextSizeMeter.calibrationSample("""{"latestContextTokens":100}"""))
        assertNull(ContextSizeMeter.calibrationSample("not json"))
    }

    @Test fun sameSessionReloadKeepsRejectionAndProbeSpendWhileDifferentSessionResets() {
        val c = ContextSizeMeter.Calibration()
        c.seed("s", listOf(sample(120)))
        assertEquals(1.5, c.rejected("entry:model", 100, 150, 128), 1e-9)
        assertFalse(c.canProbe("entry:model"))
        c.seed("s", listOf(sample(120)))
        assertEquals(1.5, c.ratio("entry:model"), 1e-9)
        assertFalse(c.canProbe("entry:model"))
        c.accepted("entry:model")
        assertTrue(c.canProbe("entry:model"))
        c.spendProbe("entry:model")
        c.seed("different", emptyList())
        assertEquals(1.0, c.ratio("entry:model"), 1e-9)
        assertTrue(c.canProbe("entry:model"))
    }

    @Test fun rejectionCountsIgnoreIdsAndImplausibleNumbers() {
        assertEquals(131244, ContextSizeMeter.requestedTokens(
            "maximum context length is 128000 tokens, requested 131,244 tokens; request 99999999"))
        assertNull(ContextSizeMeter.requestedTokens("request id 131244 exceeds context window"))
        assertEquals(1.31244, ContextSizeMeter.ratioAfterOverflow(1.05, 100000, 131244, 128000), 1e-9)
        assertEquals(1.3056, ContextSizeMeter.ratioAfterOverflow(1.0, 100000, 123456789, 128000), 1e-9)
        assertEquals(1.5, ContextSizeMeter.ratioAfterOverflow(1.5, 100000, 131244, 128000), 1e-9)
        assertEquals(1.0, ContextSizeMeter.ratioAfterOverflow(1.0, 100000, null, 0), 1e-9)
    }

    @Test fun warmUpDropsWholeTurnsWithoutBreakingToolPairsOrOverflowingSums() {
        val sizes = listOf(10, 9000, 10, 9000, 10, 300, 200)
        val starts = listOf(true, false, true, false, true, false, false)
        assertEquals(0, ContextSizeMeter.warmUpDrop(sizes, starts, 500, 40000))
        assertEquals(2, ContextSizeMeter.warmUpDrop(sizes, starts, 500, 12000))
        assertEquals(7, ContextSizeMeter.warmUpDrop(sizes, starts, 500, 100))
        assertEquals(2, ContextSizeMeter.warmUpDrop(listOf(Int.MAX_VALUE, Int.MAX_VALUE),
            listOf(true, false), 10, Int.MAX_VALUE))
    }

    @Test fun undecidedWarmUpIsNotCachedAndGrowthDoesNotRestoreTrimmedTurns() {
        val retained = ContextSizeMeter.WarmUpRetention()
        val sizes = listOf(100, 900, 100, 900)
        val starts = listOf(true, false, true, false)
        assertEquals(0, retained.drop("marker:model:cap", sizes, starts, 100, null))
        assertEquals(2, retained.drop("marker:model:cap", sizes, starts, 100, 1500))
        assertEquals(2, retained.drop("marker:model:cap", sizes, starts, 100, 5000))
        assertEquals(4, retained.drop("marker:model:cap", sizes, starts, 1300, 1500))
        assertEquals(0, retained.drop("marker:other:cap", sizes, starts, 100, 5000))
        assertNull(ContextSizeMeter.warmUpBudget(null, null, 1.0, 100))
        assertNull(ContextSizeMeter.warmUpBudget(32000, 27200, 1.0, 0))
    }

    @Test fun warmUpBudgetIncludesFixedTokensAndCalibrationWithStrictBoundary() {
        val budget = ContextSizeMeter.warmUpBudget(32000, 27200, 1.4, 9000)!!
        val maximalRawHistory = budget - 1
        assertTrue(ContextSizeMeter.calibrated(maximalRawHistory + 9000, 1.4) < 27200)
        assertTrue(ContextSizeMeter.calibrated(maximalRawHistory + 1 + 9000, 1.4) >= 27200)
        assertTrue(ContextSizeMeter.warmUpBudget(8000, 6800, 1.0, 9000)!! < 0)
    }

    @Test fun smallCapsAlsoBoundTheProducedSummary() {
        assertEquals(900, ContextSizeMeter.summaryOutputCeiling(8000, 6800, 5000, 1.0))
        assertEquals(256, ContextSizeMeter.summaryOutputCeiling(8000, 6800, 9000, 1.0))
        assertEquals(8192, ContextSizeMeter.summaryOutputCeiling(1000000, 850000, 9000, 1.0))
    }

    /** Exact counters are injectable; the production caller supplies BPE and image-grid counters. */
    private fun estimate(messages: List<LLMMessage>, system: String? = null,
                         tools: List<AgentToolDefinition> = emptyList(),
                         seen: MutableList<String> = mutableListOf()) = ContextSizeMeter.estimateRequestPayload(
        messages, system, tools, { seen.add(it); if (it == "dense") 50 else if (it == "light") 1 else 0 },
        { if (it.isEmpty()) 0 else 85 },
    )

    @Test fun estimatorUsesPartsInsteadOfDuplicateLegacyTextAndCountsReasoningAudioImages() {
        val seen = mutableListOf<String>()
        val structured = LLMMessage(LLMMessage.Role.ASSISTANT, "ignored", contentParts = listOf(
            AgentContentPart.Text("dense"), AgentContentPart.ToolUse("call", "tool", JSONObject().put("k", "v")),
            AgentContentPart.ToolResult("call", "tool", "light", imageData = byteArrayOf(1, 2)),
            AgentContentPart.ImageData(byteArrayOf(3), "image/png"),
        ), reasoningContent = "reasoning")
        val legacy = LLMMessage(LLMMessage.Role.USER, "legacy",
            imageParts = listOf(LLMMessage.ImagePart(byteArrayOf(4), "image/png")),
            audioParts = listOf(LLMMessage.AudioPart("wav", "audio-base64")))
        val result = estimate(listOf(structured, legacy), "system", seen = seen)
        assertFalse(seen.contains("ignored"))
        for (required in listOf("dense", "light", "call", "tool", "{\"k\":\"v\"}", "reasoning",
            "legacy", "wav", "audio-base64", "system")) assertTrue(required, required in seen)
        assertEquals(255, result.imageTokens)
        assertEquals(4L, result.imageBytes)
        assertEquals(8 + 2 + 6 + 8 + 51 + 255, result.inputTokens)
    }

    @Test fun fixedToolSchemasAndSameLengthMutationsAreMeasuredAgain() {
        val tool = AgentToolDefinition("tool", "description", emptyMap())
        val seen = mutableListOf<String>()
        estimate(emptyList(), "system", listOf(tool), seen)
        assertEquals(4, seen.size) // System plus all three provider schema envelopes.
        assertTrue(seen.any { it.contains("input_schema") })
        assertTrue(seen.any { it.contains("function") })
        val message = LLMMessage(LLMMessage.Role.USER, "dense", dbMessageId = "same-id")
        val before = estimate(listOf(message))
        val after = estimate(listOf(message.copy(content = "light")))
        assertEquals(49, before.inputTokens - after.inputTokens) // Same length/id, different BPE cost.
    }
}
