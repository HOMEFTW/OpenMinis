package com.openminis.app.provider.usage

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class KimiSubscriptionUsageTest {
    @Test fun `official CLI fields decode weekly and timed subscription quotas`() {
        val usage = parseKimiSubscriptionUsage(JSONObject("""{
            "usage":{"limit":"100","remaining":"35","resetTime":"2026-10-15T05:24:18.443553353Z"},
            "limits":[{"window":{"duration":300,"timeUnit":"TIME_UNIT_MINUTE"},
                       "detail":{"limit":"80","used":"20","resetIn":3600}}]
        }"""))
        assertEquals(65L, usage.weekly?.used)
        assertEquals(35L, usage.weekly?.remaining)
        assertEquals(100L, usage.weekly?.limit)
        assertEquals(0.35f, usage.weekly!!.remainingFraction!!, 0.0001f)
        assertEquals(Instant.parse("2026-10-15T05:24:18.443553353Z"), usage.weekly?.resetAt)
        assertEquals("5h", usage.limits.single().windowLabel)
        assertEquals(60L, usage.limits.single().remaining)
        assertEquals(3600L, usage.limits.single().resetInSeconds)
    }

    @Test fun `missing quota fields stay unknown instead of pretending zero or full availability`() {
        val limitOnly = parseKimiSubscriptionUsage(JSONObject("""{"usage":{"limit":100}}""")).weekly!!
        assertNull(limitOnly.used)
        assertNull(limitOnly.remaining)
        assertNull(limitOnly.remainingFraction)
        val usedOnly = parseKimiSubscriptionUsage(JSONObject("""{"usage":{"used":"0"}}""")).weekly!!
        assertEquals(0L, usedOnly.used)
        assertNull(usedOnly.limit)
        assertNull(usedOnly.remainingFraction)
        val remainingOnly = parseKimiSubscriptionUsage(JSONObject("""{"usage":{"remaining":7}}""")).weekly!!
        assertEquals(7L, remainingOnly.remaining)
        assertNull(remainingOnly.used)
        assertNull(remainingOnly.limit)
    }

    @Test fun `invalid numbers and unrelated local token data cannot become subscription quotas`() {
        for (value in listOf("null", "true", "-1", "1.5", "\"unknown\"", "\"9223372036854775808\"")) {
            assertTrue(parseKimiSubscriptionUsage(JSONObject("""{"usage":{"limit":$value,"used":$value}}""")).isEmpty)
        }
        assertTrue(parseKimiSubscriptionUsage(JSONObject("""{"input_tokens":1000,"output_tokens":42}""")).isEmpty)
        assertTrue(parseKimiSubscriptionUsage(JSONObject("""{"usage":null,"limits":[null,"bad",{}]}""")).isEmpty)
    }

    @Test fun `explicit use takes precedence and zero limits never render an invented percentage`() {
        val used = parseKimiSubscriptionUsage(JSONObject("""{"usage":{"limit":100,"used":70,"remaining":90}}""")).weekly!!
        assertEquals(30L, used.remaining)
        val exhausted = parseKimiSubscriptionUsage(JSONObject("""{"usage":{"limit":0,"used":10}}""")).weekly!!
        assertEquals(0L, exhausted.remaining)
        assertNull(exhausted.remainingFraction)
    }

    @Test fun `named limits flat details and supported reset aliases remain readable`() {
        val usage = parseKimiSubscriptionUsage(JSONObject("""{"limits":[
            {"name":"Burst","detail":{"limit":20,"used":5,"reset_at":"2026-10-15T00:00:00Z"}},
            {"title":"Requests","limit":10,"remaining":2,"ttl":30},
            {"window":{"duration":7,"timeUnit":"UNRECOGNIZED"},"detail":{"limit":10,"used":1,"resetTime":"not a date"}}
        ]}"""))
        assertEquals("Burst", usage.limits[0].name)
        assertEquals(Instant.parse("2026-10-15T00:00:00Z"), usage.limits[0].resetAt)
        assertEquals("Requests", usage.limits[1].name)
        assertEquals(8L, usage.limits[1].used)
        assertEquals(30L, usage.limits[1].resetInSeconds)
        assertNull(usage.limits[2].windowLabel)
        assertNull(usage.limits[2].resetAt)
    }
}
