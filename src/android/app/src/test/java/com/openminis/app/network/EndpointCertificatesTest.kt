package com.openminis.app.network

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class EndpointCertificatesTest {
    @Test fun privateApiCertificateDoesNotBlockImageDownloadsFromAnotherHost() {
        EndpointCertificates.initialize(com.openminis.app.util.MemorySharedPreferences())
        val server = MockWebServer()
        server.start()
        try {
            val root = HeldCertificate.Builder().certificateAuthority(0).build()
            EndpointCertificates.save("https://private.test", root.certificatePem())
            val provider = com.openminis.app.provider.openai.OpenAIProvider(
                apiKey = "test-key", basePath = "https://private.test/v1",
            )
            val bytes = byteArrayOf(1, 2, 3)
            server.enqueue(MockResponse().addHeader("Content-Type", "image/png").setBody(okio.Buffer().write(bytes)))
            val body = org.json.JSONObject().put("data", org.json.JSONArray().put(
                org.json.JSONObject().put("url", server.url("/image.png").toString()),
            ))
            val parser = provider.javaClass.getDeclaredMethod("parseImageGenerationsResult", org.json.JSONObject::class.java)
                .apply { isAccessible = true }
            val response = parser.invoke(provider, body) as com.openminis.app.data.model.LLMResponse
            assertArrayEquals(bytes, response.mediaAttachments.single().data)
            assertNull(server.takeRequest().getHeader("Authorization"))
        } finally {
            server.shutdown()
            EndpointCertificates.initialize(com.openminis.app.util.MemorySharedPreferences())
        }
    }

    @Test fun certificateChangesReachExistingProvidersAndRemovalRestoresDefaultTrust() {
        val preferences = com.openminis.app.util.MemorySharedPreferences()
        EndpointCertificates.initialize(preferences)
        try {
            val base = OkHttpClient()
            val url = "https://private.test/v1"
            assertSame(base, EndpointCertificates.client(base, url))
            val first = HeldCertificate.Builder().certificateAuthority(0).build()
            EndpointCertificates.save(url, first.certificatePem())
            val configured = EndpointCertificates.client(base, url)
            assertNotSame(base, configured)
            assertSame(configured, EndpointCertificates.client(base, url))
            assertSame(base, EndpointCertificates.client(base, "https://other.test/v1"))
            val second = HeldCertificate.Builder().certificateAuthority(0).build()
            EndpointCertificates.save(url, second.certificatePem())
            assertNotSame(configured, EndpointCertificates.client(base, url))
            EndpointCertificates.save(url, "")
            assertSame(base, EndpointCertificates.client(base, url))
        } finally { EndpointCertificates.initialize(com.openminis.app.util.MemorySharedPreferences()) }
    }

    @Test fun originRejectsCleartextAndCredentialsAndKeepsPort() {
        assertNull(EndpointCertificates.origin("http://example.test"))
        assertNull(EndpointCertificates.origin("https://user:pass@example.test"))
        assertEquals("https://example.test:8443", EndpointCertificates.origin("https://EXAMPLE.test:8443/v1"))
    }

    @Test fun parserRejectsLeafAndPrivateKeys() {
        val leaf = HeldCertificate.Builder().commonName("localhost").build()
        assertTrue(runCatching { EndpointCertificates.parse(leaf.certificatePem()) }.isFailure)
        assertTrue(runCatching { EndpointCertificates.parse(leaf.privateKeyPkcs8Pem()) }.isFailure)
    }

    @Test fun explicitRootEnablesOnlyTheConfiguredEndpoint() {
        val root = HeldCertificate.Builder().certificateAuthority(0).commonName("Test CA").build()
        val leaf = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost")
            .addSubjectAlternativeName("127.0.0.1")
            .signedBy(root).build()
        val identity = HandshakeCertificates.Builder().heldCertificate(leaf, root.certificate).build()
        val server = MockWebServer()
        server.useHttps(identity.sslSocketFactory(), false)
        server.start()
        try {
            val base = server.url("/").toString()
            val request = Request.Builder().url(base).build()
            // A private root remains untrusted unless explicitly configured.
            assertTrue(runCatching { OkHttpClient().newCall(request).execute().close() }.exceptionOrNull() is IOException)
            val client = EndpointCertificates.configure(OkHttpClient.Builder(), base,
                EndpointCertificates.parse(root.certificatePem())).build()
            server.enqueue(MockResponse().setBody("trusted"))
            client.newCall(request).execute().use { assertEquals("trusted", it.body!!.string()) }
            val otherPort = server.url("/").newBuilder().port(server.port + 1).build()
            assertTrue(runCatching {
                client.newCall(Request.Builder().url(otherPort).build()).execute().close()
            }.exceptionOrNull() is IOException)
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "https://example.test/"))
            client.newCall(request).execute().use { assertEquals(302, it.code) }
        } finally { server.shutdown() }
    }

    @Test fun customCaDoesNotDisableHostnameVerification() {
        val root = HeldCertificate.Builder().certificateAuthority(0).build()
        val leaf = HeldCertificate.Builder().commonName("wrong.test").addSubjectAlternativeName("wrong.test")
            .signedBy(root).build()
        val server = MockWebServer()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(leaf, root.certificate).build().sslSocketFactory(), false)
        server.start()
        try {
            val base = server.url("/").toString()
            val client = EndpointCertificates.configure(OkHttpClient.Builder(), base, listOf(root.certificate)).build()
            assertTrue(runCatching {
                client.newCall(Request.Builder().url(base).build()).execute().close()
            }.exceptionOrNull() is IOException)
        } finally { server.shutdown() }
    }
}
