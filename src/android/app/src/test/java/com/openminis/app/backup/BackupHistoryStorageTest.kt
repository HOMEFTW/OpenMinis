package com.openminis.app.backup

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupHistoryStorageTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `failed rewrite preserves the complete previous history`() {
        val file = File(temporary.root, "backup-history/records.json")
        val storage = BackupHistoryStorage(file)
        val original = "[{\"backupId\":\"old\"}]"
        storage.write { it.write(original.toByteArray()) }
        try {
            storage.write {
                it.write("[{\"backupId\":".toByteArray())
                throw IOException("disk write failed")
            }
            fail("Expected write failure")
        } catch (_: IOException) { }
        assertEquals(original, storage.read())
        assertEquals(listOf("records.json"), file.parentFile!!.list()!!.toList())
    }

    @Test fun `successful rewrite replaces history with UTF8 data`() {
        val file = File(temporary.root, "records.json")
        val storage = BackupHistoryStorage(file)
        storage.write { it.write("[]".toByteArray()) }
        val latest = "[{\"name\":\"本地备份 📦\"}]"
        storage.write { it.write(latest.toByteArray(Charsets.UTF_8)) }
        assertEquals(latest, storage.read())
        assertTrue(file.isFile)
    }
}
