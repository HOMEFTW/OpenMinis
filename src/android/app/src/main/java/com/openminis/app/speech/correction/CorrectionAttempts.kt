package com.openminis.app.speech.correction

import com.openminis.app.data.model.FallbackStrategy
import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.RoutingStrategy
import com.openminis.app.logging.LogRedactor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

internal enum class CorrectionFailureKind {
    NO_MODEL, UNAVAILABLE, AUTH, RATE_LIMIT, HTTP, TIMEOUT, DNS, CERTIFICATE, NETWORK, EMPTY, DECODING, PROVIDER, REWRITE, UNKNOWN,
}
internal data class CorrectionFailure(val model: String, val kind: CorrectionFailureKind, val status: Int? = null)

/** Per-request diagnostics survive the engine's intentionally non-throwing suggestion API. */
internal class CorrectionDiagnostics(val isCurrent: () -> Boolean = { true }) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<CorrectionDiagnostics>
    @Volatile var failures: List<CorrectionFailure> = emptyList()
    @Volatile var cancelled = false
}

internal fun correctionFailure(model: String, error: Exception): CorrectionFailure {
    val chain = generateSequence<Throwable>(error) { it.cause?.takeUnless { cause -> cause === it } }.take(12).toList()
    val status = when (error) {
        is LLMError.ProviderError -> error.httpStatus ?: Regex("^\\[(\\d{3})]").find(error.detail)?.groupValues?.get(1)?.toIntOrNull()
        is LLMError.TransientError -> error.httpStatus
        else -> null
    }?.takeIf { it in 100..599 }
    val kind = when {
        error is CorrectionError.NoModelAvailable -> CorrectionFailureKind.NO_MODEL
        error is CorrectionError.EmptyResponse -> CorrectionFailureKind.EMPTY
        error is LLMError.InvalidApiKey || status == 401 || status == 403 -> CorrectionFailureKind.AUTH
        error is LLMError.RateLimited || status == 429 -> CorrectionFailureKind.RATE_LIMIT
        chain.any { it is SocketTimeoutException } -> CorrectionFailureKind.TIMEOUT
        LLMError.isCertificateFailure(error) -> CorrectionFailureKind.CERTIFICATE
        chain.any { it is UnknownHostException } -> CorrectionFailureKind.DNS
        error is LLMError.NetworkError || chain.any { it is IOException } -> CorrectionFailureKind.NETWORK
        error is LLMError.DecodingError -> CorrectionFailureKind.DECODING
        status != null -> CorrectionFailureKind.HTTP
        error is LLMError.ProviderError || error is LLMError.TransientError -> CorrectionFailureKind.PROVIDER
        else -> CorrectionFailureKind.UNKNOWN
    }
    // Never retain an exception message/body in user-visible diagnostics.
    return CorrectionFailure(LogRedactor.redact(model).lineSequence().firstOrNull().orEmpty().take(80), kind, status)
}

internal fun correctionModelOrder(group: ModelGroup, available: List<ModelEntry>, balanceStart: Int): List<ModelEntry> {
    val entries = available.distinctBy { it.id }
    if (entries.isEmpty()) return emptyList()
    val start = if (group.strategy == RoutingStrategy.loadBalance) Math.floorMod(balanceStart, entries.size) else 0
    return List(entries.size.coerceAtMost(3)) { entries[(start + it) % entries.size] }
}

internal suspend fun <T> tryCorrectionModels(
    entries: List<ModelEntry>, policy: FallbackStrategy, attempt: suspend (ModelEntry) -> T,
): T {
    val diagnostics = currentCoroutineContext()[CorrectionDiagnostics]
    val failures = mutableListOf<CorrectionFailure>()
    for (entry in entries.distinctBy { it.id }.take(3)) {
        currentCoroutineContext().ensureActive()
        if (diagnostics?.isCurrent?.invoke() == false) {
            diagnostics.cancelled = true
            throw CancellationException("Correction superseded")
        }
        try {
            return attempt(entry)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: LLMError.Cancelled) {
            diagnostics?.cancelled = true
            throw CancellationException("Correction cancelled")
        } catch (error: Exception) {
            failures += correctionFailure(entry.model.displayName, error)
            diagnostics?.failures = failures.toList()
            val fallback = policy == FallbackStrategy.always || error is LLMError.RateLimited ||
                failures.last().status == 429 || error is LLMError && error.isHttpServerError
            if (!fallback) break
        }
    }
    if (failures.isEmpty()) throw CorrectionError.NoModelAvailable
    throw CorrectionError.ModelsFailed(failures)
}
