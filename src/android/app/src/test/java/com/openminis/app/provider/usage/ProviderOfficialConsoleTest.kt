package com.openminis.app.provider.usage

import org.junit.Assert.*
import org.junit.Test

class ProviderOfficialConsoleTest {
    @Test fun `known provider hosts lead to verified constant console URLs`() {
        assertEquals("https://platform.minimaxi.com/console/usage", officialProviderConsole("https://api.minimaxi.com/anthropic/v1"))
        assertEquals("https://platform.minimax.io/user-center/payment/token-plan", officialProviderConsole("https://api.minimax.io/v1"))
        assertEquals("https://opencode.ai/console", officialProviderConsole("https://opencode.ai/zen/go/v1"))
        assertEquals("https://opencode.ai/console", officialProviderConsole("https://OPENCODE.AI/zen/v1?ignored=yes"))
    }

    @Test fun `custom endpoints spoofed hosts and arbitrary labels are not console URLs`() {
        for (url in listOf(null, "", "https://relay.example/v1", "https://opencode.ai.attacker.example/v1",
            "https://api.minimax.io.attacker.example/v1", "https://opencode.ai@attacker.example/v1",
            "https://attacker.example/?console=https://opencode.ai", "opencode.ai/zen/v1", "not a URL")) {
            assertNull(officialProviderConsole(url))
        }
    }

    @Test fun `API paths query parameters and credentials never reach the external console`() {
        assertEquals("https://opencode.ai/console", officialProviderConsole("https://user:secret@opencode.ai/private?key=test#fragment"))
        assertEquals("https://platform.minimaxi.com/console/usage", officialProviderConsole("https://api.minimaxi.com/custom?token=test"))
    }
}
