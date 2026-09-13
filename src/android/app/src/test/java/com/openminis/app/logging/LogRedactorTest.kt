package com.openminis.app.logging

import com.openminis.app.util.MemorySharedPreferences
import com.openminis.app.util.SecretTrackingSharedPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogRedactorTest {

    @Test
    fun `redacts authorization cookie and signed url values`() {
        val input = """
            GET https://storage.example.test/file?X-Amz-Signature=synthetic-signature&part=1
            Authorization: Bearer synthetic-bearer
            Cookie: session=synthetic-cookie; theme=dark
        """.trimIndent()

        val output = LogRedactor.redact(input)

        assertFalse(output.contains("synthetic-signature"))
        assertFalse(output.contains("synthetic-bearer"))
        assertFalse(output.contains("synthetic-cookie"))
        assertTrue(output.contains("X-Amz-Signature=<redacted>"))
        assertTrue(output.contains("Authorization: Bearer <redacted>"))
        assertTrue(output.contains("Cookie: <redacted>"))
    }

    @Test
    fun `redacts quoted json headers and basic authorization`() {
        val input = """
            {"Authorization":"Bearer synthetic-json-bearer", "api_key":"synthetic-json-key"}
            Authorization: Basic synthetic-basic-credential
        """.trimIndent()

        val output = LogRedactor.redact(input)

        assertFalse(output.contains("synthetic-json-bearer"))
        assertFalse(output.contains("synthetic-json-key"))
        assertFalse(output.contains("synthetic-basic-credential"))
        assertTrue(output.contains("\"Authorization\":\"Bearer <redacted>\""))
        assertTrue(output.contains("\"api_key\":\"<redacted>\""))
        assertTrue(output.contains("Authorization: Basic <redacted>"))
    }

    @Test
    fun `redacts quoted shell headers and keeps following lines`() {
        val input = """curl -H "Authorization: Bearer synthetic-shell-token" \\
-H 'Cookie: session=synthetic-shell-cookie'
--verbose"""

        val output = LogRedactor.redact(input)

        assertFalse(output.contains("synthetic-shell-token"))
        assertFalse(output.contains("synthetic-shell-cookie"))
        assertTrue(output.contains("Authorization: Bearer <redacted>"))
        assertTrue(output.contains("Cookie: <redacted>"))
        assertTrue(output.endsWith("--verbose"))
    }

    @Test
    fun `replaces registered known secrets without loading preferences`() {
        LogRedactor.clearRegisteredSecrets()
        LogRedactor.registerKnownSecret("synthetic-api-key")
        try {
            val output = LogRedactor.redact("command --api-key synthetic-api-key --name demo")
            assertFalse(output.contains("synthetic-api-key"))
            assertTrue(output.contains("--api-key <redacted>"))
            assertTrue(output.contains("--name demo"))
        } finally {
            LogRedactor.clearRegisteredSecrets()
        }
    }

    @Test
    fun `credential preference writes register values for later command redaction`() {
        LogRedactor.clearRegisteredSecrets()
        val prefs = SecretTrackingSharedPreferences(MemorySharedPreferences())
        prefs.edit()
            .putBoolean("enabled", true)
            .putString("api_key", "synthetic-stored-key")
            .apply()
        try {
            assertFalse(
                LogRedactor.redact("--header X-Debug: synthetic-stored-key")
                    .contains("synthetic-stored-key"),
            )
        } finally {
            LogRedactor.clearRegisteredSecrets()
        }
    }

    @Test
    fun `credential preference writes register tokens inside oauth json`() {
        LogRedactor.clearRegisteredSecrets()
        val prefs = SecretTrackingSharedPreferences(MemorySharedPreferences())
        prefs.edit().putString(
            "oauth_tokens_instance",
            "{\"access_token\":\"synthetic-access-token\",\"refresh_token\":\"synthetic-refresh-token\"}",
        ).apply()
        try {
            val output = LogRedactor.redact(
                "Authorization: Bearer synthetic-access-token refresh=synthetic-refresh-token",
            )
            assertFalse(output.contains("synthetic-access-token"))
            assertFalse(output.contains("synthetic-refresh-token"))
        } finally {
            LogRedactor.clearRegisteredSecrets()
        }
    }

    @Test
    fun `supplier recursion is guarded and supplier failures do not expose input`() {
        LogRedactor.setKnownSecretSupplier {
            LogRedactor.redact("nested synthetic")
            error("synthetic supplier failure")
        }
        try {
            val output = LogRedactor.redact("safe message synthetic")
            assertTrue(output.contains("safe message synthetic"))
        } finally {
            LogRedactor.setKnownSecretSupplier(null)
        }
    }
}
