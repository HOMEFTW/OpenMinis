package com.openminis.app.provider.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class VoiceProviderCancellationTest {
    @Test fun `cancelling a stalled transcription releases its HTTP call promptly`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            server.start()
            val provider = VoiceProvider("test", server.url("/").toString())
            val job = launch(Dispatchers.IO) { provider.executeRequest(Request.Builder().url(server.url("/asr")).build()) }
            assertTrue(server.takeRequest(3, TimeUnit.SECONDS) != null)
            withTimeout(3000) {
                job.cancelAndJoin()
                while (VoiceProvider.httpClient.dispatcher.runningCallsCount() != 0) delay(10)
            }
            assertTrue(job.isCancelled)
        }
    }

    @Test fun `successful responses still return the audio endpoint payload`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("transcribed text"))
            server.start()
            val provider = VoiceProvider("test", server.url("/").toString())
            val data = runBlocking { provider.executeRequest(Request.Builder().url(server.url("/asr")).build()) }
            assertArrayEquals("transcribed text".toByteArray(), data)
        }
    }

    @Test fun `authentication failures retain the existing typed error`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("bad credential"))
            server.start()
            val provider = VoiceProvider("test", server.url("/").toString())
            assertThrows(VoiceProviderException.Auth::class.java) {
                runBlocking { provider.executeRequest(Request.Builder().url(server.url("/asr")).build()) }
            }
        }
    }

    @Test fun `provider HTTP failures preserve status and response`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).setBody("temporarily unavailable"))
            server.start()
            val provider = VoiceProvider("test", server.url("/").toString())
            val failure = assertThrows(VoiceProviderException.Http::class.java) {
                runBlocking { provider.executeRequest(Request.Builder().url(server.url("/asr")).build()) }
            }
            assertEquals(503, failure.code)
            assertArrayEquals("temporarily unavailable".toByteArray(), failure.body)
        }
    }
}
