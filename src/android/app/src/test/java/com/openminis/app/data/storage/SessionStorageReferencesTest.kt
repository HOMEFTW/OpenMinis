package com.openminis.app.data.storage

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class SessionStorageReferencesTest {
    @Test fun datedAndBranchMediaResolveToTheirRealOwners() {
        assertEquals("a", SessionStorageReferences.mediaOwner("2024/02/29/a/photo.png"))
        assertEquals("b", SessionStorageReferences.mediaOwner("branches/b/photo.png"))
        assertNull(SessionStorageReferences.mediaOwner("2023/02/29/a/photo.png"))
        assertNull(SessionStorageReferences.mediaOwner("legacy/photo.png"))
    }

    @Test fun nestedReferencesReserveSourcesAndUnknownLayoutsReserveAllMedia() {
        val parts = """[{"type":"toolResult","value":{"parts":[{"type":"mediaRef","value":{"relativePath":"branches/source/image.png"}},{"type":"mediaRef","value":{"relativePath":"legacy/image.png"}}]}}]"""
        assertEquals(setOf("source", SessionStorageReferences.ALL_MEDIA), SessionStorageReferences.idsForParts(parts))
    }

    @Test fun malformedDataAndTraversalCannotTurnIntoAnEmptyOwnerSet() {
        for (parts in listOf("not json", """[{"type":"mediaRef"}]""", """[{"type":"mediaRef","value":{"relativePath":"../outside"}}]""")) {
            assertTrue(runCatching { SessionStorageReferences.idsForParts(parts) }.isFailure)
        }
    }

    @Test fun draftAndFileReferencesProtectTheirActualSessionDirectories() {
        assertEquals(setOf("__new__draft"), SessionStorageReferences.idsForFiles(File("root/minis-sessions/__new__draft/attachments/file.bin")))
        assertEquals(setOf("source", "branch"), SessionStorageReferences.idsInText("file:///private/minis-sessions/source/attachments/a and file:///private/media/branches/branch/a"))
        assertEquals(setOf("draft"), SessionStorageReferences.idsForFiles(File("root/browser_tabs/draft.json")))
    }

    @Test fun escapedSlashesAndUnicodeInTextReferencesProtectTheirDecodedOwners() {
        val parts = """[{"type":"text","value":{"text":"\/private\/\u006dinis-sessions\/source\/a and \/private\/media\/branches\/branch\/a"}}]"""
        assertEquals(setOf("source", "branch"), SessionStorageReferences.idsForParts(parts))
    }

    @Test fun nestedStringifiedInputIsDecodedBeforeFindingPaths() {
        val parts = """[{"type":"toolUse","value":{"input":"{\"path\":\"\\/private\\/\\u006dinis-sessions\\/nested\\/a\"}"}}]"""
        assertEquals(setOf("nested"), SessionStorageReferences.idsForParts(parts))
        val broken = """[{"type":"toolUse","value":{"input":"{\"path\":\"\\u006dinis-sessions/nested/a\""}}]"""
        assertTrue(runCatching { SessionStorageReferences.idsForParts(broken) }.isFailure)
    }

    @Test fun ordinaryMarkdownOrCodeTextIsNotMistakenForDamagedStructuredInput() {
        val parts = """[{"type":"text","value":{"text":"[a link] and /private/minis-sessions/source/a; {unfinished code"}}]"""
        assertEquals(setOf("source"), SessionStorageReferences.idsForParts(parts))
    }

    @Test fun damagedInputUsesGlobalReservationsForWritingButStillFailsStrictCleanupReading() {
        val parts = """[{"type":"toolUse","value":{"input":"{\"command\":"}}]"""
        assertEquals(setOf(SessionStorageReferences.ALL_WORKSPACES, SessionStorageReferences.ALL_MEDIA),
            SessionStorageReferences.idsForWrite(parts))
        assertTrue(runCatching { SessionStorageReferences.idsForParts(parts) }.isFailure)
    }
}
