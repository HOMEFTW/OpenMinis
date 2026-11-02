package com.openminis.app.backup

import com.openminis.app.data.model.ProviderConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull

/**
 * The `data/provider_config.json` a backup carries: how this device's
 * [ProviderConfig] is written into a package.
 *
 * [B01 cross-platform read] [BackupFormat.json] leaves out every field equal
 * to its default, and iOS decodes several of those as REQUIRED. A real Pixel
 * package failed on vphone with `keyNotFound 'isEnabled'`: the whole file
 * unreadable, no provider restored, and behind it the same for
 * `modelEntries[].isHidden` / `isCustom` and the top-level `sessionBindings`;
 * groups without `strategy` were then dropped one by one. So the backup
 * writes those fields even at their default, plus `sessionBindings` (Android
 * keeps no session bindings in its config; iOS requires the key).
 *
 * Only these fields are added, not every default: a blanket `encodeDefaults`
 * would also put Android-only fields and values in front of iOS for the first
 * time, each one a new chance of a type mismatch there.
 *
 * `fallbackStrategy` is written as iOS spells it: iOS's `FallbackStrategy` has
 * `limited` and `always`, Android's `default` is iOS's `limited`, and a value
 * iOS does not know fails its decode just like a missing one. Android reads
 * `limited` back as `default`.
 *
 * Backups only: the device's own config (ProviderRepository) is persisted
 * elsewhere, with its own encoder, and does not change.
 */
object BackupProviderWire {

    data class Decoded(val config: ProviderConfig, val droppedInstances: Int, val dropReasons: List<String> = emptyList())

    class DecodeException(val path: String?, cause: Throwable) : Exception(
        "${describe(cause)}" + (path?.let { " (at $it)" } ?: ""), cause,
    )

    /** One unreadable instance must not discard the other providers or the fork's extra fields. */
    fun decode(text: String): Decoded {
        val root = BackupFormat.json.parseToJsonElement(text).jsonObject
        val instances = root["instances"] as? JsonArray ?: return Decoded(decodeConfig(root), 0)
        val known = com.openminis.app.data.model.ProviderType.entries.map { it.name }.toSet()
        val reasons = mutableListOf<String>()
        val kept = instances.mapNotNull { element ->
            val obj = element as? JsonObject ?: run { reasons += "an entry of instances is not an object"; return@mapNotNull null }
            val type = (obj["providerType"] as? JsonPrimitive)?.contentOrNull
            val normalized = if (type != null && type !in known) JsonObject(obj +
                ("providerType" to JsonPrimitive(com.openminis.app.data.model.ProviderType.unsupported.name))) else obj
            try {
                BackupFormat.json.decodeFromJsonElement(com.openminis.app.data.model.ProviderInstance.serializer(), normalized)
                normalized
            } catch (failure: Exception) {
                val id = (obj["id"] as? JsonPrimitive)?.contentOrNull ?: "?"
                reasons += "instance $id: ${describe(failure)}"
                null
            }
        }
        return Decoded(decodeConfig(JsonObject(root + ("instances" to JsonArray(kept)))), reasons.size, reasons)
    }

    fun warnings(parsed: Decoded?, failure: Throwable?): List<String> = when {
        failure != null -> listOf("Providers: provider_config.json could not be read, so no provider was restored (${if (failure is DecodeException) failure.message else describe(failure)})")
        else -> parsed?.dropReasons.orEmpty().map { "Providers: a provider in the backup could not be read and was skipped ($it)" }
    }

    private fun decodeConfig(root: JsonObject): ProviderConfig = try {
        BackupFormat.json.decodeFromJsonElement(ProviderConfig.serializer(), root)
    } catch (failure: Exception) {
        val sections = listOf(
            "modelEntries" to com.openminis.app.data.model.ModelEntry.serializer(),
            "modelGroups" to com.openminis.app.data.model.ModelGroup.serializer(),
            "subAgents" to com.openminis.app.data.model.SubAgentDefinition.serializer(),
        )
        var path: String? = null
        for ((key, serializer) in sections) {
            val items = root[key] as? JsonArray ?: continue
            val index = items.indexOfFirst { runCatching { BackupFormat.json.decodeFromJsonElement(serializer, it) }.isFailure }
            if (index >= 0) { path = "$.$key[$index]"; break }
        }
        throw DecodeException(path, failure)
    }

    // Decoder errors can embed the complete JSON document; notices need the
    // failing key/type, not provider headers or other configuration values.
    private fun describe(failure: Throwable): String =
        "${failure::class.simpleName}: ${failure.message?.substringBefore("JSON input:")?.trim()?.take(800)}"

    /** Fields iOS requires per array element, written even at their default. */
    private val REQUIRED_FIELDS = mapOf(
        "instances" to listOf("isEnabled", "appendV1Suffix"),
        "modelEntries" to listOf("isHidden", "isCustom"),
        "modelGroups" to listOf("strategy", "fallbackStrategy"),
    )

    /** Android's name for the default fallback strategy, and iOS's. */
    private const val ANDROID_DEFAULT_FALLBACK = "default"
    private const val IOS_DEFAULT_FALLBACK = "limited"

    private val withDefaults = Json(BackupFormat.json) { encodeDefaults = true }

    fun encode(config: ProviderConfig): String {
        val slim = BackupFormat.json.encodeToJsonElement(ProviderConfig.serializer(), config).jsonObject
        // The same config with its defaults, so a missing field's value is read
        // from the model rather than restated here.
        val full = withDefaults.encodeToJsonElement(ProviderConfig.serializer(), config).jsonObject

        val out = LinkedHashMap<String, JsonElement>(slim)
        for ((key, fields) in REQUIRED_FIELDS) {
            val fullItems = full[key] as? JsonArray ?: JsonArray(emptyList())
            val slimItems = slim[key] as? JsonArray ?: JsonArray(emptyList())
            out[key] = JsonArray(
                slimItems.mapIndexed { index, item ->
                    val obj = item as? JsonObject ?: return@mapIndexed item
                    val withFields = withFields(obj, fullItems.getOrNull(index) as? JsonObject, fields)
                    if (key == "modelGroups") iosFallback(withFields) else withFields
                },
            )
        }
        if ("sessionBindings" !in out) out["sessionBindings"] = JsonObject(emptyMap())
        return BackupFormat.json.encodeToString(JsonObject.serializer(), JsonObject(out))
    }

    private fun withFields(obj: JsonObject, full: JsonObject?, fields: List<String>): JsonObject {
        if (full == null || fields.all { it in obj }) return obj
        val merged = LinkedHashMap<String, JsonElement>(obj)
        for (field in fields) {
            if (field !in merged) full[field]?.let { merged[field] = it }
        }
        return JsonObject(merged)
    }

    private fun iosFallback(group: JsonObject): JsonObject {
        val value = (group["fallbackStrategy"] as? JsonPrimitive)?.content
        if (value != ANDROID_DEFAULT_FALLBACK) return group
        return JsonObject(group + ("fallbackStrategy" to JsonPrimitive(IOS_DEFAULT_FALLBACK)))
    }
}
