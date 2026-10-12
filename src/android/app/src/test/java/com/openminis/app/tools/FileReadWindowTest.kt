package com.openminis.app.tools

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class FileReadWindowTest {
    private fun fixture(text: String, action: (File) -> Unit) {
        val file = Files.createTempFile("file-window-", ".txt").toFile()
        try { file.writeText(text); action(file) } finally { file.delete() }
    }

    @Test fun `head offset and tail line limits preserve content and metadata`() = fixture("one\r\ntwo\rthree\nfour\n") { file ->
        val head = FileReadWindow.read(file, 2, 2, 100, false)
        assertEquals("two\nthree", head.content)
        assertEquals(4L, head.totalLines)
        assertEquals(2L, head.startLine)
        assertEquals(3L, head.endLine)
        assertFalse(head.truncated)
        val tail = FileReadWindow.read(file, 999, 2, 100, true)
        assertEquals("three\nfour", tail.content)
        assertEquals(3L, tail.startLine)
        assertEquals(4L, tail.endLine)
    }

    @Test fun `truncated head offers the next whole line and tail retains the end`() = fixture("alpha\nbeta\ngamma\ndelta") { file ->
        val head = FileReadWindow.read(file, 1, null, 10, false)
        assertEquals("alpha", head.content)
        assertTrue(head.truncated)
        assertEquals(2L, head.nextOffset)
        val next = FileReadWindow.read(file, head.nextOffset!!.toInt(), 1, 10, false)
        assertEquals("beta", next.content)
        val tail = FileReadWindow.read(file, 1, null, 8, true)
        assertEquals("delta", tail.content)
        assertEquals(4L, tail.startLine)
        assertNull(tail.nextOffset)
    }

    @Test fun `single oversized line is bounded from either direction`() = fixture("BEGIN" + "x".repeat(2_000_000) + "END") { file ->
        val head = FileReadWindow.read(file, 1, 1, 16, false)
        val tail = FileReadWindow.read(file, 1, 1, 16, true)
        assertEquals(16, head.content.length)
        assertTrue(head.content.startsWith("BEGIN"))
        assertEquals(16, tail.content.length)
        assertTrue(tail.content.endsWith("END"))
        assertTrue(head.truncated && tail.truncated)
        assertEquals(1L, head.totalLines)
    }

    @Test fun `empty lines unicode and CRLF across a read buffer boundary are counted`() {
        fixture("\n\r\n中文\r") { file ->
            val window = FileReadWindow.read(file, 1, null, 100, false)
            assertEquals(3L, window.totalLines)
            assertEquals("\n\n中文", window.content)
        }
        fixture("x".repeat(8191) + "\r\nlast") { file ->
            val window = FileReadWindow.read(file, 1, 1, 4, true)
            assertEquals(2L, window.totalLines)
            assertEquals("last", window.content)
            assertFalse(window.truncated)
        }
    }

    @Test fun `empty zero-line and zero-character windows do not allocate a full file`() {
        fixture("") { file -> assertEquals(0L, FileReadWindow.read(file, 1, null, 32, false).totalLines) }
        fixture("first\nsecond") { file ->
            assertEquals("", FileReadWindow.read(file, 1, 0, 32, false).content)
            assertEquals("", FileReadWindow.read(file, 20, 3, 32, false).content)
            assertEquals("", FileReadWindow.read(file, 1, null, 0, false).content)
            assertEquals("", FileReadWindow.read(file, 1, null, 0, true).content)
        }
    }
}
