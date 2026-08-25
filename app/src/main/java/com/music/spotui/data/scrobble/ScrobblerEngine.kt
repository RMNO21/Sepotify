package com.music.spotui.data.scrobble

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Audio Scrobbling Engine for Last.fm & ListenBrainz.
 * - Submits `nowPlaying` status upon track start.
 * - Submits scrobble payload after user listens to at least 50% or 240 seconds of the track.
 */
object ScrobblerEngine {

    private const val TAG = "ScrobblerEngine"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val httpClient = OkHttpClient()

    private const val PREFS = "scrobble_prefs"
    private const val KEY_LASTFM_ENABLED = "lastfm_enabled"
    private const val KEY_LASTFM_SESSION = "lastfm_session"
    private const val KEY_LASTFM_API_KEY = "lastfm_api_key"
    private const val KEY_LASTFM_SECRET = "lastfm_secret"

    private const val KEY_LISTENBRAINZ_ENABLED = "listenbrainz_enabled"
    private const val KEY_LISTENBRAINZ_TOKEN = "listenbrainz_token"

    private var currentTrackTitle: String = ""
    private var currentTrackArtist: String = ""
    private var currentTrackAlbum: String = ""
    private var currentTrackDurationMs: Long = 0L
    private var playbackStartTimeMs: Long = 0L
    private var hasScrobbledCurrentTrack: Boolean = false

    fun isLastFmEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_LASTFM_ENABLED, false)

    fun isListenBrainzEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_LISTENBRAINZ_ENABLED, false)

    fun onTrackStarted(
        context: Context,
        title: String,
        artist: String,
        album: String,
        durationMs: Long
    ) {
        if (title.isBlank() || artist.isBlank()) return

        currentTrackTitle = title
        currentTrackArtist = artist
        currentTrackAlbum = album
        currentTrackDurationMs = durationMs
        playbackStartTimeMs = System.currentTimeMillis()
        hasScrobbledCurrentTrack = false

        if (isLastFmEnabled(context)) {
            sendLastFmNowPlaying(context, title, artist, album)
        }
        if (isListenBrainzEnabled(context)) {
            sendListenBrainzNowPlaying(context, title, artist, album)
        }
    }

    fun onPlaybackProgress(context: Context, positionMs: Long) {
        if (hasScrobbledCurrentTrack || currentTrackTitle.isBlank()) return

        // Scrobble condition: 50% of track or 240 seconds
        val halfDuration = if (currentTrackDurationMs > 0) currentTrackDurationMs / 2 else 120_000L
        val threshold = minOf(halfDuration, 240_000L)

        if (positionMs >= threshold) {
            hasScrobbledCurrentTrack = true
            val timestampSec = playbackStartTimeMs / 1000

            if (isLastFmEnabled(context)) {
                sendLastFmScrobble(context, currentTrackTitle, currentTrackArtist, currentTrackAlbum, timestampSec)
            }
            if (isListenBrainzEnabled(context)) {
                sendListenBrainzScrobble(context, currentTrackTitle, currentTrackArtist, currentTrackAlbum, timestampSec)
            }
        }
    }

    private fun sendLastFmNowPlaying(context: Context, title: String, artist: String, album: String) {
        scope.launch {
            try {
                val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val sessionKey = sp.getString(KEY_LASTFM_SESSION, "") ?: ""
                val apiKey = sp.getString(KEY_LASTFM_API_KEY, "") ?: ""
                val secret = sp.getString(KEY_LASTFM_SECRET, "") ?: ""
                if (sessionKey.isBlank() || apiKey.isBlank() || secret.isBlank()) return@launch

                val params = sortedMapOf(
                    "method" to "track.updateNowPlaying",
                    "track" to title,
                    "artist" to artist,
                    "album" to album,
                    "api_key" to apiKey,
                    "sk" to sessionKey
                )
                val sig = generateLastFmSignature(params, secret)
                val formBuilder = FormBody.Builder()
                params.forEach { (k, v) -> formBuilder.add(k, v) }
                formBuilder.add("api_sig", sig)
                formBuilder.add("format", "json")

                val request = Request.Builder()
                    .url("https://ws.audioscrobbler.com/2.0/")
                    .post(formBuilder.build())
                    .build()

                httpClient.newCall(request).execute().use { res ->
                    Log.d(TAG, "Last.fm updateNowPlaying response: ${res.code}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Last.fm updateNowPlaying error: ${e.message}")
            }
        }
    }

    private fun sendLastFmScrobble(context: Context, title: String, artist: String, album: String, timestampSec: Long) {
        scope.launch {
            try {
                val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val sessionKey = sp.getString(KEY_LASTFM_SESSION, "") ?: ""
                val apiKey = sp.getString(KEY_LASTFM_API_KEY, "") ?: ""
                val secret = sp.getString(KEY_LASTFM_SECRET, "") ?: ""
                if (sessionKey.isBlank() || apiKey.isBlank() || secret.isBlank()) return@launch

                val params = sortedMapOf(
                    "method" to "track.scrobble",
                    "track" to title,
                    "artist" to artist,
                    "album" to album,
                    "timestamp" to timestampSec.toString(),
                    "api_key" to apiKey,
                    "sk" to sessionKey
                )
                val sig = generateLastFmSignature(params, secret)
                val formBuilder = FormBody.Builder()
                params.forEach { (k, v) -> formBuilder.add(k, v) }
                formBuilder.add("api_sig", sig)
                formBuilder.add("format", "json")

                val request = Request.Builder()
                    .url("https://ws.audioscrobbler.com/2.0/")
                    .post(formBuilder.build())
                    .build()

                httpClient.newCall(request).execute().use { res ->
                    Log.d(TAG, "Last.fm scrobble submitted: ${res.code}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Last.fm scrobble error: ${e.message}")
            }
        }
    }

    private fun sendListenBrainzNowPlaying(context: Context, title: String, artist: String, album: String) {
        scope.launch {
            try {
                val token = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_LISTENBRAINZ_TOKEN, "") ?: ""
                if (token.isBlank()) return@launch

                val payload = JSONObject().apply {
                    put("listen_type", "playing_now")
                    put("payload", JSONArray().apply {
                        put(JSONObject().apply {
                            put("track_metadata", JSONObject().apply {
                                put("artist_name", artist)
                                put("track_name", title)
                                put("release_name", album)
                            })
                        })
                    })
                }

                val body = payload.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("https://api.listenbrainz.org/1/submit-listens")
                    .header("Authorization", "Token $token")
                    .post(body)
                    .build()

                httpClient.newCall(request).execute().use { res ->
                    Log.d(TAG, "ListenBrainz now-playing status: ${res.code}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "ListenBrainz now-playing error: ${e.message}")
            }
        }
    }

    private fun sendListenBrainzScrobble(context: Context, title: String, artist: String, album: String, timestampSec: Long) {
        scope.launch {
            try {
                val token = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_LISTENBRAINZ_TOKEN, "") ?: ""
                if (token.isBlank()) return@launch

                val payload = JSONObject().apply {
                    put("listen_type", "single")
                    put("payload", JSONArray().apply {
                        put(JSONObject().apply {
                            put("listened_at", timestampSec)
                            put("track_metadata", JSONObject().apply {
                                put("artist_name", artist)
                                put("track_name", title)
                                put("release_name", album)
                            })
                        })
                    })
                }

                val body = payload.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("https://api.listenbrainz.org/1/submit-listens")
                    .header("Authorization", "Token $token")
                    .post(body)
                    .build()

                httpClient.newCall(request).execute().use { res ->
                    Log.d(TAG, "ListenBrainz scrobble submitted: ${res.code}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "ListenBrainz scrobble error: ${e.message}")
            }
        }
    }

    private fun generateLastFmSignature(params: Map<String, String>, secret: String): String {
        val s = buildString {
            params.forEach { (k, v) ->
                append(k)
                append(v)
            }
            append(secret)
        }
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(s.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
