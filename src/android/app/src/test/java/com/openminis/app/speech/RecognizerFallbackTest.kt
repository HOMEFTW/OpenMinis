package com.openminis.app.speech

import android.speech.SpeechRecognizer
import com.openminis.app.ProductionSources
import com.openminis.app.speech.RecognizerFallback.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-431-fallback] GH #431: the CLI took the on-device recognizer on Android
 * 12+ and never fell back, so a missing language pack (a zh-CN Pixel 4a: error
 * 12) or an unreachable service (vivo: ERROR_CLIENT) was a dead end.
 */
class RecognizerFallbackTest {

    @Test
    fun `connection and language errors switch, others do not`() {
        assertTrue(RecognizerFallback.shouldSwitch(SpeechRecognizer.ERROR_CLIENT))
        assertTrue(RecognizerFallback.shouldSwitch(RecognizerErrorCodes.SERVER_DISCONNECTED))
        assertTrue(RecognizerFallback.shouldSwitch(RecognizerErrorCodes.LANGUAGE_NOT_SUPPORTED))
        assertTrue(RecognizerFallback.shouldSwitch(RecognizerErrorCodes.LANGUAGE_UNAVAILABLE))
        assertFalse(RecognizerFallback.shouldSwitch(SpeechRecognizer.ERROR_NETWORK))
        assertFalse(RecognizerFallback.shouldSwitch(SpeechRecognizer.ERROR_NO_MATCH))
        assertFalse(RecognizerFallback.shouldSwitch(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS))
        // Quota exhaustion is a different problem: switching would just spend
        // the other recognizer's quota too.
        assertFalse(RecognizerFallback.shouldSwitch(RecognizerErrorCodes.TOO_MANY_REQUESTS))
    }

    @Test
    fun `regular first by default, on-device first on request`() {
        assertEquals(listOf(Kind.REGULAR, Kind.ON_DEVICE), RecognizerFallback.plan(false, 33, true))
        assertEquals(listOf(Kind.ON_DEVICE, Kind.REGULAR), RecognizerFallback.plan(true, 33, true))
    }

    @Test
    fun `no on-device recognizer means one attempt`() {
        assertEquals(listOf(Kind.REGULAR), RecognizerFallback.plan(true, 33, false))
        assertEquals(listOf(Kind.REGULAR), RecognizerFallback.plan(true, 30, null))
        assertEquals(listOf(Kind.REGULAR), RecognizerFallback.plan(false, 31, false))
    }

    @Test
    fun `next attempt only after a switching error and only once`() {
        val plan = RecognizerFallback.plan(false, 33, true)
        assertEquals(Kind.ON_DEVICE, RecognizerFallback.next(plan, 0, SpeechRecognizer.ERROR_CLIENT))
        assertNull(RecognizerFallback.next(plan, 1, SpeechRecognizer.ERROR_CLIENT))
        assertNull(RecognizerFallback.next(plan, 0, SpeechRecognizer.ERROR_NETWORK))
    }

    @Test
    fun `the chat voice input shares the SDK gate and preserves fork mode preferences`() {
        val src = ProductionSources.read("speech/SystemSpeechRecognitionEngine.kt")
        assertTrue(src.contains("modeFallback.nextModeForCode("))
        assertTrue(src.contains("errorCode = errorCode,"))
        assertTrue(src.contains("allowOnlineFallback = allowModeFallback,"))
        assertTrue(src.contains("onDeviceAvailable = onDeviceAvailable,"))
        for (code in listOf(SpeechRecognizer.ERROR_CLIENT, RecognizerErrorCodes.SERVER_DISCONNECTED,
            RecognizerErrorCodes.LANGUAGE_NOT_SUPPORTED, RecognizerErrorCodes.LANGUAGE_UNAVAILABLE)) {
            assertEquals("SDK error $code", false, SystemRecognitionFallback().attempt(code))
        }
        for (code in listOf(RecognizerErrorCodes.TOO_MANY_REQUESTS, RecognizerErrorCodes.CANNOT_CHECK_SUPPORT,
            RecognizerErrorCodes.CANNOT_LISTEN_TO_DOWNLOAD_EVENTS, -1, SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS, SpeechRecognizer.ERROR_AUDIO)) {
            assertNull("must not consume another service for $code", SystemRecognitionFallback().attempt(code))
        }
        assertEquals(true, SystemRecognitionFallback().attempt(SpeechRecognizer.ERROR_NETWORK, onDevice = false))
        assertNull(SystemRecognitionFallback().attempt(SpeechRecognizer.ERROR_NETWORK, allowFallback = false))
        assertNull(SystemRecognitionFallback().attempt(SpeechRecognizer.ERROR_NETWORK, onDevice = false, available = false))
        // Preserve the existing missing-offline-pack recovery even without opt-in.
        assertEquals(false, SystemRecognitionFallback().attempt(12, allowFallback = false))
    }

    @Test
    fun `the chat voice input never falls back once the take has something to lose`() {
        // [T-voice-fallback-after-stop] ERROR_CLIENT after the user's stop wiped
        // the transcript and restarted a recognizer that opened the mic again.
        val src = ProductionSources.read("speech/SystemSpeechRecognitionEngine.kt")
        assertTrue(src.contains("sessionCommitted = sessionCommitted,"))
        assertTrue(src.contains("stopRequested = stopRequested,"))
        assertTrue(src.contains("heardSpeech = heardSpeech,"))
        assertTrue(src.contains("hasTranscript = segmentTranscript.isNotEmpty() || !bufferedPartial.isNullOrEmpty(),"))
        assertTrue(src.contains("hasReplayAudio = replay != null,"))
        assertTrue(src.contains("if (listener == null || sessionCommitted || (stopRequested && replayPcm == null)) return@post"))
        assertTrue(src.contains("continuousSession && !stopRequested && err == RecognitionError.NO_MATCH"))
        val stop = src.substringAfter("override fun stop() {").substringBefore("stopVad()")
        val mark = stop.indexOf("stopRequested = true")
        assertTrue("stop marks the take before posting finalisation", mark >= 0 && mark < stop.indexOf("mainHandler.post {"))
        for (entry in listOf("override fun start(", "override fun transcribeRetained(")) {
            assertTrue("$entry resets the next take", src.substringAfter(entry)
                .substringBefore("\n    }\n").contains("stopRequested = false"))
        }
        for (code in listOf(SpeechRecognizer.ERROR_CLIENT, 11, 12, 13, SpeechRecognizer.ERROR_NETWORK)) {
            assertNull("stopped $code", SystemRecognitionFallback().attempt(code, stopped = true))
            assertNull("speech $code", SystemRecognitionFallback().attempt(code, heard = true))
            assertNull("transcript $code", SystemRecognitionFallback().attempt(code, transcript = true))
            assertNull("committed $code", SystemRecognitionFallback().attempt(code, committed = true, replay = true))
        }
    }

    @Test
    fun `saved audio retries once without reopening the microphone or losing its sample rate`() {
        val fallback = SystemRecognitionFallback()
        assertEquals(false, fallback.attempt(11, stopped = true, heard = true, transcript = true, replay = true))
        assertNull(fallback.attempt(12, onDevice = false, replay = true))
        fallback.reset()
        assertEquals(false, fallback.attempt(11))
        val src = ProductionSources.read("speech/SystemSpeechRecognitionEngine.kt")
        val retry = src.substringAfter("if (retryOnDevice != null && retryLocale != null) {")
            .substringBefore("// First-class ROM-level failures")
        assertTrue(retry.indexOf("val replaySampleRate = sessionPcmSampleRate") < retry.indexOf("tearDown()"))
        assertTrue(retry.contains("if (replay != null) {\n                    startReplay(replay, replaySampleRate, retryLocale, allowOnDeviceRetry = false, forceOnDevice = retryOnDevice)"))
        assertTrue(retry.contains("} else {\n                    startEndpointingVad()"))
    }

    @Test
    fun `a rejected retry does not consume the one fallback slot`() {
        val fallback = SystemRecognitionFallback()
        assertNull(fallback.attempt(11, stopped = true))
        assertNull(fallback.attempt(RecognizerErrorCodes.TOO_MANY_REQUESTS))
        assertEquals(false, fallback.attempt(11, stopped = true, replay = true))
        assertNull(fallback.attempt(12, replay = true))
    }

    private fun SystemRecognitionFallback.attempt(
        code: Int, onDevice: Boolean = true, allowFallback: Boolean = true, available: Boolean = true,
        committed: Boolean = false, stopped: Boolean = false, heard: Boolean = false,
        transcript: Boolean = false, replay: Boolean = false,
    ): Boolean? = nextModeForCode(onDevice, code, allowFallback, available, committed, stopped, heard, transcript, replay)
}
