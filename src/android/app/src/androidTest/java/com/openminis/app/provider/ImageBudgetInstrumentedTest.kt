package com.openminis.app.provider

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImageBudgetInstrumentedTest {
    private val png1x1 = Base64.decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
        Base64.DEFAULT,
    )

    @Test
    fun normalizeImageUsesAndroidDecoderAndEncodedMime() {
        val normalized = ImageBudget.normalizeImage(png1x1, "image/jpeg")

        assertNotNull(normalized)
        assertEquals("image/png", normalized!!.mimeType)
        assertArrayEquals(png1x1, normalized.data)
    }

    @Test
    fun normalizeImageRejectsTruncatedSupportedHeader() {
        val truncated = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )

        assertNull(ImageBudget.normalizeImage(truncated, "image/png"))
    }
}
