package com.openminis.app.ui.sessions

import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.db.FolderEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-search-folder-expand] [T-search-folder-pages] [T-search-folder-collapse]
 * [T-folder-pages] Search results grouped by folder, and folders paged 20 at a
 * time, on this fork's accordion (a persisted set of collapsed folders).
 * Vectors from upstream SearchFolderExpandTest / SearchFolderPagesTest /
 * SearchFolderCollapseTest / FolderPagesTest.
 */
class SearchFolderGroupingTest {

    private fun folder(id: String, name: String = "F-$id") = FolderEntity(id = id, name = name, createdAt = 0, updatedAt = 0)
    private fun s(id: String, folder: String?, at: Long, pinned: Boolean = false) =
        ChatSessionEntity(id = id, modelId = "m", createdAt = 0, updatedAt = at, folderId = folder, pinnedAt = if (pinned) 1L else null)
    private fun matches(fid: String, n: Int, from: Long = 10_000L) = (0 until n).map { s("$fid-$it", fid, from - it) }

    // ── Page maths ─────────────────────────────────────────────────────────

    @Test fun `pages of 15, 20, 21 and 45`() {
        assertEquals(15 to 0, FolderPages.page(15, 1))
        assertEquals(20 to 0, FolderPages.page(20, 1))
        assertEquals(20 to 1, FolderPages.page(21, 1))
        assertEquals(20 to 25, FolderPages.page(45, 1))
        assertEquals(40 to 5, FolderPages.page(45, 2))
        assertEquals(45 to 0, FolderPages.page(45, 99))
        assertEquals(20 to 25, FolderPages.page(45, 0))
        assertEquals(0 to 0, FolderPages.page(-3, 1))
        assertEquals(20, FolderPages.nextPageCount(25))
        assertEquals(5, FolderPages.nextPageCount(5))
    }

    // ── Folder names ───────────────────────────────────────────────────────

    @Test fun `folder names match any keyword, case-insensitively`() {
        val folders = listOf(folder("a", "Work Notes"), folder("b", "Travel"), folder("c", "Recipes"))
        assertEquals(setOf("a"), SearchFolders.matchedFolderIds(folders, "work"))
        assertEquals(setOf("a", "b"), SearchFolders.matchedFolderIds(folders, "NOTES travel"))
        assertEquals(emptySet<String>(), SearchFolders.matchedFolderIds(folders, "   "))
    }

    @Test fun `a name-matched folder brings all its members, newest first`() {
        val all = listOf(s("x1", "a", 50), s("hit", null, 40), s("x2", "a", 30), s("other", "b", 20))
        val results = listOf(s("hit", null, 40))
        assertEquals(listOf("x1", "hit", "x2"), SearchFolders.withFolderMembers(results, all, setOf("a")).map { it.id })
        assertTrue(SearchFolders.withFolderMembers(results, all, emptySet()) === results)
    }

    // ── Normal list ────────────────────────────────────────────────────────

    @Test fun `an open folder shows its first 20, stored pages show more, select mode shows all`() {
        fun block(pages: Map<String, Int> = emptyMap(), selecting: Boolean = false) =
            partitionByFolder(matches("a", 45), listOf(folder("a")), emptySet(), folderPages = pages, showAllMembers = selecting).first.single()
        val b = block()
        assertEquals((0 until 20).map { "a-$it" }, b.ids)
        assertEquals(25, b.hiddenCount)
        assertEquals(45, b.totalCount)
        assertEquals(40 to 5, block(mapOf("a" to 2)).let { it.ids.size to it.hiddenCount })
        assertEquals(45 to 0, block(selecting = true).let { it.ids.size to it.hiddenCount })
    }

    @Test fun `outside a result set the accordion and empty folders are unchanged`() {
        val folders = listOf(folder("a"), folder("b"), folder("empty"))
        val sessions = listOf(s("a1", "a", 30), s("b1", "b", 20))
        val (blocks, _) = partitionByFolder(sessions, folders, collapsedIds = setOf("a"))
        assertEquals(listOf("a", "b", "empty"), blocks.map { it.folder.id })
        assertEquals(listOf(true, false, true), blocks.map { it.isCollapsed })
    }

    // ── Result set ─────────────────────────────────────────────────────────

    @Test fun `in a result set every folder is open with its matches, with no accordion`() {
        val folders = listOf(folder("a"), folder("b"))
        val results = listOf(s("a1", "a", 30), s("b1", "b", 20), s("u", null, 10))
        val (blocks, ungrouped) = partitionByFolder(
            results, folders, collapsedIds = setOf("a", "b"), searchResultsActive = true,
        )
        assertTrue("the saved collapsed set is not read", blocks.none { it.isCollapsed })
        assertEquals(listOf(listOf("a1"), listOf("b1")), blocks.map { it.ids })
        assertEquals(listOf("u"), ungrouped.map { it.id })
    }

    @Test fun `an empty folder shows in a result set only when its name matched`() {
        val folders = listOf(folder("hit", "Hit name"), folder("other"))
        val (blocks, _) = partitionByFolder(
            listOf(s("u", null, 10)), folders, emptySet(),
            searchResultsActive = true, searchMatchedFolderIds = setOf("hit"),
        )
        assertEquals(listOf("hit"), blocks.map { it.folder.id })
    }

    @Test fun `result folders page on their own pages until nothing is left`() {
        fun block(n: Int, pages: Map<String, Int>) = partitionByFolder(
            matches("a", n), listOf(folder("a")), emptySet(),
            folderPages = mapOf("a" to 3), searchResultsActive = true, searchFolderPages = pages,
        ).first.single()
        for ((n, taps) in listOf(15 to 0, 21 to 1, 45 to 2, 101 to 5)) {
            var pages = emptyMap<String, Int>()
            var b = block(n, pages)
            assertEquals("normal pages are not read", minOf(n, 20), b.ids.size)
            var count = 0
            while (b.hiddenCount > 0) {
                pages = FolderPages.afterSearchShowMore(pages, "a", b.pagesShown)
                b = block(n, pages)
                count++
            }
            assertEquals("$n", taps, count)
            assertEquals(n, b.ids.size)
        }
    }

    @Test fun `a folded result shows no rows, the others stay open`() {
        val (blocks, _) = partitionByFolder(
            matches("a", 45) + matches("b", 3, from = 1_000L), listOf(folder("a"), folder("b")), emptySet(),
            searchResultsActive = true, searchFolderPages = mapOf("a" to 2), searchCollapsedFolderIds = setOf("a"),
        )
        val byId = blocks.associateBy { it.folder.id }
        assertTrue(byId.getValue("a").isCollapsed)
        assertTrue(byId.getValue("a").ids.isEmpty())
        assertEquals(0, byId.getValue("a").hiddenCount)
        assertEquals(45, byId.getValue("a").totalCount)
        assertFalse(byId.getValue("b").isCollapsed)
        assertEquals(3, byId.getValue("b").ids.size)
    }
}
