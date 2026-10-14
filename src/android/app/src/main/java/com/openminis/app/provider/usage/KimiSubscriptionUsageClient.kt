package com.openminis.app.provider.usage

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

internal class KimiSubscriptionUsageClient(
    client: OkHttpClient = defaultClient,
    private val endpoint: HttpUrl = USAGE_URL.toHttpUrl(),
) {
    companion object {
        // Official CLI: auth/platforms.py base URL + ui/shell/usage.py /usages.
        const val USAGE_URL = "https://api.kimi.com/coding/v1/usages"
        private val defaultClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    // A server redirect must not redirect the subscription credential.
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false).build()

    suspend fun load(
        previous: KimiSubscriptionUsage? = null,
        accessToken: suspend () -> String?,
    ): KimiUsageState {
        fun failed(reason: KimiUsageFailure) = KimiUsageState.Failed(reason, previous)
        return try {
            val token = accessToken()?.takeIf { it.isNotBlank() } ?: return failed(KimiUsageFailure.AUTHENTICATION)
            currentCoroutineContext().ensureActive()
            val request = Request.Builder().url(endpoint).get()
                .header("Authorization", "Bearer $token").header("Accept", "application/json").build()
            val response = http.newCall(request).readProviderResponse()
            when (response.status) {
                401, 403 -> failed(KimiUsageFailure.AUTHENTICATION)
                429 -> failed(KimiUsageFailure.RATE_LIMITED)
                in 500..599 -> failed(KimiUsageFailure.SERVER)
                in 200..299 -> {
                    if (response.body.isBlank()) return KimiUsageState.Empty
                    val usage = parseKimiSubscriptionUsage(JSONObject(response.body))
                    if (usage.isEmpty) KimiUsageState.Empty else KimiUsageState.Ready(usage)
                }
                else -> failed(KimiUsageFailure.UNAVAILABLE)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: InterruptedIOException) {
            failed(KimiUsageFailure.TIMEOUT)
        } catch (_: IOException) {
            failed(KimiUsageFailure.NETWORK)
        } catch (_: JSONException) {
            failed(KimiUsageFailure.INVALID_RESPONSE)
        } catch (_: IllegalArgumentException) {
            failed(KimiUsageFailure.INVALID_RESPONSE)
        } catch (_: Exception) {
            failed(KimiUsageFailure.UNAVAILABLE)
        }
    }
}
