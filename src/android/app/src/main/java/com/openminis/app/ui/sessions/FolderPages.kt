package com.openminis.app.ui.sessions

/**
 * [T-folder-pages] An open folder shows its members 20 at a time, with a
 * "Show N more (M left)" row for the rest. Port of iOS 14bc208b1
 * (`ContentView.folderPage`).
 *
 * Before, an open folder listed every member, so a busy folder took the whole
 * sidebar and pushed every other folder and date bucket off screen.
 *
 * Pure, so the page maths is tested without Compose.
 */
internal object FolderPages {
    const val PAGE_SIZE = 20

    /**
     * Members shown and still hidden for [pages] pages of [total]. A page
     * count below 1 counts as the first page; a page size below 1 as 1.
     * 15 → 15/0, 20 → 20/0, 21 → 20/1, 45 → 20/25 → 40/5 → 45/0.
     */
    fun page(total: Int, pages: Int, pageSize: Int = PAGE_SIZE): Pair<Int, Int> {
        val all = maxOf(0, total)
        val shown = minOf(all.toLong(), maxOf(1, pages).toLong() * maxOf(1, pageSize)).toInt()
        return shown to (all - shown)
    }

    /** What the "Show N more" row offers: the next page, capped by what is left. */
    fun nextPageCount(hidden: Int, pageSize: Int = PAGE_SIZE): Int = minOf(maxOf(0, hidden), maxOf(1, pageSize))

    /**
     * [T-search-folder-pages] "Show more" on a folder in a search result set
     * (iOS e709a821e): every result folder is open, so any of them pages, and
     * the pages are kept apart from the normal ones - a search neither reads
     * nor resets those. Reset when a new result set arrives.
     */
    fun afterSearchShowMore(pages: Map<String, Int>, folderId: String, pagesShown: Int): Map<String, Int> =
        pages + (folderId to maxOf(1, pagesShown) + 1)
}
