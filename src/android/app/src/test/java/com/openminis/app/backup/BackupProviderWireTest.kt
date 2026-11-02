package com.openminis.app.backup

import com.openminis.app.data.model.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class BackupProviderWireTest {
    private val instance = """{"id":"p","label":"Provider","providerType":"openAI","credentialType":"apiKey"}"""
    private fun decode(text: String) = BackupProviderWire.decode(text)

    @Test fun `backup writes the iOS required defaults and limited fallback without changing device config`() {
        val original = decode("""{"instances":[$instance],"modelGroups":[{"id":"g","name":"Group"}]}""").config
        val encoded = BackupFormat.json.parseToJsonElement(BackupProviderWire.encode(original)).jsonObject
        val provider = encoded.getValue("instances").jsonArray.single().jsonObject
        assertEquals(true, provider.getValue("isEnabled").jsonPrimitive.boolean)
        assertEquals(true, provider.getValue("appendV1Suffix").jsonPrimitive.boolean)
        val group = encoded.getValue("modelGroups").jsonArray.single().jsonObject
        assertEquals("fallback", group.getValue("strategy").jsonPrimitive.content)
        assertEquals("limited", group.getValue("fallbackStrategy").jsonPrimitive.content)
        assertEquals(JsonObject(emptyMap()), encoded["sessionBindings"])
        assertEquals(FallbackStrategy.default, original.modelGroups.single().fallbackStrategy)
        assertEquals(original.modelGroups, decode(BackupProviderWire.encode(original)).config.modelGroups)
    }

    @Test fun `empty config writes all required empty arrays and the bindings object`() {
        val encoded = BackupFormat.json.parseToJsonElement(BackupProviderWire.encode(ProviderConfig())).jsonObject
        for (key in listOf("instances", "modelEntries", "modelGroups")) assertEquals(JsonArray(emptyList()), encoded[key])
        assertEquals(JsonObject(emptyMap()), encoded["sessionBindings"])
    }

    @Test fun `one malformed provider produces a readable warning and retains healthy siblings`() {
        val decoded = decode("""{"instances":[{"id":"bad","providerType":"openAI","credentialType":"apiKey"},$instance]}""")
        assertEquals(listOf("p"), decoded.config.instances.map { it.id })
        assertEquals(1, decoded.droppedInstances)
        val warning = BackupProviderWire.warnings(decoded, null).single()
        assertTrue(warning, "bad" in warning && "label" in warning)
    }

    @Test fun `unknown provider types stay visible and malformed groups identify the failing path`() {
        val unknown = decode("""{"instances":[${instance.replace("openAI", "futureProvider")}]}""")
        assertEquals(ProviderType.unsupported, unknown.config.instances.single().providerType)
        val failure = runCatching { decode("""{"instances":[$instance],"modelGroups":[{"id":"bad-group"}]}""") }.exceptionOrNull()!!
        val warning = BackupProviderWire.warnings(null, failure).single()
        assertTrue(warning, "name" in warning && "$.modelGroups[0]" in warning)
    }

    @Test fun `fork subagent roster survives provider wire round trip`() {
        val input = ProviderConfig(subAgents = mutableListOf(SubAgentDefinition(id = "s", name = "Agent", description = "Task", updatedAt = 1_700_000_000_000L)))
        assertEquals(input.subAgents, decode(BackupProviderWire.encode(input)).config.subAgents)
    }

    @Test fun `model entries write iOS required booleans even at defaults`() {
        val input = ProviderConfig(modelEntries = mutableListOf(
            ModelEntry("p", LLMModel("model", "Model", "openAI"), uuid = "entry"),
        ))
        val output = BackupFormat.json.parseToJsonElement(BackupProviderWire.encode(input)).jsonObject
            .getValue("modelEntries").jsonArray.single().jsonObject
        assertEquals(false, output.getValue("isHidden").jsonPrimitive.boolean)
        assertEquals(false, output.getValue("isCustom").jsonPrimitive.boolean)
        assertEquals(input.modelEntries, decode(BackupProviderWire.encode(input)).config.modelEntries)
    }
}
