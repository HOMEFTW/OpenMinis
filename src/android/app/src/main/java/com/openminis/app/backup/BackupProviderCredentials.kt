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
        val key = decode("apiKey", backup.apiKey)
        val replaceOAuth = current.manualOAuthToken.isNullOrEmpty() && current.oauthToken.isNullOrEmpty() ||
            overwriteExisting && current.oauthNeedsReauth
        // A pasted bearer is an API-key credential, independent of a refreshable login.
        val manual = decode("manualOAuthToken", backup.manualOAuthToken)
        val token = if (replaceOAuth) decode("oauthToken", backup.oauthToken)?.let { json ->
            if (runCatching { Json.parseToJsonElement(json) is JsonObject }.getOrDefault(false)) json
            else { invalid.add("oauthToken"); null }
        } else null
        // Keep the fork's bundle protection and strict UTF-8/JSON validation:
        // a corrupt token must not pair an old login with another account's metadata.
        val importLogin = replaceOAuth && (backup.oauthToken == null || token != null)
        val email = if (importLogin) decode("oauthEmail", backup.oauthEmail) else null
        val project = if (importLogin) decode("oauthGcpProject", backup.oauthGcpProject) else null
        val local = linkedMapOf(
            BackupCredentialRestore.Slot.API_KEY to current.apiKey,
            BackupCredentialRestore.Slot.MANUAL_OAUTH_TOKEN to current.manualOAuthToken,
            BackupCredentialRestore.Slot.OAUTH_TOKEN to current.oauthToken,
            BackupCredentialRestore.Slot.OAUTH_EMAIL to current.oauthEmail,
            BackupCredentialRestore.Slot.OAUTH_GCP_PROJECT to current.oauthGcpProject,
        )
        val decoded = linkedMapOf<BackupCredentialRestore.Slot, String>()
        fun add(slot: BackupCredentialRestore.Slot, value: String?) { if (value != null) decoded[slot] = value }
        if (overwriteExisting || current.apiKey.isNullOrEmpty()) add(BackupCredentialRestore.Slot.API_KEY, key)
        if (overwriteExisting || current.manualOAuthToken.isNullOrEmpty()) add(BackupCredentialRestore.Slot.MANUAL_OAUTH_TOKEN, manual)
        if (importLogin) {
            add(BackupCredentialRestore.Slot.OAUTH_TOKEN, token)
            add(BackupCredentialRestore.Slot.OAUTH_EMAIL, email)
            add(BackupCredentialRestore.Slot.OAUTH_GCP_PROJECT, project)
        }
        val writes = linkedMapOf<BackupCredentialRestore.Slot, String>()
        BackupCredentialRestore.restore(decoded, object : BackupCredentialRestore.Store {
            override fun supports(slot: BackupCredentialRestore.Slot) = true
            override fun read(slot: BackupCredentialRestore.Slot) = local[slot]
            override fun write(slot: BackupCredentialRestore.Slot, value: String) { local[slot] = value; writes[slot] = value }
            override fun oauthNeedsReauth() = current.oauthNeedsReauth
        })
        return Plan(
            apiKey = writes[BackupCredentialRestore.Slot.API_KEY],
            manualOAuthToken = writes[BackupCredentialRestore.Slot.MANUAL_OAUTH_TOKEN],
            oauthToken = writes[BackupCredentialRestore.Slot.OAUTH_TOKEN],
            oauthEmail = writes[BackupCredentialRestore.Slot.OAUTH_EMAIL],
            oauthGcpProject = writes[BackupCredentialRestore.Slot.OAUTH_GCP_PROJECT],
            invalidFields = invalid,
        )
    }
}
