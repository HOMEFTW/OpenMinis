package com.openminis.app.provider

import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ProviderInstance

/** Cache only an immutable request binding, never mutate an in-flight provider. */
internal class RequestProviderCache {
    private data class Binding(
        val entry: ModelEntry,
        val instance: ProviderInstance,
        val credentialRevision: Long,
        val sessionId: String,
    )
    private var binding: Binding? = null
    private var provider: LLMProvider? = null

    @Synchronized
    fun resolve(
        entry: ModelEntry,
        instance: ProviderInstance,
        credentialRevision: Long,
        sessionId: String,
        current: LLMProvider?,
        create: () -> LLMProvider,
    ): LLMProvider {
        val next = Binding(entry, instance.copy(), credentialRevision, sessionId)
        if (binding != next || provider !== current) {
            val rebuilt = create()
            provider = rebuilt
            binding = next
        }
        return checkNotNull(provider)
    }
}
