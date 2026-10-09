package com.openminis.app.sandbox.offload

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import androidx.core.content.ContextCompat
import com.openminis.app.logging.AppLogger
import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.sandbox.NativeOffloadHandler
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import com.openminis.app.sandbox.PRootKernel
import com.openminis.app.speech.ProviderSpeechRecognitionEngine
import com.openminis.app.speech.RecognitionError
import com.openminis.app.speech.RecognitionState
import com.openminis.app.speech.SpeechAudioDecoder
import com.openminis.app.speech.SpeechAudioFile
import com.openminis.app.speech.SpeechRecognitionEngine
import com.openminis.app.speech.SpeechRecognitionManager
import com.openminis.app.speech.SystemSpeechRecognitionEngine
import com.openminis.app.speech.VoiceOutputState
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** android-speech reuses the same system/provider adapters as voice input, with private callbacks. */
class SpeechOffloadHandler(private val context: Context) : NativeOffloadHandler {
    private val micBusy = AtomicBoolean(false)

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val args = OffloadArgs(request.argv.drop(1), booleanFlags = setOf("on-device"))
        if (args.hasFlag("h", "help") || args.positional.isEmpty()) {
            return NativeOffloadResult(if (args.hasFlag("h", "help")) 0 else 2, HELP)
        }
        return try {
            when (val sub = args.positional[0]) {
                "status" -> cmdStatus(args)
                "languages" -> cmdLanguages(args)
                "transcribe", "listen" -> cmdTranscribe(args, request)
                else -> error(args, "invalid_argument", "Unknown subcommand '$sub'", 2)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            error(args, "cancelled", "Speech transcription was interrupted", 130)
        } catch (e: IllegalArgumentException) {
            error(args, "invalid_argument", e.message ?: "Invalid argument", 2)
        } catch (e: Exception) {
            AppLogger.warning(TAG, "speech failed: ${e.javaClass.simpleName}")
            error(args, "internal", e.message ?: "Speech transcription failed")
        }
    }

    private fun cmdStatus(args: OffloadArgs): NativeOffloadResult {
        val system = SystemSpeechRecognitionEngine(context).isAvailable
        val provider = ProviderSpeechRecognitionEngine(context).isAvailable
        val microphone = context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)
        return output(args, JSONObject()
            .put("available", system || provider)
            .put("system_available", system)
            .put("provider_available", provider)
            .put("has_record_audio_permission", hasMic())
            .put("has_microphone", microphone)
            .put("mic_available", (system || provider) && microphone && hasMic())
            .put("file_available", provider)
            .put("system_file_input", if (system && Build.VERSION.SDK_INT >= 33) "best_effort" else "unsupported")
            .put("availability_check", "Local service/configuration only; network, credentials and language support are verified when transcribing."))
    }

    private fun cmdTranscribe(args: OffloadArgs, request: NativeOffloadRequest): NativeOffloadResult {
        val source = args.get("source") ?: "mic"
        val mic = source.trim().lowercase(Locale.ROOT) in setOf("", "mic", "system-mic", "system_mic", "microphone")
        val duration = numberOption(args, "duration", 30, 1..60)
        val timeout = numberOption(args, "timeout", 120, 1..300)
        // Keep accepting the legacy flag; engine adapters expose one best transcript.
        numberOption(args, "max", 3, 1..20)
        val language = args.get("language")?.let {
            Locale.Builder().setLanguageTag(it.replace('_', '-')).build().also { locale ->
                require(locale.language.isNotEmpty()) { "Invalid recognition language" }
            }
        } ?: SpeechRecognitionManager.locale.value
        val requestedEngine = args.get("engine") ?: "auto"
        require(!args.hasFlag("engine", "source", "language")) { "--engine, --source and --language require values" }
        require(requestedEngine in setOf("auto", "system", "provider")) { "--engine must be auto, system or provider" }
        val onDevice = args.hasFlag("on-device") || args.getBool("on-device") == true
        require(!onDevice || requestedEngine != "provider") { "--on-device requires the system engine" }
        val provider = ProviderSpeechRecognitionEngine(context).apply { useVad = false }
        val systemPreference = provider.selectedSystemPreferOffline
        val system = SystemSpeechRecognitionEngine(context).apply { preferOffline = onDevice || systemPreference == true }
        val engine = when {
            onDevice || requestedEngine == "system" -> system
            requestedEngine == "provider" -> provider
            requestedEngine == "auto" && mic && systemPreference != null -> system
            provider.isAvailable -> provider
            else -> system
        }
        if (!engine.isAvailable) {
            return error(args, "recognizer_unavailable", if (engine === provider) {
                "No usable provider ASR model/credential is configured in Voice Input."
            } else {
                "No usable system recognition service. Configure a provider ASR model in Voice Input or install a system recognition service."
            })
        }
        if (!mic) {
            if (engine === system && Build.VERSION.SDK_INT < 33) {
                return error(args, "not_supported", "System file input needs Android 13+; configure a provider ASR model for file transcription.", 2)
            }
            val file = SpeechAudioFile.resolve(source, request.cwd) { root ->
                if (root == "/var/minis/shared") PRootKernel.resolveHostPath(root)
                else request.sessionId?.let { PRootKernel.resolveSessionHostPath(it, root, context) }
            }
            val wav = try {
                SpeechAudioDecoder.decode(file)
            } catch (e: InterruptedException) {
                throw e
            } catch (e: Exception) {
                return error(args, "invalid_audio", e.message ?: "Audio could not be decoded", 2)
            }
            return recognize(args, engine, language, wav, duration, timeout, source)
        }
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)) {
            return error(args, "audio_error", "This device has no microphone")
        }
        if (!micBusy.compareAndSet(false, true)) return error(args, "recognizer_busy", "Another tool is using the microphone")
        var sessionStarted = false
        try {
            if (SpeechRecognitionManager.state.value != RecognitionState.IDLE) {
                return error(args, "recognizer_busy", "Voice input is already using the microphone")
            }
            ensureMicPermission(args)?.let { return it }
            if (SpeechRecognitionManager.state.value != RecognitionState.IDLE) {
                return error(args, "recognizer_busy", "Voice input is already using the microphone")
            }
            sessionStarted = true
            return recognize(args, engine, language, null, duration, timeout, "mic")
        } finally {
            if (!sessionStarted) micBusy.set(false)
        }
    }

    private fun numberOption(args: OffloadArgs, name: String, default: Int, range: IntRange): Int {
        require(!args.hasFlag(name)) { "--$name requires an integer" }
        val value = args.get(name)?.let { requireNotNull(it.toIntOrNull()) { "--$name requires an integer" } } ?: default
        require(value in range) { "--$name must be in ${range.first}..${range.last}" }
        return value
    }

    private fun recognize(
        args: OffloadArgs,
        engine: SpeechRecognitionEngine,
        locale: Locale,
        wav: ByteArray?,
        duration: Int,
        timeout: Int,
        source: String,
    ): NativeOffloadResult {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Speech tool must run off the main thread" }
        val main = Handler(Looper.getMainLooper())
        val latch = CountDownLatch(1)
        val finished = AtomicBoolean(false)
        val leasedMicrophone = AtomicBoolean(false)
        val result = AtomicReference<NativeOffloadResult>()
        fun complete(body: JSONObject, exit: Int = 0) {
            synchronized(finished) {
                if (finished.get()) return
                result.set(output(args, body.put("engine", engine.id).put("source", source), exit))
                finished.set(true)
                latch.countDown()
            }
        }
        val stop = Runnable { if (!finished.get()) engine.stop() }
        val start = Runnable {
            // A request that timed out while the main thread was busy must never open the mic later.
            if (finished.get()) return@Runnable
            try {
                val listener = object : SpeechRecognitionEngine.Listener {
                    override fun onPartial(text: String) {}
                    override fun onFinal(text: String) {
                        if (text.isBlank()) onError(RecognitionError.NO_MATCH, "No speech recognized")
                        else complete(JSONObject().put("text", text))
                    }
                    override fun onError(error: RecognitionError, message: String?) {
                        complete(JSONObject().put("error", error.name.lowercase(Locale.ROOT))
                            .put("message", message ?: error.name), 1)
                    }
                    override fun onReadyForSpeech() {
                        if (wav == null && !finished.get()) main.postDelayed(stop, duration * 1_000L)
                    }
                }
                if (wav == null) {
                    if (!SpeechRecognitionManager.acquireToolMicrophone()) {
                        listener.onError(RecognitionError.RECOGNIZER_BUSY, "Voice input is already using the microphone")
                        return@Runnable
                    }
                    leasedMicrophone.set(true)
                    VoiceOutputState.suspendAllForCapture()
                    engine.start(locale, listener)
                } else engine.transcribeRetained(wav, locale, listener)
            } catch (e: Exception) {
                complete(JSONObject().put("error", "recognition_failed").put("message", e.message), 1)
            }
        }
        main.post(start)
        try {
            // Capture has its own duration; timeout bounds warmup and transcription as well.
            val waitSeconds = timeout.toLong() + if (wav == null) duration else 0
            if (!latch.await(waitSeconds, TimeUnit.SECONDS)) {
                complete(JSONObject().put("error", "timed_out").put("message", "Speech transcription timed out"), 1)
            }
            return result.get()
        } finally {
            finished.set(true)
            main.removeCallbacks(start)
            main.removeCallbacks(stop)
            main.post {
                try { engine.cancel() } finally {
                    if (leasedMicrophone.get()) {
                        SpeechRecognitionManager.releaseToolMicrophone()
                        VoiceOutputState.resumeAllAfterCapture()
                    }
                    if (wav == null) micBusy.set(false)
                }
            }
        }
    }

    /** List only locales actually returned by the system; provider ASR can auto-detect. */
    private fun cmdLanguages(args: OffloadArgs): NativeOffloadResult {
        val latch = CountDownLatch(1)
        val tags = AtomicReference<List<String>>(emptyList())
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                tags.set(getResultExtras(false)?.getStringArrayList(RecognizerIntent.EXTRA_SUPPORTED_LANGUAGES).orEmpty())
                latch.countDown()
            }
        }
        context.sendOrderedBroadcast(Intent(RecognizerIntent.ACTION_GET_LANGUAGE_DETAILS), null,
            receiver, Handler(Looper.getMainLooper()), 0, null, null)
        val reported = latch.await(3, TimeUnit.SECONDS)
        val prefix = args.get("language")
        val items = JSONArray()
        for (tag in tags.get().distinct().sorted()) {
            if (!prefix.isNullOrBlank() && !tag.startsWith(prefix, ignoreCase = true)) continue
            items.put(JSONObject().put("locale", tag)
                .put("display_name", Locale.forLanguageTag(tag.replace('_', '-')).displayName.ifEmpty { tag }))
        }
        return output(args, JSONObject().put("locales", items).put("count", items.length())
            .put("system_languages_reported", reported && tags.get().isNotEmpty())
            .put("provider_auto_detect", ProviderSpeechRecognitionEngine(context).isAvailable))
    }

    private fun hasMic() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun ensureMicPermission(args: OffloadArgs): NativeOffloadResult? {
        if (hasMic()) return null
        val result = runBlocking {
            var r = OffloadPermissionManager.requestAndroidPermission(listOf(Manifest.permission.RECORD_AUDIO))
            if (r == OffloadPermissionManager.AndroidPermissionResult.DENIED &&
                OffloadPermissionManager.pollForPermissionGrant({ hasMic() })) {
                r = OffloadPermissionManager.AndroidPermissionResult.GRANTED
            }
            if (r == OffloadPermissionManager.AndroidPermissionResult.DENIED) {
                r = OffloadPermissionManager.requestSettingsGate(
                    OffloadPermissionManager.SettingsGateRequest(
                        id = Manifest.permission.RECORD_AUDIO,
                        title = "Microphone permission needed",
                        message = "Minis needs microphone permission to transcribe speech. Open Settings to allow it.",
                        settingsAction = android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        requiresPackageUri = true,
                        positiveLabel = "Open Settings",
                    ), check = { hasMic() },
                )
            }
            r
        }
        return when (result) {
            OffloadPermissionManager.AndroidPermissionResult.GRANTED -> null
            OffloadPermissionManager.AndroidPermissionResult.DENIED -> error(args, "permission_denied", "The user declined the microphone permission.", 77)
            OffloadPermissionManager.AndroidPermissionResult.TIMEOUT -> error(args, "timeout", "Timed out waiting for microphone permission.", 77)
        }
    }

    private fun output(args: OffloadArgs, body: JSONObject, exit: Int = 0) =
        NativeOffloadResult(exit, OffloadOutput.formatBody(body.toString(), args) + "\n")

    private fun error(args: OffloadArgs, kind: String, message: String, exit: Int = 1) =
        output(args, JSONObject().put("error", kind).put("message", message), exit)

    companion object {
        private const val TAG = "SpeechOffload"
        private const val HELP = """android-speech — speech recognition (audio → text)

Usage: android-speech <transcribe|listen|languages|status> [options]

  --source <mic|path>       Microphone (default) or sandbox audio file
  --engine <auto|provider|system>  Use configured Voice Input ASR; keep system recognition available
  --duration <1..60>       Maximum mic capture seconds (default: 30)
  --timeout <1..300>       Warmup/transcription deadline seconds, plus mic duration (default: 120)
  --language <locale>      Language hint (e.g. en-US, zh-CN)
  --on-device             Prefer offline system recognition; language packs may be required
  --max <1..20>            Legacy option accepted; adapters return the best transcript
  --compact, -q, --quiet   Shape JSON output
  --help, -h              Show help

Examples:
  android-speech transcribe --duration 5
  android-speech transcribe --source /var/minis/attachments/meeting.m4a
  android-speech transcribe --engine system --language zh-CN
  android-speech status

Mic input requires RECORD_AUDIO. File input does not request it.
Files: up to 25 MiB / 300 seconds, using installed Android audio decoders.
Paths must stay within /var/minis/{attachments,offloads,workspace,browser,shared};
relative paths use the shell cwd. Private paths require the requesting session.
System file input is best-effort on Android 13+; vendor services may reject it.
Availability checks service/configuration locally; they do not prove network or API credentials work.
"""
    }
}
