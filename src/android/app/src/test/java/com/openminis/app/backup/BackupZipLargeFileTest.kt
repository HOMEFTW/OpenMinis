package com.openminis.app.backup

import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Opt-in real I/O regression; needs at least 13 GiB of temporary disk space. */
class BackupZipLargeFileTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `member and archive beyond 4GiB restore with ZIP64 sizes offsets CRC and UTF8 names`() {
        assumeTrue("Enable with -Dopenminis.backup.largeZipTest=true", java.lang.Boolean.getBoolean("openminis.backup.largeZipTest"))
        assumeTrue("Requires 13 GiB temporary disk space", temporary.root.usableSpace > 13L * 1024 * 1024 * 1024)
        val staging = temporary.newFolder("staging")
        val name = "blobs/大文件.bin"
        val size = 0x1_0000_0000L + 4096
        val source = File(staging, name).apply { parentFile!!.mkdirs() }
        RandomAccessFile(source, "rw").use {
            it.setLength(size)
            it.writeUTF("first marker")
            it.seek(size - 64)
            it.writeUTF("last marker")
        }
        File(staging, "manifest.json").writeText("{\"format\":\"minisbak/1\"}")
        val archive = File(temporary.root, "large.minisbak")
        BackupZip.archive(staging, archive)
        assertTrue(archive.length() > 0x1_0000_0000L)
        ZipFile(archive).use { zip ->
            assertEquals(size, zip.getEntry(name).size)
            assertEquals(size, zip.getEntry(name).compressedSize)
            assertEquals(ZipEntry.STORED, zip.getEntry(name).method)
            assertTrue(zip.getEntry(name).crc >= 0)
            // This small entry's central-directory offset is beyond 4 GiB.
            assertEquals("{\"format\":\"minisbak/1\"}", zip.getInputStream(zip.getEntry("manifest.json")).reader().readText())
        }
        val restored = File(temporary.root, "restored")
        BackupZip.extract(archive, restored)
        val output = File(restored, name)
        assertEquals(size, output.length())
        RandomAccessFile(output, "r").use {
            assertEquals("first marker", it.readUTF())
            it.seek(size - 64)
            assertEquals("last marker", it.readUTF())
        }
        assertEquals("{\"format\":\"minisbak/1\"}", File(restored, "manifest.json").readText())
    }
}
