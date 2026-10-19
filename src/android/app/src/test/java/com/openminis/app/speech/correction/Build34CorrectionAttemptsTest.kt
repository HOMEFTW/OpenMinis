package com.openminis.app.speech.correction

import com.openminis.app.data.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class Build34CorrectionAttemptsTest {
    private val entries = (1..5).map { ModelEntry("p$it", LLMModel("m$it", "Model $it", "Custom"), uuid = "entry$it") }

    @Test fun routesInGroupOrderOrRotatedLoadBalanceOrderAndCapsAtThree() {
        val fallback = ModelGroup(name = "Corrections")
        assertEquals(entries.take(3), correctionModelOrder(fallback, entries, 4))
        val balanced = fallback.copy(strategy = RoutingStrategy.loadBalance)
        assertEquals(listOf(entries[4], entries[0], entries[1]), correctionModelOrder(balanced, entries, -1))
        assertEquals(listOf(entries[0]), correctionModelOrder(fallback, listOf(entries[0], entries[0]), 0))
    }

    @Test fun defaultPolicyFallsBackOnRateLimitAndServerErrorThenUsesSuccess() = runBlocking {
        val diagnostics = CorrectionDiagnostics()
        val tried = mutableListOf<String>()
        val result = withContext(diagnostics) {
            tryCorrectionModels(entries, FallbackStrategy.default) {
                tried += it.id
                when (tried.size) {
                    1 -> throw LLMError.RateLimited()
                    2 -> throw LLMError.ProviderError("unavailable", 503)
                    else -> "fixed"
                }
            }
        }
        assertEquals("fixed", result)
        assertEquals(entries.take(3).map { it.id }, tried)
        assertEquals(listOf(CorrectionFailureKind.RATE_LIMIT, CorrectionFailureKind.HTTP), diagnostics.failures.map { it.kind })
    }

    @Test fun defaultPolicyDoesNotOverrideAuthenticationFailureButAlwaysTriesAtMostThreeModels() = runBlocking {
        for ((policy, limit) in listOf(FallbackStrategy.default to 1, FallbackStrategy.always to 3)) {
            val diagnostics = CorrectionDiagnostics()
            var calls = 0
            val error = runCatching {
                withContext(diagnostics) {
                    tryCorrectionModels(entries, policy) { calls++; throw LLMError.InvalidApiKey("sk-private request_body=private") }
                }
            }.exceptionOrNull()
            assertTrue(error is CorrectionError.ModelsFailed)
            assertEquals(limit, calls)
            assertEquals(limit, diagnostics.failures.size)
            assertTrue(diagnostics.failures.all { it.kind == CorrectionFailureKind.AUTH })
            assertFalse(diagnostics.failures.toString().contains("sk-private"))
            assertFalse(error!!.message.orEmpty().contains("request_body"))
        }
    }

    @Test fun emptyResponseCanFallBackUnderAlwaysPolicy() = runBlocking {
        var calls = 0
        assertEquals("original", tryCorrectionModels(entries, FallbackStrategy.always) {
            if (++calls == 1) throw CorrectionError.EmptyResponse
            "original"
        })
        assertEquals(2, calls)
    }

    @Test fun cancellationNeverStartsAnotherModelOrRecordsFailure() = runBlocking {
        val diagnostics = CorrectionDiagnostics()
        var calls = 0
        val error = runCatching {
            withContext(diagnostics) {
                tryCorrectionModels(entries, FallbackStrategy.always) { calls++; throw CancellationException("cancel") }
            }
        }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertEquals(1, calls)
        assertTrue(diagnostics.failures.isEmpty())
    }

    @Test fun providerCancellationRemainsExplicitEvenThroughNonThrowingEngine() = runBlocking {
        val diagnostics = CorrectionDiagnostics()
        val strategy = object : CorrectionStrategy {
            override val strategyIdentifier = "test"
            override suspend fun correct(transcript: String, candidates: List<CorrectionCandidate>,
                context: ConversationContext, locale: String): CorrectionOutcome =
                tryCorrectionModels(entries, FallbackStrategy.always) { throw LLMError.Cancelled() }
        }
        val engine = VoiceCorrectionEngine(emptyList(), strategy, null, { emptyList() }, { false })
        val suggestion = withContext(diagnostics) { engine.correct("original", locale = "en") }
        assertFalse(suggestion.hasChange)
        assertTrue(diagnostics.cancelled)
        assertTrue(diagnostics.failures.isEmpty())
    }

    @Test fun supersededTranscriptStopsBeforeNextProvider() = runBlocking {
        var current = true
        val diagnostics = CorrectionDiagnostics { current }
        var calls = 0
        val error = runCatching {
            withContext(diagnostics) {
                tryCorrectionModels(entries, FallbackStrategy.always) {
                    calls++; current = false; throw LLMError.RateLimited()
                }
            }
        }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertEquals(1, calls)
        assertTrue(diagnostics.cancelled)
    }

    @Test fun errorCategoriesCarryUsefulCauseAndStatusWithoutResponseBodies() {
        assertEquals(CorrectionFailureKind.DNS, correctionFailure("M", LLMError.NetworkError(UnknownHostException("private-body"))).kind)
        assertEquals(CorrectionFailureKind.TIMEOUT, correctionFailure("M", LLMError.NetworkError(SocketTimeoutException("sk-private"))).kind)
        val failure = correctionFailure("M", LLMError.ProviderError("[502] {\"messages\":[\"private prompt\"],\"api_key\":\"sk-private\"}"))
        assertEquals(502, failure.status)
        assertEquals(CorrectionFailureKind.HTTP, failure.kind)
        assertFalse(failure.toString().contains("private"))
    }
}
