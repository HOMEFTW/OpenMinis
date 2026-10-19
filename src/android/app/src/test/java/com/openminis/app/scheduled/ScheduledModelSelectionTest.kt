package com.openminis.app.scheduled

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelGroup
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ScheduledModelSelectionTest {
    private val entry = ModelEntry("provider-a", LLMModel.gpt61Sol, uuid = "entry-a")

    @Test fun rawModelIdIsResolvedToAConcreteEntryAtCreation() {
        val selection = ScheduledModelSelection.resolve("gpt-6.1-sol", listOf(entry), emptyList())
        assertEquals("gpt-6.1-sol", selection.modelId)
        assertEquals("entry-a", JSONObject(selection.binding).getString("entryId"))
    }

    @Test fun entryIdDisambiguatesProvidersServingTheSameModel() {
        val other = entry.copy(providerInstanceId = "provider-b", uuid = "entry-b")
        assertTrue(runCatching { ScheduledModelSelection.resolve("gpt-6.1-sol", listOf(entry, other), emptyList()) }.isFailure)
        val selection = ScheduledModelSelection.resolve("entry-b", listOf(entry, other), emptyList())
        assertEquals("entry-b", JSONObject(selection.binding).getString("entryId"))
    }

    @Test fun groupBindingPreservesFutureGroupRoutingRatherThanPinningItsFirstModel() {
        val group = ModelGroup(id = "group", name = "Models", memberEntryIds = mutableListOf(entry.id))
        val selection = ScheduledModelSelection.resolve("group", listOf(entry), listOf(group))
        assertNull(selection.modelId)
        assertEquals("group", JSONObject(selection.binding).getString("groupId"))
        assertEquals("group", JSONObject(selection.binding).getString("type"))
    }

    @Test fun unknownModelsAndEmptyGroupsFailBeforeCreatingATask() {
        assertTrue(runCatching { ScheduledModelSelection.resolve("missing", listOf(entry), emptyList()) }.isFailure)
        assertTrue(runCatching { ScheduledModelSelection.resolve("empty", listOf(entry), listOf(ModelGroup(id = "empty", name = "Empty"))) }.isFailure)
    }
}
