package com.openminis.app.provider.usage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class CancellableProviderRequestTest {
    @Test fun `the token refresh transport releases its call when cancelled before headers or during the body`() = runBlocking {
        for (duringBody in listOf(false, true)) {
            MockWebServer().use { server ->
                server.enqueue(if (duringBody) MockResponse().setBody("test-only-token-response").setBodyDelay(3, TimeUnit.SECONDS)
                    else MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
                server.start()
                val http = OkHttpClient()
                val request = Request.Builder().url(server.url("/api/oauth/token")).post("grant_type=refresh_token".toRequestBody()).build()
                var returned = false
                val job = launch(Dispatchers.IO) {
                    http.newCall(request).readProviderResponse()
                    returned = true
                }
                assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                if (duringBody) delay(50)
                withTimeout(2000) {
                    job.cancelAndJoin()
                    while (http.dispatcher.runningCallsCount() != 0) delay(10)
                }
                assertFalse(returned)
                assertTrue(job.isCancelled)
            }
        }
    }
}
