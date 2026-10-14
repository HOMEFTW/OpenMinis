package com.openminis.app.ui.chat

import com.openminis.app.data.repository.MCPRepository.MCPServerConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SessionMcpPresentationTest {
    @Test
    fun endpointHidesUserInfoPathQueryAndFragmentCredentials() {
        assertEquals("https://api.example.com:8443/…", sessionMcpEndpoint("https://user:password@api.example.com:8443/secret-path/mcp?custom=private#private"))
        assertEquals("https://api.example.com/…", sessionMcpEndpoint("https://api.example.com?unknown=private"))
        assertEquals("https://api.example.com", sessionMcpEndpoint("https://api.example.com/"))
    }

    @Test
    fun endpointPreservesLocalIpv6OriginAndRejectsMalformedOrNonHttpValues() {
        assertEquals("http://[::1]:3000/…", sessionMcpEndpoint("http://[::1]:3000/mcp"))
        for (url in listOf(null, "", "not a URL private", "https:private", "file:///private", "https://private@/")) {
            assertNull(sessionMcpEndpoint(url))
        }
    }

    @Test
    fun commandShowsOnlyExecutableBasenameAndHidesInlineScripts() {
        assertEquals("npx", sessionMcpCommand("/usr/local/bin/npx"))
        assertEquals("python.exe", sessionMcpCommand("C:\\tools\\python.exe"))
        for (command in listOf(null, "", "bash -lc TOKEN=private", "node;export_TOKEN=private", "\$\$COMMAND")) {
            assertNull(sessionMcpCommand(command))
        }
    }

    @Test
    fun stdioSummaryDoesNotIncludeArgumentsOrEnvironmentValues() {
        val server = MCPServerConfig(id = "private", command = "npx", args = listOf("--password", "arg-secret"), env = mapOf("TOKEN" to "env-secret"))
        assertEquals("STDIO · npx", sessionMcpSummary(server))
        assertFalse(sessionMcpSummary(server).contains("arg-secret"))
        assertFalse(sessionMcpSummary(server).contains("env-secret"))
    }

    @Test
    fun httpSummaryNeverFallsBackToRawCredentialBearingUrl() {
        assertEquals("https://host.example/…", sessionMcpSummary(MCPServerConfig(id = "http", url = "https://user:password@host.example/path?private=value")))
        assertEquals("HTTP", sessionMcpSummary(MCPServerConfig(id = "invalid", url = "not-a-url private")))
    }

    @Test
    fun noteRedactsKnownHeadersEnvironmentAndArguments() {
        val server = MCPServerConfig(id = "server", headers = mapOf("X-Key" to "header-secret"), env = mapOf("TOKEN" to "env-secret"), args = listOf("arg-secret"))
        val note = sessionMcpDisplayText(server, "Connect with header-secret env-secret arg-secret Authorization: Bearer other-secret")
        for (secret in listOf("header-secret", "env-secret", "arg-secret", "other-secret")) {
            assertFalse(note.contains(secret))
        }
    }

    @Test
    fun noteDoesNotExposeShortConfiguredSecrets() {
        val server = MCPServerConfig(id = "server", env = mapOf("PIN" to "789"))
        assertFalse(sessionMcpDisplayText(server, "Use 789 to connect").contains("789"))
    }
}
