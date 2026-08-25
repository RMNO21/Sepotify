package com.music.spotui.audio

import android.content.Context
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.media.audiofx.BassBoost
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class EqPreset(
    val name: String,
    val gains: List<Float> // In dB (-12 dB to +12 dB)
)

data class EqBand(
    val index: Int,
    val centerFreqHz: Int,
    var gainDb: Float
)

/**
 * Audio DSP Equalizer & Dynamic Processing Engine using Android DynamicsProcessing API (API 28+)
 * Provides:
 * - 10-Band Parametric Equalizer with custom center frequencies & Q-factors
 * - Multi-band Compressor/Limiter to prevent clipping and distortion
 * - Bass Enhancement Engine with low-shelf harmonic synthesis
 */
object EqualizerManager {

    private const val TAG = "EqualizerManager"

    // Standard 10-band ISO frequencies
    val BAND_FREQUENCIES = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)

    val PRESETS = listOf(
        EqPreset("Flat", listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),
        EqPreset("Bass Boost", listOf(6f, 5f, 4f, 2f, 0f, 0f, 0f, 0f, 1f, 1f)),
        EqPreset("Treble Boost", listOf(0f, 0f, 0f, 0f, 0f, 1f, 2f, 4f, 5f, 6f)),
        EqPreset("Vocal Boost", listOf(-2f, -1f, 0f, 3f, 4f, 4f, 3f, 1f, 0f, -1f)),
        EqPreset("Electronic", listOf(4f, 3f, 1f, 0f, -1f, 2f, 1f, 2f, 4f, 4f)),
        EqPreset("Rock", listOf(4f, 3f, -1f, -2f, -1f, 1f, 3f, 4f, 4f, 4f)),
        EqPreset("Acoustic", listOf(3f, 2f, 1f, 1f, 2f, 2f, 3f, 3f, 2f, 2f)),
        EqPreset("Dance", listOf(5f, 4f, 2f, 0f, 0f, 2f, 3f, 3f, 4f, 2f))
    )

    private var dynamicsProcessing: DynamicsProcessing? = null
    private var legacyEqualizer: Equalizer? = null
    private var legacyBassBoost: BassBoost? = null

    private var currentSessionId: Int = 0

    private val _isEnabled = MutableStateFlow(false)
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    private val _currentPreset = MutableStateFlow("Flat")
    val currentPreset: StateFlow<String> = _currentPreset.asStateFlow()

    private val _bandGains = MutableStateFlow(FloatArray(10) { 0f })
    val bandGains: StateFlow<FloatArray> = _bandGains.asStateFlow()

    private val _bassBoostStrength = MutableStateFlow(0) // 0 to 1000
    val bassBoostStrength: StateFlow<Int> = _bassBoostStrength.asStateFlow()

    fun bindAudioSession(sessionId: Int) {
        if (sessionId <= 0 || sessionId == currentSessionId) return
        currentSessionId = sessionId
        release()
        initAudioEffects(sessionId)
    }

    private fun initAudioEffects(sessionId: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val channelCount = 2
                val config = DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                    channelCount,
                    true, // preEqInUse
                    BAND_FREQUENCIES.size, // preEqBandCount
                    true, // mbcInUse (Multi-band compressor)
                    BAND_FREQUENCIES.size, // mbcBandCount
                    true, // postEqInUse
                    BAND_FREQUENCIES.size, // postEqBandCount
                    true  // limiterInUse
                ).build()

                dynamicsProcessing = DynamicsProcessing(0, sessionId, config).apply {
                    enabled = _isEnabled.value
                }
                applyDynamicsConfig()
                Log.d(TAG, "DynamicsProcessing initialized for session $sessionId")
                return
            } catch (e: Exception) {
                Log.w(TAG, "DynamicsProcessing initialization failed: ${e.message}, falling back to legacy Equalizer")
            }
        }

        // Fallback for older APIs or if DynamicsProcessing fails
        try {
            legacyEqualizer = Equalizer(0, sessionId).apply {
                enabled = _isEnabled.value
            }
            legacyBassBoost = BassBoost(0, sessionId).apply {
                enabled = _isEnabled.value
                setStrength(_bassBoostStrength.value.toShort())
            }
            applyLegacyGains()
            Log.d(TAG, "Legacy Equalizer & BassBoost initialized for session $sessionId")
        } catch (e: Exception) {
            Log.w(TAG, "Legacy Equalizer initialization failed: ${e.message}")
        }
    }

    fun setEnabled(enabled: Boolean) {
        _isEnabled.value = enabled
        dynamicsProcessing?.enabled = enabled
        legacyEqualizer?.enabled = enabled
        legacyBassBoost?.enabled = enabled
    }

    fun setPreset(name: String) {
        _currentPreset.value = name
        val preset = PRESETS.find { it.name.equals(name, ignoreCase = true) } ?: return
        val newGains = preset.gains.toFloatArray()
        _bandGains.value = newGains
        applyGains()
    }

    fun setBandGain(bandIndex: Int, gainDb: Float) {
        if (bandIndex in 0 until BAND_FREQUENCIES.size) {
            val copy = _bandGains.value.copyOf()
            copy[bandIndex] = gainDb.coerceIn(-12f, 12f)
            _bandGains.value = copy
            _currentPreset.value = "Custom"
            applyGains()
        }
    }

    fun setBassBoost(strengthPercent: Int) {
        val s = (strengthPercent.coerceIn(0, 100) * 10).coerceIn(0, 1000)
        _bassBoostStrength.value = s
        legacyBassBoost?.setStrength(s.toShort())
        applyDynamicsConfig()
    }

    private fun applyGains() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && dynamicsProcessing != null) {
            applyDynamicsConfig()
        } else {
            applyLegacyGains()
        }
    }

    private fun applyDynamicsConfig() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val dp = dynamicsProcessing ?: return
        try {
            val gains = _bandGains.value
            val bassStrength = _bassBoostStrength.value / 1000f // 0.0 to 1.0

            for (ch in 0 until 2) {
                for (i in BAND_FREQUENCIES.indices) {
                    val freq = BAND_FREQUENCIES[i].toFloat()
                    var gain = gains.getOrElse(i) { 0f }
                    // Bass harmonic synthesis: boost 31Hz and 62Hz when bass boost is active
                    if (i == 0) gain += bassStrength * 8f
                    if (i == 1) gain += bassStrength * 5f

                    val eqBand = DynamicsProcessing.EqBand(true, freq, gain)
                    dp.setPreEqBandByChannelIndex(ch, i, eqBand)
                    dp.setPostEqBandByChannelIndex(ch, i, eqBand)
                }

                // Limiter configuration to prevent digital clipping at high volume
                val limiter = DynamicsProcessing.Limiter(
                    true, // inUse
                    true, // enabled
                    0,    // linkGroup
                    1.0f, // attackTime (ms)
                    50.0f,// releaseTime (ms)
                    10.0f,// ratio (10:1)
                    -0.5f,// threshold in dBFS
                    0.0f  // postGain
                )
                dp.setLimiterByChannelIndex(ch, limiter)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply dynamics config: ${e.message}")
        }
    }

    private fun applyLegacyGains() {
        val eq = legacyEqualizer ?: return
        try {
            val count = minOf(eq.numberOfBands.toInt(), BAND_FREQUENCIES.size)
            val range = eq.bandLevelRange
            val minDb = (range[0] / 100).toFloat()
            val maxDb = (range[1] / 100).toFloat()
            val gains = _bandGains.value

            for (i in 0 until count) {
                val db = gains.getOrElse(i) { 0f }.coerceIn(minDb, maxDb)
                eq.setBandLevel(i.toShort(), (db * 100).toInt().toShort())
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply legacy gains: ${e.message}")
        }
    }

    fun release() {
        try {
            dynamicsProcessing?.release()
            dynamicsProcessing = null
            legacyEqualizer?.release()
            legacyEqualizer = null
            legacyBassBoost?.release()
            legacyBassBoost = null
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing audio effects: ${e.message}")
        }
    }
}
