package com.openminis.app.deeplink

import org.junit.Assert.*
import org.junit.Test

class SessionLinkTest {
    @Test fun `copied links and raw ids resolve to the same local conversation`() {
        val id = "01a1534d-3876-7b40-b2d6-5e6e33bb31d8"
        assertEquals("minis://session/$id", SessionLink.create(id))
        for (reference in listOf(id, "minis://session/$id", "  minis://session/$id  ",
            "<minis://session/$id>", "[原对话](minis://session/$id)", "minis://session/$id/")) {
            assertEquals(reference, id, SessionLink.parse(reference))
        }
    }

    @Test fun `links preserve encoded opaque ids including plus unicode and percent`() {
        for (id in listOf("session+1", "中文 会话", "literal%2F", "session?x#y")) {
            assertEquals(id, SessionLink.parse(SessionLink.create(id)))
        }
    }

    @Test fun `resource links foreign apps and ambiguous or malformed targets are rejected`() {
        for (reference in listOf("", " ", ".", "..", "minis://session/", "minis://workspace/id",
            "codex://threads/abc", "https://example.com/session/abc", "minis://session/id/file.html",
            "minis://session/id?send=yes", "minis://session/id#message", "minis://user@session/id",
            "minis://session:42/id", "minis://session/%2Froot", "minis://session/id%2F", "minis://session/%5Croot",
            "minis://session/%00", "minis://session/%ZZ", "a/b", "a\\b")) {
            assertNull(reference, SessionLink.parse(reference))
        }
    }
}
