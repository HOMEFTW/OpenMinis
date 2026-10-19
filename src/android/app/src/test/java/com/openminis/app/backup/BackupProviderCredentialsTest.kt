package com.openminis.app.backup

import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class BackupProviderCredentialsTest {
    private fun encoded(value: String) = Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
    private val token = "{\"access_token\":\"backup-token\",\"id_token\":\"identity\",\"expire_at\":123}"
    private fun backup(key: String? = "backup-key", oauth: String? = token) = BackupSecrets.ProviderSecret(
        instanceId = "p", apiKey = key?.let(::encoded), oauthToken = oauth?.let(::encoded),
        oauthEmail = encoded("backup@example.com"), oauthGcpProject = encoded("backup-project"),
    )

    @Test fun `backup API key overrides device key while active OAuth identity is kept`() {
        val plan = BackupProviderCredentials.plan(
            BackupProviderCredentials.Current(apiKey = "device-key", oauthToken = "{\"access_token\":\"active\"}", oauthEmail = "device@example.com"),
            backup(),
        )
        assertEquals("backup-key", plan.apiKey)
        assertNull(plan.oauthToken)
        assertNull(plan.oauthEmail)
        assertNull(plan.oauthGcpProject)
        assertTrue(plan.hasChanges)
    }

    @Test fun `rejected OAuth sign-in is restored without dropping token bundle metadata`() {
        val plan = BackupProviderCredentials.plan(
            BackupProviderCredentials.Current(oauthToken = "{\"access_token\":\"rejected\"}", oauthNeedsReauth = true), backup(),
        )
        assertEquals(token, plan.oauthToken)
        assertEquals("backup@example.com", plan.oauthEmail)
        assertEquals("backup-project", plan.oauthGcpProject)
    }

    @Test fun `missing backup fields preserve device only credentials`() {
        val plan = BackupProviderCredentials.plan(
            BackupProviderCredentials.Current(apiKey = "device-key"), BackupSecrets.ProviderSecret("p"),
        )
        assertFalse(plan.hasChanges)
        assertTrue(plan.invalidFields.isEmpty())
    }

    @Test fun `equal credentials do not count as restored`() {
        val plan = BackupProviderCredentials.plan(
            BackupProviderCredentials.Current(apiKey = "backup-key", oauthToken = token, oauthEmail = "backup@example.com"), backup(),
        )
        assertFalse(plan.hasChanges)
    }

    @Test fun `invalid base64 and invalid OAuth JSON cannot replace working values`() {
        val plan = BackupProviderCredentials.plan(
            BackupProviderCredentials.Current(apiKey = "device-key", oauthNeedsReauth = true),
            BackupSecrets.ProviderSecret("p", apiKey = "%%%invalid%%%", oauthToken = encoded("not JSON")),
        )
        assertFalse(plan.hasChanges)
        assertEquals(listOf("apiKey", "oauthToken"), plan.invalidFields)
    }

    @Test fun `legacy keep existing mode and manual bearer accounts remain protected`() {
        val plan = BackupProviderCredentials.plan(
            BackupProviderCredentials.Current(apiKey = "device-key", manualOAuthToken = "active-manual"), backup(),
            overwriteExisting = false,
        )
        assertFalse(plan.hasChanges)
    }

    @Test fun `empty device imports full backup and accepts wrapped base64 UTF8 keys`() {
        val secret = backup(key = "密钥/+value").let { it.copy(apiKey = it.apiKey!!.chunked(4).joinToString("\n")) }
        val plan = BackupProviderCredentials.plan(BackupProviderCredentials.Current(), secret)
        assertEquals("密钥/+value", plan.apiKey)
        assertEquals(token, plan.oauthToken)
        assertTrue(plan.hasChanges)
        assertTrue(plan.invalidFields.isEmpty())
    }
}
