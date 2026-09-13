package com.openminis.app.sandbox

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionPathResolverTest {
    @get:Rule val folder = TemporaryFolder()
    @Test fun sameLinuxPathReadsDifferentSessionFiles() {
        val path = "/var/minis/workspace/input.json"
        val a = SessionPathResolver.resolve(folder.root, path, "session-A")!!
        val b = SessionPathResolver.resolve(folder.root, path, "session-B")!!
        a.parentFile.mkdirs(); b.parentFile.mkdirs()
        a.writeText("A"); b.writeText("B")
        assertEquals("A", SessionPathResolver.resolve(folder.root, path, "session-A")!!.readText())
        assertEquals("B", SessionPathResolver.resolve(folder.root, path, "session-B")!!.readText())
    }
    @Test fun sharedAndUnscopedPathsStayInGlobalResolver() {
        assertNull(SessionPathResolver.resolve(folder.root, "/var/minis/shared/input.json", "A"))
        assertNull(SessionPathResolver.resolve(folder.root, "/var/minis/workspace/input.json", null))
    }
    @Test fun everyPrivateScopeIsIsolated() {
        for (scope in listOf("workspace", "attachments", "offloads", "browser")) {
            assertTrue(SessionPathResolver.resolve(folder.root, "/var/minis/$scope/ref.png", "A")!!
                .path.contains("minis-sessions" + java.io.File.separator + "A"))
        }
    }
    @Test(expected = IllegalArgumentException::class) fun traversalFailsClosed() {
        SessionPathResolver.resolve(folder.root, "/var/minis/workspace/../../B/workspace/input.json", "A")
    }
    @Test(expected = IllegalArgumentException::class) fun invalidSessionFailsClosed() {
        SessionPathResolver.resolve(folder.root, "/var/minis/workspace/a", "../B")
    }
}
