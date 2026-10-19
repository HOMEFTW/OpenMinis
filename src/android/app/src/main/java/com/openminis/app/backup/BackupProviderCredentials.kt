package com.openminis.app.backup

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Pure restore policy for the repository's credential storage adapter. */
object BackupProviderCredentials {
    data class Current(
        val apiKey: String? = null,
        val manualOAuthToken: String? = null,
        val oauthToken: String? = null,
        val oauthEmail: String? = null,
        val oauthGcpProject: String? = null,
        /** A rejected sign-in, not an access token that can still be refreshed. */
        val oauthNeedsReauth: Boolean = false,
    )

    /** Non-null fields are actual changes to apply. Missing backup fields never clear device values. */
    data class Plan(
        val apiKey: String? = null,
        val manualOAuthToken: String? = null,
        val oauthToken: String? = null,
        val oauthEmail: String? = null,
        val oauthGcpProject: String? = null,
        val invalidFields: List<String> = emptyList(),
    ) {
        val hasChanges: Boolean get() = apiKey != null || manualOAuthToken != null ||
            oauthToken != null || oauthEmail != null || oauthGcpProject != null
    }

    fun plan(
        current: Current,
        backup: BackupSecrets.ProviderSecret,
        overwriteExisting: Boolean = true,
    ): Plan {
        val invalid = mutableListOf<String>()
        fun decode(field: String, encoded: String?): String? {
            if (encoded == null) return null
            return runCatching {
                val bytes = Base64.getDecoder().decode(encoded.filterNot { it.isWhitespace() })
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            }.getOrElse { invalid.add(field); null }
        }
        fun changed(value: String?, old: String?): String? = value?.takeIf { it != old }
        val key = decode("apiKey", backup.apiKey)
        val replaceOAuth = current.manualOAuthToken.isNullOrEmpty() && current.oauthToken.isNullOrEmpty() ||
            overwriteExisting && current.oauthNeedsReauth
        val manual = if (replaceOAuth) decode("manualOAuthToken", backup.manualOAuthToken) else null
        val token = if (replaceOAuth) decode("oauthToken", backup.oauthToken)?.let { json ->
            if (runCatching { Json.parseToJsonElement(json) is JsonObject }.getOrDefault(false)) json
            else { invalid.add("oauthToken"); null }
        } else null
        val email = if (replaceOAuth) decode("oauthEmail", backup.oauthEmail) else null
        val project = if (replaceOAuth) decode("oauthGcpProject", backup.oauthGcpProject) else null
        return Plan(
            apiKey = if (overwriteExisting || current.apiKey.isNullOrEmpty()) changed(key, current.apiKey) else null,
            manualOAuthToken = changed(manual, current.manualOAuthToken),
            oauthToken = changed(token, current.oauthToken),
            oauthEmail = changed(email, current.oauthEmail),
            oauthGcpProject = changed(project, current.oauthGcpProject),
            invalidFields = invalid,
        )
    }
}
