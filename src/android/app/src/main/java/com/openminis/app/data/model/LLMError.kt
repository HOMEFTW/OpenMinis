package com.openminis.app.data.model

sealed class LLMError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class InvalidApiKey(val detail: String = "") : LLMError(if (detail.isBlank()) "Invalid API key" else "Invalid API key: $detail")
    class NetworkError(cause: Throwable) : LLMError(
        if (isCertificateFailure(cause)) "TLS certificate verification failed. Check the server certificate, device date and proxy trust settings."
        else "Network error: ${cause.message}", cause)
    class ProviderError(val detail: String, val httpStatus: Int? = null) : LLMError("Provider error: $detail")
    class DecodingError(cause: Throwable) : LLMError("Decoding error: ${cause.message}", cause)
    class RateLimited : LLMError("Rate limited — please try again later")
    class TransientError(val detail: String, val httpStatus: Int? = null) : LLMError("Transient error: $detail")
    class Cancelled : LLMError("Request was cancelled")
    class Unknown(cause: Throwable?) : LLMError("Unknown error: ${cause?.message}", cause)

    /** Pure connectivity failure — the request didn't land at all. */
    val isNetworkError: Boolean get() = this is NetworkError

    /** Worth retrying on the same provider (bounded backoff). */
    val isRetryable: Boolean get() = (this is NetworkError || this is TransientError) && !isCertificateFailure(this)

    companion object {
        private val LEADING_STATUS = Regex("""^\[(\d{3})\]""")
        fun isCertificateFailure(error: Throwable): Boolean {
            var cause: Throwable? = error
            repeat(12) {
                val current = cause ?: return false
                if (current is javax.net.ssl.SSLPeerUnverifiedException ||
                    current is java.security.cert.CertificateException ||
                    current is java.security.cert.CertPathValidatorException) return true
                cause = current.cause?.takeUnless { it === current }
            }
            return false
        }
    }

    /** Should immediately fall back to the next model in the group — same model won't help. */
    val isFallbackable: Boolean get() = this is RateLimited || this is InvalidApiKey || this is ProviderError

    val httpServerErrorStatus: Int?
        get() = when (this) {
            is TransientError -> httpStatus?.takeIf { it in 500..599 }
            is ProviderError -> httpStatus?.takeIf { it in 500..599 }
                ?: LEADING_STATUS.find(detail)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 500..599 }
            else -> null
        }
    val isHttpServerError: Boolean get() = httpServerErrorStatus != null

    /** Short user-facing reason shown when a fallback engages. */
    val fallbackReason: String
        get() = when (this) {
            is RateLimited -> "Rate limited"
            is InvalidApiKey -> "Invalid API key"
            is ProviderError -> "Provider error"
            is TransientError -> "Transient error"
            is NetworkError -> "Network error"
            is DecodingError -> "Decoding error"
            is Cancelled -> "Cancelled"
            is Unknown -> "Unknown error"
        }
}
