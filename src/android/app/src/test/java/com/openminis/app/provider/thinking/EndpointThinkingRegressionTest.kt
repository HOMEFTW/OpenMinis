package com.openminis.app.provider.thinking

import com.openminis.app.data.model.ThinkingLevel
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class EndpointThinkingRegressionTest {
    private fun context() = ThinkingResolveContext(
        modelId = "qwen-3.8-27b", instanceId = null, supportsReasoning = true,
        declaredEffortValues = listOf("low", "medium", "high"), level = ThinkingLevel.HIGH,
        maxTokens = 4096, isOpenRouter = false, usesUnifiedReasoningEffort = false,
        isMistral = false, isDashScope = false, offEffort = "none",
    )

    @Test fun `Cerebras Qwen uses effort instead of the native Qwen toggle`() {
        val body = JSONObject()
        ThinkingRuleResolver.apply(body, context().copy(isCerebras = true))
        assertEquals("high", body.getString("reasoning_effort"))
        assertFalse(body.has("enable_thinking"))
        assertFalse(body.has("extra_body"))
    }

    @Test fun `xAI no effort declaration suppresses off as well as enabled efforts`() {
        for (level in listOf(ThinkingLevel.OFF, ThinkingLevel.HIGH)) {
            val body = JSONObject()
            ThinkingRuleResolver.apply(body, context().copy(modelId = "grok-build-0.1", isXAI = true,
                level = level, declaredEffortValues = emptyList(), declaresNoEffortTiers = true))
            assertFalse(body.has("reasoning_effort"))
        }
    }
}
