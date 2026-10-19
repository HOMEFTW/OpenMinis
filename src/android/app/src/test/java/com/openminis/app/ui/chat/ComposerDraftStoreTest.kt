package com.openminis.app.ui.chat

import com.openminis.app.util.MemorySharedPreferences
import org.junit.Assert.*
import org.junit.Test

class ComposerDraftStoreTest {
    @Test fun latestUnsentNewChatDraftIsCarriedOverWithItsAttachments() {
        var time = 1_000_000L
        val store = ComposerDraftStore(MemorySharedPreferences()) { time }
        store.save("__new__old", ComposerDraft("old", emptyList()))
        time += 1_000
        val latest = ComposerDraft("latest", listOf(DraftAttachment("a", "note.txt", "content://test/note", "text/plain", "DOCUMENT")))
        store.save("__new__latest", latest)
        time += 14 * 60 * 1000
        assertEquals(latest, store.takeRecentNewDraft("__new__next"))
        assertNull(store.load("__new__latest"))
        assertEquals(latest, store.load("__new__next"))
        assertNull(store.takeRecentNewDraft("__new__next"))
    }

    @Test fun expiredFutureAndExistingChatDraftsAreNotCarriedIntoANewChat() {
        var time = 2_000_000L
        val store = ComposerDraftStore(MemorySharedPreferences()) { time }
        store.save("saved-chat", ComposerDraft("existing", emptyList()))
        store.save("__new__expired", ComposerDraft("expired", emptyList()))
        time += 15 * 60 * 1000 + 10_001
        assertNull(store.takeRecentNewDraft("__new__next"))
        store.save("__new__future", ComposerDraft("future", emptyList()))
        time -= 1_000
        assertNull(store.takeRecentNewDraft("__new__next"))
        assertTrue(store.has("saved-chat"))
    }

    @Test fun confirmedSharedDraftSurvivesBeforeThePendingShareIsAcknowledged() {
        val prefs = MemorySharedPreferences()
        val draft = ComposerDraft("Shared text", listOf(
            DraftAttachment("shared", "photo.png", "file:///private/share_inbound/photo.png", "image/png", "IMAGE"),
        ))
        ComposerDraftStore(prefs).saveConfirmed("__new__shared", draft)
        assertEquals(draft, ComposerDraftStore(MemorySharedPreferences(prefs.all)).load("__new__shared"))
    }

    @Test fun failedSharedDraftPersistenceIsReportedBeforeConsumption() {
        val prefs = MemorySharedPreferences()
        val failing = object : android.content.SharedPreferences by prefs {
            override fun edit(): android.content.SharedPreferences.Editor {
                val editor = prefs.edit()
                return object : android.content.SharedPreferences.Editor by editor {
                    override fun commit() = false
                }
            }
        }
        assertTrue(runCatching { ComposerDraftStore(failing).saveConfirmed("share", ComposerDraft("text", emptyList())) }
            .exceptionOrNull() is IllegalStateException)
        assertNull(ComposerDraftStore(prefs).load("share"))
    }

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
