package com.openminis.app.ui.chat

import com.openminis.app.ui.navigation.LaunchSessionHistory
import com.openminis.app.share.markdownHeading
import com.openminis.app.backup.backupIncludesCredentials
import com.openminis.app.backup.BackupCategory
import org.junit.Assert.*
import org.junit.Test

class DailyUseTest {
    @Test fun draftMigrationUsesCurrentSnapshotAndDoesNotResurrectSentText() {
        val store = ComposerDraftStore(com.openminis.app.util.MemorySharedPreferences())
        store.save("draft", ComposerDraft("already sent", emptyList()))
        store.move("draft", "session", ComposerDraft("", emptyList()))
        assertNull(store.load("draft"))
        assertNull(store.load("session"))
        store.save("draft", ComposerDraft("stale", emptyList()))
        store.move("draft", "session", ComposerDraft("latest", emptyList()))
        assertEquals("latest", store.load("session")?.text)
        assertNull(store.load("draft"))
    }
    @Test fun savingAndClearingOneDraftDoesNotChangeAnother() {
        val store = ComposerDraftStore(com.openminis.app.util.MemorySharedPreferences())
        store.save("a", ComposerDraft("first", emptyList()))
        store.save("b", ComposerDraft("second", emptyList()))
        store.save("a", ComposerDraft("", emptyList()))
        assertEquals(setOf("b"), store.list().keys)
        assertEquals("second", store.load("b")?.text)
    }

    @Test fun branchMediaRemainsReadableAfterSourceRemoval() {
        val root = java.nio.file.Files.createTempDirectory("branch-media").toFile()
        try {
            val source = java.io.File(root, "source/a.txt").apply { parentFile!!.mkdirs(); writeText("attachment") }
            val copier = EditBranchMedia(root, "branch")
            val parts = """[{"type":"mediaRef","value":{"relativePath":"source/a.txt"}}]"""
            val rewritten = org.json.JSONArray(copier.rewrite(parts)).getJSONObject(0).getJSONObject("value").getString("relativePath")
            source.delete()
            assertEquals("attachment", java.io.File(root, rewritten).readText())
            assertTrue(rewritten.startsWith("branches/branch/"))
        } finally { root.deleteRecursively() }
    }
    @Test fun branchMediaRejectsTraversal() {
        val root = java.nio.file.Files.createTempDirectory("branch-media").toFile()
        try {
            try {
                EditBranchMedia(root, "branch").rewrite("""[{"type":"mediaRef","value":{"relativePath":"../secret"}}]""")
                fail("Expected path rejection")
            } catch (_: IllegalArgumentException) { }
        } finally { root.deleteRecursively() }
    }

    @Test fun lastViewedWinsOverBackgroundUpdatesAndDeletedSessionsFallBack() {
        val sessions = listOf("background" to 5000L, "viewed" to 1000L)
        assertEquals("viewed" to 4000L, LaunchSessionHistory.candidate("viewed", 4000L, sessions) { false })
        assertEquals("background" to 5000L, LaunchSessionHistory.candidate("deleted", 4000L, sessions) { false })
        assertNull(LaunchSessionHistory.candidate(null, 0, emptyList()) { false })
    }
    @Test fun onlySavedNonemptyDraftCanBeLaunchCandidate() {
        assertEquals("__new__draft" to 2000L, LaunchSessionHistory.candidate("__new__draft", 2000L, emptyList()) { true })
        assertNull(LaunchSessionHistory.candidate("__new__empty", 2000L, emptyList()) { false })
    }

    @Test fun draftRoundTripPreservesWhitespaceAttachmentsAndEditTarget() {
        val draft = ComposerDraft("  你好\n\n", listOf(DraftAttachment("a", "a.png", "content://files/a", "image/png", "IMAGE")), "message")
        assertEquals(draft, ComposerDraft.decode(draft.encode()))
    }
    @Test fun corruptedDraftDoesNotCrashRestoration() {
        assertNull(ComposerDraft.decode("not json"))
        assertNull(ComposerDraft.decode("{}"))
        assertNull(ComposerDraft.decode(null))
    }
    @Test fun emptyDraftAndLiteralNullTextRoundTrip() {
        assertEquals(ComposerDraft("null", emptyList()), ComposerDraft.decode(ComposerDraft("null", emptyList()).encode()))
        assertEquals(ComposerDraft("", emptyList()), ComposerDraft.decode(ComposerDraft("", emptyList()).encode()))
    }
    @Test fun autoLaunchBoundaryAndClockChanges() {
        assertTrue(LaunchSessionHistory.isRecent(1000, 900999))
        assertFalse(LaunchSessionHistory.isRecent(1000, 901000))
        assertFalse(LaunchSessionHistory.isRecent(2000, 1000))
        assertFalse(LaunchSessionHistory.isRecent(0, 100))
    }
    @Test fun quotaTakesPrecedenceOver429() {
        assertEquals(FailureAdvice.QUOTA, FailureAdvice.classify("HTTP 429 insufficient_quota"))
        assertEquals(FailureAdvice.RATE, FailureAdvice.classify("HTTP 429 rate_limit_exceeded"))
    }
    @Test fun failureAdviceDistinguishesRemedies() {
        assertEquals(FailureAdvice.AUTH, FailureAdvice.classify("401 Invalid API key"))
        assertEquals(FailureAdvice.NETWORK, FailureAdvice.classify("UnknownHostException"))
        assertEquals(FailureAdvice.TIMEOUT, FailureAdvice.classify("connection timed out"))
        assertEquals(FailureAdvice.OTHER, FailureAdvice.classify("unexpected response"))
    }
    @Test fun markdownTitleCannotInsertExtraHeadingOrRawHtml() {
        val result = markdownHeading("Hello\n# title <script>")
        assertFalse(result.contains("\n"))
        assertFalse(result.contains("<script>"))
        assertTrue(result.contains("\\#"))
    }
    @Test fun credentialPreviewIncludesAllSecretCategories() {
        assertFalse(backupIncludesCredentials(setOf(BackupCategory.CHATS, BackupCategory.MEMORY)))
        listOf(BackupCategory.PROVIDERS, BackupCategory.MCP_SERVERS, BackupCategory.ENVIRONMENT_VARIABLES).forEach {
            assertTrue(backupIncludesCredentials(setOf(it)))
        }
    }
}
