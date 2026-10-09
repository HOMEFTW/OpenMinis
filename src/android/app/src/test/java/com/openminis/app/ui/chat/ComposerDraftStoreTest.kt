package com.openminis.app.ui.chat

import com.openminis.app.util.MemorySharedPreferences
import org.junit.Assert.*
import org.junit.Test

class ComposerDraftStoreTest {
    @Test fun newChatDraftSurvivesStoreRecreationAndPromotion() {
        val prefs = MemorySharedPreferences()
        val draft = ComposerDraft("尚未发送的内容", listOf(
            DraftAttachment("a", "note.txt", "content://test/a", "text/plain", "FILE"),
        ))
        ComposerDraftStore(prefs).save("__new__test", draft)
        val reopened = ComposerDraftStore(MemorySharedPreferences(prefs.all))
        assertEquals(draft, reopened.load("__new__test"))
        assertTrue(reopened.list().containsKey("__new__test"))
        reopened.move("__new__test", "saved-session", draft)
        assertNull(reopened.load("__new__test"))
        assertEquals(draft, reopened.load("saved-session"))
    }

    @Test fun untouchedChatDoesNotCreateDraftButAttachmentOnlyDoes() {
        val store = ComposerDraftStore(MemorySharedPreferences())
        store.save("__new__empty", ComposerDraft("", emptyList()))
        assertTrue(store.list().isEmpty())
        store.save("__new__attachment", ComposerDraft("", listOf(
            DraftAttachment("a", "photo.png", "content://test/a", "image/png", "IMAGE"),
        )))
        assertTrue(store.has("__new__attachment"))
    }
}
