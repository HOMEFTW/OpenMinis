package com.openminis.app.forge

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ForgeInformationMetricsTest {
    private fun row(id: String = "m1", session: String = "s1", model: String? = "model-a", usage: String = """{"inputTokens":10,"cacheReadTokens":20,"cacheCreationTokens":5,"outputTokens":100,"reasoningTokens":40,"cacheReported":true}""") =
        ForgeInformationUsageRow(id, session, model, "p1", 123_000, usage)
    private fun receipt(id: String, output: Int, ms: Long, session: String = "s1", model: String = "model-a", message: String? = null): JSONObject =
        JSONObject().put("id", id).put("threadId", session).put("model", model).put("provider", "p1")
            .put("endMs", 123_000 + ms).put("durationMs", ms).nullable("messageId", message)
            .put("usage", JSONObject().put("inputTokens", 10).put("outputTokens", output))

    @Test fun cacheAndReasoningAreNotDoubleCounted() {
        val s = ForgeInformationMetrics.aggregate("s1", listOf(row()), emptyList())
        assertEquals(35L, s.getLong("inputTokens")); assertEquals(100L, s.getLong("outputTokens"))
        assertEquals(135L, s.getLong("totalTokens")); assertEquals(40L, s.getLong("reasoningTokens"))
        assertEquals(20L, s.getLong("cachedInputTokens")); assertEquals(5L, s.getLong("cacheWriteTokens"))
    }

    @Test fun averageWeightsOutputAndElapsedInsteadOfAveragingRates() {
        val s = ForgeInformationMetrics.aggregate("s1", emptyList(), listOf(receipt("a", 100, 1000), receipt("b", 100, 3000)))
        assertEquals(50.0, s.getDouble("average"), 1e-12)
        assertEquals(4000.0, s.getDouble("elapsedMs"), 0.0)
        assertEquals(2, s.getInt("measuredResponses"))
    }

    @Test fun oldTimestampsCannotProduceASpeedOrPretendReasoningWasReported() {
        val s = ForgeInformationMetrics.aggregate("s1", listOf(row(usage = """{"inputTokens":10,"outputTokens":20}""")), emptyList())
        assertTrue(s.isNull("average")); assertTrue(s.isNull("latest")); assertTrue(s.isNull("elapsedMs"))
        assertTrue(s.isNull("reasoningTokens")); assertFalse(s.getBoolean("reasoningComplete"))
        assertEquals(0, s.getInt("cacheReportedResponses")); assertEquals(0, s.getJSONArray("samples").length())
    }

    @Test fun zeroNegativeOrUnknownDurationsAreNotMeasured() {
        val unknown = receipt("unknown", 100, 1).put("durationMs", JSONObject.NULL)
        val s = ForgeInformationMetrics.aggregate("s1", emptyList(), listOf(receipt("a", 100, 0), receipt("b", 100, -1), unknown))
        assertTrue(s.isNull("average")); assertTrue(s.isNull("elapsedMs"))
    }

    @Test fun copiedAndInvalidUsageAreSkippedAndReported() {
        val s = ForgeInformationMetrics.aggregate("s1", listOf(row(), row("copy", usage = """{"copied":true,"outputTokens":999}"""), row("bad", usage = "broken")), emptyList())
        assertEquals(100L, s.getLong("outputTokens")); assertEquals(1, s.getInt("copiedSkipped")); assertEquals(1, s.getInt("malformed"))
    }

    @Test fun otherSessionsAndDuplicateRecordsCannotContaminateAggregation() {
        val r = receipt("a", 100, 1000)
        val s = ForgeInformationMetrics.aggregate("s1", listOf(row(), row(), row("foreign", "s2")), listOf(r, r, receipt("foreign", 999, 1, "s2")))
        assertEquals(100L, s.getLong("outputTokens")); assertEquals(1, s.getInt("measuredResponses")); assertEquals(100.0, s.getDouble("average"), 0.0)
    }

    @Test fun globalOverviewUsesAllRealSessions() {
        val s = ForgeInformationMetrics.aggregate(null, listOf(row(), row("m2", "s2")), emptyList())
        assertEquals("global", s.getString("scope")); assertEquals(200L, s.getLong("outputTokens")); assertTrue(s.isNull("average"))
    }

    @Test fun missingModelSnapshotRemainsUnknown() {
        val s = ForgeInformationMetrics.aggregate("s1", listOf(row(model = null)), emptyList())
        assertEquals(1, s.getInt("unrecordedModelResponses")); assertTrue(s.getJSONArray("models").getJSONObject(0).isNull("model"))
    }

    @Test fun exactLinkedRequestsRecoverRealModelChangesWithoutCountingTheMessageTwice() {
        val usage = """{"inputTokens":20,"outputTokens":100}"""
        val a = receipt("a", 40, 1000, model = "model-a", message = "m1")
        val b = receipt("b", 60, 1000, model = "model-b", message = "m1")
        val s = ForgeInformationMetrics.aggregate("s1", listOf(row(usage = usage)), listOf(a, b))
        assertEquals(100L, s.getLong("outputTokens")); assertEquals(20L, s.getLong("inputTokens")); assertEquals(2, s.getJSONArray("models").length())
        assertEquals(40L, s.getJSONArray("models").getJSONObject(0).getLong("output"))
        assertEquals(60L, s.getJSONArray("models").getJSONObject(1).getLong("output"))
    }

    @Test fun incompleteOrForeignLinksCannotReplaceDBTruth() {
        val s = ForgeInformationMetrics.aggregate("s1", listOf(row()), listOf(receipt("a", 999, 1000, message = "m1"), receipt("b", 100, 1, session = "s2", message = "m1")))
        assertEquals(100L, s.getLong("outputTokens")); assertEquals(1, s.getJSONArray("models").length())
    }

    @Test fun interruptedCallsAreNotSuccessfulSpeedSamples() {
        val s = ForgeInformationMetrics.aggregate("s1", emptyList(), listOf(receipt("a", 100, 1000).put("status", "interrupted")))
        assertTrue(s.isNull("average"))
    }

    @Test fun trustedSessionWinsOverBothUntrustedClaimLocations() {
        assertEquals("s1", ForgeInformationContext.requireSession("s1", "s1", JSONObject.NULL))
        for (claims in listOf(arrayOf<Any?>("s2", null), arrayOf<Any?>(null, "s2"), arrayOf<Any?>(42, null))) {
            assertThrows(IllegalArgumentException::class.java) { ForgeInformationContext.requireSession("s1", *claims) }
        }
        assertThrows(IllegalArgumentException::class.java) { ForgeInformationContext.requireSession(null, "s1") }
    }

    @Test fun detectionResultsRequireEveryContextFieldIncludingEndpoint() {
        val c = ForgeInformationContext("s1", "model", "p1", "HIGH", "endpoint1")
        assertTrue(c.matches(c.json()))
        for (field in listOf("threadId", "model", "provider", "effort", "endpoint")) assertFalse(c.matches(c.json().put(field, "different")))
    }

    @Test fun appliedSettingsAndModelChangesDoNotInventWindowDowngrades() {
        val state = JSONObject()
        fun capture(model: String, effort: String, window: Int) = JSONObject().put("model", model).put("provider", "p1").put("effort", effort).put("contextWindow", window)
        ForgeInformationObservation.observe(state, capture("a", "HIGH", 100_000), false, 1)
        ForgeInformationObservation.observe(state, capture("a", "LOW", 80_000), true, 2)
        assertEquals(0, state.getJSONArray("events").objects().count { it.getString("severity") == "warning" })
        ForgeInformationObservation.observe(state, capture("b", "LOW", 20_000), false, 3)
        assertEquals(1, state.getJSONArray("events").objects().count { it.getString("kind") == "context_change" })
        assertEquals("model_change", state.getJSONArray("events").objects().last().getString("kind"))
    }
}
