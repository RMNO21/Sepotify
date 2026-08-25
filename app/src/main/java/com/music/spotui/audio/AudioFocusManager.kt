package com.music.spotui.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Audio Focus & System Peripheral State Machine.
 *
 * Handles:
 * 1. AUDIOFOCUS_GAIN: Resumes playback or ramps volume back up from ducked state.
 * 2. AUDIOFOCUS_LOSS: Pauses and releases playback session.
 * 3. AUDIOFOCUS_LOSS_TRANSIENT: Temporarily pauses playback (e.g. phone call).
 * 4. AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK: Lowers volume to 25% for GPS navigation / notifications.
 * 5. ACTION_AUDIO_BECOMING_NOISY: Instant pause on headphone / Bluetooth disconnection.
 */
class AudioFocusManager(
    private val context: Context,
    private val onPlay: () -> Unit = {},
    private val onPause: () -> Unit = {},
    private val onDuck: ((Boolean) -> Unit)? = null,
    private val getPlayer: (() -> ExoPlayer?) = { null }
) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var audioFocusRequest: AudioFocusRequest? = null
    private var isNoisyReceiverRegistered = false
    private var hasAudioFocus = false
    private var resumeOnFocusGain = false
    private var isDucked = false
    private var volumeRampJob: Job? = null

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        handleAudioFocusChange(focusChange)
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY == intent.action) {
                Log.d(TAG, "Peripheral disconnected (BECOMING_NOISY) -> Pausing playback immediately")
                onPause()
            }
        }
    }

    fun requestAudioFocus(): Boolean {
        if (hasAudioFocus) return true

        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val playbackAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(playbackAttributes)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(focusChangeListener)
                .build()

            audioFocusRequest = request
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                focusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }

        hasAudioFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        if (hasAudioFocus) {
            registerNoisyReceiver()
        }
        return hasAudioFocus
    }

    fun abandonAudioFocus() {
        if (!hasAudioFocus) return
        hasAudioFocus = false
        unregisterNoisyReceiver()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusChangeListener)
        }
    }

    fun requestFocus(): Boolean = requestAudioFocus()

    fun abandonFocus() = abandonAudioFocus()

    fun release() {
        abandonAudioFocus()
        unregisterNoisyReceiver()
    }

    private fun handleAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.d(TAG, "Audio focus gained")
                hasAudioFocus = true
                if (isDucked) {
                    onDuck?.invoke(false)
                    rampVolume(from = 0.25f, to = 1.0f, durationMs = 400)
                    isDucked = false
                }
                if (resumeOnFocusGain) {
                    resumeOnFocusGain = false
                    onPlay()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                Log.d(TAG, "Audio focus permanently lost")
                hasAudioFocus = false
                resumeOnFocusGain = false
                onPause()
                abandonAudioFocus()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Log.d(TAG, "Audio focus transiently lost (phone call/notification)")
                resumeOnFocusGain = true
                onPause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                Log.d(TAG, "Audio focus transiently lost with ducking allowed")
                isDucked = true
                onDuck?.invoke(true)
                rampVolume(from = 1.0f, to = 0.25f, durationMs = 300)
            }
        }
    }

    private fun rampVolume(from: Float, to: Float, durationMs: Long) {
        volumeRampJob?.cancel()
        val player = getPlayer() ?: return
        volumeRampJob = scope.launch {
            val steps = 15
            val stepDelay = durationMs / steps
            for (i in 1..steps) {
                val fraction = i.toFloat() / steps
                val currentVol = from + (to - from) * fraction
                player.volume = currentVol.coerceIn(0f, 1f)
                delay(stepDelay)
            }
            player.volume = to.coerceIn(0f, 1f)
        }
    }

    private fun registerNoisyReceiver() {
        if (isNoisyReceiverRegistered) return
        try {
            val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(noisyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(noisyReceiver, filter)
            }
            isNoisyReceiverRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register noisy receiver: ${e.message}")
        }
    }

    private fun unregisterNoisyReceiver() {
        if (!isNoisyReceiverRegistered) return
        try {
            context.unregisterReceiver(noisyReceiver)
        } catch (e: Exception) {
            // Receiver not registered
        } finally {
            isNoisyReceiverRegistered = false
        }
    }

    companion object {
        private const val TAG = "AudioFocusManager"
    }
}
