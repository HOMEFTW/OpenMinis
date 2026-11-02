package com.openminis.app.forge

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ForgeInformationFingerprintTest {
    private val assets = informationAssets()
    private val fingerprint = ForgeInformationFingerprint(JSONObject(File(assets, "information-bank.json").readText()))

    @Test fun oneTwoAndThreeSamplesMatchTheOriginalJavaScriptIncludingFamiliesAndCalibration() {
        val vectors = JSONObject(File(assets, "information-test-vectors.json").readText()).getJSONArray("cases")
        vectors.objects().forEach { vector -> compare(vector.getJSONObject("analysis"), fingerprint.analyze(vector.getJSONArray("outputs")), "analysis") }
    }

    @Test fun unknownModelCannotRaiseAMismatchEvenWithStrongEvidence() {
        val vector = JSONObject(File(assets, "information-test-vectors.json").readText()).getJSONArray("cases").getJSONObject(2)
        val result = fingerprint.assess("gpt-6.1-sol", vector.getJSONObject("analysis"))
        assertEquals("UNLISTED", result.getString("verdict")); assertFalse(result.getBoolean("warning"))
        assertEquals("UNLISTED", fingerprint.assess("brand-new-model", null).getString("verdict"))
    }

    @Test fun highConfidenceMismatchRequiresTwoSamplesAndAllThresholds() {
        val analysis = JSONObject().put("used_outputs", 1).put("results", JSONArray().put(
            JSONObject().put("model", "gpt-5.4").put("score", 1.0).put("probability", .9)).put(
            JSONObject().put("model", "gpt-5.4-mini").put("score", 0.0).put("probability", .1)))
        assertEquals("SUSPICIOUS", fingerprint.assess("gpt-5.4-mini", analysis).getString("verdict"))
        analysis.put("used_outputs", 2)
        assertEquals("MISMATCH", fingerprint.assess("gpt-5.4-mini", analysis).getString("verdict"))
    }

    @Test fun expirationUses24HoursAndRejectsFutureClockSkew() {
        val now = 2_000_000_000_000L
        assertFalse(ForgeInformationFingerprint.stale(now - ForgeInformationFingerprint.TTL_MS, now))
        assertTrue(ForgeInformationFingerprint.stale(now - ForgeInformationFingerprint.TTL_MS - 1, now))
        assertTrue(ForgeInformationFingerprint.stale(now + 60_001, now))
    }

    @Test fun longestRunParsingMatchesSourceAndInvalidInputsAreRejected() {
        assertEquals(listOf(7, 8, 9), ForgeInformationFingerprint.parseNumbers("explain 1,2 abc [7,8,9] end 355,356"))
        assertThrows(IllegalArgumentException::class.java) { fingerprint.analyze(JSONArray()) }
        assertThrows(IllegalArgumentException::class.java) { fingerprint.analyze(JSONArray().put(JSONObject().put("text", "[1,2]").put("expected_count", 320))) }
        assertThrows(IllegalArgumentException::class.java) { fingerprint.analyze(JSONArray().put(JSONObject().put("text", "1 ".repeat(100)).put("expected_count", "100"))) }
    }

    private fun compare(expected: Any, actual: Any, at: String) {
        when (expected) {
            is JSONObject -> {
                actual as JSONObject
                assertEquals(at, expected.keySet(), actual.keySet())
                expected.keySet().forEach { compare(expected.get(it), actual.get(it), "$at.$it") }
            }
            is JSONArray -> {
                actual as JSONArray
                assertEquals(at, expected.length(), actual.length())
                for (i in 0 until expected.length()) compare(expected.get(i), actual.get(i), "$at[$i]")
            }
            is Number -> assertEquals(at, expected.toDouble(), (actual as Number).toDouble(), 1e-10)
            else -> assertEquals(at, expected, actual)
        }
    }
}

internal fun informationAssets(): File {
    var root: File? = File(System.getProperty("user.dir") ?: ".")
    repeat(8) {
        val base = root ?: error("Cannot locate information test assets")
        for (relative in listOf("src/main/assets/forge", "app/src/main/assets/forge", "src/android/app/src/main/assets/forge")) {
            val dir = File(base, relative)
            if (File(dir, "information-bank.json").isFile) return dir
        }
        root = base.parentFile
    }
    error("Cannot locate information test assets")
}
