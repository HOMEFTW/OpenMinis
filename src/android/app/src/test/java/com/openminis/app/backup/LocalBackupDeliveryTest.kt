package com.openminis.app.backup

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalBackupDeliveryTest {
    @get:Rule val folder = TemporaryFolder()
    private fun source(): File = File(folder.newFolder("Backups"), "test.minisbak").apply {
        writeBytes(ByteArray(32_000) { (it % 251).toByte() })
    }

    @Test fun copiesAllBytesAndClosesDestination() = runBlocking {
        val source = source()
        var closed = false
        val output = object : ByteArrayOutputStream() {
            override fun close() { closed = true; super.close() }
        }
        assertEquals(source.length(), copyLocalBackup(source, source.parentFile!!) { output })
        assertArrayEquals(source.readBytes(), output.toByteArray())
        assertTrue(closed)
    }
    @Test fun rejectsSourceOutsideBackupDirectoryBeforeOpeningDestination() = runBlocking {
        val source = source()
        var opened = false
        assertTrue(runCatching { copyLocalBackup(source, folder.newFolder("other")) { opened = true; ByteArrayOutputStream() } }.isFailure)
        assertFalse(opened)
    }
    @Test fun rejectsMissingSourceBeforeOpeningDestination() = runBlocking {
        var opened = false
        assertTrue(runCatching { copyLocalBackup(File(folder.root, "missing"), folder.root) { opened = true; ByteArrayOutputStream() } }.isFailure)
        assertFalse(opened)
    }
    @Test fun nullDestinationIsFailure() = runBlocking {
        val source = source()
        assertTrue(runCatching { copyLocalBackup(source, source.parentFile!!) { null } }.isFailure)
    }
    @Test fun writeFailureIsPropagatedAndStreamClosed() = runBlocking {
        val source = source()
        var closed = false
        val output = object : OutputStream() {
            override fun write(b: Int) { throw IOException("disk full") }
            override fun close() { closed = true }
        }
        val failure = runCatching { copyLocalBackup(source, source.parentFile!!) { output } }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertTrue(closed)
    }
    @Test fun cancellationStopsCopyAndClosesStream() = runBlocking {
        val source = source()
        var closed = false
        var cancelled = false
        val job = launch {
            val taskContext = currentCoroutineContext()
            val output = object : OutputStream() {
                override fun write(b: Int) { taskContext.cancel() }
                override fun close() { closed = true }
            }
            try { copyLocalBackup(source, source.parentFile!!) { output } }
            catch (e: CancellationException) { cancelled = true; throw e }
        }
        job.join()
        assertTrue(cancelled)
        assertTrue(closed)
    }
}
