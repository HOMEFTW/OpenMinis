package com.openminis.app.ui.settings

import com.openminis.app.data.db.UsageRecord
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.ProviderType
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal enum class Attribution { MEASURED, ESTIMATED, UNKNOWN_SESSION, MEASURED_REMOVED }

internal fun providerDisplayName(rawValue: String): String =
    runCatching { ProviderType.valueOf(rawValue).displayName }.getOrDefault(rawValue)

internal fun classifyAttribution(modelId: String?, hasSnapshot: Boolean, resolvesInConfig: Boolean): Attribution = when {
    modelId == null -> Attribution.UNKNOWN_SESSION
    !hasSnapshot -> Attribution.ESTIMATED
    !resolvesInConfig -> Attribution.MEASURED_REMOVED
    else -> Attribution.MEASURED
}

internal data class UsageTokens(
    val input: Long = 0,
    val output: Long = 0,
    val cacheRead: Long = 0,
    val cacheCreation: Long = 0,
) {
    val totalInput: Long get() = input + cacheRead + cacheCreation
    val total: Long get() = totalInput + output
    val cacheHitRate: Double? get() = if (totalInput > 0) cacheRead.toDouble() / totalInput * 100 else null
    operator fun plus(other: UsageTokens) = UsageTokens(input + other.input, output + other.output,
        cacheRead + other.cacheRead, cacheCreation + other.cacheCreation)
}

internal data class UsageSample(
    val modelId: String,
    val displayName: String,
    val providerKey: String,
    val providerName: String,
    val attribution: Attribution,
    val createdAt: Long,
    val sessionId: String,
    val tokens: UsageTokens,
)

internal data class UsageModelStats(val sample: UsageSample, val tokens: UsageTokens, val days: Int, val sessions: Int)
internal data class UsageBucket(val start: Long, val label: String, val tokens: UsageTokens)

internal fun usageSamples(records: List<UsageRecord>, config: ProviderConfig?): List<UsageSample> {
    val instances = config?.instances.orEmpty().associateBy { it.id }
    val entries = config?.modelEntries.orEmpty()
    val byInstance = entries.associateBy { it.providerInstanceId to it.model.id }
    val builtIns = LLMModel.allModels.associateBy { it.id }
    return records.mapNotNull { record ->
        val json = runCatching { JSONObject(record.tokenUsage) }.getOrNull() ?: return@mapNotNull null
        fun count(key: String, legacy: String? = null): Long =
            json.optLong(key, legacy?.let { json.optLong(it, 0) } ?: 0).coerceAtLeast(0)
        val instance = record.providerInstanceId?.let(instances::get)
        val entry = if (record.providerInstanceId != null) byInstance[record.providerInstanceId to record.modelId] else
            entries.firstOrNull { it.model.id == record.modelId &&
                (record.providerType == null || instances[it.providerInstanceId]?.providerType?.name == record.providerType) }
        val builtIn = builtIns[record.modelId]?.takeIf { record.providerInstanceId == null }
        val type = record.providerType?.takeIf(String::isNotBlank) ?: instance?.providerType?.name
        val provider = type?.let(::providerDisplayName) ?: entry?.model?.provider ?: builtIn?.provider ?: "Unknown"
        UsageSample(
            modelId = record.modelId ?: "(unknown model)",
            displayName = record.modelDisplayName?.takeIf(String::isNotBlank) ?: entry?.model?.displayName
                ?: builtIn?.displayName ?: record.modelId ?: "(unknown model)",
            providerKey = record.providerInstanceId?.let { "instance:$it" } ?: "type:${type ?: provider}",
            providerName = instance?.label ?: if (record.providerInstanceId != null)
                "$provider (${record.providerInstanceId.take(8)})" else provider,
            attribution = classifyAttribution(record.modelId, record.hasSnapshot, entry != null || builtIn != null),
            createdAt = record.createdAt,
            sessionId = record.sessionId,
            tokens = UsageTokens(count("inputTokens"), count("outputTokens"),
                count("cacheReadTokens", "cacheReadInputTokens"), count("cacheCreationTokens", "cacheCreationInputTokens")),
        )
    }
}

/** Today plus the preceding N-1 local dates, including DST and timezone changes. */
internal fun usageInDateRange(createdAt: Long, now: Long, days: Int, zone: ZoneId = ZoneId.systemDefault()): Boolean {
    if (createdAt > now) return false
    if (days <= 0) return true
    val start = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().minusDays(days.toLong() - 1)
        .atStartOfDay(zone).toInstant().toEpochMilli()
    return createdAt >= start
}

internal fun usageMatchesSearch(id: String, name: String, provider: String, search: String): Boolean =
    search.trim().let { query -> listOf(id, name, provider).any { it.contains(query, ignoreCase = true) } }

internal fun usageTotal(samples: List<UsageSample>): UsageTokens = samples.fold(UsageTokens()) { total, row -> total + row.tokens }

internal fun usageModelStats(samples: List<UsageSample>, zone: ZoneId): List<UsageModelStats> = samples
    .groupBy { Triple(it.providerKey, it.modelId, it.attribution) }.values.map { rows ->
        UsageModelStats(rows.maxBy { it.createdAt }, usageTotal(rows),
            rows.map { Instant.ofEpochMilli(it.createdAt).atZone(zone).toLocalDate() }.toSet().size,
            rows.map { it.sessionId }.toSet().size)
    }.sortedByDescending { it.tokens.total }

internal fun usageTrend(samples: List<UsageSample>, now: Long, days: Int, zone: ZoneId): List<UsageBucket> {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val firstDate = if (days > 0) today.minusDays(days.toLong() - 1) else
        samples.minOfOrNull { Instant.ofEpochMilli(it.createdAt).atZone(zone).toLocalDate() } ?: return emptyList()
    val monthly = days <= 0 && firstDate.isBefore(today.minusDays(62))
    var cursor = (if (monthly) firstDate.withDayOfMonth(1) else firstDate).atStartOfDay(zone)
    val end = today.plusDays(1).atStartOfDay(zone)
    val formatter = DateTimeFormatter.ofPattern(when {
        days == 1 -> "HH:mm z"
        monthly -> "yyyy-MM"
        else -> "MM-dd"
    }, Locale.getDefault())
    val sorted = samples.filter { usageInDateRange(it.createdAt, now, days, zone) }.sortedBy { it.createdAt }
    var index = 0
    val buckets = mutableListOf<UsageBucket>()
    while (cursor < end) {
        val next = when { days == 1 -> cursor.plusHours(1); monthly -> cursor.plusMonths(1); else -> cursor.plusDays(1) }
        var tokens = UsageTokens()
        val until = next.toInstant().toEpochMilli()
        while (index < sorted.size && sorted[index].createdAt < until) {
            tokens += sorted[index++].tokens
        }
        buckets += UsageBucket(cursor.toInstant().toEpochMilli(), cursor.format(formatter), tokens)
        cursor = next
    }
    return buckets
}
