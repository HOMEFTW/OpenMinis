package com.openminis.app.backup

import com.openminis.app.backup.remote.RcloneRemoteStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RcloneRemoteEditTest {
    private val original = RcloneRemoteStore.Remote(
        name = "nas", backend = "sftp", params = mapOf("host" to "old", "user" to "a"),
        path = "/old", createdAt = 123, enabled = false,
    )

    @Test fun `editing changes connection name and absolute path without re-enabling the server`() {
        val edited = RcloneRemoteStore.editedRemote(
            listOf(original), "nas", " new-nas ", mapOf("host" to "new", "user" to "b"),
            "/srv/backup", true,
        )
        assertEquals("new-nas", edited.name)
        assertEquals("new", edited.params["host"])
        assertEquals("/srv/backup/package.minisbak", edited.join("package.minisbak"))
        assertEquals(original.createdAt, edited.createdAt)
        assertFalse(edited.enabled)
        assertTrue(edited.allowInsecureTLS)
        assertEquals("old", original.params["host"])
    }

    @Test(expected = RcloneRemoteStore.StoreException::class)
    fun `renaming cannot overwrite a different saved destination`() {
        RcloneRemoteStore.editedRemote(
            listOf(original, original.copy(name = "other")), "nas", "other",
            emptyMap(), "", false,
        )
    }

    @Test(expected = RcloneRemoteStore.StoreException::class)
    fun `editing a removed destination cannot recreate it`() {
        RcloneRemoteStore.editedRemote(emptyList(), "nas", "new", emptyMap(), "", false)
    }

    @Test fun `failed metadata commit restores both published configuration and credential`() {
        var credential = "original-key"
        var metadata = "original-server"
        val result = runCatching {
            RcloneRemoteStore.persistEditWithRollback(
                saveCredential = { credential = "new-key"; true },
                saveMetadata = { metadata = "new-server"; false },
                restoreCredential = { credential = "original-key"; true },
                restoreMetadata = { metadata = "original-server"; true },
            )
        }
        assertTrue(result.exceptionOrNull() is RcloneRemoteStore.StoreException)
        assertEquals("original-key", credential)
        assertEquals("original-server", metadata)
    }

    @Test fun `failed credential commit cannot leave a changed in memory key or save metadata`() {
        var credential = "original-key"
        var savedMetadata = false
        val result = runCatching {
            RcloneRemoteStore.persistEditWithRollback(
                saveCredential = { credential = "new-key"; false },
                saveMetadata = { savedMetadata = true; true },
                restoreCredential = { credential = "original-key"; true },
                restoreMetadata = { true },
            )
        }
        assertTrue(result.exceptionOrNull() is RcloneRemoteStore.StoreException)
        assertEquals("original-key", credential)
        assertFalse(savedMetadata)
    }

    @Test fun `successful edit keeps the new configuration and credential without rollback`() {
        var credential = "original-key"
        var metadata = "original-server"
        var rolledBack = false
        RcloneRemoteStore.persistEditWithRollback(
            saveCredential = { credential = "new-key"; true },
            saveMetadata = { metadata = "new-server"; true },
            restoreCredential = { rolledBack = true; true },
            restoreMetadata = { rolledBack = true; true },
        )
        assertEquals("new-key", credential)
        assertEquals("new-server", metadata)
        assertFalse(rolledBack)
    }
}
