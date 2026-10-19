package com.openminis.app.backup

import com.openminis.app.data.model.*
import org.junit.Assert.*
import org.junit.Test

class BackupProviderMergeTest {
    private fun provider(id: String, label: String = id) = ProviderInstance(
        id, label, ProviderType.openAI, ProviderCredential.apiKey, createdAt = 1,
    )
    private fun entry(id: String, temperature: Double = 0.2) = ModelEntry(
        providerInstanceId = "p", baseModel = LLMModel("model", "Model", "openAI"),
        overrides = ModelOverrides(temperature = temperature), uuid = id,
    )
    private fun group(id: String, name: String, vararg members: String) =
        ModelGroup(id, name, members.toMutableList())

    @Test fun `backup overwrites same id provider model parameters and group settings`() {
        val local = ProviderConfig(
            instances = mutableListOf(provider("p", "local"), provider("device")),
            modelEntries = mutableListOf(entry("e"), entry("device-entry")),
            modelGroups = mutableListOf(group("g", "Old", "e", "device-entry")),
        )
        val remote = ProviderConfig(
            instances = mutableListOf(provider("p", "backup").copy(customBaseURL = "https://backup.example")),
            modelEntries = mutableListOf(entry("e", 0.8)),
            modelGroups = mutableListOf(group("g", "Backup", "e").copy(defaultThinkingLevel = ThinkingLevel.HIGH)),
        )
        val result = BackupProviderMerge.merge(local, remote)
        assertEquals("backup", result.config.instances.first().label)
        assertEquals(0.8, result.config.modelEntries.first().overrides.temperature!!, 0.0)
        assertEquals(ThinkingLevel.HIGH, result.config.modelGroups.single().defaultThinkingLevel)
        assertEquals(listOf("e", "device-entry"), result.config.modelGroups.single().memberEntryIds)
        assertEquals(listOf("p", "device"), result.config.instances.map { it.id })
        assertEquals(0, result.imported)
        assertEquals(3, result.updated)
        assertEquals("local", local.instances.first().label)
    }

    @Test fun `same name group keeps local identity and remaps all restored bindings`() {
        val local = ProviderConfig(
            modelGroups = mutableListOf(group("device-group", " Work ", "device-entry")),
            subAgents = mutableListOf(SubAgentDefinition("a", "research", "", modelGroupId = "device-group")),
        )
        val remote = ProviderConfig(
            modelGroups = mutableListOf(group("backup-group", "work", "backup-entry")),
            defaultPrimaryGroupId = "backup-group", defaultSubGroupId = "backup-group",
            voiceInputGroupId = "backup-group", voiceOutputGroupId = "backup-group", visionGroupId = "backup-group",
            agentLoopGroupIds = mutableListOf("backup-group"),
        )
        val result = BackupProviderMerge.merge(local, remote)
        val config = result.config
        assertEquals("device-group", config.modelGroups.single().id)
        assertEquals(listOf("backup-entry", "device-entry"), config.modelGroups.single().memberEntryIds)
        assertEquals("device-group", config.defaultPrimaryGroupId)
        assertEquals("device-group", config.defaultSubGroupId)
        assertEquals("device-group", config.voiceInputGroupId)
        assertEquals("device-group", config.voiceOutputGroupId)
        assertEquals("device-group", config.visionGroupId)
        assertEquals(listOf("device-group"), config.agentLoopGroupIds)
        assertEquals(mapOf("backup-group" to "device-group"), result.groupIdRemapping)
        assertEquals(0, result.imported)
        assertEquals(1, result.updated)
    }

    @Test fun `same id membership replaces backed up models while preserving device only models`() {
        val local = ProviderConfig(modelGroups = mutableListOf(group("g", "G", "removed", "device")))
        val remote = ProviderConfig(
            modelEntries = mutableListOf(entry("removed"), entry("added")),
            modelGroups = mutableListOf(group("g", "G", "added")),
        )
        assertEquals(listOf("added", "device"), BackupProviderMerge.merge(local, remote).config.modelGroups.single().memberEntryIds)
    }

    @Test fun `swapped names do not merge groups whose ids already match`() {
        val local = ProviderConfig(modelGroups = mutableListOf(group("a", "Alpha"), group("b", "Beta")))
        val remote = ProviderConfig(modelGroups = mutableListOf(group("a", "Beta"), group("b", "Alpha")))
        val merged = BackupProviderMerge.merge(local, remote).config
        assertEquals(listOf("a", "b"), merged.modelGroups.map { it.id })
        assertEquals(listOf("Beta", "Alpha"), merged.modelGroups.map { it.name })
    }

    @Test fun `restoring twice is unchanged and does not double counts`() {
        val remote = ProviderConfig(
            instances = mutableListOf(provider("p")), modelEntries = mutableListOf(entry("e")),
            modelGroups = mutableListOf(group("g", "G", "e")),
        )
        val first = BackupProviderMerge.merge(ProviderConfig(), remote)
        assertEquals(3, first.imported)
        val second = BackupProviderMerge.merge(first.config, remote)
        assertEquals(0, second.imported)
        assertEquals(0, second.updated)
        assertEquals(3, second.skipped)
        assertFalse(second.hasChanges)
    }

    @Test fun `unrelated duplicate device names are left untouched`() {
        val local = ProviderConfig(modelGroups = mutableListOf(group("a", "Local"), group("b", "Local")))
        val result = BackupProviderMerge.merge(local, ProviderConfig())
        assertEquals(listOf("a", "b"), result.config.modelGroups.map { it.id })
        assertFalse(result.hasChanges)
    }

    @Test fun `same name restore remains idempotent and keeps device only exposure`() {
        val local = ProviderConfig(
            modelGroups = mutableListOf(group("local", "Work", "local-entry"), group("only", "Device")),
            agentLoopGroupIds = mutableListOf("only"),
        )
        val remote = ProviderConfig(
            modelGroups = mutableListOf(group("remote", "Work", "remote-entry")),
            agentLoopGroupIds = mutableListOf("remote"),
        )
        val first = BackupProviderMerge.merge(local, remote)
        assertEquals(listOf("local", "only"), first.config.agentLoopGroupIds)
        val second = BackupProviderMerge.merge(first.config, remote)
        assertFalse(second.hasChanges)
        assertEquals(0, second.updated)
    }

    @Test fun `same name merge never deletes a second local group that a live session can reference`() {
        val local = ProviderConfig(modelGroups = mutableListOf(group("a", "Work"), group("b", "Work", "device")))
        val remote = ProviderConfig(modelGroups = mutableListOf(group("c", "Work", "incoming")))
        val merged = BackupProviderMerge.merge(local, remote)
        assertEquals(listOf("a", "b"), merged.config.modelGroups.map { it.id })
        assertEquals(listOf("device"), merged.config.modelGroups.last().memberEntryIds)
        assertEquals(mapOf("c" to "a"), merged.groupIdRemapping)
    }
}
