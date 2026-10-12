package com.openminis.app.browser

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** A page script can outlive its caller; callbacks must retain their original identity. */
internal class AsyncJsRequests {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<String>>()

    suspend fun await(timeoutMs: Long, evaluate: suspend (String) -> Unit): String? {
        val token = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<String>()
        pending[token] = deferred
        try {
            evaluate(token)
            return withTimeoutOrNull(timeoutMs) { deferred.await() }
        } finally {
            pending.remove(token, deferred)
            deferred.cancel()
        }
    }

    fun complete(token: String, result: String) {
        pending.remove(token)?.complete(result)
    }

    fun failAll(result: String) {
        pending.forEach { (token, deferred) ->
            if (pending.remove(token, deferred)) deferred.complete(result)
        }
    }
}
