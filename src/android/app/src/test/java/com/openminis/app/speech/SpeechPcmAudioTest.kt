package com.openminis.app.speech

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SpeechPcmAudioTest {
    private fun pcm(vararg samples: Int) = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
        samples.forEach { putShort(it.toShort()) }
    }.array()

    @Test fun `mono 16 kHz samples are preserved`() {
        val audio = pcm(-32768, -1000, 0, 1000, 32767)
        assertArrayEquals(audio, SpeechPcmAudio.toMono16k(audio, 16000, 1))
    }

    @Test fun `stereo mixes both channels without overflowing`() {
        assertArrayEquals(pcm(0, 32767, -32768), SpeechPcmAudio.toMono16k(pcm(-2000, 2000, 32767, 32767, -32768, -32768), 16000, 2))
    }

    @Test fun `48 kHz converts to provider rate`() {
        assertArrayEquals(pcm(100, 400), SpeechPcmAudio.toMono16k(pcm(100, 200, 300, 400, 500, 600), 48000, 1))
    }

    @Test fun `8 kHz interpolates without inventing an extra final sample`() {
        assertArrayEquals(pcm(0, 500, 1000, 1000), SpeechPcmAudio.toMono16k(pcm(0, 1000), 8000, 1))
    }

    @Test fun `invalid PCM and format are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { SpeechPcmAudio.toMono16k(byteArrayOf(1), 16000, 1) }
        assertThrows(IllegalArgumentException::class.java) { SpeechPcmAudio.toMono16k(pcm(1), 16000, 2) }
        assertThrows(IllegalArgumentException::class.java) { SpeechPcmAudio.toMono16k(pcm(1), 0, 1) }
        assertThrows(IllegalArgumentException::class.java) { SpeechPcmAudio.toMono16k(pcm(1), 16000, 0) }
    }

    @Test fun `duration limit rejects excessive decoded audio`() {
        assertThrows(IllegalArgumentException::class.java) {
            SpeechPcmAudio.toMono16k(ByteArray((8000 * SpeechPcmAudio.MAX_SECONDS + 1) * 2), 8000, 1)
        }
    }

    @Test fun `interruption aborts normalization`() {
        Thread.currentThread().interrupt()
        try {
            assertThrows(InterruptedException::class.java) { SpeechPcmAudio.toMono16k(pcm(1, 2), 16000, 1) }
        } finally {
            Thread.interrupted()
        }
    }
}
