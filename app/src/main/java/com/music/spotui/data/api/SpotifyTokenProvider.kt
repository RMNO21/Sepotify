package com.music.spotui.data.api

import android.content.Context
import android.util.Log
import com.metrolist.spotify.Spotify
import com.metrolist.spotify.SpotifyAuth
import com.music.spotui.data.network.NetworkMonitor
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Ensures the [Spotify] singleton holds a valid web access token before any
 * metadata call.
 *
 * Fast offline check: returns immediately if device has no network to avoid
 * freezing UI or delaying startup.
 */
object SpotifyTokenProvider {
    private const val TAG = "SpotifyTokenProvider"
    private val mutex = Mutex()
    private var expiresAtMs: Long = 0L

    suspend fun ensureToken(context: Context): Boolean = mutex.withLock {
        val now = System.currentTimeMillis()
        if (Spotify.accessToken != null && now < expiresAtMs - 60_000L) {
            return@withLock true
        }

        // Fast offline check: do not block or timeout when offline
        if (!NetworkMonitor.isOnlineNow(context)) {
            Log.d(TAG, "Offline — skipping token network request")
            return@withLock false
        }

        val spDc = SpotifySession.spDc(context)
        if (spDc.isBlank()) {
            Log.w(TAG, "No sp_dc cookie set — Spotify data unavailable")
            return@withLock false
        }
        SpotifyAuth.fetchAccessToken(spDc).fold(
            onSuccess = { token ->
                Spotify.accessToken = token.accessToken
                expiresAtMs = token.accessTokenExpirationTimestampMs
                Log.d(TAG, "Spotify token refreshed")
                true
            },
            onFailure = {
                Log.e(TAG, "Failed to fetch Spotify token", it)
                false
            },
        )
    }
}
