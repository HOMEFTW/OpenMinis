package com.openminis.app.provider.anthropic

import android.content.Context
import com.openminis.app.data.model.LLMModel
import com.openminis.app.provider.ModelsDevApi
import com.openminis.app.provider.applyUserAgentOverride
import com.openminis.app.provider.openai.OpenAIModelsApi
import com.openminis.app.provider.openai.OpenAIModelLimits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

object AnthropicModelsApi {
    private val client = OkHttpClient()

    data class FetchResult(val models: List<LLMModel>, val error: String?)

    /**
     * Fetch models from Anthropic API.
     * @param apiKey The API key or OAuth access token
     * @param baseURL Optional custom base URL
     * @param isOAuth Whether to use OAuth Bearer auth + beta headers (vs x-api-key)
     * @param context Optional Android [Context]; when provided, results are cached
     *   for 7 days at `cacheDir/models-cache/<sha256(credential)>.json` (matches
     *   iOS `ModelsCache`). When null, the fetch bypasses the cache entirely.
     * @param forceRefresh When true, skip the cache read but still write-through
     *   on success. Used for explicit "refresh models" UI actions.
     */
    suspend fun fetchModels(
        apiKey: String,
        baseURL: String? = null,
        isOAuth: Boolean = false,
        context: Context? = null,
        forceRefresh: Boolean = false,
        // [T-provider-custom-user-agent] Per-provider UA override; null/blank
        // keeps the default UA. Threaded from ProviderRepository.refreshModels.
        customUserAgent: String? = null,
        netTag: String? = null,
    ): List<LLMModel> = fetchModelsDetailed(
        apiKey, baseURL, isOAuth, context, forceRefresh, customUserAgent, netTag,
    ).models

    suspend fun fetchModelsDetailed(
        apiKey: String,
        baseURL: String? = null,
        isOAuth: Boolean = false,
        context: Context? = null,
        forceRefresh: Boolean = false,
        customUserAgent: String? = null,
        netTag: String? = null,
    ): FetchResult = withContext(Dispatchers.IO) {
        // Cache key: base URL included so the same API key across proxies doesn't
        // share cached model lists (vLLM vs api.anthropic.com can differ).
        val cacheKey = (baseURL ?: "") + "|" + apiKey + "|" + isOAuth
        if (context != null && !forceRefresh) {
            AnthropicModelsCache.load(context, cacheKey)?.let { return@withContext FetchResult(it, null) }
        }

        // Anthropic-compatible custom endpoints (e.g. DashScope/Bailian) often don't
        // expose `/v1/models`. On any failure for a custom endpoint, return empty so
        // the caller can fall through to the models.dev registry; only the official
        // api.anthropic.com is given the hardcoded fallback as a network-failure net.
        val isCustomEndpoint = baseURL != null && !isOfficialAnthropic(baseURL)

        // Build candidate bases: start with the user-supplied base, then climb the
        // path up to 3 levels. Lets vendor-specific bases like
        // `https://api.deepseek.com/anthropic` discover `/v1/models` at the host root.
        val candidateBases = mutableListOf<String?>(baseURL)
        if (baseURL != null) {
            var current: String = baseURL
            for (i in 0 until 3) {
                val parent = parentPath(current) ?: break
                if (parent == current) break
                candidateBases.add(parent)
                current = parent
            }
        }

        var firstError: String? = null
        val attemptedURLs = mutableSetOf<String>()
        for ((idx, candidate) in candidateBases.withIndex()) {
            // idx == 0 is the user-supplied base, which arrives already resolved by
            // ProviderConfig.effectiveBaseURL (appendV1Suffix honored). Trust it as-is
            // and only append /models — never force /v1, or the "append /v1" toggle is
            // overridden and services without /v1 return 404. idx > 0 are climbed parent
            // paths used purely for host-root discovery (e.g. deepseek.com/anthropic ->
            // host root /v1/models); those keep the /v1 auto-append.
            val url = buildURL(candidate, forceV1Discovery = idx > 0)
            if (!attemptedURLs.add(url)) continue
            val requestBuilder = Request.Builder()
                .url(url)
                .header("anthropic-version", "2023-06-01")
                // [T-provider-custom-user-agent] models-list UA override.
                .applyUserAgentOverride(customUserAgent)

            if (isOAuth) {
                // OAuth: Bearer token + required beta header (mirrors iOS fetchModels(oauthToken:))
                requestBuilder.header("Authorization", "Bearer $apiKey")
                requestBuilder.header("anthropic-beta", "oauth-2025-04-20")
            } else if (apiKey.isEmpty()) {
                // Match the message path for keyless local compatible endpoints.
            } else if (isCustomEndpoint) {
                // Custom endpoint: Bearer auth
                requestBuilder.header("Authorization", "Bearer $apiKey")
            } else {
                // Standard Anthropic API key
                requestBuilder.header("x-api-key", apiKey)
            }

            val request = requestBuilder.build()
            android.util.Log.d("AnthropicModels", "Fetching models (level=$idx): ${request.url} isOAuth=$isOAuth")

            val (status, body) = try {
                com.openminis.app.network.NetworkHttp.client(
                    netTag, com.openminis.app.network.EndpointCertificates.client(client, url),
                ).newCall(request).execute().use { it.code to it.body?.string().orEmpty() }
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                android.util.Log.e("AnthropicModels", "Fetch error (level=$idx): ${e.message}")
                if (firstError == null) firstError = e.message ?: e.javaClass.simpleName
                continue
            }

            if (status !in 200..299) {
                val error = "HTTP $status: ${OpenAIModelsApi.errorSnippet(body)}"
                android.util.Log.e("AnthropicModels", "Fetch failed (level=$idx): $error")
                // 401/403 almost always means the credential rotated; drop any stale
                // cache entry so the next lookup hits the network again.
                if (context != null && (status == 401 || status == 403)) {
                    AnthropicModelsCache.invalidate(context, cacheKey)
                }
                if (firstError == null) firstError = error
                continue
            }

            val models = parseModels(body, isCustomEndpoint)
            if (models.isEmpty()) {
                if (firstError == null) firstError = "the response contains no usable models: ${OpenAIModelsApi.errorSnippet(body)}"
                // Some gateways serve an HTML landing page with HTTP 200 at
                // the messages base. Continue looking for their model catalog.
                continue
            }
            if (idx > 0) {
                android.util.Log.i("AnthropicModels", "Parent-path fallback succeeded at level=$idx base=$candidate")
            }
            if (context != null) AnthropicModelsCache.save(context, cacheKey, models)
            return@withContext FetchResult(models, null)
        }

        return@withContext FetchResult(
            if (isCustomEndpoint) emptyList() else LLMModel.allAnthropic,
            firstError ?: "the provider returned no models",
        )
    }

    private suspend fun parseModels(
        body: String,
        isCustomEndpoint: Boolean,
    ): List<LLMModel> {
        // Compatible gateways may use the same catalog envelopes as OpenAI.
        // The catalog format does not change the selected message protocol.
        val data = OpenAIModelsApi.modelArray(body) ?: return emptyList()
        val parsed = mutableListOf<LLMModel>()
        for (i in 0 until data.length()) {
            val obj = when (val raw = data.opt(i)) {
                is JSONObject -> raw
                is String -> JSONObject().put("id", raw)
                else -> continue
            }
            fun text(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
                (obj.opt(key) as? String)?.trim()?.takeIf { it.isNotEmpty() }
            }
            val id = text("id", "model", "model_id", "name") ?: continue
            val displayName = text("display_name", "name") ?: id
            val defaults = LLMModel(id, displayName, if (isCustomEndpoint) "Custom" else "Anthropic")
            val limits = OpenAIModelLimits.read(obj)
            parsed.add(defaults.copy(
                supportsReasoning = if (AnthropicProvider.supportsThinking(id)) true else defaults.supportsReasoning,
                contextWindow = limits.context ?: defaults.contextWindow,
                maxOutputTokens = limits.output ?: defaults.maxOutputTokens,
                limitsReportedByEndpoint = if (limits.context != null || limits.output != null) true else null,
            ))
        }
        return ModelsDevApi.enrichModels(parsed.distinctBy { it.id }, preserveProviderLimits = isCustomEndpoint)
    }

    internal fun isOfficialAnthropic(baseURL: String): Boolean =
        baseURL.toHttpUrlOrNull()?.host == "api.anthropic.com"

    /**
     * Build the /models endpoint.
     *
     * For the user-supplied base ([forceV1Discovery] == false) the incoming value is
     * already ProviderConfig.effectiveBaseURL — it has /v1 iff the appendV1Suffix toggle
     * is on — so we only append /models and never inject /v1. Forcing /v1 here would
     * override the toggle and 404 on services that don't expose /v1.
     *
     * For climbed parent candidates ([forceV1Discovery] == true) we still try the
     * host-root /v1/models convention to keep deepseek.com/anthropic-style discovery
     * working; those bases never passed through effectiveBaseURL.
     */
    private fun buildURL(baseURL: String?, forceV1Discovery: Boolean = false): String {
        if (baseURL == null) return "https://api.anthropic.com/v1/models?limit=512"
        val base = baseURL.trimEnd('/')
        val endpoint = when {
            base.endsWith("/v1") -> "$base/models"
            forceV1Discovery -> "$base/v1/models"
            else -> "$base/models"
        }
        return "$endpoint?limit=512"
    }

    /**
     * Strip the last path segment from [base], returning a URL one level up.
     * Returns null when [base] is invalid or already at `scheme://host[:port]`
     * with no remaining path. Mirrors iOS `parentPath(of:)`.
     */
    private fun parentPath(base: String): String? {
        val httpUrl = base.toHttpUrlOrNull() ?: return null
        // pathSegments always contains at least one element (possibly ""). Drop
        // trailing empty segment caused by a trailing slash, then drop the last
        // real segment.
        val segments = httpUrl.pathSegments.toMutableList()
        while (segments.isNotEmpty() && segments.last().isEmpty()) {
            segments.removeAt(segments.size - 1)
        }
        if (segments.isEmpty()) return null
        segments.removeAt(segments.size - 1)
        val builder = httpUrl.newBuilder()
        // Reset path: clear all segments by rebuilding from scratch.
        // okhttp's HttpUrl.Builder doesn't expose a direct setter, so iterate.
        for (i in httpUrl.pathSegments.indices.reversed()) {
            builder.removePathSegment(i)
        }
        for (seg in segments) {
            builder.addPathSegment(seg)
        }
        return builder.build().toString().trimEnd('/')
    }
}
