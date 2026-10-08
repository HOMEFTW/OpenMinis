package com.openminis.app.speech

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RetainedAudioSampleRateTest {
    private fun wav(sampleRate: Int): ByteArray =
        ByteBuffer.allocate(46).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(24, sampleRate)
            putInt(28, sampleRate * 2)
        }.array()

    @Test
    fun `provider and system audio retain their original sample rate`() {
        assertEquals(16_000, VoiceAsrWatchdog.wavSampleRate(wav(16_000)))
        assertEquals(48_000, VoiceAsrWatchdog.wavSampleRate(wav(48_000)))
    }

    @Test
    fun `missing audio and invalid rates cannot open a replay session`() {
        assertEquals(0, VoiceAsrWatchdog.wavSampleRate(ByteArray(44)))
        assertEquals(0, VoiceAsrWatchdog.wavSampleRate(wav(0)))
        assertEquals(0, VoiceAsrWatchdog.wavSampleRate(wav(-1)))
    }

    @Test
    fun `replay and its retry use the rate from the saved WAV`() {
        val source = File("src/main/java/com/openminis/app/speech/SystemSpeechRecognitionEngine.kt").readText()
        assertTrue(source.contains("val sampleRate = VoiceAsrWatchdog.wavSampleRate(wav)"))
        assertTrue(source.contains("startReplay(pcm, sampleRate, locale, allowOnDeviceRetry = true)"))
        assertTrue(source.contains("EXTRA_AUDIO_SOURCE_SAMPLING_RATE, sessionPcmSampleRate"))
        assertTrue(source.contains("startReplay(replay, replaySampleRate, retryLocale, allowOnDeviceRetry = false)"))
        assertTrue(source.contains("wrapPcm16InWav(pcm, sessionPcmSampleRate)"))
    }
}
