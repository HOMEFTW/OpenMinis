package com.openminis.app.speech

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import com.openminis.app.provider.voice.VoiceProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer

/** Decode local WAV/M4A/MP3/etc. through installed Android codecs; no microphone permission needed. */
internal object SpeechAudioDecoder {
    fun decode(file: File): ByteArray {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            FileInputStream(file).use { input ->
                val fileSize = input.channel.size()
                require(fileSize in 1..SpeechAudioFile.MAX_INPUT_BYTES.toLong()) { "Audio file exceeds 25 MiB" }
                extractor.setDataSource(input.fd, 0, fileSize)
                val deadline = SystemClock.elapsedRealtime() + 30_000L
                val track = (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                } ?: throw IllegalArgumentException("No decodable audio track")
                extractor.selectTrack(track)
                val format = extractor.getTrackFormat(track)
                if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    require(format.getLong(MediaFormat.KEY_DURATION) <= SpeechPcmAudio.MAX_SECONDS * 1_000_000L) {
                        "Audio exceeds 300 seconds"
                    }
                }
                var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME))
                if (mime == "audio/raw") {
                    require(!format.containsKey(MediaFormat.KEY_PCM_ENCODING) ||
                        format.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_16BIT) {
                        "WAV input must contain PCM16 audio"
                    }
                    val pcm = ByteArrayOutputStream()
                    val buffer = ByteBuffer.allocate(1024 * 1024)
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw InterruptedException()
                        require(SystemClock.elapsedRealtime() < deadline) { "Audio decoding timed out" }
                        buffer.clear()
                        val count = extractor.readSampleData(buffer, 0)
                        if (count < 0) break
                        require(pcm.size().toLong() + count <= SpeechPcmAudio.MAX_DECODED_BYTES) { "Decoded audio exceeds 64 MiB" }
                        pcm.write(buffer.array(), 0, count)
                        if (!extractor.advance()) break
                    }
                    return VoiceProvider.wrapPcm16InWav(
                        SpeechPcmAudio.toMono16k(pcm.toByteArray(), sampleRate, channels), SpeechPcmAudio.SAMPLE_RATE,
                    )
                }
                val decoder = MediaCodec.createDecoderByType(mime)
                codec = decoder
                format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                decoder.configure(format, null, null, 0)
                decoder.start()
                val pcm = ByteArrayOutputStream()
                val info = MediaCodec.BufferInfo()
                var inputEnded = false
                var outputEnded = false
                while (!outputEnded) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    require(SystemClock.elapsedRealtime() < deadline) { "Audio decoding timed out" }
                    if (!inputEnded) {
                        val index = decoder.dequeueInputBuffer(10_000)
                        if (index >= 0) {
                            val buffer = requireNotNull(decoder.getInputBuffer(index))
                            buffer.clear()
                            val count = extractor.readSampleData(buffer, 0)
                            if (count < 0) {
                                decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEnded = true
                            } else {
                                decoder.queueInputBuffer(index, 0, count, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val index = decoder.dequeueOutputBuffer(info, 10_000)
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val outputFormat = decoder.outputFormat
                        val nextRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        val nextChannels = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        require(pcm.size() == 0 || (sampleRate == nextRate && channels == nextChannels)) {
                            "Audio format changed during decoding"
                        }
                        sampleRate = nextRate
                        channels = nextChannels
                        require(!outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING) ||
                            outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_16BIT) {
                            "Decoder did not produce PCM16 audio"
                        }
                    } else if (index >= 0) {
                        try {
                            require(pcm.size().toLong() + info.size <= SpeechPcmAudio.MAX_DECODED_BYTES) {
                                "Decoded audio exceeds 64 MiB"
                            }
                            if (info.size > 0) {
                                val buffer = requireNotNull(decoder.getOutputBuffer(index))
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                val bytes = ByteArray(info.size)
                                buffer.get(bytes)
                                pcm.write(bytes)
                            }
                            outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        } finally {
                            decoder.releaseOutputBuffer(index, false)
                        }
                    }
                }
                return VoiceProvider.wrapPcm16InWav(
                    SpeechPcmAudio.toMono16k(pcm.toByteArray(), sampleRate, channels),
                    SpeechPcmAudio.SAMPLE_RATE,
                )
            }
        } finally {
            codec?.let { runCatching { it.stop() }; runCatching { it.release() } }
            extractor.release()
        }
    }
}
