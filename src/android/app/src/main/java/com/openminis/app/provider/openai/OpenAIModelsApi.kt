package com.openminis.app.provider.openai

import android.content.Context
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.normalizeModalities
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.ModelsDevApi
import com.openminis.app.provider.ProviderModelsCache
import com.openminis.app.provider.applyUserAgentOverride
import com.openminis.app.provider.safeOptString
import com.openminis.app.provider.safeOptString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

object OpenAIModelsApi {
    private const val TAG = "OpenAIModelsApi"
    private val client = OkHttpClient()
    private val cache = ProviderModelsCache("openai")

    /**
     * Static model list for Codex OAuth (tokens can't call /v1/models).
     * Matches iOS `LLMModel.allOpenAICodexOAuth` (Providers/LLMTypes.swift).
     * Order is preserved so the model picker shows the same default rank.
     */
    // T119: every GPT-5.x model on Codex OAuth supports reasoning_effort
    // (`/v1/responses` requires the `reasoning` object on this auth path),
    // so set supportsReasoning = true up front. Without it the Thinking
    // pill in chat is disabled and the user can't pick low/medium/high.
    fun fetchModelsOAuth(): List<LLMModel> = listOf(
        LLMModel.gpt6Astra,
        LLMModel.gpt61Sol,
        LLMModel.gpt6Sol,
        LLMModel.gpt6Luna,
        // [T-android-thinking-level-arch] GPT-5.6 family — Codex OAuth only
        // (not in LLMModel.allOpenAI, matching iOS). sol/terra reach ULTRA,
        // luna reaches MAX (see ThinkingLevelCatalog).
        LLMModel("gpt-5.6-sol", "GPT-5.6 Sol", "OpenAI", supportsReasoning = true),
        LLMModel("gpt-5.6-terra", "GPT-5.6 Terra", "OpenAI", supportsReasoning = true),
        LLMModel("gpt-5.6-luna", "GPT-5.6 Luna", "OpenAI", supportsReasoning = true),
        LLMModel("gpt-5.5", "GPT-5.5", "OpenAI", supportsReasoning = true),
        LLMModel("gpt-5.4", "GPT-5.4", "OpenAI", supportsReasoning = true),
        // [T-codex-oauth-model-prune] Verified callable on a live
        // ChatGPT-account token (2026-08-01); we had never listed it.
        LLMModel("gpt-5.4-mini", "GPT-5.4 Mini", "OpenAI", supportsReasoning = true),
        // [T-codex-oauth-model-prune] gpt-5.3-codex, gpt-5.3-codex-spark,
        // gpt-5-codex-mini, gpt-5.3, gpt-5.2 and gpt-5 were removed here.
        // The Codex backend answers each with
        //   HTTP 400 {"detail":"The '<id>' model is not supported when using
        //   Codex with a ChatGPT account."}
        // and that error renders as an EMPTY assistant turn, so leaving them in
        // the picker reads to the user as "tool calls are broken" rather than
        // "wrong model". Verified on-device 2026-08-01 against a real Codex
        // OAuth token; the survivors match the set CLIProxyAPI ships for its
        // Codex client (internal/registry/models/codex_client_models.json).
        // Availability is tier-dependent — re-probe before restoring any id,
        // do not add one back from documentation alone. Mirrors iOS
        // LLMModel.allOpenAICodexOAuth.
    ).let {
        AppLogger.info(TAG, "Codex OAuth model list (${it.size} models): ${it.joinToString { m -> m.id }}")
        ModelsDevApi.enrichModels(it)
    } + listOf(
        LLMModel.gptImage25Flare,
        LLMModel.gptImage25Sunburst,
        // [T-codex-gpt-image2-oauth-android] Special image-generation model on
        // the Codex OAuth path. Appended AFTER enrichModels so its declared
        // image input/output modalities survive (models.dev doesn't know it).
        // It does NOT take the normal Chat Completions / Responses path — the
        // gpt-image-2 branch in OpenAIProvider routes it through the Codex
        // image_generation tool. Additive only: the GPT-5.x entries above and
        // their existing OAuth flow are unchanged.
        LLMModel(
            id = "gpt-image-2",
            displayName = "GPT Image 2",
            provider = "OpenAI",
            inputModalities = listOf("text", "image"),
            outputModalities = listOf("image"),
        ),
    )

    // Chat-capable model prefixes (matching iOS)
    fun codexImageModels(): List<LLMModel> = fetchModelsOAuth().filter { "image" in it.outputModalities.orEmpty() }

    private val chatPrefixes = listOf("gpt-", "o1", "o3", "o4-", "codex-", "chatgpt-")

    // Suffixes to exclude (matching iOS)
    private val excludeSuffixes = listOf(
        "-instruct", "-realtime", "-audio", "-transcribe", "-tts", "-embedding"
    )

    data class FetchResult(val models: List<LLMModel>, val error: String?)

    suspend fun fetchModels(
        apiKey: String,
        baseURL: String? = null,
        context: Context? = null,
        forceRefresh: Boolean = false,
        // [T-provider-custom-user-agent] Per-provider UA override; null/blank
        // keeps the default UA. Threaded from ProviderRepository.refreshModels.
        customUserAgent: String? = null,
        netTag: String? = null,
    ): List<LLMModel> = fetchModelsDetailed(apiKey, baseURL, context, forceRefresh, customUserAgent, netTag).models

    suspend fun fetchModelsDetailed(
        apiKey: String,
        baseURL: String? = null,
        context: Context? = null,
        forceRefresh: Boolean = false,
        customUserAgent: String? = null,
        netTag: String? = null,
    ): FetchResult = withContext(Dispatchers.IO) {
        val isCustomBase = baseURL != null && !isOfficialOpenAI(baseURL)
        // For third-party endpoints (vLLM, Ollama, etc.), return empty on failure
        // so the caller preserves existing models instead of replacing with built-in GPT list.
        val fallback = if (isCustomBase) emptyList() else LLMModel.allOpenAI
        fun fail(why: String) = FetchResult(fallback, why)

        val cacheKey = (baseURL ?: "") + "|" + apiKey
        if (context != null && !forceRefresh) {
            cache.load(context, cacheKey)?.let { return@withContext FetchResult(it, null) }
        }

        val url = buildURL(baseURL)
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            // [T-provider-custom-user-agent] models-list UA override.
            .applyUserAgentOverride(customUserAgent)
            .build()

        val endpointClient = com.openminis.app.network.NetworkHttp.client(
            netTag, com.openminis.app.network.EndpointCertificates.client(client, url),
        )
        val (status, body) = endpointClient.newCall(request).execute().use { it.code to it.body?.string().orEmpty() }

        if (status !in 200..299) {
            if (context != null && (status == 401 || status == 403)) {
                cache.invalidate(context, cacheKey)
            }
            return@withContext fail("HTTP $status: ${errorSnippet(body)}")
        }

        val data = modelArray(body)
            ?: return@withContext fail("the response is not a model list: ${errorSnippet(body)}")
        val models = try {
            val parsed = mutableListOf<LLMModel>()
            for (i in 0 until data.length()) {
                val raw = data.opt(i)
                val obj = raw as? JSONObject ?: JSONObject().put("id", raw?.toString().orEmpty())
                val id = listOf("id", "model", "model_id", "name")
                    .firstNotNullOfOrNull { key -> obj.safeOptString(key, "").trim().takeIf { it.isNotEmpty() } }
                    ?: continue

                // Only filter by chat prefixes for official OpenAI endpoints;
                // custom endpoints (vLLM, Ollama) may serve any model ID.
                if (!isCustomBase) {
                    if (!chatPrefixes.any { id.startsWith(it) }) continue
                    if (excludeSuffixes.any { id.contains(it) }) continue
                    if (id.contains(":ft-")) continue
                }

                val displayName = obj.optString("name", id)
                // Third-party gateways (vLLM, OpenRouter-compat proxies) often
                // report per-model modalities under `architecture.{input,output}_modalities`
                // the same way OpenRouter does — pick them up so vision/audio
                // models are routable without waiting for models.dev enrichment.
                val arch = obj.optJSONObject("architecture")
                // OpenAI / OpenRouter return modalities as `image_input` / `text_output` with
                // suffixes; the rest of the codebase (models.dev, capability fragments,
                // ModelEntryDetailScreen toggles) uses the bare form. Normalize at the parse
                // boundary so persisted overrides round-trip correctly through the toggles.
                val inputModalities = arch?.optJSONArray("input_modalities")?.toStringList().normalizeModalities()
                val outputModalities = arch?.optJSONArray("output_modalities")?.toStringList().normalizeModalities()

                // T119: known reasoning families (GPT-5.x, o-series, Codex
                // Mini) get supportsReasoning pre-set to true so the
                // Thinking pill enables before models.dev enrichment lands
                // — for brand-new ids (e.g. gpt-5.5) the catalog rarely has
                // the `reasoning` flag yet, and without this the pill
                // stays disabled.
                val idLower = id.lowercase()
                val knownReasoning = LLMModel.isGPT6Id(id) || idLower.startsWith("gpt-5") ||
                    idLower.startsWith("o1") ||
                    idLower.startsWith("o3") ||
                    idLower.startsWith("o4") ||
                    idLower.contains("codex")

                val defaults = LLMModel(id, displayName, "OpenAI")
                val limits = OpenAIModelLimits.read(obj)
                parsed.add(
                    LLMModel(
                        id = id,
                        displayName = displayName,
                        provider = if (isCustomBase) "Custom" else "OpenAI",
                        inputModalities = inputModalities ?: defaults.inputModalities,
                        outputModalities = outputModalities ?: defaults.outputModalities,
                        supportsReasoning = if (knownReasoning) true else defaults.supportsReasoning,
                        limitsReportedByEndpoint = if (limits.context != null || limits.output != null) true else null,
                        contextWindow = limits.context ?: defaults.contextWindow,
                        maxOutputTokens = limits.output ?: defaults.maxOutputTokens,
                        supportedServiceTiers = OpenAISpeedTiers.read(obj),
                    )
                )
            }
            if (parsed.isEmpty()) return@withContext fail("the provider listed no models")
            ModelsDevApi.enrichModels(parsed, preserveProviderLimits = isCustomBase)
        } catch (error: Exception) {
            return@withContext fail("could not read the model list: ${error.message ?: error.javaClass.simpleName}")
        }

        if (context != null) cache.save(context, cacheKey, models)
        FetchResult(models, null)
    }

    internal fun modelArray(body: String): JSONArray? {
        val text = body.trim()
        if (text.startsWith("[")) return runCatching { JSONArray(text) }.getOrNull()
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val keys = listOf("data", "models", "items", "results", "result")
        for (key in keys) json.optJSONArray(key)?.let { return it }
        for (key in keys) {
            val inner = json.optJSONObject(key) ?: continue
            for (nested in keys) inner.optJSONArray(nested)?.let { return it }
        }
        return null
    }

    internal fun errorSnippet(body: String): String {
        val json = runCatching { JSONObject(body.trim()) }.getOrNull()
        val message = json?.optJSONObject("error")?.safeOptString("message", "")?.takeIf { it.isNotBlank() }
            ?: json?.safeOptString("message", "")?.takeIf { it.isNotBlank() }
            ?: json?.safeOptString("error", "")?.takeIf { it.isNotBlank() && !it.startsWith("{") }
        return (message ?: body).replace(Regex("\\s+"), " ").trim().take(200).ifEmpty { "(empty body)" }
    }

    private fun JSONArray.toStringList(): List<String> {
        val out = ArrayList<String>(length())
        for (i in 0 until length()) {
            val s = optString(i, "")
            if (s.isNotEmpty()) out.add(s)
        }
        return out
    }

    /** Check if a base URL points to official OpenAI endpoints. */
    internal fun isOfficialOpenAI(baseURL: String): Boolean = runCatching {
        java.net.URI(baseURL).host?.lowercase() in setOf("api.openai.com", "chatgpt.com")
    }.getOrDefault(false)

    private fun buildURL(baseURL: String?): String {
        if (baseURL == null) return "https://api.openai.com/v1/models"
        // baseURL is ProviderConfig.effectiveBaseURL — it already has /v1 iff the
        // appendV1Suffix toggle is on. Only append /models; never force /v1, or the
        // toggle is overridden and services without /v1 return 404.
        val base = baseURL.trimEnd('/')
        return "$base/models"
    }
}
