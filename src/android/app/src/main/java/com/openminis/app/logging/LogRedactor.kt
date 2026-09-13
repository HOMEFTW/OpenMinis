package com.openminis.app.logging

import java.util.concurrent.CopyOnWriteArraySet

/** Redacts credential material before text reaches logcat, files, or sharing. */
object LogRedactor {
    private const val REDACTED = "<redacted>"

    private val registeredSecrets = CopyOnWriteArraySet<String>()
    private val supplierGuard = ThreadLocal.withInitial { false }

    /**
     * Optional supplier for secrets already held by the app. The default is
     * empty on purpose: reading encrypted prefs here could recurse while the
     * Keystore-backed prefs factory is reporting its own failure.
     */
    @Volatile
    private var knownSecretSupplier: (() -> Iterable<String>)? = null

    fun setKnownSecretSupplier(supplier: (() -> Iterable<String>)?) {
        knownSecretSupplier = supplier
    }

    fun registerKnownSecret(secret: String?) {
        secret?.takeIf { it.length >= 4 }?.let { registeredSecrets += it }
    }

    fun clearRegisteredSecrets() {
        registeredSecrets.clear()
    }

    fun redact(value: String, additionalKnownSecrets: Iterable<String> = emptyList()): String {
        var result = redactStructuredValues(value)
        val secrets = LinkedHashSet<String>()
        secrets += registeredSecrets
        secrets += additionalKnownSecrets.filter { it.length >= 4 }
        secrets += suppliedSecrets()
        // Replace longest values first so a short prefix cannot partially
        // consume a larger token before it is considered.
        secrets.filter { it.isNotEmpty() }
            .sortedByDescending { it.length }
            .forEach { secret -> result = result.replace(secret, REDACTED) }
        return result
    }

    private fun suppliedSecrets(): Set<String> {
        if (supplierGuard.get()) return emptySet()
        supplierGuard.set(true)
        return try {
            knownSecretSupplier?.invoke()
                ?.filter { it.length >= 4 }
                ?.toSet()
                ?: emptySet()
        } catch (_: Throwable) {
            emptySet()
        } finally {
            supplierGuard.set(false)
        }
    }

    private fun redactStructuredValues(input: String): String {
        var result = input
        result = quotedCredentialHeader.replace(result) { match ->
            match.groupValues[1] + match.groupValues[2] + match.groupValues[3] +
                " " + REDACTED + match.groupValues[2]
        }
        result = credentialHeader.replace(result) { match ->
            match.groupValues[1] + REDACTED
        }
        result = quotedCookieHeader.replace(result) { match ->
            match.groupValues[1] + match.groupValues[2] + REDACTED + match.groupValues[2]
        }
        result = cookieHeader.replace(result) { match ->
            match.groupValues[1] + REDACTED
        }
        result = sensitiveAssignment.replace(result) { match ->
            val quote = match.groupValues[2]
            match.groupValues[1] + quote + REDACTED + quote
        }
        result = sensitiveQuery.replace(result) { match ->
            match.groupValues[1] + REDACTED
        }
        return result
    }

    private val quotedCredentialHeader = Regex(
        """(?i)([\"']?\b(?:authorization|proxy-authorization)\b[\"']?\s*[:=]\s*)([\"'])(bearer|basic)\s+([^\"'\s,;}]+)\2""",
    )

    private val credentialHeader = Regex(
        """(?i)([\"']?\b(?:authorization|proxy-authorization)\b[\"']?\s*[:=]\s*(?:bearer|basic)\s+)[^\s,;}\]<>\"']+""",
    )

    private val quotedCookieHeader = Regex(
        """(?im)([\"']?\b(?:cookie|set-cookie)\b[\"']?\s*[:=]\s*)([\"'])[^\r\n]*?\2""",
    )

    private val cookieHeader = Regex(
        """(?im)([\"']?\b(?:cookie|set-cookie)\b[\"']?\s*[:=]\s*)(?![\"'])[^\r\n]+""",
    )

    private val sensitiveAssignment = Regex(
        """(?i)([\"']?\b(?:x-api-key|api[-_]?key|access[-_]?token|refresh[-_]?token|id[-_]?token|client[-_]?secret|device[-_]?code|password|secret|signature|sig)\b[\"']?\s*[:=]\s*)([\"']?)([^,\s}\"']+)(\2)""",
    )

    private val sensitiveQuery = Regex(
        """(?i)([?&](?:x-amz-signature|x-amz-credential|x-amz-security-token|signature|sig|access_token|refresh_token|id_token|api[-_]?key|apikey|token|auth)=)[^&#\s]+""",
    )
}
