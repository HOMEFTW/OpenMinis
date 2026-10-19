package com.openminis.app.ui.settings

import com.openminis.app.data.db.UsageRecord
import com.openminis.app.data.model.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class Build34UsageStatsTest {
    private val hk = ZoneId.of("Asia/Hong_Kong")
    private fun time(local: String, zone: ZoneId = hk) = LocalDateTime.parse(local).atZone(zone).toInstant().toEpochMilli()
    private fun record(instance: String? = "a", snapshot: Boolean = true, model: String? = "shared",
                       at: Long = time("2026-10-19T09:00:00"), usage: String = """{"inputTokens":10,"outputTokens":5}""") =
        UsageRecord(model, "Saved name", "openAI", snapshot, usage, at, "session", instance)

    @Test fun todayAndSevenDaysUseLocalCalendarBoundariesAndNeverCountFutureRows() {
        val now = time("2026-10-19T12:00:00")
        val start = time("2026-10-19T00:00:00")
        assertTrue(usageInDateRange(start, now, 1, hk))
        assertFalse(usageInDateRange(start - 1, now, 1, hk))
        val week = time("2026-10-13T00:00:00")
        assertTrue(usageInDateRange(week, now, 7, hk))
        assertFalse(usageInDateRange(week - 1, now, 7, hk))
        assertFalse(usageInDateRange(now + 1, now, 0, hk))
        assertTrue(usageInDateRange(0, now, 0, hk))
    }

    @Test fun timezoneChangesAlterDateMembership() {
        val now = time("2026-10-19T01:00:00")
        val earlier = time("2026-10-18T23:00:00")
        assertFalse(usageInDateRange(earlier, now, 1, hk))
        assertTrue(usageInDateRange(earlier, now, 1, ZoneId.of("UTC")))
    }

    @Test fun hourlyTrendUses23And25BucketsAcrossDstAndKeepsTotals() {
        val la = ZoneId.of("America/Los_Angeles")
        for ((date, hours) in listOf("2026-03-08" to 23, "2026-11-01" to 25)) {
            val now = time("${date}T23:59:00", la)
            val row = usageSamples(listOf(record(at = time("${date}T12:00:00", la))), null)
            val trend = usageTrend(row, now, 1, la)
            assertEquals(hours, trend.size)
            assertEquals(usageTotal(row), trend.fold(UsageTokens()) { total, bucket -> total + bucket.tokens })
        }
    }

    @Test fun instancesServingSameModelRemainSeparateAndRemovedInstancesRetainUsage() {
        val model = LLMModel("shared", "Live", "Custom")
        val config = ProviderConfig(
            instances = mutableListOf(ProviderInstance("a", "Endpoint A", ProviderType.openAI, ProviderCredential.apiKey),
                ProviderInstance("b", "Endpoint B", ProviderType.openAI, ProviderCredential.apiKey)),
            modelEntries = mutableListOf(ModelEntry("a", model), ModelEntry("b", model)),
        )
        val rows = usageSamples(listOf(record("a"), record("b"), record("removed")), config)
        assertEquals(3, rows.map { it.providerKey }.toSet().size)
        assertEquals(listOf("Endpoint A", "Endpoint B"), rows.take(2).map { it.providerName })
        assertEquals(Attribution.MEASURED_REMOVED, rows.last().attribution)
        assertEquals("Saved name", rows.last().displayName)
        assertEquals(45L, usageTotal(rows).total)
    }

    @Test fun malformedUnknownLegacyAndLargeTokenRowsDoNotLoseValidUsage() {
        val records = listOf(record(usage = "bad"), record(instance = null, snapshot = false, model = null,
            usage = """{"inputTokens":3000000000,"outputTokens":7,"cacheReadInputTokens":30,"cacheCreationInputTokens":20}"""))
        val rows = usageSamples(records, null)
        assertEquals(1, rows.size)
        assertEquals(Attribution.UNKNOWN_SESSION, rows.single().attribution)
        assertEquals(3_000_000_057L, usageTotal(rows).total)
        val bounded = usageTrend(rows, time("2026-10-19T12:00:00"), 7, hk)
        assertEquals(7, bounded.size)
        assertEquals(usageTotal(rows), bounded.fold(UsageTokens()) { a, b -> a + b.tokens })
    }

    @Test fun measuredAndEstimatedRowsStayDistinctAndEmptySelectionsHaveZeroTotals() {
        val rows = usageSamples(listOf(record(snapshot = true), record(snapshot = false)), null)
        assertEquals(2, usageModelStats(rows, hk).size)
        assertEquals(0L, usageTotal(emptyList()).total)
        assertEquals(30, usageTrend(emptyList(), time("2026-10-19T12:00:00"), 30, hk).size)
        assertTrue(usageTrend(emptyList(), time("2026-10-19T12:00:00"), 0, hk).isEmpty())
    }

    @Test fun allTimeMonthlyBucketsRetainEveryToken() {
        val rows = usageSamples(listOf(record(at = time("2025-12-31T23:00:00")), record()), null)
        val trend = usageTrend(rows, time("2026-10-19T12:00:00"), 0, hk)
        assertEquals("2025-12", trend.first().label)
        assertEquals(11, trend.size)
        assertEquals(usageTotal(rows), trend.fold(UsageTokens()) { a, b -> a + b.tokens })
    }
}
