package com.music.spotui.di

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.utils.YouTubeUrlParser
import com.metrolist.music.constants.AudioQuality
import com.metrolist.music.utils.YTPlayerUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin

/**
 * Plays audio resolved from YouTube. The `song` argument is a "title artist"
 * search query (set by the Spotify-backed data layer): it's matched to a
 * YouTube video, whose stream URL is resolved via the ported [YTPlayerUtils]
 * flow (cipher / PoToken / sabr) and handed to ExoPlayer.
 */
// ── Playlist Download Batch Progress ──
data class PlaylistBatchStatus(
    val playlistId: String,
    val playlistName: String,
    val totalTracks: Int,
    val completedTracks: Int,
    val failedTracks: Int,
    val currentTrackTitle: String,
    val isDownloading: Boolean,
) {
    val progressPercent: Int
        get() = if (totalTracks > 0) ((completedTracks * 100) / totalTracks).coerceIn(0, 100) else 0
}

object SongPlayer {
    private const val TAG = "SongPlayer"
    private const val SPOTIFY_TRACK_PREFIX = "spotify:track:"
    private var player: ExoPlayer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Active playlist / album batch downloads
    private val _batchDownloads = MutableStateFlow<Map<String, PlaylistBatchStatus>>(emptyMap())
    val batchDownloads: StateFlow<Map<String, PlaylistBatchStatus>> = _batchDownloads.asStateFlow()

    // Concurrency limiter to prevent network flooding and rate-limiting
    private val downloadSemaphore = Semaphore(2)

    fun getBatchProgress(playlistId: String): PlaylistBatchStatus? = _batchDownloads.value[playlistId]
    fun isPlaylistDownloading(playlistId: String): Boolean = _batchDownloads.value[playlistId]?.isDownloading == true
    fun isAnyBatchDownloading(): Boolean = _batchDownloads.value.values.any { it.isDownloading }
    fun cancelBatchDownload(playlistId: String) {
        _batchDownloads.value = _batchDownloads.value - playlistId
        onDownloadsChanged?.invoke()
    }

    // Cache of resolved stream URLs keyed by the "title artist" query, so replays
    // and prefetched neighbours start instantly instead of re-hitting the network.
    private val streamCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    // Which engine each cached stream came from ("YouTube", "Lossless • …") so a
    // cache hit can restore the correct source badge.
    private val sourceCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val failedSourcesForSong = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()
    private val failedVideoIdsForSong = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()
    private val activeResolvedVideoId = java.util.concurrent.ConcurrentHashMap<String, String>()

    // ── Lossless (SpotiFLAC) ──
    // When enabled, playback first tries to resolve a lossless FLAC stream (Tidal/
    // Amazon via SpotiFLAC's free community proxies) for the current track and only
    // falls back to YouTube if no FLAC is available or the proxies are throttled.
    // Trades a little first-tap latency for true lossless audio.
    @Volatile var losslessStreaming = true
    @Volatile var losslessHiRes = true

    // Source kill-switches. The Spotify web player is currently broken (off).
    // YouTube is the last-resort fallback, kept on so tracks SpotiFLAC misses or
    // can't serve during a proxy cooldown still play — with the wrong-song guards
    // (videoId match check + artist/title scoring + candidate fallback).
    @Volatile var webPlayerEnabled = false
    @Volatile var youtubeEnabled = true

    // ── Playback Status ──
    sealed interface PlaybackStatus {
        data object Idle : PlaybackStatus
        data class Loading(val message: String = "Loading stream…") : PlaybackStatus
        data class Buffering(val source: String = "", val quality: String = "") : PlaybackStatus
        data class Playing(val source: String = "", val quality: String = "") : PlaybackStatus
        data class Paused(val source: String = "", val quality: String = "") : PlaybackStatus
        data class Reconnecting(val attempt: Int, val message: String = "Reconnecting…") : PlaybackStatus
        data class Error(val message: String, val canRetry: Boolean = true) : PlaybackStatus
        data class OfflineBufferExhausted(val message: String = "Offline buffer exhausted") : PlaybackStatus
    }

    private val _playbackStatus = MutableStateFlow<PlaybackStatus>(PlaybackStatus.Idle)
    val playbackStatus: StateFlow<PlaybackStatus> = _playbackStatus.asStateFlow()

    // ── Deezer ──
    // When enabled and a Deezer account is logged in (ARL stored), playback tries
    // Deezer FIRST (ISRC → Deezer track → encrypted CDN stream, decrypted on the
    // fly by DeezerDataSource). Quality follows the account tier: free → MP3 128,
    // Premium → MP3 320 / FLAC. Falls back to SpotiFLAC/YouTube on any miss.
    @Volatile var deezerEnabled = true

    // Which engine is feeding the CURRENT track, for the on-screen source badge.
    // "Lossless" (SpotiFLAC: Tidal/Qobuz/Amazon) is NOT Spotify — surfaced so the
    // user knows real Spotify vs a lossless mirror vs the YouTube fallback.
    @Volatile var currentSource: String = "YouTube"
        private set
    // Human-readable quality of the CURRENT stream (e.g. "FLAC 16-bit",
    // "OPUS 141 kbps"), shown next to the source badge.
    @Volatile var currentQuality: String = ""
        private set
    private val qualityCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    // Maps a "title artist" play query -> the track's real Spotify id, so the
    // lossless resolver can be seeded from a play site that only has the query.
    // Populated centrally whenever the queue changes (see CurrentSongState).
    private val trackIdRegistry = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val alternativeKeyRegistry = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Register query→spotifyTrackId pairs so lossless can be resolved by query. */
    fun registerLossless(pairs: List<Pair<String, String>>) {
        pairs.forEach { (query, spotifyId) ->
            if (query.isNotBlank() && spotifyId.isNotBlank()) trackIdRegistry[query] = spotifyId
        }
    }

    fun registerAlternativeKeys(pairs: List<Pair<String, String>>) {
        pairs.forEach { (query, key) ->
            if (query.isNotBlank() && key.isNotBlank()) alternativeKeyRegistry[query] = key
        }
    }

    // Whether each play query is the explicit version on Spotify, so the YouTube
    // fallback can pick the matching (explicit vs clean) edit.
    private val explicitRegistry = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** Register query→explicit pairs (populated whenever the queue changes). */
    fun registerExplicit(pairs: List<Pair<String, Boolean>>) {
        pairs.forEach { (query, explicit) ->
            if (query.isNotBlank()) explicitRegistry[query] = explicit
        }
    }

    // Expected track length (ms) per query, from Spotify — lets the YouTube match
    // reject a same-title song by a different artist (different duration).
    private val durationRegistry = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** Register query→durationMs pairs (populated whenever the queue changes). */
    fun registerDuration(pairs: List<Pair<String, Int>>) {
        pairs.forEach { (query, ms) ->
            if (query.isNotBlank() && ms > 0) durationRegistry[query] = ms
        }
    }

    data class TrackMatchMetadata(
        val title: String,
        val artist: String,
        val album: String,
    )

    private val metadataRegistry =
        java.util.concurrent.ConcurrentHashMap<String, TrackMatchMetadata>()

    /** Register query→Spotify metadata pairs for strict YouTube result scoring. */
    fun registerMetadata(pairs: List<Pair<String, TrackMatchMetadata>>) {
        pairs.forEach { (query, meta) ->
            if (query.isNotBlank() && meta.title.isNotBlank()) metadataRegistry[query] = meta
        }
    }
    // Tracks which query is the latest play request so a slow resolve for an old
    // tap doesn't clobber a newer one (fast switching).
    @Volatile private var currentRequest: String = ""

    // Latest track metadata (title / artist / cover URL) so the MediaItem we build
    // carries it into the system media notification. Set via [setNowPlayingMeta]
    // (driven by CurrentSongState) just before / as playback starts.
    @Volatile private var metaTitle: String = ""
    @Volatile private var metaArtist: String = ""
    @Volatile private var metaCover: String = ""

    fun setNowPlayingMeta(title: String, artist: String, coverUri: String) {
        metaTitle = title
        metaArtist = artist
        metaCover = coverUri
        if (title.isNotBlank() || artist.isNotBlank()) {
            scope.launch(Dispatchers.Main) {
                player?.let { p ->
                    val currentItem = p.currentMediaItem ?: return@let
                    val updatedMetadata = currentItem.mediaMetadata.buildUpon()
                        .setTitle(title.ifBlank { currentItem.mediaMetadata.title })
                        .setArtist(artist.ifBlank { currentItem.mediaMetadata.artist })
                        .apply { if (coverUri.isNotBlank()) setArtworkUri(android.net.Uri.parse(coverUri)) }
                        .build()
                    val updatedItem = currentItem.buildUpon().setMediaMetadata(updatedMetadata).build()
                    p.replaceMediaItem(p.currentMediaItemIndex, updatedItem)
                }
            }
        }
    }

    /**
     * Build a stable playback identity for Spotify tracks. The full value is used
     * as cache/registry key, while only the text after "|" is sent to YouTube
     * search. This prevents same-title/same-artist tracks from reusing each
     * other's resolved stream.
     */
    fun buildSpotifyPlayQuery(spotifyTrackId: String, title: String, artist: String): String {
        val searchText = listOf(cleanSpotifySearchTitle(title), artist)
            .filter { it.isNotBlank() }
            .joinToString(" ")
        return if (spotifyTrackId.isBlank()) searchText else "$SPOTIFY_TRACK_PREFIX$spotifyTrackId|$searchText"
    }

    private val featSearchPattern = Regex("""\s*[\(\[]\s*(feat|ft|with|prod)\.?\s+.*?[\]\)]""", RegexOption.IGNORE_CASE)
    private val remasterSearchPattern = Regex("""\s*[-–—]?\s*[\(\[]?\s*(\d{4}\s+)?remaster(ed)?(\s+\d{4})?\s*[\)\]]?""", RegexOption.IGNORE_CASE)
    private val editSearchPattern = Regex("""\s*[-–—]?\s*[\(\[]?\s*(radio\s+edit|single\s+version|original\s+mix|album\s+version|deluxe\s+edition|bonus\s+track|anniversary\s+edition)\s*[\)\]]?""", RegexOption.IGNORE_CASE)
    private val ostSearchPattern = Regex("""\s*[\(\[]\s*(from\s+(the\s+)?(motion\s+picture|soundtrack|ost|movie|series|film).*?)[\]\)]""", RegexOption.IGNORE_CASE)
    private val trailingFeatPattern = Regex("""\s*[-–—:]\s*(feat|ft|with|prod)\.?\s+.*$""", RegexOption.IGNORE_CASE)

    private fun cleanSpotifySearchTitle(title: String): String =
        title
            .replace(featSearchPattern, "")
            .replace(trailingFeatPattern, "")
            .replace(remasterSearchPattern, "")
            .replace(editSearchPattern, "")
            .replace(ostSearchPattern, "")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun searchTextForPlayback(song: String): String =
        if (song.startsWith(SPOTIFY_TRACK_PREFIX) && song.contains('|')) {
            song.substringAfter('|').ifBlank { song }
        } else {
            song
        }

    private fun spotifyTrackIdForPlayback(song: String): String? =
        if (song.startsWith(SPOTIFY_TRACK_PREFIX)) {
            song.removePrefix(SPOTIFY_TRACK_PREFIX).substringBefore('|').takeIf { it.isNotBlank() }
        } else {
            null
        }

    fun videoIdFromYouTubeLink(text: String): String? =
        YouTubeUrlParser.extractVideoId(text)
            ?: text.trim().takeIf { it.matches(Regex("""[A-Za-z0-9_-]{11}""")) }

    fun invalidateResolvedStream(song: String) {
        streamCache.remove(song)
        sourceCache.remove(song)
        qualityCache.remove(song)
        com.music.spotui.player.StreamUrlCache.remove(song)
        val trackKey = if (song.startsWith(SPOTIFY_TRACK_PREFIX)) song.removePrefix(SPOTIFY_TRACK_PREFIX).substringBefore('|') else song
        com.music.spotui.player.StreamUrlCache.remove(trackKey)
        activeResolvedVideoId.remove(song)?.let { videoId ->
            YTPlayerUtils.forceRefreshForVideo(videoId)
        }
    }

    private var reconnectAttempt = 0
    private var reconnectJob: kotlinx.coroutines.Job? = null

    fun retryLastPlay(context: Context) {
        val song = currentRequest
        if (song.isNotBlank()) {
            reconnectAttempt = 0
            reconnectJob?.cancel()
            invalidateResolvedStream(song)
            playSong(song, context)
        }
    }

    private fun triggerReconnect(context: Context) {
        val song = currentRequest
        if (song.isBlank() || reconnectAttempt >= 3) {
            Log.w(TAG, "Max reconnect attempts reached or empty request — auto skipping to next track")
            scope.launch(Dispatchers.Main) { skipToNextTrack(context) }
            return
        }
        reconnectAttempt++
        val attempt = reconnectAttempt
        val delayMs = when (attempt) {
            1 -> 400L
            2 -> 1200L
            else -> 2500L
        }
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            _playbackStatus.value = PlaybackStatus.Reconnecting(attempt, "Reconnecting stream…")
            kotlinx.coroutines.delay(delayMs)
            if (currentRequest != song) return@launch
            invalidateResolvedStream(song)
            val pos = withContext(Dispatchers.Main) { player?.currentPosition ?: 0L }
            val newUrl = resolveStreamUrl(song, context.applicationContext, forPlayback = true)
            if (newUrl == null) {
                _playbackStatus.value = PlaybackStatus.Error("Network connection failed", canRetry = true)
                withContext(Dispatchers.Main) { skipToNextTrack(context) }
                return@launch
            }
            if (currentRequest != song) return@launch
            withContext(Dispatchers.Main) {
                ensurePlayer(context.applicationContext)
                val p = player ?: return@withContext
                p.stop()
                p.clearMediaItems()
                _playbackStatus.value = PlaybackStatus.Buffering(currentSource, currentQuality)
                p.setMediaItem(buildMediaItem(newUrl, streamMimeType(newUrl)))
                p.prepare()
                if (pos > 0) p.seekTo(pos)
                p.playWhenReady = true
            }
        }
    }


    @Volatile private var consecutiveFailures = 0
    @Volatile private var lastSkipTimestamp = 0L

    fun playSong(song: String, context: Context) {
        val appContext = context.applicationContext
        appCtx = appContext
        currentRequest = song
        reconnectAttempt = 0
        reconnectJob?.cancel()
        hasPreloadedCurrentTrack = false
        _playbackStatus.value = PlaybackStatus.Loading("Loading stream…")
        // A manual play (tap / next / prev) supersedes any in-flight crossfade.
        cancelCrossfade()
        // Do not keep the previous track audible while this request resolves.
        // Otherwise the UI can show the newly tapped track while old audio keeps
        // playing for several seconds, or forever if resolution fails.
        runCatching {
            player?.pause()
            player?.clearMediaItems()
        }

        // Podcast episodes are encoded as "episode:<id>" queries — play them via the
        // Spotify web player's episode page (same engine as tracks).
        if (song.startsWith("episode:") && webPlayerEnabled && SpotifyWebPlayer.canPlay &&
            com.music.spotui.data.preferences.isWebPlaybackEnabled(appContext)
        ) {
            runCatching { player?.pause() }
            currentSource = "Spotify"
            currentQuality = ""
            _playbackStatus.value = PlaybackStatus.Playing("Spotify", "")
            SpotifyWebPlayer.playEpisode(song.removePrefix("episode:"))
            return
        }

        // Downloaded tracks ALWAYS play the local file — instantly with 0ms delay!
        var downloadedPath = com.music.spotui.data.preferences.downloadedPathForQuery(appContext, song)
        if (downloadedPath == null && song.startsWith(SPOTIFY_TRACK_PREFIX)) {
            val spotifyId = song.removePrefix(SPOTIFY_TRACK_PREFIX).substringBefore('|').trim()
            downloadedPath = com.music.spotui.data.preferences.downloadedPathForQuery(appContext, spotifyId)
        }
        if (downloadedPath == null && metadataRegistry[song] != null) {
            val meta = metadataRegistry[song]!!
            downloadedPath = com.music.spotui.data.preferences.downloadedPathForQuery(appContext, "${meta.title} ${meta.artist}")
                ?: com.music.spotui.data.preferences.downloadedPathForQuery(appContext, meta.title)
        }

        if (downloadedPath != null && java.io.File(downloadedPath).exists()) {
            consecutiveFailures = 0
            currentSource = "Downloaded"
            currentQuality = downloadedPath.substringAfterLast('.', "").uppercase()
            _playbackStatus.value = PlaybackStatus.Playing("Downloaded", currentQuality)
            ensurePlayer(appContext)
            player!!.setMediaItem(buildMediaItem(android.net.Uri.fromFile(java.io.File(downloadedPath)).toString(), streamMimeType(downloadedPath)))
            player!!.prepare()
            if (song == restoreQuery && restorePositionMs > 0) {
                player!!.seekTo(restorePositionMs)
            }
            restoreQuery = null
            player!!.playWhenReady = true
            startPositionWatch()

            // Update LRU tracking in database
            val trackKey = if (song.startsWith(SPOTIFY_TRACK_PREFIX)) song.removePrefix(SPOTIFY_TRACK_PREFIX).substringBefore('|') else song
            scope.launch(Dispatchers.IO) {
                runCatching {
                    com.music.spotui.data.db.AppDatabase.getInstance(appContext).trackDao().updateLastPlayed(trackKey, System.currentTimeMillis())
                }
            }
            return
        }

        if (webPlayerEnabled &&
            // Experimental: stream through Spotify's own web player (real Spotify audio,
            // no bypass) when enabled AND the device WebView has Widevine. Otherwise
            // fall through to the normal YouTube/FLAC engine so playback is never silent.
            com.music.spotui.data.preferences.isWebPlaybackEnabled(appContext) &&
            SpotifyWebPlayer.canPlay
        ) {
            val spotifyId = trackIdRegistry[song] ?: spotifyTrackIdForPlayback(song)
            if (spotifyId != null) {
                runCatching { player?.pause() }
                currentSource = "Spotify"
                currentQuality = ""
                _playbackStatus.value = PlaybackStatus.Playing("Spotify", "")
                SpotifyWebPlayer.play(spotifyId)
                return
            }
            Log.w(TAG, "web playback on but no Spotify id for query: $song — using fallback engine")
        }
        scope.launch {
            try {
                val isOnline = com.music.spotui.data.network.NetworkMonitor.isOnlineNow(appContext)
                // In offline mode, check if the track is downloaded. If not, auto-skip safely!
                if (!isOnline) {
                    consecutiveFailures++
                    if (consecutiveFailures >= 3) {
                        consecutiveFailures = 0
                        _playbackStatus.value = PlaybackStatus.Idle
                        withContext(Dispatchers.Main) {
                            showShortToast(appContext, "You are offline and no more downloaded tracks are available")
                        }
                        return@launch
                    }
                    withContext(Dispatchers.Main) {
                        showShortToast(appContext, "Track not downloaded")
                        kotlinx.coroutines.delay(350L)
                        skipToNextTrack(appContext)
                    }
                    return@launch
                }

                val streamUrl = resolveStreamUrl(song, appContext, forPlayback = true) ?: run {
                    consecutiveFailures++
                    _playbackStatus.value = PlaybackStatus.Error("Playback failed", canRetry = true)
                    if (consecutiveFailures >= 3) {
                        consecutiveFailures = 0
                        withContext(Dispatchers.Main) {
                            showShortToast(appContext, "Could not stream track. Please check connection.")
                        }
                        return@launch
                    }
                    if (currentRequest == song) withContext(Dispatchers.Main) {
                        val msg = "No playback found for this track"
                        showShortToast(appContext, msg)
                        kotlinx.coroutines.delay(400L)
                        skipToNextTrack(appContext)
                    }
                    return@launch
                }
                // A newer tap superseded this one while we were resolving — drop it.
                if (currentRequest != song) return@launch
                consecutiveFailures = 0
                val effectivePlaybackUrl = streamUrl
                withContext(Dispatchers.Main) {
                    if (currentRequest != song) return@withContext
                    ensurePlayer(appContext)
                    _playbackStatus.value = PlaybackStatus.Buffering(currentSource, currentQuality)
                    player!!.setMediaItem(buildMediaItem(effectivePlaybackUrl, streamMimeType(streamUrl)))
                    player!!.prepare()
                    // Restored session: continue from where the last run stopped.
                    if (song == restoreQuery && restorePositionMs > 0) {
                        player!!.seekTo(restorePositionMs)
                    }
                    restoreQuery = null
                    player!!.playWhenReady = true
                }
                startPositionWatch()
                prefetchNextTracks(appContext, 3)
            } catch (e: Exception) {
                Log.e(TAG, "playSong failed for query: $song", e)
                consecutiveFailures++
                _playbackStatus.value = PlaybackStatus.Error("Playback error", canRetry = true)
                if (consecutiveFailures < 3) {
                    withContext(Dispatchers.Main) {
                        showShortToast(appContext, "No playback found for this track")
                        kotlinx.coroutines.delay(400L)
                        skipToNextTrack(appContext)
                    }
                } else {
                    consecutiveFailures = 0
                }
            }
        }
    }

    fun showShortToast(context: Context, message: String) {
        val toast = android.widget.Toast.makeText(context.applicationContext, message, android.widget.Toast.LENGTH_SHORT)
        toast.show()
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ toast.cancel() }, 1000L)
    }

    fun skipToNextTrack(context: Context, forceNextIfRepeat: Boolean = false) {
        val state = boundState ?: CurrentSongState.instance ?: return
        val q = state.queue.value
        if (q.isEmpty()) return
        val curIdx = state.songIndex.value

        val now = System.currentTimeMillis()
        if (now - lastSkipTimestamp < 250L) {
            return
        }
        lastSkipTimestamp = now

        // If repeat is ON and this was called from track completion (not manual user skip), repeat the same track
        if (state.repeat.value && !forceNextIfRepeat) {
            val cur = q.getOrNull(curIdx) ?: q.firstOrNull()
            if (cur != null) {
                playSong(cur.url, context)
                return
            }
        }

        val isOnline = com.music.spotui.data.network.NetworkMonitor.isOnlineNow(context)
        val isShuffle = state.shuffle.value

        var targetIndex = -1
        if (!isOnline) {
            val availableOffline = q.indices.filter { com.music.spotui.data.preferences.isSongDownloaded(context, q[it]) }
            if (availableOffline.isNotEmpty()) {
                if (isShuffle && availableOffline.size > 1) {
                    val pool = availableOffline.filter { it != curIdx }
                    targetIndex = if (pool.isNotEmpty()) pool.random() else availableOffline.first()
                } else {
                    targetIndex = availableOffline.firstOrNull { it > curIdx } ?: availableOffline.first()
                }
            } else {
                showShortToast(context, "No offline tracks available in queue")
                _playbackStatus.value = PlaybackStatus.Idle
                return
            }
        } else {
            if (isShuffle && q.size > 1) {
                val pool = q.indices.filter { it != curIdx }
                targetIndex = if (pool.isNotEmpty()) pool.random() else curIdx
            } else {
                if (curIdx >= 0 && curIdx < q.size - 1) {
                    targetIndex = curIdx + 1
                } else if (q.isNotEmpty()) {
                    targetIndex = 0
                }
            }
        }

        if (targetIndex in q.indices) {
            val next = q[targetIndex]
            state.updateSongState(next.coverUri, next.title, next.singer, true, next.id, targetIndex, next.album)
            playSong(next.url, context)
        } else if (!isOnline) {
            showShortToast(context, "No offline tracks available in queue")
            _playbackStatus.value = PlaybackStatus.Idle
        }
    }

    fun skipToPreviousTrack(context: Context) {
        val state = boundState ?: CurrentSongState.instance ?: return
        val q = state.queue.value
        if (q.isEmpty()) return
        val curIdx = state.songIndex.value
        val isOnline = com.music.spotui.data.network.NetworkMonitor.isOnlineNow(context)

        // If current song has played for more than 3 seconds, restart it instead of going back
        val currentPosition = getCurrentPosition()
        if (currentPosition > 3000L) {
            seekTo(0)
            return
        }

        var targetIndex = -1
        if (!isOnline) {
            for (i in (curIdx - 1) downTo 0) {
                if (com.music.spotui.data.preferences.isSongDownloaded(context, q[i])) {
                    targetIndex = i
                    break
                }
            }
            if (targetIndex == -1) {
                for (i in (q.size - 1) downTo curIdx + 1) {
                    if (com.music.spotui.data.preferences.isSongDownloaded(context, q[i])) {
                        targetIndex = i
                        break
                    }
                }
            }
        } else {
            if (curIdx > 0) {
                targetIndex = curIdx - 1
            } else if (q.isNotEmpty()) {
                targetIndex = q.size - 1
            }
        }

        if (targetIndex in q.indices) {
            val prev = q[targetIndex]
            state.updateSongState(prev.coverUri, prev.title, prev.singer, true, prev.id, targetIndex, prev.album)
            playSong(prev.url, context)
        } else if (!isOnline) {
            showShortToast(context, "No offline tracks available in queue")
        }
    }

    // Build a MediaItem carrying the current track's metadata so the system media
    // notification (MediaSession) shows the right title / artist / artwork.
    private fun buildMediaItem(streamUrl: String, mimeType: String? = null): MediaItem {
        val metadata = androidx.media3.common.MediaMetadata.Builder()
            .setTitle(metaTitle)
            .setArtist(metaArtist)
            .apply { if (metaCover.isNotBlank()) setArtworkUri(android.net.Uri.parse(metaCover)) }
            .build()
        return MediaItem.Builder()
            .setUri(streamUrl)
            // Hint the container so ExoPlayer picks the right source/extractor even
            // when the URL has no extension: TIDAL lossless is a DASH .mpd manifest,
            // and single-file lossless is FLAC.
            .apply { if (mimeType != null) setMimeType(mimeType) }
            .setMediaMetadata(metadata)
            .build()
    }

    /** MIME hint for a resolved stream: DASH manifest, single-file FLAC, or none. */
    private fun streamMimeType(streamUrl: String): String? {
        val bare = streamUrl.substringBefore('?').lowercase()
        return when {
            streamUrl.startsWith("deezer://") ->
                if (streamUrl.contains("fmt=flac")) androidx.media3.common.MimeTypes.AUDIO_FLAC
                else androidx.media3.common.MimeTypes.AUDIO_MPEG
            streamUrl.startsWith("data:application/dash+xml") ||
                bare.endsWith(".mpd") || streamUrl.contains("manifest.tidal.com") || streamUrl.contains("/manifests/") ->
                androidx.media3.common.MimeTypes.APPLICATION_MPD
            bare.endsWith(".flac") || currentSource.startsWith("Lossless") ->
                androidx.media3.common.MimeTypes.AUDIO_FLAC
            else -> null
        }
    }

    /** Warm the cache for an upcoming track (e.g. the next/previous queue item). */
    fun prefetch(song: String, context: Context) {
        if (song.isBlank() || streamCache.containsKey(song)) return
        val appContext = context.applicationContext
        // No point resolving YouTube streams while Spotify web is the engine
        if (webPlaybackActive()) return
        scope.launch(Dispatchers.IO) {
            val url = runCatching { resolveStreamUrl(song, appContext, forPlayback = false) }.getOrNull()
            if (url != null) cacheIntro(url, appContext)
        }
    }

    /**
     * Warm the cache for the first [count] tracks of a freshly-loaded list
     * (album/artist/search). Resolves them sequentially so we don't fire a dozen
     * PoToken/player chains at once, but get the likely-next taps ready ahead of
     * time — this is what kills the "~3s per track" first-tap latency.
     */
    fun prefetchList(songs: List<String>, context: Context, count: Int = 4) {
        // Do not resolve streams for whole result/album lists.
    }

    /**
     * Concurrent lookahead pre-buffering pipeline.
     * When online: Pre-caches the first segment of the next 3 tracks in the current queue into the 1GB LRU SimpleCache.
     * When offline: Inspects the next tracks in queue and warms up local file descriptors for instant gapless playback.
     */
    fun prefetchNextTracks(context: Context, count: Int = 3) {
        val state = boundState ?: CurrentSongState.instance ?: return
        val q = state.queue.value
        if (q.isEmpty()) return
        val curIndex = state.songIndex.value
        val isOnline = com.music.spotui.data.network.NetworkMonitor.isOnlineNow(context)

        scope.launch(Dispatchers.IO) {
            for (i in 1..count) {
                val nextIdx = curIndex + i
                if (nextIdx >= q.size) break
                val nextSong = q[nextIdx]
                launch {
                    if (isOnline) {
                        prefetch(nextSong.url, context)
                    } else {
                        // Offline preloading: verify and touch local file descriptor
                        val path = com.music.spotui.data.preferences.downloadedPathForQuery(context, nextSong.url)
                        if (path != null) {
                            val f = java.io.File(path)
                            if (f.exists() && f.canRead()) {
                                Log.d(TAG, "Offline preloaded local file descriptor for '${nextSong.title}' (${f.length()} bytes)")
                            }
                        }
                    }
                }
            }
        }
    }

    // High-performance connection-pooled OkHttpClient dedicated to media streaming
    private val exoOkHttpClient: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectionPool(okhttp3.ConnectionPool(32, 5, java.util.concurrent.TimeUnit.MINUTES))
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    // ── Intro preloading (instant playback) ──
    private const val PRELOAD_BYTES = 1536L * 1024 // 1.5 MB intro segment

    @Volatile private var mediaCache: androidx.media3.datasource.cache.SimpleCache? = null

    private fun mediaCache(context: Context): androidx.media3.datasource.cache.SimpleCache =
        mediaCache ?: synchronized(this) {
            mediaCache ?: androidx.media3.datasource.cache.SimpleCache(
                java.io.File(context.cacheDir, "media"),
                androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor(1024L * 1024 * 1024), // 1GB media cache
                androidx.media3.database.StandaloneDatabaseProvider(context),
            ).also { mediaCache = it }
        }

    private fun cacheDataSourceFactory(context: Context): androidx.media3.datasource.cache.CacheDataSource.Factory {
        val okHttpDataSourceFactory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(exoOkHttpClient)
            .setUserAgent(
                "Mozilla/5.0 (Linux; Android 14; Pixel) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
            )
            .setDefaultRequestProperties(
                mapOf(
                    "Accept" to "*/*",
                    "Accept-Encoding" to "identity",
                    "Connection" to "keep-alive",
                )
            )
        val upstream = androidx.media3.datasource.DefaultDataSource.Factory(context, okHttpDataSourceFactory)
        return androidx.media3.datasource.cache.CacheDataSource.Factory()
            .setCache(mediaCache(context))
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    /** Pre-cache the first [PRELOAD_BYTES] of [url] into the media cache (http(s) only). */
    private fun cacheIntro(url: String, appContext: Context) {
        if (!url.startsWith("http")) return
        if (!com.music.spotui.data.preferences.isPreloadEnabled(appContext)) return
        runCatching {
            val ds = cacheDataSourceFactory(appContext).createDataSource()
            val spec = androidx.media3.datasource.DataSpec.Builder()
                .setUri(android.net.Uri.parse(url))
                .setLength(PRELOAD_BYTES)
                .build()
            androidx.media3.datasource.cache.CacheWriter(ds, spec, null, null).cache()
        }.onFailure { Log.d(TAG, "intro preload skipped: ${it.message}") }
    }

    // forPlayback=true only for the track actually being played — so background
    // prefetch of upcoming tracks doesn't clobber the current source badge (a
    // prefetch resolving the NEXT track via YouTube was flipping the badge to
    // "YouTube" while the current track streamed from Spotify).
    private suspend fun resolveStreamUrl(song: String, appContext: Context, forPlayback: Boolean = false): String? {
        // Imported local files: the play query IS the file's content:// / file:// URI.
        // ExoPlayer plays it directly (FLAC/MP3/WAV/… via its built-in extractors).
        if (song.startsWith("content://") || song.startsWith("file://")) {
            if (forPlayback) {
                currentSource = "Local file"
                currentQuality = song.substringBefore('?').substringAfterLast('.', "")
                    .uppercase().takeIf { it.length in 2..5 }.orEmpty()
            }
            return song
        }
        alternativeStreamForPlayback(song, appContext)?.let { alt ->
            invalidateResolvedStream(song)
            return when {
                alt.isLocal -> {
                    if (forPlayback) {
                        currentSource = "Alternative file"
                        currentQuality = alt.label.substringAfterLast('.', "").uppercase().takeIf { it.length in 2..5 }.orEmpty()
                    }
                    alt.value
                }
                alt.isYouTube -> {
                    if (forPlayback) {
                        currentSource = "Alternative YouTube"
                        currentQuality = ""
                    }
                    val quality = com.music.spotui.data.preferences.currentStreamingQuality(appContext)
                    val playback = resolveYtPlayback(alt.value, quality.audioQuality, appContext) ?: return null
                    val codec = playback.format.mimeType
                        .substringAfter("codecs=\"", "").substringBefore('"').substringBefore('.')
                        .uppercase()
                    val ytQuality = listOf(codec, "${playback.format.bitrate / 1000} kbps")
                        .filter { it.isNotBlank() }.joinToString(" ")
                    if (forPlayback) currentQuality = ytQuality
                    playback.streamUrl
                }
                else -> null
            }
        }
        // Offline: Check Local Storage First (SSOT disk check + Self Healing)
        val localTrackKey = if (song.startsWith(SPOTIFY_TRACK_PREFIX)) song.removePrefix(SPOTIFY_TRACK_PREFIX).substringBefore('|') else song
        val localManager = com.music.spotui.storage.LocalFileManager.getInstance(appContext)
        val localUri = localManager.getValidLocalUri(localTrackKey)
        if (localUri != null) {
            if (forPlayback) {
                currentSource = "Downloaded"
                currentQuality = localUri.path?.substringAfterLast('.', "")?.uppercase().orEmpty()
            }
            return localUri.toString()
        }

        // Offline legacy fallback check
        com.music.spotui.data.preferences.downloadedPathForQuery(appContext, song)?.let { path ->
            val f = java.io.File(path)
            if (f.exists() && f.length() >= com.music.spotui.storage.LocalFileManager.MIN_AUDIO_FILE_SIZE_BYTES) {
                if (forPlayback) {
                    currentSource = "Downloaded"
                    currentQuality = path.substringAfterLast('.', "").uppercase()
                }
                return android.net.Uri.fromFile(f).toString()
            }
        }

        // High-Speed Stream URL LRU Cache (4-hr TTL)
        com.music.spotui.player.StreamUrlCache.getEntry(song)?.let { cached ->
            if (forPlayback) {
                currentSource = cached.source.ifBlank { "YouTube" }
                currentQuality = cached.quality
            }
            return cached.url
        }

        streamCache[song]?.let {
            // Cache hits must still update the badge — returning early kept the
            // previous track's label (e.g. "Downloaded") on a streamed track.
            if (forPlayback) {
                currentSource = sourceCache[song] ?: "YouTube"
                currentQuality = qualityCache[song] ?: ""
            }
            return it
        }
        // Quality for the current network (Wi-Fi vs cellular), from Settings.
        val quality = com.music.spotui.data.preferences.currentStreamingQuality(appContext)

        // Deezer: When configured and enabled, Deezer is the primary streaming engine.
        // It resolves immediately (FLAC for Premium, MP3 320/128 for others) without delay.
        if (deezerEnabled && com.music.spotui.data.preferences.isDeezerEnabled(appContext) &&
            com.music.spotui.deezer.DeezerSource.isConfigured(appContext) &&
            failedSourcesForSong[song]?.contains("Deezer") != true
        ) {
            val spotifyId = trackIdRegistry[song] ?: spotifyTrackIdForPlayback(song)
            val meta = metadataRegistry[song]
            val expectedDurationSec = durationRegistry[song]?.let { it / 1000 }
            val r = kotlinx.coroutines.withTimeoutOrNull(6_000) {
                com.music.spotui.deezer.DeezerSource.resolve(
                    context = appContext,
                    spotifyId = spotifyId,
                    isrc = null,
                    searchQuery = searchTextForPlayback(song),
                    expectedTitle = meta?.title,
                    expectedArtist = meta?.artist,
                    expectedDurationSec = expectedDurationSec,
                )
            }
            if (r is com.music.spotui.deezer.DeezerSource.Result.Success) {
                Log.d(TAG, "Deezer stream resolved (${r.qualityLabel}) for: $song")
                if (forPlayback) { currentSource = "Deezer"; currentQuality = r.qualityLabel }
                streamCache[song] = r.uri
                sourceCache[song] = "Deezer"
                qualityCache[song] = r.qualityLabel
                return r.uri
            } else {
                Log.d(TAG, "Deezer miss ($r), continuing to fallback for: $song")
            }
        }

        // Lossless FLAC: SpotiFLAC gated (if verified) + Tidal/community, ISRC-matched.
        if (losslessStreaming && quality.lossless &&
            failedSourcesForSong[song]?.contains("Lossless") != true
        ) {
            (trackIdRegistry[song] ?: spotifyTrackIdForPlayback(song))?.let { spotifyId ->
                val r = kotlinx.coroutines.withTimeoutOrNull(3_500) {
                    com.music.spotui.lossless.LosslessSource.resolve(appContext, spotifyId, preferHiRes = losslessHiRes)
                }
                if (r is com.music.spotui.lossless.LosslessSource.Result.Success) {
                    val flacQuality = "FLAC ${r.track.quality}-bit"
                    if (forPlayback) {
                        currentSource = "Lossless • ${r.track.provider}"
                        currentQuality = flacQuality
                    }
                    streamCache[song] = r.track.url
                    sourceCache[song] = "Lossless • ${r.track.provider}"
                    qualityCache[song] = flacQuality
                    return r.track.url
                } else {
                    Log.d(TAG, "lossless miss ($r) for: $song")
                }
            }
        }

        // JioSaavn: High-speed ad-free direct 320 kbps / 160 kbps CDN stream engine
        if (failedSourcesForSong[song]?.contains("Saavn") != true) {
            val meta = metadataRegistry[song] ?: ensureSpotifyMatchMetadata(song)
            val titleToSearch = meta?.title ?: searchTextForPlayback(song).substringAfter(" - ").ifBlank { searchTextForPlayback(song) }
            val artistToSearch = meta?.artist ?: searchTextForPlayback(song).substringBefore(" - ").takeIf { searchTextForPlayback(song).contains(" - ") }.orEmpty()
            val expectedDuration = durationRegistry[song]

            val saavnRes = kotlinx.coroutines.withTimeoutOrNull(3_000) {
                com.music.spotui.saavn.SaavnSource.resolve(
                    title = titleToSearch,
                    artist = artistToSearch,
                    expectedDurationMs = expectedDuration,
                )
            }
            if (saavnRes is com.music.spotui.saavn.SaavnSource.Result.Success) {
                val qLabel = saavnRes.track.qualityLabel
                if (forPlayback) {
                    currentSource = "Saavn"
                    currentQuality = qLabel
                }
                streamCache[song] = saavnRes.track.url
                sourceCache[song] = "Saavn"
                qualityCache[song] = qLabel
                Log.d(TAG, "Saavn direct stream resolved ($qLabel) for: $song")
                return saavnRes.track.url
            }
        }

        if (!youtubeEnabled) {
            Log.w(TAG, "YouTube fallback disabled — no stream for: $song")
            return null
        }
        if (forPlayback) {
            currentSource = "YouTube"
            // Clear the previous track's quality so a failed resolve can't leave
            // a stale "FLAC 24-bit" badge on a YouTube stream.
            currentQuality = ""
        }
        val playback = resolveYtPlayback(song, quality.audioQuality, appContext) ?: return null
        // e.g. "OPUS 141 kbps" from the chosen adaptive format.
        val codec = playback.format.mimeType
            .substringAfter("codecs=\"", "").substringBefore('"').substringBefore('.')
            .uppercase()
        val ytQuality = listOf(codec, "${playback.format.bitrate / 1000} kbps")
            .filter { it.isNotBlank() }.joinToString(" ")
        if (forPlayback) currentQuality = ytQuality
        streamCache[song] = playback.streamUrl
        sourceCache[song] = "YouTube"
        qualityCache[song] = ytQuality
        return playback.streamUrl
    }

    private fun alternativeStreamForPlayback(
        song: String,
        appContext: Context,
    ): com.music.spotui.data.preferences.AlternativeStream? {
        val key = alternativeKeyRegistry[song]
            ?: spotifyTrackIdForPlayback(song)?.let {
                com.music.spotui.data.preferences.alternativeStreamKeyForSpotifyId(it)
            }
        return key?.let { com.music.spotui.data.preferences.getAlternativeStream(appContext, it) }
    }

    // ── Downloads (offline playback) ──
    // Tracks which song queries are mid-download so the UI can show a spinner and
    // we don't kick off the same download twice.
    private val downloading = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    )
    @Volatile var onDownloadsChanged: (() -> Unit)? = null

    // Per-query download progress, 0..100. Present only while a download is active.
    private val downloadProgress = java.util.concurrent.ConcurrentHashMap<String, Int>()
    // The actual SongsModel of each in-progress download, so the Downloads screen can
    // render it (with a progress bar) before the file exists / it's added to prefs.
    private val downloadingSongs =
        java.util.concurrent.ConcurrentHashMap<String, com.music.spotui.data.entity.SongsModel>()

    fun isDownloading(query: String): Boolean = downloading.contains(query)

    /** Current download progress (0..100) for a query, or -1 if unknown/not downloading. */
    fun downloadProgress(query: String): Int = downloadProgress[query] ?: -1

    /** Snapshot of the currently-downloading tracks paired with their percent (0..100). */
    fun downloadingSnapshot(): List<Pair<com.music.spotui.data.entity.SongsModel, Int>> =
        downloadingSongs.entries.map { (q, song) -> song to (downloadProgress[q] ?: 0) }

    // Last download failure reason, surfaced to the user as a Toast for diagnosis.
    @Volatile var lastDownloadError: String? = null

    // googlevideo stream URLs 403 without a browser User-Agent and need redirects
    // followed (http↔https) — ExoPlayer does both, so a raw URLConnection must too.
    private fun openDownloadConn(url: String): java.net.HttpURLConnection =
        (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            instanceFollowRedirects = true
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14; Pixel) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
            )
        }

    /**
     * Download [url] to [tmpFile] using HTTP **Range** requests in chunks, reporting
     * progress (0..100) for [query]. A single full-file GET of a googlevideo stream gets
     * reset partway through (`SocketException: Connection reset`) — the server expects the
     * audio fetched in byte ranges, which is how ExoPlayer/NewPipe get it. Each chunk is a
     * short connection (retried a few times on reset); writing is append-continuous so a
     * retried chunk resumes from the current byte position. Returns true iff the whole
     * file was written. Falls back gracefully if the server ignores Range (HTTP 200).
     */
    private fun httpDownloadRanged(
        url: String,
        tmpFile: java.io.File,
        query: String,
        onProgress: ((Int) -> Unit)? = null
    ): Boolean {
        val chunk = 8L * 1024 * 1024 // 8 MB
        var total = -1L
        var position = 0L
        downloadProgress[query] = 0
        onProgress?.invoke(0)
        try {
            java.io.BufferedOutputStream(tmpFile.outputStream()).use { output ->
                outer@ while (true) {
                    val end = if (total > 0) minOf(position + chunk - 1, total - 1) else position + chunk - 1
                    var attempt = 0
                    var fullBody = false
                    while (true) {
                        attempt++
                        val conn = openDownloadConn(url)
                        conn.setRequestProperty("Range", "bytes=$position-$end")
                        try {
                            val code = conn.responseCode
                            if (code !in 200..299 && code != 416) {
                                if (position == 0L) {
                                    return directDownloadStream(url, tmpFile, query, onProgress)
                                }
                                lastDownloadError = "Stream returned HTTP $code"
                                return false
                            }
                            if (code == 416) {
                                break@outer
                            }
                            if (total < 0) {
                                total = conn.getHeaderField("Content-Range")
                                    ?.substringAfter('/')?.toLongOrNull()
                                    ?: conn.contentLengthLong
                            }
                            fullBody = code == 200 // server ignored Range → whole file in one body
                            conn.inputStream.use { input ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    val r = input.read(buf)
                                    if (r < 0) break
                                    output.write(buf, 0, r)
                                    position += r
                                    if (total > 0) {
                                        val pct = ((position * 100) / total).toInt().coerceIn(0, 100)
                                        if (downloadProgress[query] != pct) {
                                            downloadProgress[query] = pct
                                            onProgress?.invoke(pct)
                                            onDownloadsChanged?.invoke()
                                        }
                                    }
                                }
                            }
                            break // this chunk completed
                        } catch (e: Exception) {
                            Log.w(TAG, "chunk @${position} failed (attempt $attempt): ${e.message}")
                            if (attempt >= 4) {
                                if (position == 0L) {
                                    return directDownloadStream(url, tmpFile, query, onProgress)
                                }
                                lastDownloadError = e.message ?: "Connection reset"
                                return false
                            }
                            runCatching { Thread.sleep(500) }
                        } finally {
                            conn.disconnect()
                        }
                    }
                    if (fullBody) { total = position; break@outer }
                    if (total in 1..position) break@outer
                    if (total < 0) break@outer // couldn't determine size; assume done
                }
            }
            downloadProgress[query] = 100
            onProgress?.invoke(100)
            return total <= 0 || position >= total
        } catch (e: Exception) {
            lastDownloadError = e.message ?: "Download error"
            return false
        }
    }

    private fun directDownloadStream(
        url: String,
        tmpFile: java.io.File,
        query: String,
        onProgress: ((Int) -> Unit)? = null
    ): Boolean {
        return try {
            val conn = openDownloadConn(url)
            val total = conn.contentLengthLong
            var position = 0L
            java.io.BufferedOutputStream(tmpFile.outputStream()).use { output ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val r = input.read(buf)
                        if (r < 0) break
                        output.write(buf, 0, r)
                        position += r
                        if (total > 0) {
                            val pct = ((position * 100) / total).toInt().coerceIn(0, 100)
                            if (downloadProgress[query] != pct) {
                                downloadProgress[query] = pct
                                onProgress?.invoke(pct)
                                onDownloadsChanged?.invoke()
                            }
                        }
                    }
                }
            }
            downloadProgress[query] = 100
            onProgress?.invoke(100)
            conn.disconnect()
            true
        } catch (e: Exception) {
            lastDownloadError = e.message ?: "Direct download error"
            Log.e(TAG, "directDownloadStream failed: ${e.message}")
            false
        }
    }

    /** Download every track in a list (album/playlist) associated with that playlist/album. */
    fun downloadAll(
        songs: List<com.music.spotui.data.entity.SongsModel>,
        context: Context,
        playlistId: String = "",
        playlistName: String = "",
        isAlbum: Boolean = false,
    ) {
        if (songs.isEmpty()) return
        val appContext = context.applicationContext
        val pending = songs.filter {
            !com.music.spotui.data.preferences.isDownloaded(appContext, it.id.toString())
        }
        if (pending.isEmpty()) {
            android.widget.Toast.makeText(appContext, "All tracks are already downloaded", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        val batchKey = if (playlistId.isNotBlank()) playlistId else "batch_${System.currentTimeMillis()}"
        val effectiveName = playlistName.ifBlank { if (isAlbum) "Album" else "Playlist" }

        if (playlistId.isNotBlank() && !isAlbum) {
            com.music.spotui.data.preferences.markPlaylistAutoDownload(appContext, playlistId, true)
        }

        val initialStatus = PlaylistBatchStatus(
            playlistId = batchKey,
            playlistName = effectiveName,
            totalTracks = pending.size,
            completedTracks = 0,
            failedTracks = 0,
            currentTrackTitle = pending.firstOrNull()?.title.orEmpty(),
            isDownloading = true,
        )
        _batchDownloads.value = _batchDownloads.value + (batchKey to initialStatus)
        onDownloadsChanged?.invoke()

        scope.launch {
            var completed = 0
            var failed = 0
            for (song in pending) {
                if (_batchDownloads.value[batchKey]?.isDownloading != true) break

                _batchDownloads.value = _batchDownloads.value + (batchKey to PlaylistBatchStatus(
                    playlistId = batchKey,
                    playlistName = effectiveName,
                    totalTracks = pending.size,
                    completedTracks = completed,
                    failedTracks = failed,
                    currentTrackTitle = song.title,
                    isDownloading = true,
                ))
                onDownloadsChanged?.invoke()

                val ok = downloadSongInternal(song, appContext, batchKey, effectiveName, isAlbum)
                if (ok) {
                    completed++
                } else {
                    failed++
                    Log.w(TAG, "Batch download: failed to download '${song.title}' ($lastDownloadError) - will skip in offline mode")
                }

                _batchDownloads.value = _batchDownloads.value + (batchKey to PlaylistBatchStatus(
                    playlistId = batchKey,
                    playlistName = effectiveName,
                    totalTracks = pending.size,
                    completedTracks = completed,
                    failedTracks = failed,
                    currentTrackTitle = song.title,
                    isDownloading = true,
                ))
                onDownloadsChanged?.invoke()
            }

            val finalStatus = PlaylistBatchStatus(
                playlistId = batchKey,
                playlistName = effectiveName,
                totalTracks = pending.size,
                completedTracks = completed,
                failedTracks = failed,
                currentTrackTitle = "",
                isDownloading = false,
            )
            _batchDownloads.value = _batchDownloads.value + (batchKey to finalStatus)
            onDownloadsChanged?.invoke()

            withContext(Dispatchers.Main) {
                val msg = if (failed == 0) {
                    "Downloaded all $completed tracks for '$effectiveName'"
                } else {
                    "Downloaded $completed of ${pending.size} tracks for '$effectiveName' ($failed skipped)"
                }
                android.widget.Toast.makeText(appContext, msg, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    /** True once every track in [songs] is downloaded (for the album's "downloaded" state). */
    fun allDownloaded(
        songs: List<com.music.spotui.data.entity.SongsModel>,
        context: Context,
    ): Boolean {
        if (songs.isEmpty()) return false
        val appContext = context.applicationContext
        return songs.all {
            com.music.spotui.data.preferences.isDownloaded(appContext, it.id.toString())
        }
    }

    fun downloadSong(
        song: com.music.spotui.data.entity.SongsModel,
        context: Context,
        playlistId: String = "",
        playlistName: String = "",
        isAlbum: Boolean = false,
        onComplete: (Boolean) -> Unit = {},
    ) {
        val appContext = context.applicationContext
        val query = song.url
        if (query.isBlank() || com.music.spotui.data.preferences.isDownloaded(appContext, song.id.toString())) {
            onComplete(true)
            return
        }
        scope.launch {
            val ok = downloadSongInternal(song, appContext, playlistId, playlistName, isAlbum)
            withContext(Dispatchers.Main) {
                if (!ok) {
                    android.widget.Toast.makeText(
                        appContext,
                        "Download failed: ${lastDownloadError ?: "unknown reason"}",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
                onComplete(ok)
            }
        }
    }

    suspend fun downloadSongSync(
        song: com.music.spotui.data.entity.SongsModel,
        appContext: Context,
        playlistId: String = "",
        playlistName: String = "",
        isAlbum: Boolean = false,
        onProgress: ((Int) -> Unit)? = null,
    ): Boolean = downloadSongInternal(song, appContext, playlistId, playlistName, isAlbum, onProgress)

    private suspend fun downloadSongInternal(
        song: com.music.spotui.data.entity.SongsModel,
        appContext: Context,
        playlistId: String = "",
        playlistName: String = "",
        isAlbum: Boolean = false,
        onProgress: ((Int) -> Unit)? = null,
    ): Boolean = downloadSemaphore.withPermit {
        val query = song.url
        if (query.isBlank() || com.music.spotui.data.preferences.isDownloaded(appContext, song.id.toString())) {
            return@withPermit true
        }
        if (!downloading.add(query)) {
            return@withPermit true
        }
        downloadingSongs[query] = song
        downloadProgress[query] = 0
        onProgress?.invoke(0)
        onDownloadsChanged?.invoke()
        lastDownloadError = null
        try {
            var ok = false
            for (attempt in 1..2) {
                ok = runCatching { downloadToFile(song, appContext, playlistId, playlistName, isAlbum, onProgress) }
                    .onFailure { lastDownloadError = it.message ?: "Download error" }
                    .getOrDefault(false)
                if (ok) break
                kotlinx.coroutines.delay(800)
            }
            ok
        } finally {
            downloading.remove(query)
            downloadProgress.remove(query)
            downloadingSongs.remove(query)
            onDownloadsChanged?.invoke()
        }
    }

    private suspend fun downloadToFile(
        song: com.music.spotui.data.entity.SongsModel,
        appContext: Context,
        playlistId: String = "",
        playlistName: String = "",
        isAlbum: Boolean = false,
        onProgress: ((Int) -> Unit)? = null,
    ): Boolean {
        // Enforce storage quota limits (reject or LRU evict if needed)
        try {
            com.music.spotui.data.storage.OfflineStorageManager.checkStorageQuota(appContext, estimatedBytes = 10 * 1024 * 1024L)
        } catch (e: Exception) {
            lastDownloadError = e.message ?: "Storage quota exceeded"
            com.music.spotui.data.storage.DownloadSyncManager.onTrackDownloadFailed(appContext, song, lastDownloadError!!, playlistId)
            return false
        }

        com.music.spotui.data.storage.DownloadSyncManager.onTrackDownloadStarted(appContext, song, playlistId)

        val dlQuality = com.music.spotui.data.preferences.getDownloadQuality(appContext)
        // Deezer: use immediately if it yields FLAC (HiFi). If it only yields MP3
        // (free account), HOLD it and try the real FLAC sources first — otherwise a
        // free Deezer MP3 would pre-empt lossless.
        var heldDeezer: com.music.spotui.deezer.DeezerSource.Resolved? = null
        if (deezerEnabled && com.music.spotui.data.preferences.isDeezerEnabled(appContext)) {
            val raw = kotlinx.coroutines.withTimeoutOrNull(30_000) {
                com.music.spotui.deezer.DeezerSource.resolveRaw(
                    context = appContext,
                    spotifyId = song.spotifyTrackId.takeIf { it.isNotBlank() },
                    searchQuery = "${song.title} ${song.singer}".trim().takeIf { it.isNotBlank() }
                )
            }
            if (raw != null) {
                if (raw.isFlac) {
                    if (downloadDeezerRaw(song, appContext, raw, playlistId, playlistName, isAlbum, onProgress)) return true
                } else {
                    heldDeezer = raw
                }
            }
        }
        // Lossless FLAC: SpotiFLAC gated (if verified) + Tidal/community. Saves .flac.
        if (losslessStreaming && song.spotifyTrackId.isNotBlank()) {
            val r = kotlinx.coroutines.withTimeoutOrNull(45_000) {
                com.music.spotui.lossless.LosslessSource.resolve(appContext, song.spotifyTrackId, preferHiRes = losslessHiRes)
            }
            if (r is com.music.spotui.lossless.LosslessSource.Result.Success) {
                val baseKey = song.spotifyTrackId.ifBlank { song.id.toString() }
                val tmpFile = com.music.spotui.data.storage.OfflineStorageManager.createTempFile(appContext, baseKey, "flac.tmp")
                val outFile = com.music.spotui.data.storage.OfflineStorageManager.getFinalFile(appContext, song.id.toString(), "flac", playlistName = playlistName, trackTitle = song.title)
                if (httpDownloadRanged(r.track.url, tmpFile, song.url, onProgress) &&
                    com.music.spotui.data.storage.OfflineStorageManager.commitAtomicFile(tmpFile, outFile, minBytes = 4096L)) {
                    com.music.spotui.data.preferences.addDownload(appContext, song, outFile.absolutePath, playlistId, playlistName, isAlbum)
                    com.music.spotui.data.storage.DownloadSyncManager.onTrackDownloadCompleted(appContext, song, outFile, playlistId, playlistName)
                    Log.d(TAG, "lossless downloaded (${r.track.provider} ${r.track.quality}-bit): ${song.title}")
                    return true
                }
                runCatching { tmpFile.delete() }
            }
        }
        // Deezer MP3 fallback (held above) before dropping to a YouTube m4a.
        heldDeezer?.let { if (downloadDeezerRaw(song, appContext, it, playlistId, playlistName, isAlbum, onProgress)) return true }

        // Saavn Direct 320kbps / 160kbps CDN download
        val saavnRes = kotlinx.coroutines.withTimeoutOrNull(6_000) {
            val titleToSearch = cleanSpotifySearchTitle(song.title)
            com.music.spotui.saavn.SaavnSource.resolve(
                title = titleToSearch,
                artist = song.singer,
                expectedDurationMs = if (song.durationMs > 0) song.durationMs else durationRegistry[song.url],
            )
        }
        if (saavnRes is com.music.spotui.saavn.SaavnSource.Result.Success) {
            val tmpFile = com.music.spotui.data.storage.OfflineStorageManager.createTempFile(appContext, song.id.toString(), "m4a.tmp")
            val outFile = com.music.spotui.data.storage.OfflineStorageManager.getFinalFile(appContext, song.id.toString(), "m4a", playlistName = playlistName, trackTitle = song.title)
            if (httpDownloadRanged(saavnRes.track.url, tmpFile, song.url, onProgress) &&
                com.music.spotui.data.storage.OfflineStorageManager.commitAtomicFile(tmpFile, outFile, minBytes = 4096L)) {
                com.music.spotui.data.preferences.addDownload(appContext, song, outFile.absolutePath, playlistId, playlistName, isAlbum)
                com.music.spotui.data.storage.DownloadSyncManager.onTrackDownloadCompleted(appContext, song, outFile, playlistId, playlistName)
                Log.d(TAG, "Saavn downloaded (${saavnRes.track.qualityLabel}): ${song.title}")
                return true
            }
            runCatching { tmpFile.delete() }
        }

        if (!youtubeEnabled) {
            lastDownloadError = "Track not available for download"
            com.music.spotui.data.storage.DownloadSyncManager.onTrackDownloadFailed(appContext, song, lastDownloadError!!, playlistId)
            return false
        }

        val query = song.url
        if (song.durationMs > 0 && durationRegistry[query] == null) {
            durationRegistry[query] = song.durationMs
        }
        if (metadataRegistry[query] == null && song.title.isNotBlank()) {
            metadataRegistry[query] = TrackMatchMetadata(title = song.title, artist = song.singer, album = song.album.orEmpty())
        }
        if (song.spotifyTrackId.isNotBlank() && trackIdRegistry[query] == null) {
            trackIdRegistry[query] = song.spotifyTrackId
        }
        // Resolve a fresh network stream URL (bypass any local-file short-circuit),
        // walking the ranked video candidates like playback does.
        var playback = resolveYtPlayback(query, dlQuality.audioQuality, appContext)
        if (playback == null) {
            val alt1 = "${song.title} ${song.singer}".trim()
            if (alt1 != query && alt1.isNotBlank()) {
                playback = resolveYtPlayback(alt1, dlQuality.audioQuality, appContext)
            }
        }
        if (playback == null) {
            val cleanTitle = cleanSpotifySearchTitle(song.title)
            val alt2 = "$cleanTitle ${song.singer}".trim()
            if (alt2 != query && alt2.isNotBlank()) {
                playback = resolveYtPlayback(alt2, dlQuality.audioQuality, appContext)
            }
        }
        if (playback == null) {
            val alt3 = "${song.singer} ${song.title}".trim()
            if (alt3 != query && alt3.isNotBlank()) {
                playback = resolveYtPlayback(alt3, dlQuality.audioQuality, appContext)
            }
        }
        if (playback == null) {
            lastDownloadError = "Couldn't resolve stream from source"
            com.music.spotui.data.storage.DownloadSyncManager.onTrackDownloadFailed(appContext, song, lastDownloadError!!, playlistId)
            return false
        }

        val isOpus = playback.format.mimeType.contains("opus", ignoreCase = true) || playback.format.mimeType.contains("webm", ignoreCase = true)
        val ext = if (isOpus) "opus" else "m4a"
        val tmpFile = com.music.spotui.data.storage.OfflineStorageManager.createTempFile(appContext, song.id.toString(), "$ext.tmp")
        val outFile = com.music.spotui.data.storage.OfflineStorageManager.getFinalFile(appContext, song.id.toString(), ext, playlistName = playlistName, trackTitle = song.title)

        if (!httpDownloadRanged(playback.streamUrl, tmpFile, song.url, onProgress)) {
            runCatching { tmpFile.delete() }
            com.music.spotui.data.storage.DownloadSyncManager.onTrackDownloadFailed(appContext, song, "Network stream failed", playlistId)
            return false
        }

        // If WebM container with Opus stream, perform lossless demuxing/remuxing if needed
        if (isOpus && playback.format.mimeType.contains("webm", ignoreCase = true)) {
            val remuxOut = File(tmpFile.parentFile, "${tmpFile.name}.remuxed")
            if (com.music.spotui.storage.ContainerDemuxer.remuxWebmToOpus(tmpFile, remuxOut)) {
                tmpFile.delete()
                remuxOut.renameTo(tmpFile)
            }
        }

        if (!com.music.spotui.data.storage.OfflineStorageManager.commitAtomicFile(tmpFile, outFile, minBytes = 4096L)) {
            lastDownloadError = "Couldn't save file"
            runCatching { tmpFile.delete() }
            com.music.spotui.data.storage.DownloadSyncManager.onTrackDownloadFailed(appContext, song, lastDownloadError!!, playlistId)
            return false
        }
        com.music.spotui.data.preferences.addDownload(appContext, song, outFile.absolutePath, playlistId, playlistName, isAlbum)
        com.music.spotui.data.storage.DownloadSyncManager.onTrackDownloadCompleted(appContext, song, outFile, playlistId, playlistName)

        // Export to system MediaStore if requested
        if (com.music.spotui.data.preferences.isPublicStorageExportEnabled(appContext)) {
            com.music.spotui.storage.MediaStoreExporter.exportTrackToMediaStore(
                context = appContext,
                sourceFile = outFile,
                title = song.title,
                artist = song.singer,
                album = song.album,
                mimeType = if (isOpus) "audio/ogg" else "audio/mp4",
                playlistName = playlistName
            )
        }
        return true
    }

    private suspend fun downloadDeezerRaw(
        song: com.music.spotui.data.entity.SongsModel,
        appContext: Context,
        raw: com.music.spotui.deezer.DeezerSource.Resolved,
        playlistId: String = "",
        playlistName: String = "",
        isAlbum: Boolean = false,
        onProgress: ((Int) -> Unit)? = null,
    ): Boolean {
        val ext = if (raw.isFlac) "flac" else "mp3"
        val tmpFile = com.music.spotui.data.storage.OfflineStorageManager.createTempFile(appContext, song.id.toString(), "$ext.tmp")
        val outFile = com.music.spotui.data.storage.OfflineStorageManager.getFinalFile(appContext, song.id.toString(), ext, playlistName = playlistName, trackTitle = song.title)
        if (!deezerDownloadDecrypted(raw.url, raw.encrypted, raw.trackId, tmpFile, song.url, onProgress)) {
            runCatching { tmpFile.delete() }
            com.music.spotui.data.storage.DownloadSyncManager.onTrackDownloadFailed(appContext, song, "Deezer decryption error", playlistId)
            return false
        }
        if (!com.music.spotui.data.storage.OfflineStorageManager.commitAtomicFile(tmpFile, outFile, minBytes = 4096L)) {
            lastDownloadError = "Couldn't save file"
            runCatching { tmpFile.delete() }
            com.music.spotui.data.storage.DownloadSyncManager.onTrackDownloadFailed(appContext, song, lastDownloadError!!, playlistId)
            return false
        }
        com.music.spotui.data.preferences.addDownload(appContext, song, outFile.absolutePath, playlistId, playlistName, isAlbum)
        com.music.spotui.data.storage.DownloadSyncManager.onTrackDownloadCompleted(appContext, song, outFile, playlistId, playlistName)
        Log.d(TAG, "Deezer downloaded (${raw.qualityLabel}): ${song.title}")
        return true
    }

    /**
     * Stream [url] and, if [encrypted], Blowfish-decrypt every 3rd 2048-byte block
     * (Deezer's stripe cipher) while writing to [tmpFile], reporting progress for
     * [query]. Produces a plain, fully-decrypted audio file.
     */
    private fun deezerDownloadDecrypted(
        url: String,
        encrypted: Boolean,
        trackId: String,
        tmpFile: java.io.File,
        query: String,
        onProgress: ((Int) -> Unit)? = null,
    ): Boolean {
        downloadProgress[query] = 0
        onProgress?.invoke(0)
        val conn = openDownloadConn(url)
        return try {
            val code = conn.responseCode
            if (code !in 200..299) {
                lastDownloadError = "Deezer CDN HTTP $code"
                return false
            }
            val total = conn.contentLengthLong
            val cipher = if (encrypted) {
                com.music.spotui.deezer.DeezerCrypto.cipher(
                    com.music.spotui.deezer.DeezerCrypto.trackKey(trackId),
                )
            } else {
                null
            }
            java.io.BufferedOutputStream(tmpFile.outputStream()).use { output ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(2048)
                    var counter = 0
                    var position = 0L
                    while (true) {
                        var read = 0
                        while (read < 2048) {
                            val r = input.read(buf, read, 2048 - read)
                            if (r < 0) break
                            read += r
                        }
                        if (read == 0) break
                        val out = if (encrypted && read == 2048 && counter % 3 == 0) {
                            cipher!!.doFinal(buf)
                        } else {
                            buf
                        }
                        output.write(out, 0, read)
                        counter++
                        position += read
                        if (total > 0) {
                            val pct = ((position * 100) / total).toInt().coerceIn(0, 100)
                            if (downloadProgress[query] != pct) {
                                downloadProgress[query] = pct
                                onProgress?.invoke(pct)
                                onDownloadsChanged?.invoke()
                            }
                        }
                        if (read < 2048) break // final partial chunk
                    }
                }
            }
            downloadProgress[query] = 100
            onProgress?.invoke(100)
            true
        } catch (e: Exception) {
            lastDownloadError = e.message ?: "Deezer download error"
            false
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private data class CandidateScore(
        val item: SongItem,
        val score: Double,
        val titleScore: Double,
        val artistScore: Double,
        val artistEvidenceScore: Double,
        val durationScore: Double?,
        val albumScore: Double?,
        val alternatePenalty: Double,
        val unexpectedAlternates: List<String>,
    )

    private fun normalizedForMatch(value: String): String =
        value.lowercase()
            .replace(featSearchPattern, "")
            .replace(Regex("""[^\p{L}\p{Nd}\s]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private val anyLatinTransliterator by lazy {
        runCatching {
            android.icu.text.Transliterator.getInstance("Any-Latin; Latin-ASCII")
        }.getOrNull()
    }

    private fun foldLatinDiacritics(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("""\p{Mn}+"""), "")

    private fun transliterateCyrillic(value: String): String {
        val map = mapOf(
            'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d",
            'е' to "e", 'ё' to "e", 'ж' to "zh", 'з' to "z", 'и' to "i",
            'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n",
            'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t",
            'у' to "u", 'ф' to "f", 'х' to "h", 'ц' to "ts", 'ч' to "ch",
            'ш' to "sh", 'щ' to "sch", 'ъ' to "", 'ы' to "y", 'ь' to "",
            'э' to "e", 'ю' to "yu", 'я' to "ya",
        )
        return buildString {
            value.lowercase().forEach { ch ->
                append(map[ch] ?: ch)
            }
        }
    }

    private fun bigramSimilarity(a: String, b: String): Double {
        fun variants(value: String): List<String> =
            listOfNotNull(
                value,
                foldLatinDiacritics(value),
                transliterateCyrillic(value),
                anyLatinTransliterator?.transliterate(value),
            )
                .map(::normalizedForMatch)
                .filter { it.isNotBlank() }
                .distinct()

        fun score(na: String, nb: String): Double {
            if (na == nb) return 1.0
            if (na.length < 2 || nb.length < 2) return 0.0
            val aBigrams = na.windowed(2).toSet()
            val bBigrams = nb.windowed(2).toSet()
            if (aBigrams.isEmpty() || bBigrams.isEmpty()) return 0.0
            val intersection = aBigrams.count { it in bBigrams }
            return (2.0 * intersection) / (aBigrams.size + bBigrams.size)
        }
        return variants(a).maxOf { aa ->
            variants(b).maxOf { bb -> score(aa, bb) }
        }
    }

    private data class VersionMarker(
        val name: String,
        val pattern: Regex,
        val hardReject: Boolean,
    )

    private fun markerPattern(terms: String): Regex =
        Regex("""(^|\s)($terms)(\s|$)""", RegexOption.IGNORE_CASE)

    private val alternateVersionMarkers = listOf(
        VersionMarker("remix", markerPattern("""re\s*mix|rmx|club mix|dance mix|dub mix|vip mix|remixed|ремикс|рмикс"""), true),
        VersionMarker("alternate", markerPattern("""alternative|alternate|alt version|demo|demo version|unreleased|rough mix|early version|альтернатив\w*|демо|неиздан\w*|чернов\w*"""), true),
        VersionMarker("sped up", markerPattern("""sped\s*up|speed\s*up|fast version|nightcore|daycore|ускоренн\w*|быстрая версия"""), true),
        VersionMarker("slowed", markerPattern("""slowed|slowed\s*(?:and|\+)?\s*reverb|slow version|reverb|замедленн\w*|медленная версия"""), true),
        VersionMarker("8d audio", markerPattern("""8d\s*audio|3d\s*audio|spatial audio|8d music"""), true),
        VersionMarker("bass boosted", markerPattern("""bass\s*boosted|bass\s*boost|megabass"""), true),
        VersionMarker("live", markerPattern("""live\s*(?:at|from|in|session|performance|version)?|in\s*concert|unplugged|лайв|концерт|с концерта|выступлен\w*"""), true),
        VersionMarker("acoustic", markerPattern("""acoustic|piano version|guitar version|акустик\w*|пианино|гитар\w*"""), true),
        VersionMarker("cover", markerPattern("""cover|covered by|tribute|fingerstyle|synthesia|tutorial|how to play|guitar cover|piano cover|drum cover|vocal cover|кавер|трибьют"""), true),
        VersionMarker("karaoke", markerPattern("""karaoke|minus one|backing track|караоке|минусовка"""), true),
        VersionMarker("instrumental", markerPattern("""instrumental|no vocals|инструментал|без вокала"""), true),
        VersionMarker("mashup", markerPattern("""mashup|mash up|bootleg|rework|flip|мешап|мэшап|бутлег"""), true),
        VersionMarker("fan edit", markerPattern("""fan\s*edit|fan\s*made|tiktok\s*version|edit\s*audio|type\s*beat|parody|reaction|reacts\s*to|перезалив|перезалит\w*"""), true),
        VersionMarker("extended", markerPattern("""extended mix|extended version|12 inch|12"""), false),
        VersionMarker("radio edit", markerPattern("""radio edit|single edit|edit version"""), false),
        VersionMarker("remaster", markerPattern("""remaster|remastered|anniversary edition"""), false),
    )

    private fun versionMarkers(value: String): Set<String> {
        val normalized = normalizedForMatch(value)
        return alternateVersionMarkers
            .filter { marker -> marker.pattern.containsMatchIn(normalized) }
            .map { it.name }
            .toSet()
    }

    private fun hardVersionMarkers(names: Collection<String>): Set<String> {
        val hardNames = alternateVersionMarkers.filter { it.hardReject }.map { it.name }.toSet()
        return names.filterTo(mutableSetOf()) { it in hardNames }
    }

    private fun ytmusicTransferScore(
        candidate: SongItem,
        expected: TrackMatchMetadata,
        expectedDurationMs: Int,
    ): CandidateScore {
        var candidateTitle = candidate.title
        var titleArtistScore = 0.0

        // Handle formats like "Artist - Title (Official Visualiser)" or "Artist: Title"
        val split = candidateTitle.split(Regex("""\s*[-–—:]\s*"""), limit = 2)
        if (split.size == 2) {
            val prefix = split[0].trim()
            val suffix = split[1].trim()
            val pArtistScore = bigramSimilarity(prefix, expected.artist)
            if (pArtistScore >= 0.45 || prefix.contains(expected.artist, ignoreCase = true) || expected.artist.contains(prefix, ignoreCase = true)) {
                candidateTitle = suffix
                titleArtistScore = maxOf(titleArtistScore, pArtistScore, 0.95)
            } else {
                val sArtistScore = bigramSimilarity(suffix, expected.artist)
                if (sArtistScore >= 0.45 || suffix.contains(expected.artist, ignoreCase = true) || expected.artist.contains(suffix, ignoreCase = true)) {
                    candidateTitle = prefix
                    titleArtistScore = maxOf(titleArtistScore, sArtistScore, 0.95)
                }
            }
        }

        fun clean(t: String) = cleanSpotifySearchTitle(t)
            .replace(Regex("""(?i)\b(official\s*(audio|video|music\s*video|lyric\s*video|visuali[sz]er|track|mv|clip)?|visuali[sz]er|lyric\s*video|music\s*video|mv|hd|4k|hq|audio|video|clip\s*officiel)\b"""), "")
            .replace(Regex("""[\[\]\(\)\-_|]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

        val cleanExpTitle = clean(expected.title).lowercase()
        val cleanCandTitle = clean(candidateTitle).lowercase()

        val rawTitleScore = bigramSimilarity(candidateTitle, expected.title)
        val cleanTitleScore = bigramSimilarity(cleanCandTitle, cleanExpTitle)
        val containsTitleMatch = if (cleanExpTitle.isNotEmpty() && cleanCandTitle.isNotEmpty()) {
            if (cleanCandTitle == cleanExpTitle) 1.0
            else if (cleanCandTitle.contains(cleanExpTitle) || cleanExpTitle.contains(cleanCandTitle)) 0.95
            else 0.0
        } else 0.0

        val titleScore = maxOf(rawTitleScore, cleanTitleScore, containsTitleMatch)

        // Robust multi-artist matching: split by commas, &, ft., feat., with, x
        val artistSplitRegex = Regex("""(?i)\s*(?:,|\band\b|&|\bfeat\.?|\bft\.?|\bwith\b|\bx\b)\s*""")
        val expectedArtists = expected.artist.split(artistSplitRegex)
            .map { it.trim().lowercase() }.filter { it.isNotBlank() }
        val candidateArtists = candidate.artists.map { it.name.trim().lowercase() }
        val primaryExpected = expectedArtists.firstOrNull().orEmpty()
        val primaryCandidate = candidateArtists.firstOrNull().orEmpty()

        val primaryMatch = if (primaryExpected.isNotEmpty() && primaryCandidate.isNotEmpty()) {
            if (primaryCandidate == primaryExpected) 1.0
            else if (primaryCandidate.contains(primaryExpected) || primaryExpected.contains(primaryCandidate)) 0.95
            else bigramSimilarity(primaryCandidate, primaryExpected)
        } else 0.0

        val anyArtistMatch = if (expectedArtists.any { exp -> candidateArtists.any { cand -> cand.contains(exp) || exp.contains(cand) } }) 1.0 else 0.0

        val uploaderArtistScore = bigramSimilarity(
            candidate.artists.joinToString(" ") { it.name },
            expected.artist,
        )
        val artistScore = maxOf(uploaderArtistScore, titleArtistScore, primaryMatch * 0.98, anyArtistMatch * 0.90)

        val expectedDurationSec = expectedDurationMs / 1000.0
        val candidateDuration = candidate.duration
        val durationScore = if (expectedDurationSec > 0 && candidateDuration != null) {
            val diffSec = abs(candidateDuration - expectedDurationSec)
            when {
                diffSec <= 2 -> 1.0
                diffSec <= 4 -> 0.95
                diffSec <= 7 -> 0.85
                diffSec <= 12 -> 0.40
                diffSec <= 20 -> -0.20
                diffSec <= 35 -> -1.50
                else -> -4.0
            }
        } else {
            null
        }

        val albumScore = if (!candidate.isVideoSong && expected.album.isNotBlank()) {
            candidate.album?.name?.let { bigramSimilarity(it, expected.album) }
        } else {
            null
        }

        val expectedMarkers = versionMarkers(expected.title)
        val candidateMarkers = versionMarkers(
            listOf(
                candidate.title,
                candidate.album?.name.orEmpty(),
            ).joinToString(" "),
        )
        val unexpectedAlternates = (candidateMarkers - expectedMarkers).toList().sorted()
        val hardUnexpected = hardVersionMarkers(unexpectedAlternates).size
        val softUnexpected = unexpectedAlternates.size - hardUnexpected
        val alternatePenalty = hardUnexpected * 6.0 + softUnexpected * 2.0

        val isTopicChannel = candidate.artists.any { it.name.endsWith(" - Topic", ignoreCase = true) }
        val isOfficialAudioTrack = !candidate.isVideoSong || isTopicChannel
        val durationIsAccurate = durationScore != null && durationScore >= 0.85

        val officialBoost = when {
            isOfficialAudioTrack && durationIsAccurate -> 3.5
            isTopicChannel -> 3.0
            !candidate.isVideoSong -> 2.2
            else -> 1.0
        }

        val parts = mutableListOf(titleScore * 2.0, artistScore * 2.0)
        durationScore?.let { parts += it * 4.5 }
        albumScore?.let { parts += it * 1.0 }
        val baseScore = parts.average() * officialBoost
        return CandidateScore(
            item = candidate,
            score = (baseScore - alternatePenalty).coerceAtLeast(0.0),
            titleScore = titleScore,
            artistScore = uploaderArtistScore,
            artistEvidenceScore = artistScore,
            durationScore = durationScore,
            albumScore = albumScore,
            alternatePenalty = alternatePenalty,
            unexpectedAlternates = unexpectedAlternates,
        )
    }

    private fun CandidateScore.isAcceptableMatch(): Boolean {
        val durationStrong = durationScore?.let { it >= 0.80 } ?: false
        val albumUseful = albumScore?.let { it >= 0.40 } ?: false
        val hasDuration = durationScore != null
        val minScore = when {
            !item.isVideoSong && hasDuration -> 0.90
            !item.isVideoSong -> 0.85
            hasDuration -> 1.35
            else -> 1.10
        }

        val hasUnexpectedHardAlternate = hardVersionMarkers(unexpectedAlternates).isNotEmpty()
        val durationSevereMismatch = durationScore?.let { it < -0.5 } ?: false
        return score >= minScore &&
            !hasUnexpectedHardAlternate &&
            !durationSevereMismatch &&
            titleScore >= 0.40 &&
            (
                artistEvidenceScore >= 0.25 ||
                    (albumUseful && artistEvidenceScore >= 0.15) ||
                    (durationStrong && artistEvidenceScore >= 0.18) ||
                    (titleScore >= 0.85 && durationStrong)
                )
    }

    private suspend fun ensureSpotifyMatchMetadata(query: String): TrackMatchMetadata? {
        val currentMeta = metadataRegistry[query]
        val hasUsefulMeta = currentMeta?.let {
            it.title.isNotBlank() && it.artist.isNotBlank() && it.album.isNotBlank()
        } ?: false
        if (hasUsefulMeta && durationRegistry[query] != null && explicitRegistry.containsKey(query)) {
            return currentMeta
        }

        val spotifyId = trackIdRegistry[query] ?: spotifyTrackIdForPlayback(query) ?: return currentMeta
        val track = runCatching { com.metrolist.spotify.Spotify.track(spotifyId).getOrNull() }
            .onFailure { Log.w(TAG, "Spotify metadata repair failed for $spotifyId", it) }
            .getOrNull()
            ?: return currentMeta

        val repaired = TrackMatchMetadata(
            title = track.name,
            artist = track.artists.joinToString(", ") { it.name },
            album = track.album?.name ?: currentMeta?.album.orEmpty(),
        )
        metadataRegistry[query] = repaired
        trackIdRegistry[query] = spotifyId
        explicitRegistry[query] = track.explicit
        if (track.durationMs > 0) durationRegistry[query] = track.durationMs
        return repaired
    }

    private val trackResolver = com.music.spotui.resolver.TrackResolver()

    private suspend fun resolveVideoCandidates(
        query: String,
        filter: YouTube.SearchFilter = YouTube.SearchFilter.FILTER_SONG,
    ): List<String> {
        val searchText = searchTextForPlayback(query)
        // A raw YouTube videoId is 11 chars with no spaces — accept it directly.
        if (searchText.length == 11 && !searchText.contains(' ')) return listOf(searchText)

        val exactMeta = ensureSpotifyMatchMetadata(query)
        val target = if (exactMeta != null) {
            com.music.spotui.resolver.TrackTarget(
                title = exactMeta.title,
                artist = exactMeta.artist,
                durationMs = durationRegistry[query]?.toLong() ?: 0L,
                album = exactMeta.album
            )
        } else {
            val parts = searchText.split(" - ", limit = 2)
            if (parts.size == 2) {
                com.music.spotui.resolver.TrackTarget(
                    title = parts[1],
                    artist = parts[0],
                    durationMs = durationRegistry[query]?.toLong() ?: 0L
                )
            } else {
                com.music.spotui.resolver.TrackTarget(
                    title = searchText,
                    artist = "",
                    durationMs = durationRegistry[query]?.toLong() ?: 0L
                )
            }
        }

        // Multi-Tiered Track Matching Engine with fallback
        val resolverCandidates = trackResolver.resolveRankedCandidates(target)
        if (resolverCandidates.isNotEmpty()) {
            val resolverIds = resolverCandidates.map { it.videoId }.distinct()
            Log.d(TAG, "TrackResolver produced ${resolverIds.size} ranked candidates for '$searchText': primary='${resolverCandidates.first().title}' [${resolverIds.first()}]")
            return resolverIds
        }

        val queriesToTry = mutableListOf<String>()
        if (exactMeta != null) {
            val primaryArtist = exactMeta.artist
                .split(Regex("""(?i)\s*(?:,|\band\b|&|\bfeat\.?|\bft\.?|\bwith\b|\bx\b)\s*"""))
                .firstOrNull { it.isNotBlank() }?.trim() ?: exactMeta.artist
            val cleanTitle = cleanSpotifySearchTitle(exactMeta.title)

            if (cleanTitle.isNotBlank() && primaryArtist.isNotBlank()) {
                queriesToTry.add("$cleanTitle $primaryArtist")
                queriesToTry.add("$primaryArtist $cleanTitle")
            }
            if (primaryArtist != exactMeta.artist) {
                queriesToTry.add("$cleanTitle ${exactMeta.artist}")
                queriesToTry.add("${exactMeta.artist} $cleanTitle")
            }
            queriesToTry.add("${exactMeta.title} $primaryArtist")
            queriesToTry.add("${exactMeta.title} ${exactMeta.artist}")
            queriesToTry.add("${exactMeta.title} $primaryArtist official audio")
            queriesToTry.add("${exactMeta.title} $primaryArtist topic")
            queriesToTry.addAll(com.metrolist.spotify.SpotifyMapper.buildSearchQueries(exactMeta.title, exactMeta.artist))
            queriesToTry.add(cleanTitle.ifBlank { exactMeta.title })
        } else {
            queriesToTry.add(searchText)
            val cleanQ = com.metrolist.spotify.SpotifyMapper.cleanTitleForSearch(searchText)
            if (cleanQ.isNotBlank() && cleanQ != searchText) {
                queriesToTry.add(cleanQ)
            }
        }

        val queries = queriesToTry.distinct().take(4)
        val allHits = mutableListOf<SongItem>()
        coroutineScope {
            val jobs = queries.map { q ->
                async {
                    runCatching {
                        YouTube.search(q, filter).getOrNull()?.items?.filterIsInstance<SongItem>().orEmpty()
                    }.getOrDefault(emptyList())
                }
            }
            jobs.awaitAll().forEach { allHits.addAll(it) }
        }

        // If song filter returned no items or insufficient results, query video filter
        if (allHits.isEmpty() && filter == YouTube.SearchFilter.FILTER_SONG) {
            coroutineScope {
                val jobs = queries.take(2).map { q ->
                    async {
                        runCatching {
                            YouTube.search(q, YouTube.SearchFilter.FILTER_VIDEO).getOrNull()?.items?.filterIsInstance<SongItem>().orEmpty()
                        }.getOrDefault(emptyList())
                    }
                }
                jobs.awaitAll().forEach { allHits.addAll(it) }
            }
        }

        val hits = allHits.distinctBy { it.id }
        if (hits.isEmpty()) {
            Log.w(TAG, "resolveVideoId: no YouTube song results for: $searchText")
            return emptyList()
        }

        fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
        val qn = norm(searchText)
        val wantSec = durationRegistry[query]?.let { it / 1000 }
        val scored = hits.map { h ->
            val cleanTitle = norm(h.title.substringBefore('(').substringBefore('['))
            var s = 0
            if (cleanTitle.isNotEmpty() && qn.contains(cleanTitle)) s += 1
            if (h.artists.any { a -> norm(a.name).let { it.isNotEmpty() && qn.contains(it) } }) s += 2
            val hDur = h.duration
            if (wantSec != null && hDur != null && kotlin.math.abs(hDur - wantSec) <= 4) s += 3
            h to s
        }
        val effectiveMeta = exactMeta ?: run {
            val parts = searchText.split(" - ", limit = 2)
            if (parts.size == 2) TrackMatchMetadata(title = parts[1], artist = parts[0], album = "")
            else TrackMatchMetadata(title = searchText, artist = "", album = "")
        }
        val transferScored = hits.map { ytmusicTransferScore(it, effectiveMeta, durationRegistry[query] ?: 0) }

        fun verified(h: SongItem): Boolean {
            val artistOk = h.artists.any { a -> norm(a.name).let { it.isNotEmpty() && qn.contains(it) } }
            val d = h.duration
            val durOk = wantSec != null && d != null && kotlin.math.abs(d - wantSec) <= 4
            return artistOk || durOk
        }
        val wantExplicit = explicitRegistry[query]
        fun explicitFirst(list: List<SongItem>) =
            if (wantExplicit != null) list.sortedByDescending { it.explicit == wantExplicit } else list

        val accepted = transferScored
            .filter { it.isAcceptableMatch() }
            .sortedWith(
                compareByDescending<CandidateScore> { it.item.explicit == wantExplicit || wantExplicit == null }
                    .thenByDescending { !it.item.isVideoSong }
                    .thenByDescending { it.score }
            )
            .map { it.item }

        val ordered = if (accepted.isNotEmpty()) {
            accepted.distinctBy { it.id }
        } else {
            val withoutHardMismatches = transferScored.filter { hardVersionMarkers(it.unexpectedAlternates).isEmpty() }
            val candidatesToRank = if (withoutHardMismatches.isNotEmpty()) withoutHardMismatches else transferScored
            candidatesToRank
                .sortedWith(
                    compareByDescending<CandidateScore> { it.item.explicit == wantExplicit || wantExplicit == null }
                        .thenByDescending { !it.item.isVideoSong }
                        .thenByDescending { it.score }
                )
                .map { it.item }
                .distinctBy { it.id }
        }

        if (ordered.isEmpty()) return emptyList()
        val chosen = ordered.first()
        if (transferScored.isEmpty() && !verified(chosen)) {
            Log.w(TAG, "resolveVideoId: no verified match for: $searchText (want=${wantSec}s) — best-effort '${chosen.title}'")
        }
        val chosenScore = transferScored.firstOrNull { it.item.id == chosen.id }
        Log.d(
            TAG,
            "resolveVideoId: '$searchText' -> '${chosen.title}' by " +
                chosen.artists.joinToString { it.name } +
                " [explicit=${chosen.explicit} dur=${chosen.duration}s want=${wantSec}s id=${chosen.id}] " +
                (chosenScore?.let {
                    "score=${"%.2f".format(it.score)} title=${"%.2f".format(it.titleScore)} " +
                        "artist=${"%.2f".format(it.artistEvidenceScore)} duration=${"%.2f".format(it.durationScore ?: 0.0)} " +
                        "album=${"%.2f".format(it.albumScore ?: 0.0)} " +
                        "altPenalty=${"%.2f".format(it.alternatePenalty)} " +
                        "alt=${it.unexpectedAlternates.joinToString("/")}"
                } ?: "${ordered.count { verified(it) }} verified/${ordered.size}"),
        )
        return ordered.map { it.id }.distinct()
    }

    /**
     * Resolves a playable YouTube stream for [query], falling back through up to
     * 3 ranked video candidates when one has no obtainable stream.
     */
    private suspend fun resolveYtPlayback(
        query: String,
        audioQuality: com.metrolist.music.constants.AudioQuality,
        appContext: Context,
    ): YTPlayerUtils.PlaybackData? {
        val connectivityManager =
            appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val tried = mutableSetOf<String>()
        val failedIds = failedVideoIdsForSong[query].orEmpty()
        val qualityList = listOf(audioQuality, com.metrolist.music.constants.AudioQuality.HIGH, com.metrolist.music.constants.AudioQuality.LOW, com.metrolist.music.constants.AudioQuality.AUTO).distinct()

        suspend fun tryIds(ids: List<String>): YTPlayerUtils.PlaybackData? {
            for (videoId in ids) {
                if (videoId in failedIds) continue
                for (q in qualityList) {
                    val tryKey = "$videoId-${q.name}"
                    if (!tried.add(tryKey)) continue
                    YTPlayerUtils.playerResponseForPlayback(
                        videoId = videoId,
                        audioQuality = q,
                        connectivityManager = connectivityManager,
                    ).fold(
                        onSuccess = {
                            activeResolvedVideoId[query] = videoId
                            return it
                        },
                        onFailure = { Log.w(TAG, "stream failed for $videoId @ quality ${q.name} (${it.message}) — rolling to next option") },
                    )
                }
            }
            return null
        }
        tryIds(resolveVideoCandidates(query).take(4))?.let { return it }
        if (!com.music.spotui.data.preferences.isVideoFallbackEnabled(appContext)) {
            Log.w(TAG, "song candidates exhausted and video fallback disabled for: ${searchTextForPlayback(query)}")
            return null
        }
        Log.w(TAG, "song candidates exhausted, trying video search for: ${searchTextForPlayback(query)}")
        tryIds(resolveVideoCandidates(query, YouTube.SearchFilter.FILTER_VIDEO).take(4))?.let { return it }
        Log.e(TAG, "All YouTube candidates failed for: ${searchTextForPlayback(query)}")
        return null
    }

    private fun buildAudioAttributes() =
        androidx.media3.common.AudioAttributes.Builder()
            .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(androidx.media3.common.C.USAGE_MEDIA)
            .build()

    @Volatile private var duckingJob: kotlinx.coroutines.Job? = null

    fun rampVolume(targetVolume: Float, durationMs: Long = 250L) {
        duckingJob?.cancel()
        duckingJob = scope.launch(Dispatchers.Main) {
            val p = player ?: return@launch
            val startVol = p.volume
            val steps = 10
            val delayStep = (durationMs / steps).coerceAtLeast(10L)
            for (i in 1..steps) {
                val v = startVol + (targetVolume - startVol) * (i.toFloat() / steps)
                p.volume = v
                kotlinx.coroutines.delay(delayStep)
            }
            p.volume = targetVolume
        }
    }

    /**
     * Build an ExoPlayer that reads through the shared media cache (so preloaded intro
     * bytes are reused) and carries its own [CrossfadeFilterAudioProcessor] so the DJ-style
     * low/high-pass sweep can be applied per track during a crossfade. The filter is
     * disabled (pass-through) outside a crossfade, so there's no overhead in normal playback.
     *
     * @param handleAudioFocus true for the active/session player, false for the transient
     *   secondary (incoming) player so it doesn't fight the primary for focus mid-fade.
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun createPlayerWithFilter(
        context: Context,
        handleAudioFocus: Boolean,
    ): Pair<ExoPlayer, com.music.spotui.audio.CrossfadeFilterAudioProcessor> {
        val filter = com.music.spotui.audio.CrossfadeFilterAudioProcessor()
        val loudnessNormalizer = com.music.spotui.audio.LoudnessNormalizerAudioProcessor()
        val renderers = object : androidx.media3.exoplayer.DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): androidx.media3.exoplayer.audio.AudioSink =
                androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessorChain(
                        androidx.media3.exoplayer.audio.DefaultAudioSink.DefaultAudioProcessorChain(
                            filter,
                            loudnessNormalizer,
                        ),
                    ).build()
        }
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 30_000,
                /* maxBufferMs = */ 120_000,
                /* bufferForPlaybackMs = */ 250,
                /* bufferForPlaybackAfterRebufferMs = */ 600,
            )
            .setTargetBufferBytes(16 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val p = ExoPlayer.Builder(context)
            .setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                    // Routes deezer:// URIs to the decrypting DeezerDataSource and
                    // everything else through the normal cached HTTP stack.
                    com.music.spotui.deezer.DeezerAwareDataSourceFactory(cacheDataSourceFactory(context)),
                ).setDrmSessionManagerProvider { androidx.media3.exoplayer.drm.DrmSessionManager.DRM_UNSUPPORTED },
            )
            .setLoadControl(loadControl)
            .setRenderersFactory(renderers)
            .setAudioAttributes(buildAudioAttributes(), handleAudioFocus)
            .setHandleAudioBecomingNoisy(handleAudioFocus)
            .build()

        if (handleAudioFocus) {
            p.addListener(object : androidx.media3.common.Player.Listener {
                override fun onAudioSessionIdChanged(audioSessionId: Int) {
                    com.music.spotui.audio.EqualizerManager.bindAudioSession(audioSessionId)
                }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        androidx.media3.common.Player.STATE_BUFFERING -> {
                            val isOnline = com.music.spotui.data.network.NetworkMonitor.isOnlineNow(context)
                            val isLocal = currentSource == "Downloaded" || currentSource == "Local file"
                            if (!isOnline && !isLocal) {
                                // Online stream ran dry while device transitioned to offline mid-playback
                                Log.w(TAG, "Dynamic handover: streaming buffer exhausted while offline")
                                p.pause()
                                _playbackStatus.value = PlaybackStatus.OfflineBufferExhausted("Playback paused: Audio buffer exhausted while offline")
                                showShortToast(context, "Offline: Audio buffer exhausted")
                                return
                            }
                            val cur = _playbackStatus.value
                            if (cur !is PlaybackStatus.Reconnecting && cur !is PlaybackStatus.Error && cur !is PlaybackStatus.OfflineBufferExhausted) {
                                _playbackStatus.value = PlaybackStatus.Buffering(currentSource, currentQuality)
                            }
                        }
                        androidx.media3.common.Player.STATE_READY -> {
                            consecutiveFailures = 0
                            if (p.playWhenReady) {
                                _playbackStatus.value = PlaybackStatus.Playing(currentSource, currentQuality)
                            } else {
                                _playbackStatus.value = PlaybackStatus.Paused(currentSource, currentQuality)
                            }
                        }
                        androidx.media3.common.Player.STATE_ENDED -> {
                            _playbackStatus.value = PlaybackStatus.Idle
                            scope.launch(Dispatchers.Main) {
                                val state = boundState ?: CurrentSongState.instance
                                if (state != null && state.repeat.value) {
                                    p.seekTo(0)
                                    p.play()
                                } else {
                                    skipToNextTrack(context)
                                }
                            }
                        }
                        androidx.media3.common.Player.STATE_IDLE -> {
                            val cur = _playbackStatus.value
                            if (cur !is PlaybackStatus.Error && cur !is PlaybackStatus.Loading && cur !is PlaybackStatus.Reconnecting && cur !is PlaybackStatus.OfflineBufferExhausted) {
                                _playbackStatus.value = PlaybackStatus.Idle
                            }
                        }
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) {
                        _playbackStatus.value = PlaybackStatus.Playing(currentSource, currentQuality)
                    } else if (p.playbackState == androidx.media3.common.Player.STATE_READY) {
                        _playbackStatus.value = PlaybackStatus.Paused(currentSource, currentQuality)
                    }
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    Log.e(TAG, "ExoPlayer error occurred: ${error.errorCodeName} / ${error.message}", error)
                    val song = currentRequest
                    if (song.isNotBlank()) {
                        if (currentSource.startsWith("Deezer")) {
                            Log.w(TAG, "Deezer playback error for $song — blacklisting Deezer for this track")
                            failedSourcesForSong.getOrPut(song) { java.util.concurrent.ConcurrentHashMap.newKeySet() }.add("Deezer")
                        } else if (currentSource.startsWith("Lossless")) {
                            Log.w(TAG, "Lossless playback error for $song — blacklisting Lossless for this track")
                            failedSourcesForSong.getOrPut(song) { java.util.concurrent.ConcurrentHashMap.newKeySet() }.add("Lossless")
                        } else if (currentSource.startsWith("Saavn")) {
                            Log.w(TAG, "Saavn playback error for $song — blacklisting Saavn for this track")
                            failedSourcesForSong.getOrPut(song) { java.util.concurrent.ConcurrentHashMap.newKeySet() }.add("Saavn")
                        } else if (currentSource.contains("YouTube") || currentSource.contains("Piped")) {
                            activeResolvedVideoId[song]?.let { badVid ->
                                Log.w(TAG, "YouTube/Piped playback error for $song (videoId=$badVid) — blacklisting candidate")
                                failedVideoIdsForSong.getOrPut(song) { java.util.concurrent.ConcurrentHashMap.newKeySet() }.add(badVid)
                            }
                        }
                        invalidateResolvedStream(song)
                    }
                    currentQuality = ""
                    val isNetwork = error.errorCode in listOf(
                        androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                        androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                        androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                    ) || error.cause is java.io.IOException

                    val msg = if (isNetwork) "Network connection failed" else "Stream playback error"
                    _playbackStatus.value = PlaybackStatus.Error(msg, canRetry = true)
                    triggerReconnect(context)
                }
            })
        }
        return p to filter
    }

    private fun ensurePlayer(context: Context) {
        appCtx = context.applicationContext
        if (player == null) {
            val (p, filter) = createPlayerWithFilter(context, handleAudioFocus = true)
            player = p
            currentPlayerFilter = filter
            onPlayerCreated?.invoke(p)
        }
    }

    /** The live ExoPlayer instance (may be null before first play). */
    val exoPlayer: ExoPlayer? get() = player

    /** Make sure the player exists (used by the media-session service). */
    fun ensureCreated(context: Context) = ensurePlayer(context.applicationContext)

    /** Notified right after the ExoPlayer is built so the session can attach to it. */
    @Volatile var onPlayerCreated: ((ExoPlayer) -> Unit)? = null

    fun isPlaying(): Boolean {
        if (webPlaybackActive()) return SpotifyWebPlayer.isPlaying
        return player?.isPlaying ?: false
    }

    fun webPlaybackActive(): Boolean {
        if (!webPlayerEnabled) return false
        val ctx = appCtx ?: return false
        // Spotify web playback needs: user hasn't opted out, the WebView actually has
        // Widevine, AND the user is logged into Spotify (sp_dc). Missing any of these
        // → fall back to the YouTube/FLAC engine so playback is never silent.
        return com.music.spotui.data.preferences.isWebPlaybackEnabled(ctx) &&
            SpotifyWebPlayer.canPlay &&
            com.music.spotui.data.api.SpotifySession.spDc(ctx).isNotBlank()
    }

    // ── Session restore (survive app restarts) ──
    // Set at launch from the persisted playback state; the first playSong for
    // this query seeks to the saved position, and play() with an empty player
    // re-resolves the track instead of doing nothing.
    @Volatile private var restoreQuery: String? = null
    @Volatile private var restorePositionMs: Long = 0L

    fun setRestorePoint(query: String, positionMs: Long) {
        if (query.isBlank()) return
        restoreQuery = query
        restorePositionMs = positionMs.coerceAtLeast(0L)
    }

    fun play() {
        if (webPlaybackActive()) { SpotifyWebPlayer.resume(); return }
        // Fresh launch: nothing loaded yet — resume the restored session track.
        if ((player?.mediaItemCount ?: 0) == 0) {
            val q = restoreQuery
            val ctx = appCtx
            if (q != null && ctx != null) { playSong(q, ctx); return }
        }
        player?.play()
    }

    fun pause() {
        cancelCrossfade()
        if (webPlaybackActive()) { SpotifyWebPlayer.pause(); return }
        player?.let {
            it.playWhenReady = false
            // Remember where we stopped so a relaunch can resume mid-track.
            appCtx?.let { ctx ->
                val pos = it.currentPosition
                if (pos > 0) com.music.spotui.data.preferences.saveLastPosition(ctx, pos)
            }
        }
    }

    fun stop() {
        cancelCrossfade()
        player?.stop()
    }

    fun seekTo(position: Long) {
        cancelCrossfade()
        if (webPlaybackActive()) { SpotifyWebPlayer.seekTo(position); return }
        player?.seekTo(position)
    }

    fun release() {
        positionWatchJob?.cancel()
        cancelCrossfade()
        player?.release()
        player = null
    }

    fun getDuration(): Long {
        if (webPlaybackActive()) return SpotifyWebPlayer.durationMs
        return player?.duration ?: 0L
    }

    fun getCurrentPosition(): Long {
        if (webPlaybackActive()) return SpotifyWebPlayer.positionMs
        return player?.currentPosition ?: 0L
    }

    fun isPrepared(): Boolean {
        val playerState = player?.playbackState
        return playerState != null && playerState != ExoPlayer.STATE_IDLE && playerState != ExoPlayer.STATE_ENDED
    }

    // ── Crossfade + DJ-style mixing ──
    // The end of the current track is blended into the start of the next over a user-set
    // window (Settings). A second, transient ExoPlayer plays the incoming track while the
    // primary fades out; volumes follow an equal-power (cos/sin) curve so total loudness
    // stays constant. In DJ mode, the outgoing track is low-passed (treble drops out) and the
    // incoming track high-passed (bass fills in) via per-player [CrossfadeFilterAudioProcessor]s,
    // swept on an S-curve — like a real DJ mixer. When the blend finishes the secondary player
    // is promoted to primary and the media session is re-bound to it via [onPlayerSwapped].
    private const val CF_LPF_START_HZ = 20000f
    private const val CF_LPF_END_HZ = 200f
    private const val CF_HPF_START_HZ = 2000f
    private const val CF_HPF_END_HZ = 20f
    private const val CF_SIGMOID_K = 6f

    @Volatile private var appCtx: Context? = null
    @Volatile private var boundState: CurrentSongState? = null
    @Volatile private var currentPlayerFilter: com.music.spotui.audio.CrossfadeFilterAudioProcessor? = null
    @Volatile private var secondaryPlayer: ExoPlayer? = null
    @Volatile private var secondaryPlayerFilter: com.music.spotui.audio.CrossfadeFilterAudioProcessor? = null
    @Volatile private var isCrossfading = false
    @Volatile private var crossfadeJob: kotlinx.coroutines.Job? = null
    @Volatile private var positionWatchJob: kotlinx.coroutines.Job? = null

    /** Notified (on the main thread) when the active ExoPlayer instance changes after a
     *  crossfade, so the media session can re-bind to the promoted player. */
    @Volatile var onPlayerSwapped: ((ExoPlayer) -> Unit)? = null

    /** Give the player access to the shared queue/now-playing state so it can advance the
     *  app's notion of "current track" itself when a crossfade fires. Called once at startup. */
    fun bindState(state: CurrentSongState) { boundState = state }

    fun isCrossfadeActive(): Boolean = isCrossfading

    private fun sigmoid(t: Float): Float = 1.0f / (1.0f + exp(-CF_SIGMOID_K * (t - 0.5f)))

    private fun expInterpolate(start: Float, end: Float, t: Float): Float {
        if (start <= 0f || end <= 0f) return end
        return exp(ln(start) + (ln(end) - ln(start)) * t).toFloat()
    }

    /** Cancel an in-flight crossfade and tear down the secondary player, restoring the
     *  primary to full volume with its filter disabled. Safe to call when not crossfading. */
    private fun cancelCrossfade() {
        if (!isCrossfading && secondaryPlayer == null) return
        crossfadeJob?.cancel()
        crossfadeJob = null
        currentPlayerFilter?.enabled = false
        secondaryPlayerFilter?.enabled = false
        runCatching { secondaryPlayer?.release() }
        secondaryPlayer = null
        secondaryPlayerFilter = null
        player?.volume = 1f
        isCrossfading = false
    }

    /** (Re)start the loop that watches playback position and fires a crossfade as the
     *  current track approaches its end. */
    private var posSaveTick = 0
    @Volatile private var bufferingStartMs: Long = 0L

    private fun startPositionWatch() {
        positionWatchJob?.cancel()
        positionWatchJob = scope.launch {
            while (isActive) {
                kotlinx.coroutines.delay(250)
                val ctx = appCtx ?: continue

                // Stalled stream auto-recovery: check if player is stuck in STATE_BUFFERING at 00:00 for >4.5s
                player?.let { p ->
                    val isBuffering = withContext(Dispatchers.Main) { p.playbackState == androidx.media3.common.Player.STATE_BUFFERING }
                    val currentPos = withContext(Dispatchers.Main) { p.currentPosition }
                    if (isBuffering && currentPos <= 1000L) {
                        if (bufferingStartMs == 0L) {
                            bufferingStartMs = System.currentTimeMillis()
                        } else if (System.currentTimeMillis() - bufferingStartMs > 4500L) {
                            val song = currentRequest
                            Log.w(TAG, "Stream stalled at 00:00 for over 4.5s on source $currentSource — failing over to next source")
                            bufferingStartMs = 0L
                            if (song.isNotBlank()) {
                                invalidateResolvedStream(song)
                                if (currentSource.startsWith("Deezer")) {
                                    failedSourcesForSong.getOrPut(song) { java.util.concurrent.ConcurrentHashMap.newKeySet() }.add("Deezer")
                                } else if (currentSource.startsWith("Lossless")) {
                                    failedSourcesForSong.getOrPut(song) { java.util.concurrent.ConcurrentHashMap.newKeySet() }.add("Lossless")
                                } else if (currentSource.startsWith("Saavn")) {
                                    failedSourcesForSong.getOrPut(song) { java.util.concurrent.ConcurrentHashMap.newKeySet() }.add("Saavn")
                                } else if (currentSource.startsWith("YouTube")) {
                                    activeResolvedVideoId[song]?.let { badVid ->
                                        failedVideoIdsForSong.getOrPut(song) { java.util.concurrent.ConcurrentHashMap.newKeySet() }.add(badVid)
                                    }
                                }
                                triggerReconnect(ctx)
                            } else {
                                withContext(Dispatchers.Main) {
                                    skipToNextTrack(ctx)
                                }
                            }
                        }
                    } else {
                        bufferingStartMs = 0L
                    }
                }

                // Persist the position every ~3s so a relaunch resumes mid-track.

                if (++posSaveTick % 12 == 0 && !webPlaybackActive()) {
                    player?.let { p ->
                        val pos = withContext(Dispatchers.Main) {
                            if (p.isPlaying) p.currentPosition else -1L
                        }
                        if (pos > 0) com.music.spotui.data.preferences.saveLastPosition(ctx, pos)
                    }
                }
                val p = player ?: continue
                val playing = withContext(Dispatchers.Main) { p.isPlaying }
                if (!playing) continue
                val dur = withContext(Dispatchers.Main) { p.duration }
                val pos = withContext(Dispatchers.Main) { p.currentPosition }
                if (dur <= 10_000L || pos < 3000L) continue

                // Audio Scrobbling Engine update
                com.music.spotui.data.scrobble.ScrobblerEngine.onPlaybackProgress(ctx, pos)

                // Zero-Latency Gapless Playback Engine: Pre-resolve & pre-buffer next track at 80% track duration
                if (pos >= (dur * 0.80) && !hasPreloadedCurrentTrack) {
                    hasPreloadedCurrentTrack = true
                    triggerLookaheadPreResolution(ctx)
                }

                if (isCrossfading) continue
                val crossfadeMs = com.music.spotui.data.preferences.getCrossfadeMs(ctx)
                if (crossfadeMs <= 0) continue
                val state = boundState ?: continue
                if (state.repeat.value) continue // repeat-one loops the same track
                val remainingMs = dur - pos
                if (remainingMs in 1..crossfadeMs && pos >= minOf(5000L, dur / 2)) {
                    triggerCrossfade(ctx, crossfadeMs)
                }
            }
        }
    }

    @Volatile private var hasPreloadedCurrentTrack = false

    /**
     * Zero-Latency Lookahead Pre-Resolution Worker:
     * Fires at 80% track duration to pre-resolve stream URLs and pre-buffer the intro into media cache.
     */
    private fun triggerLookaheadPreResolution(ctx: Context) {
        val state = boundState ?: return
        val q = state.queue.value
        if (q.isEmpty()) return
        val cur = q.indexOfFirst { it.id == state.songId.value }
        if (cur < 0 || cur >= q.size - 1) return
        val nextSong = q[cur + 1]

        scope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "Gapless Lookahead Pre-Resolution triggered at 80% mark for '${nextSong.title}'")
                val localManager = com.music.spotui.storage.LocalFileManager.getInstance(ctx)
                val isLocal = localManager.hasDownloadedFile(nextSong.id.toString()) ||
                    (nextSong.spotifyTrackId.isNotBlank() && localManager.hasDownloadedFile(nextSong.spotifyTrackId))

                if (isLocal) {
                    Log.d(TAG, "Next track '${nextSong.title}' verified locally on disk.")
                    return@launch
                }

                val resolvedUrl = resolveStreamUrl(nextSong.url, ctx, forPlayback = false)
                if (resolvedUrl != null) {
                    com.music.spotui.player.StreamUrlCache.put(nextSong.url, resolvedUrl, source = currentSource, quality = currentQuality)
                    cacheIntro(resolvedUrl, ctx)
                    Log.d(TAG, "Gapless Pre-Resolution completed & cached for '${nextSong.title}'")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Gapless lookahead pre-resolution failed for next track: ${e.message}")
            }
        }
    }

    /** Begin blending the current track into the next queue item. */
    private fun triggerCrossfade(ctx: Context, configuredMs: Int) {
        if (isCrossfading) return
        val state = boundState ?: return
        val q = state.queue.value
        if (q.isEmpty()) return
        val cur = q.indexOfFirst { it.id == state.songId.value }
        if (cur < 0 || cur >= q.size - 1) return // last track ends normally
        val nextSong = q[cur + 1]
        isCrossfading = true
        currentRequest = nextSong.url
        scope.launch {
            try {
                val nextUrl = resolveStreamUrl(nextSong.url, ctx, forPlayback = true) ?: run {
                    isCrossfading = false; return@launch
                }
                // Effective duration: never longer than the real time left on the outgoing track.
                val remaining = withContext(Dispatchers.Main) {
                    val p = player ?: return@withContext configuredMs.toLong()
                    val d = p.duration; val ps = p.currentPosition
                    if (d > 0 && ps >= 0) (d - ps) else configuredMs.toLong()
                }
                val effectiveMs = minOf(configuredMs.toLong(), remaining).coerceAtLeast(1000L).toInt()
                val djMode = com.music.spotui.data.preferences.isCrossfadeDjMode(ctx)

                withContext(Dispatchers.Main) {
                    // Advance the app's now-playing state immediately so the in-app UI follows
                    // the incoming track during the blend. Also sets the now-playing meta used
                    // to tag the secondary player's MediaItem.
                    state.updateSongState(
                        nextSong.coverUri, nextSong.title, nextSong.singer, true,
                        nextSong.id, cur + 1, nextSong.album,
                    )
                    val (sp, sf) = createPlayerWithFilter(ctx, handleAudioFocus = false)
                    secondaryPlayer = sp
                    secondaryPlayerFilter = sf
                    val effectiveNextUrl = nextUrl
                    sp.setMediaItem(buildMediaItem(effectiveNextUrl, streamMimeType(nextUrl)))
                    sp.prepare()
                    sp.volume = 0f
                    sp.playWhenReady = true
                }
                performCrossfade(effectiveMs, djMode)
            } catch (e: Exception) {
                Log.e(TAG, "crossfade failed", e)
                cancelCrossfade()
            }
        }
    }

    private suspend fun performCrossfade(effectiveMs: Int, djMode: Boolean) {
        val steps = 50
        val delayPerStep = (effectiveMs / steps).coerceAtLeast(20)
        if (djMode) {
            currentPlayerFilter?.apply {
                filterType = com.music.spotui.audio.BiquadFilter.FilterType.LOW_PASS
                cutoffFrequencyHz = CF_LPF_START_HZ; enabled = true
            }
            secondaryPlayerFilter?.apply {
                filterType = com.music.spotui.audio.BiquadFilter.FilterType.HIGH_PASS
                cutoffFrequencyHz = CF_HPF_START_HZ; enabled = true
            }
        }
        crossfadeJob?.cancel()
        val job = scope.launch {
            try {
                for (step in 0..steps) {
                    if (!isActive) break
                    val progress = step.toFloat() / steps
                    val angle = (progress * PI / 2).toFloat()
                    withContext(Dispatchers.Main) {
                        player?.volume = cos(angle)
                        secondaryPlayer?.volume = sin(angle)
                        if (djMode) {
                            val fp = sigmoid(progress)
                            currentPlayerFilter?.cutoffFrequencyHz = expInterpolate(CF_LPF_START_HZ, CF_LPF_END_HZ, fp)
                            secondaryPlayerFilter?.cutoffFrequencyHz = expInterpolate(CF_HPF_START_HZ, CF_HPF_END_HZ, fp)
                        }
                    }
                    kotlinx.coroutines.delay(delayPerStep.toLong())
                }
                finalizeCrossfade()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            }
        }
        crossfadeJob = job
        job.join()
    }

    private suspend fun finalizeCrossfade() {
        withContext(Dispatchers.Main) {
            val incoming = secondaryPlayer ?: run { isCrossfading = false; return@withContext }
            val old = player
            // Promote the incoming (secondary) player to primary.
            currentPlayerFilter?.enabled = false
            secondaryPlayerFilter?.enabled = false
            player = incoming
            currentPlayerFilter = secondaryPlayerFilter
            secondaryPlayer = null
            secondaryPlayerFilter = null
            incoming.volume = 1f
            // The promoted player now owns audio focus / becoming-noisy handling.
            incoming.setAudioAttributes(buildAudioAttributes(), /* handleAudioFocus = */ true)
            incoming.setHandleAudioBecomingNoisy(true)
            runCatching { old?.stop(); old?.release() }
            isCrossfading = false
            hasPreloadedCurrentTrack = false
            consecutiveFailures = 0
            // Re-bind the media session to the new player.
            onPlayerSwapped?.invoke(incoming)
        }
        // Watch the newly-promoted track for its own end.
        startPositionWatch()
    }

    // ── Sleep timer ──
    // Pauses playback after a delay. A new call replaces any pending timer;
    // passing 0 (or calling cancelSleepTimer) clears it.
    @Volatile private var sleepJob: kotlinx.coroutines.Job? = null
    @Volatile var sleepTimerEndAt: Long = 0L
        private set

    fun setSleepTimer(durationMillis: Long) {
        sleepJob?.cancel()
        if (durationMillis <= 0L) {
            sleepTimerEndAt = 0L
            player?.volume = 1f
            return
        }
        sleepTimerEndAt = System.currentTimeMillis() + durationMillis
        sleepJob = scope.launch {
            if (durationMillis > 30_000L) {
                kotlinx.coroutines.delay(durationMillis - 30_000L)
                // Smart exponential volume fade-out V(t) = e^(-k * t) over 30s
                val steps = 30
                for (i in 1..steps) {
                    val k = 3.0
                    val t = i.toFloat() / steps
                    val vol = kotlin.math.exp(-k * t).toFloat().coerceIn(0.02f, 1f)
                    withContext(Dispatchers.Main) {
                        player?.volume = vol
                    }
                    kotlinx.coroutines.delay(1000L)
                }
            } else {
                kotlinx.coroutines.delay(durationMillis)
            }
            withContext(Dispatchers.Main) {
                pause()
                player?.volume = 1f
            }
            sleepTimerEndAt = 0L
        }
    }

    fun cancelSleepTimer() {
        sleepJob?.cancel()
        sleepJob = null
        sleepTimerEndAt = 0L
        player?.volume = 1f
    }
}
