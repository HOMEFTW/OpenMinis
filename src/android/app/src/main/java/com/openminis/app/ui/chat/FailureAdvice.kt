package com.openminis.app.ui.chat

import com.openminis.app.R

internal enum class FailureAdvice(val messageRes: Int) {
    AUTH(R.string.daily_error_auth), QUOTA(R.string.daily_error_quota), RATE(R.string.daily_error_rate),
    TIMEOUT(R.string.daily_error_timeout), NETWORK(R.string.daily_error_network), OTHER(R.string.daily_error_other);
    companion object {
        fun classify(message: String): FailureAdvice {
            val text = message.lowercase()
            return when {
                listOf("insufficient_quota", "quota", "credit", "balance", "billing", "余额", "额度").any { it in text } -> QUOTA
                listOf("401", "403", "api key", "api_key", "unauthorized", "authentication", "鉴权").any { it in text } -> AUTH
                listOf("429", "rate limit", "rate_limit", "too many requests").any { it in text } -> RATE
                listOf("timeout", "timed out", "超时").any { it in text } -> TIMEOUT
                listOf("network", "connect", "dns", "unknownhost", "网络", "断网").any { it in text } -> NETWORK
                else -> OTHER
            }
        }
    }
}
