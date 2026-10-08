package com.openminis.app.provider.thinking

import com.openminis.app.data.model.ThinkingLevel
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class CustomThinkingRuleRegressionTest {
    private val instanceId = "custom-rule-regression"
    @After fun clearRules() = ThinkingRuleResolver.setCustomRules(instanceId, emptyList())

    private fun apply(format: ThinkingWireFormat, level: ThinkingLevel, body: JSONObject = JSONObject()): JSONObject {
        ThinkingRuleResolver.setCustomRules(instanceId, listOf(ThinkingRule(
            kind = ThinkingRule.Kind.CUSTOM, scope = ThinkingRule.Scope.AllModels,
            wireFormat = format, label = "relay rule",
        )))
        ThinkingRuleResolver.apply(body, ThinkingResolveContext(
            modelId = "relay-model", instanceId = instanceId, supportsReasoning = true,
            declaredEffortValues = null, level = level, maxTokens = 4096,
            isOpenRouter = false, usesUnifiedReasoningEffort = false,
            isMistral = false, isDashScope = false, offEffort = null,
        ))
        return body
    }

    @Test fun customPathUsesHighFallbackAndExplicitOffValue() {
        val format = ThinkingWireFormat.CustomPath("reasoning.effort", mapOf(ThinkingLevel.HIGH to "deep"), "none")
        assertEquals("deep", apply(format, ThinkingLevel.LOW).getJSONObject("reasoning").getString("effort"))
        assertEquals("none", apply(format, ThinkingLevel.OFF).getJSONObject("reasoning").getString("effort"))
        assertFalse(apply(format.copy(offValue = null), ThinkingLevel.OFF).has("reasoning"))
    }

    @Test fun booleanAndExtraBodyRulesEmitRealBooleansIncludingOff() {
        assertTrue(apply(ThinkingWireFormat.BooleanToggle("thinking"), ThinkingLevel.HIGH).getBoolean("thinking"))
        val body = apply(ThinkingWireFormat.ExtraBodyToggle("extra_body.thinking.enabled"), ThinkingLevel.OFF)
        assertFalse(body.getJSONObject("extra_body").getJSONObject("thinking").getBoolean("enabled"))
    }

    @Test fun reservedRootCannotRetargetTheModelOrReplaceMessages() {
        for (path in listOf("model", " Messages .content", "stream", "tools.0")) {
            val body = JSONObject().put("model", "original").put("messages", "original messages")
            apply(ThinkingWireFormat.CustomPath(path, mapOf(ThinkingLevel.HIGH to "bad"), null), ThinkingLevel.HIGH, body)
            assertEquals("original", body.getString("model"))
            assertEquals("original messages", body.getString("messages"))
            assertEquals(2, body.length())
        }
    }
}
