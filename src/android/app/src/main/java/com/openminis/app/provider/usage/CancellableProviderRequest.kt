package com.openminis.app.provider.usage

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class ProviderUsageHttpResponse(val status: Int, val body: String)

/** Used by the usage GET and Kimi's token refresh; cancellation closes either call. */
internal suspend fun Call.readProviderResponse(): ProviderUsageHttpResponse = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            try {
                val result = response.use {
                    // Keep the call cancellable while reading, and always close the response.
                    ProviderUsageHttpResponse(it.code, if (continuation.isActive) it.body?.string().orEmpty() else "")
                }
                if (continuation.isActive) continuation.resume(result)
            } catch (e: Exception) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
        }
    })
}
