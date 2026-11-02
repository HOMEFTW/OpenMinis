package com.openminis.app.ui.sessions

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [ISS-038] Duplicated sessions get distinct, localized titles (port of iOS 03da252a1). */
class SessionDuplicateTitleTest {

    private val known = listOf("Copy", "副本")
    private fun make(src: String?, existing: List<String> = emptyList(), word: String = "Copy") =
        SessionDuplicateTitle.make(src, existing, word, known, "Untitled")

    @Test
    fun `copies are numbered, never stacked`() {
        assertEquals("Foo (Copy)", make("Foo"))
        assertEquals("Foo (Copy 2)", make("Foo", listOf("Foo", "Foo (Copy)")))
        assertEquals("Foo (Copy 3)", make("Foo (Copy)", listOf("Foo", "Foo (Copy)", "Foo (Copy 2)")))
        assertEquals("copying a copy uses the original base", "Foo (Copy 2)", make("Foo (Copy)", listOf("Foo (Copy)")))
    }

    @Test
    fun `the smallest free number is reused`() {
        assertEquals("Foo (Copy 2)", make("Foo", listOf("Foo (Copy)", "Foo (Copy 3)")))
    }

    @Test
    fun `localized word, and copies from another language are recognized`() {
        assertEquals("Foo (副本)", make("Foo", word = "副本"))
        assertEquals("Foo (副本 2)", make("Foo (Copy)", listOf("Foo (Copy)"), word = "副本"))
        assertEquals("full-width parentheses", "Foo (副本 2)", make("Foo（副本）", listOf("Foo（副本）"), word = "副本"))
    }

    @Test
    fun `text that is not a copy suffix stays part of the title`() {
        for (t in listOf("Foo (draft)", "Foo (Copy) notes", "Foo (Copy 2x)", "Foo (Copy 0)", "Foo (Copy 1)", "Foo (Copy 02)")) {
            assertNull(t, SessionDuplicateTitle.parse(t, known))
        }
        assertEquals("Foo (draft) (Copy)", make("Foo (draft)"))
        assertEquals("a bare suffix keeps itself as the base", "(Copy) (Copy)", make("(Copy)"))
        assertEquals("case-insensitive", "Foo" to 4, SessionDuplicateTitle.parse("Foo (copy 4)", known))
    }

    @Test
    fun `a blank title uses Untitled`() {
        assertEquals("Untitled (Copy)", make(null))
        assertEquals("Untitled (Copy 2)", make("  ", listOf("Untitled (Copy)")))
    }

    @Test
    fun `duplicateSession preserves the fork resource copier and releases reservations`() {
        val src = ProductionSources.read("ui/sessions/SessionListViewModel.kt")
        val body = src.substringAfter("fun duplicateSession(id: String) {").substringBefore("private fun allCopySuffixWords")
        assertTrue(body.contains("SessionDuplicateTitle.make("))
        assertTrue(body.contains("existingTitles = existingTitles + reservedDuplicateTitles,"))
        assertTrue(body.contains("reservedDuplicateTitles.add(title)"))
        assertTrue(body.contains("SessionForkManager(chatRepository, filesDir = context.filesDir)"))
        assertTrue(body.contains("chatRepository.updateSessionTitle(copiedId, title)"))
        assertTrue(body.contains("finally {"))
        assertTrue(body.contains("reservedDuplicateTitles.remove(it)"))
        assertTrue(body.contains("withContext(kotlinx.coroutines.NonCancellable)"))
        assertFalse(body.contains("chatRepository.appendMessage("))
    }

    @Test
    fun `only one suffix is stripped and other bases do not occupy a number`() {
        assertEquals("Foo (Copy) (Copy 2)", make("Foo (Copy) (Copy)", listOf("Foo (Copy) (Copy)")))
        assertEquals("Foo (Copy)", make("Foo", listOf("Food (Copy)", "foo (Copy)")))
        assertNull(SessionDuplicateTitle.parse("Foo (Copy 9999999999)", known))
    }

    @Test
    fun `copies in all translated suffixes share the same sequence`() {
        val words = listOf("Copy", "副本", "Kopie", "Copia", "Másolat", "کپی")
        assertEquals(
            "Foo (副本 3)",
            SessionDuplicateTitle.make("Foo (Kopie 2)", listOf("Foo (Copia)", "Foo (Kopie 2)", "Foo (کپی 4)"), "副本", words, "Untitled"),
        )
    }
}
