package com.openminis.app.provider.usage

import org.json.JSONObject
import java.math.BigDecimal
import java.time.Instant

internal data class KimiUsageLimit(
    val name: String? = null,
    val windowLabel: String? = null,
    val used: Long? = null,
    val limit: Long? = null,
    val remaining: Long? = null,
    val resetAt: Instant? = null,
    val resetInSeconds: Long? = null,
) {
    val remainingFraction: Float?
        get() = if (limit != null && limit > 0 && remaining != null) {
            (remaining.toDouble() / limit).coerceIn(0.0, 1.0).toFloat()
        } else null
}

internal data class KimiSubscriptionUsage(
    val weekly: KimiUsageLimit? = null,
    val limits: List<KimiUsageLimit> = emptyList(),
) {
    val isEmpty: Boolean get() = weekly == null && limits.isEmpty()
}

internal enum class KimiUsageFailure {
    AUTHENTICATION, UNAVAILABLE, RATE_LIMITED, SERVER, NETWORK, TIMEOUT, INVALID_RESPONSE,
}

internal sealed interface KimiUsageState {
    val usage: KimiSubscriptionUsage? get() = null
    data class Loading(override val usage: KimiSubscriptionUsage? = null) : KimiUsageState
    data class Ready(override val usage: KimiSubscriptionUsage) : KimiUsageState
    data object Empty : KimiUsageState
    data class Failed(val reason: KimiUsageFailure, override val usage: KimiSubscriptionUsage? = null) : KimiUsageState
}

/** Fields verified in MoonshotAI/kimi-cli's ui/shell/usage.py, not chat token usage. */
internal fun parseKimiSubscriptionUsage(json: JSONObject): KimiSubscriptionUsage {
    val weekly = json.optJSONObject("usage")?.let { parseLimit(it) }
    val rawLimits = json.optJSONArray("limits")
    val limits = (0 until (rawLimits?.length() ?: 0)).mapNotNull { index ->
        val item = rawLimits?.optJSONObject(index) ?: return@mapNotNull null
        val detail = item.optJSONObject("detail") ?: item
        val name = listOf("name", "title", "scope").firstNotNullOfOrNull { key ->
            text(item, key) ?: text(detail, key)
        }
        val window = item.optJSONObject("window")
        val duration = number(window, "duration") ?: number(item, "duration") ?: number(detail, "duration")
        val unit = text(window, "timeUnit") ?: text(item, "timeUnit") ?: text(detail, "timeUnit")
        parseLimit(detail, name, quotaWindowLabel(duration, unit))
    }
    return KimiSubscriptionUsage(weekly, limits)
}

private fun parseLimit(json: JSONObject, name: String? = null, windowLabel: String? = null): KimiUsageLimit? {
    val limit = number(json, "limit")
    val explicitUsed = number(json, "used")
    val explicitRemaining = number(json, "remaining")
    val used = explicitUsed ?: if (limit != null && explicitRemaining != null && explicitRemaining <= limit) {
        limit - explicitRemaining
    } else null
    val remaining = if (limit != null && used != null) (limit - used).coerceAtLeast(0) else explicitRemaining
    if (limit == null && used == null && remaining == null) return null
    val resetAt = listOf("reset_at", "resetAt", "reset_time", "resetTime").firstNotNullOfOrNull { key ->
        text(json, key)?.let { runCatching { Instant.parse(it) }.getOrNull() }
    }
    val resetIn = listOf("reset_in", "resetIn", "ttl", "window").firstNotNullOfOrNull { number(json, it) }
    return KimiUsageLimit(text(json, "name") ?: text(json, "title") ?: name, windowLabel,
        used, limit, remaining, resetAt, resetIn)
}

private fun number(json: JSONObject?, key: String): Long? {
    val value = json?.opt(key) ?: return null
    if (value !is Number && value !is String) return null
    return try {
        BigDecimal(value.toString()).longValueExact().takeIf { it >= 0 }
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: ArithmeticException) {
        null
    }
}

private fun text(json: JSONObject?, key: String): String? =
    (json?.opt(key) as? String)?.trim()?.takeIf { it.isNotEmpty() }

private fun quotaWindowLabel(duration: Long?, unit: String?): String? {
    if (duration == null || duration == 0L) return null
    return when {
        unit?.contains("MINUTE") == true -> if (duration % 60 == 0L) "${duration / 60}h" else "${duration}min"
        unit?.contains("HOUR") == true -> "${duration}h"
        unit?.contains("DAY") == true -> "${duration}d"
        unit?.contains("SECOND") == true -> "${duration}s"
        else -> null // Unknown units must not acquire an invented duration.
    }
}
