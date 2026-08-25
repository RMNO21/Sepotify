package com.music.spotui.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Real-time Loudness Normalization AudioProcessor targeting -14 LUFS (EBU R128 standard).
 * Evaluates short-term RMS energy across PCM 16-bit buffers and smoothly scales gain,
 * with an integrated soft-knee limiter to prevent digital clipping before DAC delivery.
 */
@UnstableApi
class LoudnessNormalizerAudioProcessor : BaseAudioProcessor() {

    @Volatile
    var enabled: Boolean = true

    // Target loudness in LUFS (-14.0 LUFS standard)
    @Volatile
    var targetLufs: Float = -14.0f

    // Current dynamic gain multiplier applied to samples
    private var currentGain: Float = 1.0f
    private var targetGain: Float = 1.0f

    private var sampleRate: Int = 0
    private var channelCount: Int = 0

    // Reference digital full-scale peak (-14 LUFS is roughly 0.20 RMS full scale for sine reference)
    private val targetRms: Double
        get() = 10.0.pow((targetLufs + 3.01) / 20.0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        currentGain = 1.0f
        targetGain = 1.0f
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        if (!enabled || sampleRate == 0 || channelCount == 0) {
            val output = replaceOutputBuffer(remaining)
            copyBuffer(inputBuffer, output, remaining)
            output.flip()
            return
        }

        val output = replaceOutputBuffer(remaining)
        inputBuffer.order(ByteOrder.nativeOrder())

        val shortCount = remaining / 2
        var sumSquares = 0.0
        val sampleList = ShortArray(shortCount)

        var idx = 0
        while (inputBuffer.remaining() >= 2 && idx < shortCount) {
            val s = inputBuffer.short
            sampleList[idx++] = s
            val normalized = s.toDouble() / Short.MAX_VALUE
            sumSquares += normalized * normalized
        }

        // Calculate RMS of this chunk
        if (shortCount > 0) {
            val rms = sqrt(sumSquares / shortCount)
            if (rms > 0.005) { // Only adjust if audio is actively playing (ignore silence)
                val desiredGain = (targetRms / rms).toFloat().coerceIn(0.25f, 4.0f)
                // Smoothly slew target gain towards desired gain
                targetGain = (targetGain * 0.9f) + (desiredGain * 0.1f)
            }
        }

        // Apply gain with sample-by-sample smoothing and soft-knee limiting
        for (i in 0 until idx) {
            // Smooth gain ramp to prevent audio zipper noise
            currentGain += (targetGain - currentGain) * 0.005f

            var sample = (sampleList[i].toDouble() / Short.MAX_VALUE) * currentGain

            // Soft-knee cubic compression / limiting to eliminate harsh digital clipping
            if (sample > 0.90) {
                sample = 0.90 + (sample - 0.90) / (1.0 + (sample - 0.90).pow(2.0))
            } else if (sample < -0.90) {
                sample = -0.90 + (sample + 0.90) / (1.0 + (-sample - 0.90).pow(2.0))
            }

            val outShort = (sample.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()
            output.putShort(outShort)
        }

        // Copy any residual bytes
        while (inputBuffer.hasRemaining()) {
            output.put(inputBuffer.get())
        }

        output.flip()
    }

    private fun copyBuffer(src: ByteBuffer, dst: ByteBuffer, size: Int) {
        if (src === dst) {
            dst.position(0)
            dst.limit(size)
            return
        }
        val pos = src.position()
        val toCopy = min(size, src.remaining())
        for (i in 0 until toCopy) {
            dst.put(src.get(pos + i))
        }
        src.position(pos + toCopy)
    }

    override fun onFlush() {
        super.onFlush()
        currentGain = 1.0f
        targetGain = 1.0f
    }

    override fun onReset() {
        super.onReset()
        currentGain = 1.0f
        targetGain = 1.0f
    }
}
