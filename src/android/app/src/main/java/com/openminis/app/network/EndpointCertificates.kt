package com.openminis.app.network

import android.content.Context
import android.content.SharedPreferences
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Explicit PEM trust for one HTTPS endpoint; never changes Android's global trust store. */
object EndpointCertificates {
    private var prefs: SharedPreferences? = null
    private val clients = java.util.WeakHashMap<OkHttpClient, Pair<String, OkHttpClient>>()

    fun initialize(context: Context) {
        initialize(context.applicationContext.getSharedPreferences("endpoint_certificates", Context.MODE_PRIVATE))
    }

    internal fun initialize(preferences: SharedPreferences) {
        prefs = preferences
        synchronized(clients) { clients.clear() }
    }

    internal fun origin(raw: String): String? = raw.toHttpUrlOrNull()
        ?.takeIf { it.isHttps && it.username.isEmpty() && it.password.isEmpty() }
        ?.let { "${it.scheme}://${it.host}:${it.port}" }

    fun get(baseUrl: String): String = origin(baseUrl)?.let { prefs?.getString(it, "") }.orEmpty()

    fun save(baseUrl: String, pem: String) {
        val key = requireNotNull(origin(baseUrl)) { "A valid HTTPS endpoint is required" }
        if (pem.isNotBlank()) parse(pem)
        val editor = checkNotNull(prefs) { "Certificate store is not initialized" }.edit()
        if (pem.isBlank()) editor.remove(key) else editor.putString(key, pem.trim())
        check(editor.commit()) { "Could not save certificate" }
        NetworkMonitor.sharedLLMConnectionPool.evictAll()
    }

    internal fun parse(pem: String): List<X509Certificate> {
        require(pem.length <= 65_536 && !pem.contains("PRIVATE KEY")) { "Paste CA certificates only" }
        val certificates = CertificateFactory.getInstance("X.509")
            .generateCertificates(pem.byteInputStream()).map { it as X509Certificate }
        require(certificates.isNotEmpty()) { "No certificate found" }
        certificates.forEach {
            require(it.basicConstraints >= 0) { "A CA certificate is required" }
            it.checkValidity()
        }
        return certificates
    }

    fun configure(builder: OkHttpClient.Builder, baseUrl: String): OkHttpClient.Builder {
        val pem = get(baseUrl)
        if (pem.isBlank()) return builder
        return configure(builder, baseUrl, parse(pem))
    }

    /** Re-read the endpoint setting for each call, including existing chat providers. */
    fun client(base: OkHttpClient, baseUrl: String): OkHttpClient = synchronized(clients) {
        val pem = get(baseUrl)
        if (pem.isBlank()) {
            clients.remove(base)
            return@synchronized base
        }
        val key = "${origin(baseUrl)}\n$pem"
        clients[base]?.takeIf { it.first == key }?.let { return@synchronized it.second }
        configure(base.newBuilder(), baseUrl, parse(pem)).build().also {
            clients[base] = key to it
        }
    }

    internal fun configure(builder: OkHttpClient.Builder, baseUrl: String, certificates: List<X509Certificate>): OkHttpClient.Builder {
        val url = requireNotNull(baseUrl.toHttpUrlOrNull())
        requireNotNull(origin(baseUrl))
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            certificates.forEachIndexed { i, cert -> setCertificateEntry("ca-$i", cert) }
        }
        fun manager(keyStore: KeyStore?): X509TrustManager = TrustManagerFactory
            .getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keyStore) }
            .trustManagers.filterIsInstance<X509TrustManager>().single()
        val system = manager(null)
        val custom = manager(store)
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers() = system.acceptedIssuers + custom.acceptedIssuers
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
                system.checkClientTrusted(chain, authType)
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                try { system.checkServerTrusted(chain, authType) }
                catch (_: CertificateException) { custom.checkServerTrusted(chain, authType) }
            }
        }
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        val verifier = OkHttpClient().hostnameVerifier
        return builder.sslSocketFactory(tls.socketFactory, trust)
            // A custom-CA client must not carry that trust to another endpoint.
            .followRedirects(false).followSslRedirects(false)
            .hostnameVerifier { host, session -> host == url.host && verifier.verify(host, session) }
            .addInterceptor { chain ->
                val requestUrl = chain.request().url
                if (requestUrl.scheme != url.scheme || requestUrl.host != url.host || requestUrl.port != url.port) {
                    throw java.io.IOException("Custom certificate is restricted to its configured HTTPS endpoint")
                }
                chain.proceed(chain.request())
            }
    }
}
