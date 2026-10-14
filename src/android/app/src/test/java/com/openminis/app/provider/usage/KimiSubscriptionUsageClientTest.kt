package com.openminis.app.provider.usage

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class KimiSubscriptionUsageClientTest {
    @Test fun `subscription fetch uses bearer authentication and the usage path`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"usage":{"limit":100,"remaining":80}}"""))
            server.start()
            val result = KimiSubscriptionUsageClient(endpoint = server.url("/coding/v1/usages")).load { "test-only-token" }
            assertTrue(result is KimiUsageState.Ready)
            assertEquals(20L, result.usage?.weekly?.used)
            val request = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("GET", request.method)
            assertEquals("/coding/v1/usages", request.path)
            assertEquals("Bearer test-only-token", request.getHeader("Authorization"))
            assertEquals("https://api.kimi.com/coding/v1/usages", KimiSubscriptionUsageClient.USAGE_URL)
        }
    }

    @Test fun `no authentication does not send an unauthenticated usage request`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val result = KimiSubscriptionUsageClient(endpoint = server.url("/")).load { null }
            assertEquals(KimiUsageFailure.AUTHENTICATION, (result as KimiUsageState.Failed).reason)
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun `HTTP failures have safe distinct states and retain previous refresh data`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val client = KimiSubscriptionUsageClient(endpoint = server.url("/usages"))
            val previous = parseKimiSubscriptionUsage(org.json.JSONObject("""{"usage":{"limit":100,"used":30}}"""))
            for ((code, reason) in listOf(401 to KimiUsageFailure.AUTHENTICATION, 403 to KimiUsageFailure.AUTHENTICATION,
                404 to KimiUsageFailure.UNAVAILABLE, 429 to KimiUsageFailure.RATE_LIMITED, 503 to KimiUsageFailure.SERVER)) {
                server.enqueue(MockResponse().setResponseCode(code).setBody("private-account-response test-only-token"))
                val failure = client.load(previous) { "test-only-token" } as KimiUsageState.Failed
                assertEquals(reason, failure.reason)
                assertSame(previous, failure.usage)
                assertFalse(failure.toString().contains("private-account-response"))
                assertFalse(failure.toString().contains("test-only-token"))
            }
        }
    }

    @Test fun `empty data differs from malformed responses and clears previous data`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val client = KimiSubscriptionUsageClient(endpoint = server.url("/usages"))
            val previous = parseKimiSubscriptionUsage(org.json.JSONObject("""{"usage":{"limit":100,"used":30}}"""))
            for (body in listOf("{}", "{\"usage\":null,\"limits\":[]}")) {
                server.enqueue(MockResponse().setBody(body))
                assertEquals(KimiUsageState.Empty, client.load(previous) { "test" })
            }
            server.enqueue(MockResponse().setResponseCode(204))
            assertEquals(KimiUsageState.Empty, client.load { "test" })
            server.enqueue(MockResponse().setBody("<html>not a quota</html>"))
            assertEquals(KimiUsageFailure.INVALID_RESPONSE, (client.load { "test" } as KimiUsageState.Failed).reason)
        }
    }

    @Test fun `refresh obtains a new server snapshot and resolves the current token each time`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"usage":{"limit":100,"used":30}}"""))
            server.enqueue(MockResponse().setBody("""{"usage":{"limit":100,"used":60}}"""))
            val client = KimiSubscriptionUsageClient(endpoint = server.url("/usages"))
            var refresh = 0
            val first = client.load { "test-${++refresh}" }
            val second = client.load(first.usage) { "test-${++refresh}" }
            assertEquals(30L, first.usage?.weekly?.used)
            assertEquals(60L, second.usage?.weekly?.used)
            assertEquals("Bearer test-1", server.takeRequest().getHeader("Authorization"))
            assertEquals("Bearer test-2", server.takeRequest().getHeader("Authorization"))
        }
    }

    @Test fun `credential and transport failures never expose raw exception messages`() = runBlocking {
        val client = KimiSubscriptionUsageClient()
        val network = client.load { throw IOException("private test-account credential") } as KimiUsageState.Failed
        assertEquals(KimiUsageFailure.NETWORK, network.reason)
        val storage = client.load { throw IllegalStateException("private test-account credential") } as KimiUsageState.Failed
        assertEquals(KimiUsageFailure.UNAVAILABLE, storage.reason)
        assertFalse(network.toString().contains("private test-account"))
        assertFalse(storage.toString().contains("private test-account"))
    }

    @Test fun `usage redirects never forward the bearer credential to another endpoint`() = runBlocking {
        MockWebServer().use { server ->
            MockWebServer().use { other ->
                server.start(); other.start()
                server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/credentials")))
                val result = KimiSubscriptionUsageClient(endpoint = server.url("/usages")).load { "test-only-token" }
                assertTrue(result is KimiUsageState.Failed)
                assertEquals(0, other.requestCount)
            }
        }
    }

    @Test fun `cancelling a stalled usage request closes the actual HTTP call promptly`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            server.start()
            val http = OkHttpClient()
            val client = KimiSubscriptionUsageClient(http, server.url("/usages"))
            val job = launch(Dispatchers.IO) { client.load { "test" } }
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            withTimeout(2000) {
                job.cancelAndJoin()
                while (http.dispatcher.runningCallsCount() != 0) delay(10)
            }
            assertTrue(job.isCancelled)
        }
    }

    @Test fun `cancellation during token refresh never becomes a failure state or sends usage`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val started = CompletableDeferred<Unit>()
            val client = KimiSubscriptionUsageClient(endpoint = server.url("/usages"))
            var returned = false
            val job = launch {
                client.load { started.complete(Unit); awaitCancellation() }
                returned = true
            }
            withTimeout(2000) { started.await() }
            withTimeout(2000) { job.cancelAndJoin() }
            assertFalse(returned)
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun `timeouts are visible and do not create quota data`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            server.start()
            val http = OkHttpClient.Builder().callTimeout(100, TimeUnit.MILLISECONDS).build()
            val failure = KimiSubscriptionUsageClient(http, server.url("/usages")).load { "test" } as KimiUsageState.Failed
            assertEquals(KimiUsageFailure.TIMEOUT, failure.reason)
            assertNull(failure.usage)
        }
    }
}
