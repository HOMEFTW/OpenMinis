package com.openminis.app.config

import android.content.Context
import com.openminis.app.config.collections.SubAgentConfigRules
import com.openminis.app.config.collections.SubAgentsCollection
import com.openminis.app.config.fields.ClosureField
import com.openminis.app.config.fields.PrefsBoolField
import com.openminis.app.config.fields.ReadOnlyField
import com.openminis.app.data.DeviceIdentity
import com.openminis.app.data.FastModePrefs
import com.openminis.app.data.model.SystemVoiceEntries
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.isVoiceInputCandidate
import com.openminis.app.data.model.isVoiceOutputCandidate
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.speech.VoiceOutputState
import com.openminis.app.tools.AgentToolSwitch
import com.openminis.app.ui.chat.voice.VoiceModePrefs
import com.openminis.app.ui.scheduled.ScheduledTaskBadgePreference
import com.openminis.app.ui.settings.*

internal object Build34ConfigFields {
    internal fun validateVoiceOverride(entry: ModelEntry, output: Boolean) {
        val compatible = if (output) entry.model.isVoiceOutputCandidate else entry.model.isVoiceInputCandidate
        if (!compatible) throw ConfigError.InvalidValue("Model entry ${entry.id} does not support voice ${if (output) "output" else "input"}.")
    }

    fun register(registry: ConfigRegistry, context: Context, repo: ProviderRepository) {
        val appearance = getAppearancePrefs(context)
        val agent = context.getSharedPreferences(AgentToolSwitch.PREFS, Context.MODE_PRIVATE)
        fun bool(path: String, label: String, key: String, default: Boolean, prefs: android.content.SharedPreferences = appearance) {
            registry.register(PrefsBoolField(path = path, displayName = label, description = "Uses the same preference as the app UI.",
                prefs = prefs, key = key, defaultValue = default))
        }
        bool("chat.autoExpandThinking", "Expand thinking while streaming", KEY_AUTO_EXPAND_THINKING, true)
        bool("chat.autoGrouping", "Auto-group new chats", KEY_AUTO_GROUPING, true)
        bool("chat.tools.browserUse", "Browser tool", AgentToolSwitch.BROWSER.key, true, agent)
        bool("chat.tools.subAgents", "Sub agent tool", AgentToolSwitch.AGENTS.key, true, agent)
        bool("agentTools.userAsk.enabled", "User interaction tool", AgentToolSwitch.USER_ASK.key, true, agent)
        bool("agentTools.userAsk.notifyInBackground", "Notify about background questions", "agent.tools.userAsk.notifyInBackground", true, agent)
        bool("scheduled.showHomeBadge", "Show scheduled task count on Home", ScheduledTaskBadgePreference.KEY, true)
        bool("files.showHidden", "Show hidden files", "file_browser_show_hidden", false,
            context.getSharedPreferences("file_browser_prefs", Context.MODE_PRIVATE))
        bool("chat.foldTools", "Fold consecutive completed tools", KEY_FOLD_COMPLETED_TOOLS, true, agent)

        registry.register(ClosureField(
            path = "chat.speedTier", displayName = "Response speed", description = "standard / fast / ultrafast. Unsupported ultrafast models use fast. Applies from the next request.",
            valueSchema = ConfigSchema.StrEnum(listOf("standard", "fast", "ultrafast")),
            reader = { ConfigValue.Str(FastModePrefs.speedTier.value) },
            writer = { FastModePrefs.setSpeedTier(context, (it as ConfigValue.Str).value) },
        ))
        registry.register(ClosureField(
            path = "speech.volume", displayName = "Read-aloud volume", description = "0.0–2.0; 1.0 is normal and values above it use a session loudness boost.",
            valueSchema = ConfigSchema.Double(0.0, 2.0),
            reader = { VoiceOutputState.init(context); ConfigValue.Double(VoiceOutputState.volume.value.toDouble()) },
            writer = {
                val value = when (it) { is ConfigValue.Int -> it.value.toDouble(); is ConfigValue.Double -> it.value; else -> throw ConfigError.TypeMismatch("number") }
                if (!value.isFinite()) throw ConfigError.InvalidValue("Volume must be finite.")
                VoiceOutputState.init(context)
                VoiceOutputState.setVolume(value.toFloat())
            },
        ))
        registry.register(ClosureField(
            path = "voice.panel.expanded", displayName = "Expand voice panel", description = "The same expanded state used by the voice input panel.",
            valueSchema = ConfigSchema.Bool,
            reader = { VoiceModePrefs.init(context); ConfigValue.Bool(VoiceModePrefs.expanded) },
            writer = { VoiceModePrefs.init(context); VoiceModePrefs.expanded = (it as ConfigValue.Bool).value },
        ))
        for (output in listOf(false, true)) {
            registry.register(ClosureField(
                path = "voice.${if (output) "output" else "input"}.overrideEntryId",
                displayName = if (output) "Voice output model override" else "Voice input model override",
                description = "A configured model entry ID or system voice entry ID; null restores group routing.",
                valueSchema = ConfigSchema.Optional(ConfigSchema.Str()),
                reader = { (if (output) repo.voiceOutputOverrideEntryId else repo.voiceInputOverrideEntryId)?.let { ConfigValue.Str(it) } ?: ConfigValue.Null },
                writer = { value ->
                    val id = (value as? ConfigValue.Str)?.value?.takeIf(String::isNotBlank)
                    if (id != null) {
                        val entry = SystemVoiceEntries.resolve(id) ?: repo.config.value.modelEntries.firstOrNull { it.id == id }
                            ?: throw ConfigError.InvalidValue("Unknown voice model entry: $id")
                        validateVoiceOverride(entry, output)
                    }
                    if (output) repo.voiceOutputOverrideEntryId = id else repo.voiceInputOverrideEntryId = id
                },
            ))
        }
        registry.register(ClosureField(
            path = "backup.deviceName", displayName = "Backup device name", description = "The device name used in backup filenames. Empty restores the system name.",
            valueSchema = ConfigSchema.Str(maxLength = DeviceIdentity.CUSTOM_NAME_MAX_LENGTH),
            reader = { ConfigValue.Str(DeviceIdentity.customName(context).orEmpty()) },
            writer = { DeviceIdentity.setCustomName(context, (it as ConfigValue.Str).value) },
        ))

        registry.register(ClosureField(
            path = "updates.channel", displayName = "Update channel", description = "stable or beta. Only controls offered releases; installation remains manual.",
            valueSchema = ConfigSchema.StrEnum(listOf("stable", "beta")),
            reader = { ConfigValue.Str(com.openminis.app.data.UpdatePreferences.channel(context)) },
            writer = { com.openminis.app.data.UpdatePreferences.setChannel(context, (it as ConfigValue.Str).value) },
        ))
        registry.register(ClosureField(
            path = "updates.autoCheck", displayName = "Automatic update checks", description = "At most once a day on stable, or every six hours on beta. Manual checking still works when disabled.",
            valueSchema = ConfigSchema.Bool,
            reader = { ConfigValue.Bool(com.openminis.app.data.UpdatePreferences.autoCheckEnabled(context)) },
            writer = { com.openminis.app.data.UpdatePreferences.setAutoCheckEnabled(context, (it as ConfigValue.Bool).value) },
        ))
        registry.register(SubAgentsCollection(repo))
        registry.register(ReadOnlyField(
            path = "subagents", displayName = "Sub agent roster", description = "Read-only roster summary. Edit subagents.<id>.name/description/instructions/model_group/thinking.",
            valueSchema = ConfigSchema.Json,
            reader = { ConfigValue.Arr(repo.subAgents.map { definition -> ConfigValue.Obj(mapOf(
                "id" to ConfigValue.Str(definition.id), "name" to ConfigValue.Str(definition.name),
                "description" to ConfigValue.Str(definition.description), "isBuiltIn" to ConfigValue.Bool(definition.isBuiltIn),
                "model_group" to (definition.modelGroupId?.let { ConfigValue.Str(it) } ?: ConfigValue.Null),
                "thinking" to (definition.thinkingLevelOverride?.let { ConfigValue.Str(it.name.lowercase(java.util.Locale.ROOT)) } ?: ConfigValue.Null),
            )) }) },
        ))
        registry.register(ClosureField(
            path = "subagents.order", displayName = "Sub agent order", description = "Every roster ID once, with the built-in agent first.",
            valueSchema = ConfigSchema.Array(ConfigSchema.Str()),
            reader = { ConfigValue.Arr(repo.subAgents.map { ConfigValue.Str(it.id) }) },
            writer = {
                val ids = (it as ConfigValue.Arr).value.map { item -> (item as ConfigValue.Str).value }
                SubAgentConfigRules.validateOrder(ids, repo.subAgents)
                repo.reorderSubAgents(ids)
            },
        ))
    }
}
