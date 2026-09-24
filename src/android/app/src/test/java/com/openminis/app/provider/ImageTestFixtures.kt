package com.openminis.app.provider

import java.io.ByteArrayInputStream
import java.util.Base64
import java.io.InputStream

internal object ImageTestFixtures {
    private val png1x1Bytes = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
    )

    val png1x1: ByteArray
        get() = png1x1Bytes.copyOf()

    /** JVM-only decoder used by tests that exercise the provider pipeline. */
    @Suppress("UNUSED_PARAMETER")
    fun normalize(data: ByteArray, mimeType: String): ImageBudget.NormalizedImage? {
        if (data.isEmpty()) return null
        // Android's compile classpath omits java.desktop, but the JVM test
        // runtime includes it. Reflection is confined to this test fixture.
        val decoded = runCatching {
            Class.forName("javax.imageio.ImageIO").getMethod("read", InputStream::class.java)
                .invoke(null, ByteArrayInputStream(data))
        }.getOrNull() ?: return null
        val width = decoded.javaClass.getMethod("getWidth").invoke(decoded) as Int
        val height = decoded.javaClass.getMethod("getHeight").invoke(decoded) as Int
        if (width <= 0 || height <= 0) return null
        val encodedMime = when {
            data.size >= 8 && data[0] == 0x89.toByte() && data[1] == 0x50.toByte() -> "image/png"
            data.size >= 3 && data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte() -> "image/jpeg"
            data.size >= 6 && String(data, 0, 3, Charsets.US_ASCII) == "GIF" -> "image/gif"
            else -> return null
        }
        return ImageBudget.NormalizedImage(data.copyOf(), encodedMime)
    }
}
