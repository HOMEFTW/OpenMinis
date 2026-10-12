package com.openminis.app.provider

import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.provider.anthropic.AnthropicProvider
import com.openminis.app.provider.gemini.GeminiProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ProviderNonStreamCancellationTest {
    private val messages = listOf(LLMMessage(LLMMessage.Role.USER, "anonymous fixture"))
    private fun provider(kind: String, server: MockWebServer): LLMProvider =
        if (kind == "anthropic") AnthropicProvider("fixture", LLMModel.claudeHaiku45,
            basePath = server.url("/").toString().trimEnd('/'))
        else GeminiProvider("fixture", LLMModel.gemini25Flash,
            basePath = server.url("/").toString().trimEnd('/'))

    private fun payload(kind: String) = if (kind == "anthropic")
        """{"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn"}"""
        else """{"candidates":[{"content":{"parts":[{"text":"ok"}]},"finishReason":"STOP"}]}"""

    private fun cancel(kind: String, delayBody: Boolean) = runBlocking {
        MockWebServer().use { server ->
            val headers = CountDownLatch(1)
            val cancelled = AtomicBoolean(false)
            val client = OkHttpClient.Builder().eventListener(object : EventListener() {
                override fun responseHeadersEnd(call: Call, response: Response) { headers.countDown() }
                override fun canceled(call: Call) { cancelled.set(true) }
            }).build()
            val response = MockResponse().setBody(payload(kind))
            if (delayBody) response.setBodyDelay(2, TimeUnit.SECONDS)
            else response.setHeadersDelay(2, TimeUnit.SECONDS)
            server.enqueue(response)
            server.start()
            val provider = provider(kind, server)
            provider.javaClass.getDeclaredField("baseClient").apply { isAccessible = true }.set(provider, client)
            val job = launch(Dispatchers.IO) { provider.sendMessage(messages, null, 100) }
            assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
            if (delayBody) assertTrue(headers.await(3, TimeUnit.SECONDS))
            val started = System.nanoTime()
            job.cancelAndJoin()
            assertTrue("$kind cancellation still waits for network", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1000)
            assertTrue(cancelled.get())
            assertEquals(0, client.dispatcher.runningCallsCount())
        }
    }

    @Test fun `anthropic cancellation interrupts response headers`() = cancel("anthropic", false)
    @Test fun `gemini cancellation interrupts response headers`() = cancel("gemini", false)
    @Test fun `anthropic cancellation interrupts response body`() = cancel("anthropic", true)
    @Test fun `gemini cancellation interrupts response body`() = cancel("gemini", true)

    @Test fun `success and authentication errors retain their behavior`() {
        for (kind in listOf("anthropic", "gemini")) MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(payload(kind)))
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"type":"authentication_error","message":"fixture"}}"""))
            server.start()
            val provider = provider(kind, server)
            assertEquals("ok", runBlocking { provider.sendMessage(messages, null, 100).text })
            assertThrows(LLMError.InvalidApiKey::class.java) {
                runBlocking { provider.sendMessage(messages, null, 100) }
            }
        }
    }
}
