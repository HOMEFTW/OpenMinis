package com.openminis.app.data

import com.openminis.app.data.db.toProviderConfig
import com.openminis.app.data.db.toSnapshot
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.SubAgentDefinition
import com.openminis.app.data.model.ThinkingLevel
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ProviderSubAgentStorageTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }

    @Test fun anExplicitlyClearedRosterWritesEmptyMetaAndLegacySnapshotLoadsEmpty() {
        val snapshot = ProviderConfig().toSnapshot(json)
        assertEquals("[]", snapshot.meta.single { it.key == "sub_agents_json" }.value)
        val legacy = snapshot.copy(meta = snapshot.meta.filterNot { it.key == "sub_agents_json" })
        assertTrue(legacy.toProviderConfig(json).subAgents.isEmpty())
    }

    @Test fun customRosterSurvivesRoomSnapshotAndJsonMirror() {
        val agent = SubAgentDefinition(id = "research", name = "Research", description = "Read sources",
            instructions = "Cite findings", modelGroupId = "group", thinkingLevelOverride = ThinkingLevel.HIGH,
            updatedAt = 1_758_000_000_000L)
        val config = ProviderConfig(subAgents = mutableListOf(agent))
        val fromDb = config.toSnapshot(json).toProviderConfig(json)
        assertEquals(listOf(agent), fromDb.subAgents)
        val fromMirror = json.decodeFromString(ProviderConfig.serializer(), json.encodeToString(ProviderConfig.serializer(), config))
        assertEquals(listOf(agent), fromMirror.subAgents)
    }

    @Test fun iosLowercaseThinkingLevelIsPreservedInsteadOfCoercedToNull() {
        val config = json.decodeFromString(ProviderConfig.serializer(),
            """{"subAgents":[{"id":"a","name":"Research","description":"Read","thinkingLevelOverride":"high","updatedAt":0}]}""")
        assertEquals(ThinkingLevel.HIGH, config.subAgents.single().thinkingLevelOverride)
        assertEquals(ThinkingLevel.HIGH, ThinkingLevel.parseOrNull(" HiGh "))
        assertNull(ThinkingLevel.parseOrNull("unknown"))
        assertEquals(ThinkingLevel.XHIGH, ThinkingLevel.decoded("unknown"))
    }
}
