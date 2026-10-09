package com.openminis.app.speech

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Normalize platform-decoded PCM to the same mono PCM16/16 kHz used by provider capture. */
internal object SpeechPcmAudio {
    const val SAMPLE_RATE = 16_000
    const val MAX_SECONDS = 300
    const val MAX_DECODED_BYTES = 64 * 1024 * 1024

    fun toMono16k(pcm: ByteArray, sampleRate: Int, channels: Int): ByteArray {
        require(sampleRate in 8_000..192_000 && channels in 1..8) { "Unsupported PCM format" }
        val frameBytes = channels * 2
        require(pcm.isNotEmpty() && pcm.size % frameBytes == 0) { "Invalid PCM16 frame data" }
        val frames = pcm.size / frameBytes
        require(frames.toLong() <= sampleRate.toLong() * MAX_SECONDS) { "Audio exceeds 300 seconds" }
        val outputFrames = (frames.toLong() * SAMPLE_RATE / sampleRate).toInt()
        require(outputFrames > 0) { "Audio is too short" }
        val input = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        val output = ByteBuffer.allocate(outputFrames * 2).order(ByteOrder.LITTLE_ENDIAN)
        fun mono(frame: Int): Double {
            var sum = 0
            for (channel in 0 until channels) sum += input.getShort((frame * channels + channel) * 2).toInt()
            return sum.toDouble() / channels
        }
        for (i in 0 until outputFrames) {
            if (i % SAMPLE_RATE == 0 && Thread.currentThread().isInterrupted) throw InterruptedException()
            val position = i.toDouble() * sampleRate / SAMPLE_RATE
            val frame = position.toInt().coerceAtMost(frames - 1)
            val a = mono(frame)
            val b = mono((frame + 1).coerceAtMost(frames - 1))
            output.putShort((a + (b - a) * (position - frame)).toInt().coerceIn(-32768, 32767).toShort())
        }
        return output.array()
    }
}
