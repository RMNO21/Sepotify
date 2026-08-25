package com.music.spotui.piped

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fallback audio stream resolver using public, ad-free Piped and Invidious backends.
 * If YouTube InnerTube throttles or blocks streams (HTTP 403 / 429), this extracts
 * direct high-quality OPUS / AAC audio streams.
 */
object PipedSource {

    private const val TAG = "PipedSource"

    private val PIPED_INSTANCES = listOf(
        "https://pipedapi.kavin.rocks",
        "https://api.piped.privacydev.net",
        "https://piped-api.lunar.icu",
        "https://api.piped.projectsegfau.lt",
        "https://pipedapi.tokhmi.xyz",
    )

    data class PipedAudioStream(
        val url: String,
        val bitrate: Int,
        val mimeType: String,
        val qualityLabel: String,
    )

    /**
     * Resolves a YouTube videoId to an ad-free direct audio stream.
     */
    suspend fun resolveAudioStream(videoId: String): PipedAudioStream? = withContext(Dispatchers.IO) {
        if (videoId.isBlank() || videoId.length != 11) return@withContext null

        for (instance in PIPED_INSTANCES) {
            try {
                val urlStr = "$instance/streams/$videoId"
                val jsonStr = httpGet(urlStr) ?: continue
                val json = JSONObject(jsonStr)
                val audioStreams = json.optJSONArray("audioStreams") ?: continue

                var bestStream: PipedAudioStream? = null
                var maxBitrate = 0

                for (i in 0 until audioStreams.length()) {
                    val stream = audioStreams.optJSONObject(i) ?: continue
                    val streamUrl = stream.optString("url", "")
                    if (streamUrl.isBlank() || !streamUrl.startsWith("http")) continue

                    val bitrate = stream.optInt("bitrate", 0)
                    val mimeType = stream.optString("mimeType", "audio/webm")
                    val codec = stream.optString("codec", "opus")

                    if (bitrate > maxBitrate || bestStream == null) {
                        maxBitrate = bitrate
                        val kbps = if (bitrate > 1000) bitrate / 1000 else bitrate
                        bestStream = PipedAudioStream(
                            url = streamUrl,
                            bitrate = bitrate,
                            mimeType = mimeType,
                            qualityLabel = "${codec.uppercase()} ${kbps} kbps",
                        )
                    }
                }

                if (bestStream != null) {
                    Log.d(TAG, "Resolved Piped audio stream for $videoId (${bestStream.qualityLabel}) via $instance")
                    return@withContext bestStream
                }
            } catch (e: Exception) {
                Log.d(TAG, "Piped instance $instance failed for $videoId: ${e.message}")
            }
        }
        null
    }

    private fun httpGet(urlStr: String): String? {
        return runCatching {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3500
                readTimeout = 4000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                setRequestProperty("Accept", "application/json")
            }
            conn.connect()
            val code = conn.responseCode
            val text = if (code in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                null
            }
            conn.disconnect()
            text
        }.getOrNull()
    }
}
