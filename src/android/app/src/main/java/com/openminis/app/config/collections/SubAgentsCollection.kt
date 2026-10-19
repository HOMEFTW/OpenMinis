package com.openminis.app.config.collections

import com.openminis.app.config.*
import com.openminis.app.config.fields.ClosureField
import com.openminis.app.data.model.SubAgentDefinition
import com.openminis.app.data.model.SubAgentLimits
import com.openminis.app.data.model.SubAgentRoster
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.repository.ProviderRepository
import java.util.Locale

internal object SubAgentConfigRules {
    fun validate(value: SubAgentDefinition, roster: List<SubAgentDefinition>, groupIds: Set<String>) {
        if (value.name.isBlank() || value.name.length > SubAgentLimits.NAME_MAX_LENGTH ||
            value.description.length > SubAgentLimits.DESCRIPTION_MAX_LENGTH ||
            value.instructions.length > SubAgentLimits.INSTRUCTIONS_MAX_LENGTH) {
            throw ConfigError.InvalidValue("Sub agent name, description or instructions exceed their limits.")
        }
        if (roster.any { it.id != value.id && SubAgentRoster.nameKey(it.name) == SubAgentRoster.nameKey(value.name) }) {
            throw ConfigError.InvalidValue("A sub agent with that name already exists.")
        }
        if (roster.none { it.id == value.id } && roster.size >= SubAgentLimits.MAX_COUNT) {
            throw ConfigError.InvalidValue("The sub agent roster is full.")
        }
        if (value.modelGroupId != null && value.modelGroupId !in groupIds) {
            throw ConfigError.InvalidValue("Unknown model group: ${value.modelGroupId}")
        }
        roster.firstOrNull { it.id == value.id && it.isBuiltIn }?.let {
            if (value.name != it.name || value.description != it.description) {
                throw ConfigError.PermissionDenied("The built-in sub agent's name and description are read-only.")
            }
        }
    }

    fun validateOrder(ids: List<String>, roster: List<SubAgentDefinition>) {
        if (ids.size != roster.size || ids.toSet() != roster.map { it.id }.toSet() ||
            ids.firstOrNull() != SubAgentDefinition.BUILT_IN_ID) {
            throw ConfigError.InvalidValue("Order must contain every sub agent once, with the built-in agent first.")
        }
    }
}

class SubAgentsCollection(private val repo: ProviderRepository) : ConfigCollection {
    override val basePath = "subagents"
    override val displayName = "Sub agents"
    override val description = "The configured sub agent roster. The built-in agent cannot be renamed or removed."

    override fun childIds(): List<String> = repo.subAgents.map { it.id }

    private fun current(id: String) = repo.subAgent(id) ?: throw ConfigError.InvalidValue("Unknown sub agent: $id")

    private fun save(value: SubAgentDefinition) {
        SubAgentConfigRules.validate(value, repo.subAgents, repo.config.value.modelGroups.map { it.id }.toSet())
        repo.upsertSubAgent(value)
    }

    override fun fields(forId: String): List<ConfigField> {
        val definition = repo.subAgent(forId) ?: return emptyList()
        fun text(leaf: String, limit: Int, readOnly: Boolean = false,
                 read: (SubAgentDefinition) -> String, update: (SubAgentDefinition, String) -> SubAgentDefinition) = ClosureField(
            path = "$basePath.$forId.$leaf", displayName = leaf, description = "Sub agent $leaf.",
            valueSchema = ConfigSchema.Str(maxLength = limit),
            access = if (readOnly) ConfigAccess.READONLY else ConfigAccess.READWRITE,
            reader = { ConfigValue.Str(read(current(forId))) },
            writer = { value ->
                if (readOnly) throw ConfigError.PermissionDenied("This built-in field is read-only.")
                save(update(current(forId), (value as ConfigValue.Str).value))
            },
        )
        return listOf(
            text("name", SubAgentLimits.NAME_MAX_LENGTH, definition.isBuiltIn, { it.name }) { item, value -> item.copy(name = value.trim()) },
            text("description", SubAgentLimits.DESCRIPTION_MAX_LENGTH, definition.isBuiltIn, { it.description }) { item, value -> item.copy(description = value) },
            text("instructions", SubAgentLimits.INSTRUCTIONS_MAX_LENGTH, read = { it.instructions }) { item, value -> item.copy(instructions = value) },
            ClosureField(
                path = "$basePath.$forId.model_group", displayName = "Model group", description = "Configured model group ID, or null for Auto.",
                valueSchema = ConfigSchema.Optional(ConfigSchema.Str()),
                reader = { current(forId).modelGroupId?.let { ConfigValue.Str(it) } ?: ConfigValue.Null },
                writer = { save(current(forId).copy(modelGroupId = (it as? ConfigValue.Str)?.value?.takeIf(String::isNotBlank))) },
            ),
            ClosureField(
                path = "$basePath.$forId.thinking", displayName = "Thinking strength", description = "A thinking level, or null/\"inherit\" to use the group's default.",
                valueSchema = ConfigSchema.Optional(ConfigSchema.StrEnum(listOf("inherit") + ThinkingLevel.entries.map { it.name.lowercase(Locale.ROOT) })),
                reader = { current(forId).thinkingLevelOverride?.let { ConfigValue.Str(it.name.lowercase(Locale.ROOT)) } ?: ConfigValue.Null },
                writer = { value -> save(current(forId).copy(thinkingLevelOverride = thinking((value as? ConfigValue.Str)?.value))) },
            ),
        )
    }

    override fun add(payload: ConfigValue): String {
        val values = (payload as? ConfigValue.Obj)?.value ?: throw ConfigError.TypeMismatch("object")
        fun string(key: String): String? = when (val value = values[key]) {
            null, ConfigValue.Null -> null
            is ConfigValue.Str -> value.value
            else -> throw ConfigError.TypeMismatch("string for $key")
        }
        val value = SubAgentDefinition(
            name = string("name")?.trim() ?: throw ConfigError.InvalidValue("name is required"),
            description = string("description").orEmpty(), instructions = string("instructions").orEmpty(),
            modelGroupId = string("model_group")?.takeIf(String::isNotBlank),
            thinkingLevelOverride = thinking(string("thinking")), sortOrder = repo.subAgents.size,
        )
        save(value)
        return value.id
    }

    override fun remove(id: String) {
        if (current(id).isBuiltIn) throw ConfigError.PermissionDenied("The built-in sub agent cannot be removed.")
        repo.deleteSubAgent(id)
    }

    private fun thinking(token: String?): ThinkingLevel? = when (token) {
        null, "", "inherit" -> null
        else -> ThinkingLevel.entries.firstOrNull { it.name.equals(token, ignoreCase = true) }
            ?: throw ConfigError.InvalidValue("Unknown thinking level: $token")
    }
}
