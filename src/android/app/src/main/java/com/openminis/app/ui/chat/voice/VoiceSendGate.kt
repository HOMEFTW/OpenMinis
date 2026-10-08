package com.openminis.app.ui.chat.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.openminis.app.R
import com.openminis.app.speech.RecognitionState
import com.openminis.app.speech.RetryPromptQueue
import com.openminis.app.speech.SpeechRecognitionManager
import com.openminis.app.speech.VoiceFinishTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Shared by the panel callbacks and every composer send entry. */
object VoiceSendGate {
    class FailedUtterance(val id: Long, val audio: SpeechRecognitionManager.FailedAudio)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val nextId = AtomicLong(1)
    private val failures = AtomicInteger(0)
    private var pendingSend: Job? = null
    private var sendAttempt = 0L

    var queue by mutableStateOf(RetryPromptQueue<FailedUtterance>())
        private set
    var generation by mutableIntStateOf(0)
        private set
    var isFinishingForSend by mutableStateOf(false)
        private set
    var isCorrecting by mutableStateOf(false)
        internal set
    @Volatile var transcript: String = ""

    fun cancelPendingSend() {
        sendAttempt += 1
        pendingSend?.cancel()
        pendingSend = null
        isFinishingForSend = false
    }

    /** A cleared composer or a different session cannot inherit old callbacks/audio. */
    fun bumpGeneration() {
        cancelPendingSend()
        generation += 1
        queue = RetryPromptQueue()
        isCorrecting = false
    }

    fun recordFailure() { failures.incrementAndGet() }

    fun recordFailure(failed: SpeechRecognitionManager.FailedAudio, captureGeneration: Int) {
        if (captureGeneration != generation) return
        failures.incrementAndGet()
        val item = FailedUtterance(nextId.getAndIncrement(), failed)
        val enqueue = {
            if (captureGeneration == generation) queue = queue.enqueue(item)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) enqueue()
        else mainHandler.post { enqueue() }
    }

    fun takeForRetry(): FailedUtterance? {
        val (item, rest) = queue.takeForRetry()
        queue = rest
        return item
    }

    fun requeue(item: FailedUtterance) { queue = queue.enqueue(item) }
    fun discardCurrent() { queue = queue.discard() }

    fun owesText(): Boolean = VoiceModePrefs.isVoiceActive &&
        (isFinishingForSend || isCorrecting || !queue.isEmpty() ||
            SpeechRecognitionManager.state.value != RecognitionState.IDLE)

    /** True means this send is deferred; repeated taps cannot enqueue it twice. */
    fun deferSendIfOwed(scope: CoroutineScope, context: Context, onReady: () -> Unit): Boolean {
        if (!owesText()) return false
        if (isFinishingForSend) return true
        val state = SpeechRecognitionManager.state.value
        val failuresBefore = failures.get()
        val captureGeneration = generation
        val attempt = ++sendAttempt
        isFinishingForSend = true
        if (state == RecognitionState.STARTING || state == RecognitionState.RECORDING) {
            SpeechRecognitionManager.stopRecording()
        }
        pendingSend = scope.launch {
            val tracker = VoiceFinishTracker()
            val started = SystemClock.elapsedRealtime()
            try {
                while (captureGeneration == generation) {
                    delay(100)
                    when (tracker.tick(
                        idle = SpeechRecognitionManager.state.value == RecognitionState.IDLE && !isCorrecting,
                        failedSinceStart = failures.get() != failuresBefore || !queue.isEmpty(),
                        elapsedMs = SystemClock.elapsedRealtime() - started,
                    )) {
                        VoiceFinishTracker.Step.WAIT -> Unit
                        VoiceFinishTracker.Step.SEND -> {
                            onReady()
                            return@launch
                        }
                        VoiceFinishTracker.Step.FAILED -> return@launch
                        VoiceFinishTracker.Step.TIMED_OUT -> {
                            android.widget.Toast.makeText(
                                context.applicationContext,
                                context.getString(R.string.voice_send_still_transcribing),
                                android.widget.Toast.LENGTH_LONG,
                            ).show()
                            return@launch
                        }
                    }
                }
            } finally {
                if (captureGeneration == generation && attempt == sendAttempt) {
                    isFinishingForSend = false
                    pendingSend = null
                }
            }
        }
        return true
    }
}
