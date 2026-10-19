package com.openminis.app.data.repository

import com.openminis.app.data.model.*
import org.junit.Assert.*
import org.junit.Test

class ModelEntryDeletionTest {
    @Test fun deletingApiOrCustomModelsCleansEveryReferenceAndPreservesOtherModels() {
        for (custom in listOf(false, true)) {
            val model = LLMModel("served", "Served", "Custom")
            val removed = ModelEntry("a", model, isCustom = custom, isHidden = true, uuid = "removed")
            val kept = ModelEntry("b", model, uuid = "kept")
            val group = ModelGroup(name = "Shared", memberEntryIds = mutableListOf(removed.id, kept.id))
            val config = ProviderConfig(modelEntries = mutableListOf(removed, kept), modelGroups = mutableListOf(group),
                agentLoopModelEntryIds = mutableListOf(removed.id, kept.id), defaultPrimaryGroupId = group.id)
            removeModelEntryReferences(config, setOf(removed.id))
            assertEquals(listOf(kept), config.modelEntries)
            assertEquals(listOf(kept.id), group.memberEntryIds)
            assertEquals(listOf(kept.id), config.agentLoopModelEntryIds)
            assertEquals(group.id, config.defaultPrimaryGroupId)
        }
    }

    @Test fun automaticCatalogRefreshKeepsDeletionsAndManualRefreshRestoresApiModels() {
        val deleted = LLMModel("deleted", "Deleted", "Custom")
        val kept = LLMModel("kept", "Kept", "Custom")
        val catalog = listOf(deleted, kept, kept)
        assertEquals(listOf(kept), modelsAfterDeletionPolicy(catalog, setOf("deleted"), false))
        assertEquals(listOf(deleted, kept), modelsAfterDeletionPolicy(catalog, setOf("deleted"), true))
        // A failed/empty fetch cannot fabricate a replacement model.
        assertTrue(modelsAfterDeletionPolicy(emptyList(), setOf("deleted"), true).isEmpty())
    }
}
