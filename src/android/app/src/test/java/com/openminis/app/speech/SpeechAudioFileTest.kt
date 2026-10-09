package com.openminis.app.speech

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SpeechAudioFileTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `relative files resolve against the requesting cwd`() {
        val root = temp.newFolder("attachments")
        val expected = File(root, "take.wav").apply { writeBytes(byteArrayOf(1)) }
        assertEquals(expected.canonicalFile, SpeechAudioFile.resolve("./take.wav", "/var/minis/attachments") { root })
    }

    @Test fun `absolute files use their own mount`() {
        val root = temp.newFolder("workspace")
        val expected = File(root, "take.wav").apply { writeBytes(byteArrayOf(1)) }
        var requested = ""
        assertEquals(expected.canonicalFile, SpeechAudioFile.resolve("/var/minis/workspace/take.wav", "/tmp") {
            requested = it
            root
        })
        assertEquals("/var/minis/workspace", requested)
    }

    @Test fun `host credentials and traversal are rejected before resolution`() {
        for (path in listOf("/data/data/com.openminis.app/shared_prefs/keys.xml", "/var/minis/attachments/../keys.xml", "C:\\keys.wav")) {
            assertThrows(IllegalArgumentException::class.java) {
                SpeechAudioFile.resolve(path, "/var/minis/attachments") { error("Unsafe path reached resolver") }
            }
        }
    }

    @Test fun `private files with no requesting session do not use a global fallback`() {
        assertThrows(IllegalArgumentException::class.java) {
            SpeechAudioFile.resolve("/var/minis/attachments/take.wav", "/tmp") { null }
        }
    }

    @Test fun `directories missing empty and oversized files are rejected`() {
        val root = temp.newFolder("attachments")
        File(root, "directory").mkdir()
        File(root, "empty.wav").createNewFile()
        RandomAccessFile(File(root, "large.wav"), "rw").use { it.setLength(SpeechAudioFile.MAX_INPUT_BYTES + 1L) }
        for (name in listOf("directory", "missing.wav", "empty.wav", "large.wav")) {
            assertThrows(IllegalArgumentException::class.java) {
                SpeechAudioFile.resolve(name, "/var/minis/attachments") { root }
            }
        }
    }

    @Test fun `symlinks escaping the selected mount are rejected`() {
        val root = temp.newFolder("attachments")
        val outside = temp.newFile("outside.wav").apply { writeBytes(byteArrayOf(1)) }
        try {
            Files.createSymbolicLink(File(root, "take.wav").toPath(), outside.toPath())
        } catch (e: Exception) {
            assumeNoException("Host cannot create symbolic links", e)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SpeechAudioFile.resolve("take.wav", "/var/minis/attachments") { root }
        }
    }
}
