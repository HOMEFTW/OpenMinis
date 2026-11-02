package com.openminis.app.usage

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Stable release omitted its shared JSON fixtures; these cases specify the wire contracts directly. */
class UsageVectorsTest {
    @Test fun deepSeekCacheHitIsNotHiddenByOpenAIPlaceholderZero() {
        val u = ProviderUsageParser.openAIChat(JSONObject("""{"prompt_tokens":100,"prompt_tokens_details":{"cached_tokens":0},"prompt_cache_hit_tokens":70,"completion_tokens":20}"""))
        assertEquals(30L, u.input); assertEquals(70L, u.cacheRead); assertEquals(100L, u.contextTokens)
    }
    @Test fun cacheWritesAreNotDoubleCounted() {
        val u = ProviderUsageParser.openAIChat(JSONObject("""{"prompt_tokens":100,"prompt_tokens_details":{"cached_tokens":20,"cache_write_tokens":30}}"""))
        assertEquals(50L, u.input); assertEquals(30L, u.cacheWrite)
    }
    @Test fun responsesReasoningIsSubsetOfOutput() {
        val u = ProviderUsageParser.openAIResponses(JSONObject("""{"input_tokens":100,"input_tokens_details":{"cached_tokens":40},"output_tokens":30,"output_tokens_details":{"reasoning_tokens":20}}"""))
        assertEquals(60L, u.input); assertEquals(30L, u.output); assertEquals(20L, u.reasoning)
    }
    @Test fun geminiAddsSeparatelyReportedThinkingToOutput() {
        val u = ProviderUsageParser.gemini(JSONObject("""{"promptTokenCount":100,"cachedContentTokenCount":30,"candidatesTokenCount":20,"thoughtsTokenCount":10}"""))
        assertEquals(70L, u.input); assertEquals(30L, u.output); assertEquals(10L, u.reasoning)
    }
    @Test fun missingAndZeroCacheRemainDistinct() {
        assertFalse(ProviderUsageParser.openAIChat(JSONObject("{}" )).cacheKeyPresent)
        val u = ProviderUsageParser.openAIChat(JSONObject("""{"prompt_cache_hit_tokens":0}"""))
        assertTrue(u.cacheKeyPresent); assertEquals(true, u.toLLMUsage().cacheReported)
    }
    @Test fun anthropicDeltaPreservesStartInputAndCache() {
        val start = ProviderUsageParser.anthropic(JSONObject("""{"input_tokens":10,"cache_read_input_tokens":90,"output_tokens":0}"""))
        val delta = ProviderUsageParser.anthropic(JSONObject("""{"output_tokens":25}"""))
        val u = UsageChunkMerger.merge(start, delta)
        assertEquals(10L, u.input); assertEquals(90L, u.cacheRead); assertEquals(25L, u.output)
    }
    @Test fun lateCacheBreakdownReplacesUnsplitInput() {
        val a = ProviderUsageParser.openAIChat(JSONObject("""{"prompt_tokens":100}"""))
        val b = ProviderUsageParser.openAIChat(JSONObject("""{"prompt_tokens":100,"prompt_cache_hit_tokens":80}"""))
        val merged = UsageChunkMerger.merge(a.toLLMUsage(), b.toLLMUsage())
        assertEquals(20, merged.inputTokens); assertEquals(80, merged.cacheReadInputTokens)
    }
    @Test fun repeatedCumulativeBlocksDoNotSumTwiceOrResetToZero() {
        var u = UsageChunk(output = 25, reasoning = 10)
        u = UsageChunkMerger.merge(u, UsageChunk(output = 25))
        u = UsageChunkMerger.merge(u, UsageChunk(output = 0))
        assertEquals(25L, u.output); assertEquals(10L, u.reasoning)
    }
    @Test fun numericStringsBooleansAndOutOfRangeCountsAreNotAccepted() {
        assertNull(UsageNumbers.count("100")); assertNull(UsageNumbers.count(true))
        assertEquals(0L, UsageNumbers.count(-1)); assertEquals(0L, UsageNumbers.count(Double.POSITIVE_INFINITY))
        assertEquals(12L, UsageNumbers.count(12.9))
    }
    @Test fun copiedUsageIsMarkedAndInvalidPayloadPreserved() {
        assertTrue(JSONObject(StoredUsageJson.markCopied("""{"inputTokens":10}""")!!).getBoolean("copied"))
        assertEquals("broken", StoredUsageJson.markCopied("broken"))
    }
}
