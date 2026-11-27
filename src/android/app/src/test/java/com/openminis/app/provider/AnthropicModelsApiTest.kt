package com.openminis.app.provider

import com.openminis.app.provider.anthropic.AnthropicModelsApi
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test

class AnthropicModelsApiTest {
    @Test fun `standard catalog keeps models from every manufacturer and their known defaults`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"data":[
                {"id":"claude-sonnet-4-5","display_name":"Claude"},
                {"id":"gpt-6.1-sol","display_name":"GPT"},
                {"id":"deepseek-v4.1-flash","display_name":"DeepSeek"},
                {"id":"private-model","display_name":"Private"}
            ]}"""))
            val models = AnthropicModelsApi.fetchModels("fixture", server.url("/v1").toString())
            assertEquals(listOf("claude-sonnet-4-5", "gpt-6.1-sol", "deepseek-v4.1-flash", "private-model"), models.map { it.id })
            assertEquals(listOf("Claude", "GPT", "DeepSeek", "Private"), models.map { it.displayName })
            assertEquals(true, models[1].supportsReasoning)
            assertEquals(1_050_000, models[1].contextWindow)
            assertEquals(true, models[2].supportsReasoning)
            assertEquals(1_000_000, models[2].contextWindow)
        }
    }

    @Test fun `relay catalog formats already supported by OpenAI are accepted for Anthropic`() = runBlocking {
        val entry = """{"model_id":"private-model","name":"Private model","contextWindow":8192,"maxOutputTokens":1024}"""
        val bodies = listOf("[$entry]", """{"models":[$entry]}""", """{"items":[$entry]}""",
            """{"results":[$entry]}""", """{"data":{"models":[$entry]}}""", """{"data":["private-model"]}""")
        MockWebServer().use { server ->
            for (body in bodies) {
                // A root URL keeps this test to one discovery candidate.
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest) = MockResponse().setBody(body)
                }
                val model = AnthropicModelsApi.fetchModels("fixture", server.url("/").toString()).single()
                assertEquals("private-model", model.id)
                if ("contextWindow" in body) {
                    assertEquals("Private model", model.displayName)
                    assertEquals(8192, model.contextWindow)
                    assertEquals(1024, model.maxOutputTokens)
                    assertEquals(true, model.limitsReportedByEndpoint)
                }
            }
        }
    }

    @Test fun `invalid entries do not discard valid models or become invented model ids`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"data":[
                {"id":"gpt-6.1-sol"},null,{},17,false,{"id":null},{"id":" "},
                {"id":{}},"deepseek-v4.1-flash",{"model":"private-model"}
            ]}"""))
            assertEquals(listOf("gpt-6.1-sol", "deepseek-v4.1-flash", "private-model"),
                AnthropicModelsApi.fetchModels("fixture", server.url("/").toString()).map { it.id })
        }
    }

    @Test fun `HTML success on messages path still discovers the parent model catalog without duplicate requests`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                    "/v1/models" -> MockResponse().setBody("""{"data":[{"id":"private-model"}]}""")
                    else -> MockResponse().setBody("<html>gateway</html>")
                }
            }
            val models = AnthropicModelsApi.fetchModels("fixture", server.url("/anthropic/v1").toString())
            assertEquals(listOf("private-model"), models.map { it.id })
            assertEquals(2, server.requestCount)
            assertEquals("/anthropic/v1/models?limit=512", server.takeRequest().path)
            assertEquals("/v1/models?limit=512", server.takeRequest().path)
        }
    }

    @Test fun `keyless model discovery matches keyless message authentication`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"data":[{"id":"private-model"}]}"""))
            AnthropicModelsApi.fetchModels("", server.url("/").toString())
            val request = server.takeRequest()
            assertNull(request.getHeader("Authorization"))
            assertNull(request.getHeader("x-api-key"))
        }
    }

    @Test fun `failed relay catalogs report a reason and do not substitute Claude`() = runBlocking {
        val responses = listOf(
            MockResponse().setResponseCode(401).setBody("""{"error":{"message":"invalid credential"}}"""),
            MockResponse().setBody("<html>gateway</html>"),
            MockResponse().setBody("""{"data":[]}"""),
        )
        MockWebServer().use { server ->
            for (response in responses) {
                server.enqueue(response)
                val result = AnthropicModelsApi.fetchModelsDetailed("fixture", server.url("/").toString())
                assertTrue(result.models.isEmpty())
                assertFalse(result.error.isNullOrBlank())
                if (response.status.contains("401")) assertTrue(result.error!!, result.error.contains("HTTP 401: invalid credential"))
            }
        }
    }

    @Test fun `API key and OAuth discovery retain their authentication headers`() = runBlocking {
        MockWebServer().use { server ->
            for (oauth in listOf(false, true)) {
                server.enqueue(MockResponse().setBody("""{"data":[{"id":"private-model"}]}"""))
                AnthropicModelsApi.fetchModels("fixture", server.url("/").toString(), isOAuth = oauth,
                    customUserAgent = "test-catalog-client")
                val request = server.takeRequest()
                assertEquals("Bearer fixture", request.getHeader("Authorization"))
                assertEquals("2023-06-01", request.getHeader("anthropic-version"))
                assertEquals("test-catalog-client", request.getHeader("User-Agent"))
                assertEquals(if (oauth) "oauth-2025-04-20" else null, request.getHeader("anthropic-beta"))
            }
        }
    }
}
