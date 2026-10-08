package com.openminis.app.data

import com.openminis.app.data.db.toSnapshot
import com.openminis.app.data.db.toProviderConfig
import com.openminis.app.data.model.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ProviderTimeoutRoundTripTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun instance(id: String, timeout: Int?) = ProviderInstance(
        id, id, ProviderType.openAI, ProviderCredential.apiKey, responseTimeoutSeconds = timeout,
    )

    @Test fun timeoutSurvivesJsonAndDatabaseAndIsIsolatedPerProvider() {
        val config = ProviderConfig(instances = mutableListOf(instance("a", 900), instance("b", null)))
        val decoded = json.decodeFromString(ProviderConfig.serializer(), json.encodeToString(ProviderConfig.serializer(), config))
        val restored = decoded.toSnapshot(json).toProviderConfig(json)
        assertEquals(900, restored.instances[0].responseTimeoutSeconds)
        assertNull(restored.instances[1].responseTimeoutSeconds)
        restored.instances[0].responseTimeoutSeconds = null
        assertNull(restored.toSnapshot(json).toProviderConfig(json).instances[0].responseTimeoutSeconds)
    }

    @Test fun oldConfigurationUsesDefaultAndStoredLimitsAreBounded() {
        val encoded = json.encodeToString(ProviderInstance.serializer(), instance("a", null))
        val old = org.json.JSONObject(encoded).apply { remove("responseTimeoutSeconds") }.toString()
        assertNull(json.decodeFromString(ProviderInstance.serializer(), old).responseTimeoutSeconds)
        val config = ProviderConfig(instances = mutableListOf(instance("a", 1), instance("b", 99999)))
        assertEquals(listOf(30, 3600), config.toSnapshot(json).toProviderConfig(json).instances.map { it.responseTimeoutSeconds })
    }
}
