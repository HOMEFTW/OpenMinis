package com.openminis.app.provider

import com.openminis.app.data.model.*
import com.openminis.app.network.NetworkRuntime
import com.openminis.app.network.NetworkTagged
import com.openminis.app.provider.voice.VoiceProvider
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.*
import org.junit.Test

class ProviderNetworkRoutingTest {
    private fun client(provider: LLMProvider): OkHttpClient = provider.javaClass
        .getDeclaredMethod("getClient").apply { isAccessible = true }.invoke(provider) as OkHttpClient

    private fun proxyIdentity(client: OkHttpClient): String? {
        val response = Response.Builder().request(Request.Builder().url("https://fixture.invalid/").build())
            .code(407).message("fixture").protocol(Protocol.HTTP_1_1).build()
        return client.proxyAuthenticator.authenticate(null, response)?.header("Proxy-Authorization")
    }

    @Test fun `all chat factories route their real clients and pick up route changes`() {
        val saved = NetworkRuntime.snapshot
        try {
            val types = listOf(ProviderType.openAI, ProviderType.openAIResponses, ProviderType.anthropic,
                ProviderType.gemini, ProviderType.deepSeek, ProviderType.openRouter, ProviderType.xAI, ProviderType.kimiCode)
            val providers = types.map { type ->
                val instance = ProviderInstance(type.name, type.name, type, ProviderCredential.apiKey,
                    customBaseURL = "https://fixture.invalid/v1", responseTimeoutSeconds = 900)
                ProviderFactory.create(instance, "fixture-key", LLMModel.gpt4oMini).also {
                    assertEquals(NetworkRuntime.providerTag(instance.id), (it as NetworkTagged).netTag)
                }
            }
            NetworkRuntime.publish(NetworkRuntime.Snapshot())
            val direct = providers.map(::client)
            NetworkRuntime.publish(NetworkRuntime.Snapshot(port = 1080, httpPort = 18080, password = "fixture",
                defaultRoute = "proxy:test"))
            val routed = providers.map(::client)
            providers.indices.forEach { index ->
                assertNotSame(direct[index], routed[index])
                assertEquals(direct[index].readTimeoutMillis, routed[index].readTimeoutMillis)
                assertSame(direct[index].connectionPool, routed[index].connectionPool)
                assertEquals(Credentials.basic((providers[index] as NetworkTagged).netTag!!, "fixture"),
                    proxyIdentity(routed[index]))
            }
            NetworkRuntime.publish(NetworkRuntime.Snapshot())
            providers.indices.forEach { assertSame(direct[it], client(providers[it])) }
        } finally { NetworkRuntime.publish(saved) }
    }

    @Test fun `speech uses the default route and retains timeouts`() {
        val saved = NetworkRuntime.snapshot
        try {
            NetworkRuntime.publish(NetworkRuntime.Snapshot())
            val voice = VoiceProvider("instance", "https://fixture.invalid")
            val direct = voice.httpClient
            NetworkRuntime.publish(NetworkRuntime.Snapshot(port = 1080, httpPort = 18080, password = "fixture",
                defaultRoute = "proxy:test"))
            assertEquals(Credentials.basic("default", "fixture"), proxyIdentity(voice.httpClient))
            assertEquals(direct.readTimeoutMillis, voice.httpClient.readTimeoutMillis)
            assertSame(direct.dispatcher, voice.httpClient.dispatcher)
        } finally { NetworkRuntime.publish(saved) }
    }
}
