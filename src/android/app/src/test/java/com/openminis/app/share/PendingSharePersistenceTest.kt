package com.openminis.app.share

import android.content.SharedPreferences
import com.openminis.app.util.MemorySharedPreferences
import org.junit.Assert.*
import org.junit.Test

class PendingSharePersistenceTest {
    private fun share(text: String) = PendingShare(listOf(PendingShare.Item(PendingShare.Item.Kind.INLINE_TEXT, text)), System.currentTimeMillis())

    @Test fun `reading and recreating the producer does not acknowledge a pending share`() {
        val prefs = MemorySharedPreferences()
        val pending = share("not injected")
        SharedShareStore.savePendingShare(prefs, pending)
        assertEquals(pending, SharedShareStore.loadPendingShare(prefs))
        assertEquals(pending, SharedShareStore.loadPendingShare(MemorySharedPreferences(prefs.all)))
        SharedShareStore.acknowledgeConsumed(prefs, pending)
        assertNull(SharedShareStore.loadPendingShare(prefs))
    }

    @Test fun `acknowledging a snapshot retains sharing that arrived during injection`() {
        val prefs = MemorySharedPreferences()
        val first = share("first")
        SharedShareStore.savePendingShare(prefs, first)
        SharedShareStore.savePendingShare(prefs, share("second"))
        val remaining = SharedShareStore.acknowledgeConsumed(prefs, first)!!
        assertEquals(listOf("second"), remaining.items.map { it.value })
        assertEquals(remaining, SharedShareStore.loadPendingShare(MemorySharedPreferences(prefs.all)))
    }

    @Test fun `failed durable write is reported before accepting share`() {
        val base = MemorySharedPreferences()
        val prefs = object : SharedPreferences by base {
            override fun edit(): SharedPreferences.Editor {
                val editor = base.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun putString(key: String, value: String?): SharedPreferences.Editor {
                        editor.putString(key, value)
                        return this
                    }
                    override fun commit() = false
                }
            }
        }
        assertThrows(IllegalStateException::class.java) { SharedShareStore.savePendingShare(prefs, share("lost on disk")) }
        assertNull(SharedShareStore.loadPendingShare(base))
    }
}
