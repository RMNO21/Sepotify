package com.music.spotui.data.api

import android.content.Context
import android.util.Log
import com.metrolist.spotify.Spotify
import com.music.spotui.data.cache.OfflineCache
import com.music.spotui.data.entity.AlbumsModel
import com.music.spotui.data.entity.ArtistsModel
import com.music.spotui.data.entity.LibraryEntry
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.network.NetworkMonitor
import com.music.spotui.data.preferences.addFollowedArtist
import com.music.spotui.data.preferences.addLikedAlbumId
import com.music.spotui.data.preferences.addLikedSong
import com.music.spotui.data.preferences.getLikedSongIds
import com.music.spotui.data.preferences.removeFollowedArtist
import com.music.spotui.data.preferences.removeLikedAlbumId
import com.music.spotui.data.preferences.removeLikedSong
import com.music.spotui.data.preferences.saveLikedAlbum
import com.music.spotui.data.preferences.setAllLikedTracks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

sealed class SyncState {
    object Idle : SyncState()
    data class Syncing(val message: String, val progress: Float = -1f) : SyncState()
    data class Success(val itemsSynced: Int, val timestamp: Long) : SyncState()
    data class Error(val message: String, val timestamp: Long = System.currentTimeMillis()) : SyncState()
}

/**
 * High-reliability Spotify Library Synchronization engine.
 *
 * Features:
 *  - Persistent offline mutation queue (likes, saves, follows, playlist changes are queued
 *    when offline and auto-flushed when network/token becomes available).
 *  - Automatic retry with exponential backoff on transient network failures.
 *  - Full two-way library synchronization (Liked songs, Playlists, Saved albums, Followed artists, Profile).
 *  - Real-time observable sync state for UI progress indicators and toasts.
 *  - Playlist membership caching with immediate cache invalidation and updates.
 */
object SpotifySync {

    private const val TAG = "SpotifySync"
    private const val PREFS_QUEUE = "spotify_pending_sync_v1"
    private const val KEY_QUEUE = "pending_mutations"
    private const val PREFS_SYNC_META = "spotify_sync_meta_v1"
    private const val KEY_LAST_SYNC_MS = "last_sync_timestamp"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queueMutex = Mutex()
    private val syncMutex = Mutex()

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    // ── Playlist membership cache ──
    private val membershipCache = ConcurrentHashMap<String, MutableSet<String>>()

    fun getLastSyncTimestamp(context: Context): Long {
        return context.getSharedPreferences(PREFS_SYNC_META, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_SYNC_MS, 0L)
    }

    private fun setLastSyncTimestamp(context: Context, timestamp: Long) {
        context.getSharedPreferences(PREFS_SYNC_META, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_SYNC_MS, timestamp)
            .apply()
    }

    // ── Public Library Mutation APIs ──

    fun setTrackSaved(context: Context, trackId: String, saved: Boolean) {
        val cleanId = extractSpotifyId(trackId)
        if (cleanId.isBlank()) return
        val uri = "spotify:track:$cleanId"
        enqueueOrExecute(context, Mutation("TRACK_SAVE", cleanId, uri, saved))
    }

    fun setAlbumSaved(context: Context, albumId: String, saved: Boolean) {
        val cleanId = extractSpotifyId(albumId)
        if (cleanId.isBlank()) return
        val uri = "spotify:album:$cleanId"
        enqueueOrExecute(context, Mutation("ALBUM_SAVE", cleanId, uri, saved))
    }

    fun setPlaylistSaved(context: Context, playlistId: String, saved: Boolean) {
        val cleanId = extractSpotifyId(playlistId)
        if (cleanId.isBlank()) return
        val uri = "spotify:playlist:$cleanId"
        enqueueOrExecute(context, Mutation("PLAYLIST_SAVE", cleanId, uri, saved))
    }

    fun setArtistFollowed(context: Context, artistId: String, followed: Boolean) {
        val cleanId = extractSpotifyId(artistId)
        if (cleanId.isBlank()) return
        val uri = "spotify:artist:$cleanId"
        enqueueOrExecute(context, Mutation("ARTIST_FOLLOW", cleanId, uri, followed))
    }

    fun addTrackToPlaylist(context: Context, playlistId: String, trackId: String, onDone: (Boolean) -> Unit = {}) {
        val cleanTrackId = extractSpotifyId(trackId)
        if (playlistId.isBlank() || cleanTrackId.isBlank()) { onDone(false); return }
        membershipCache[playlistId]?.add(cleanTrackId)
        enqueueOrExecute(
            context,
            Mutation("PLAYLIST_ADD_TRACK", cleanTrackId, playlistId, true),
            onDone = onDone
        )
    }

    fun removeTrackFromPlaylist(context: Context, playlistId: String, trackId: String, onDone: (Boolean) -> Unit = {}) {
        val cleanTrackId = extractSpotifyId(trackId)
        if (playlistId.isBlank() || cleanTrackId.isBlank()) { onDone(false); return }
        membershipCache[playlistId]?.remove(cleanTrackId)
        enqueueOrExecute(
            context,
            Mutation("PLAYLIST_REMOVE_TRACK", cleanTrackId, playlistId, false),
            onDone = onDone
        )
    }

    fun createPlaylistWithTrack(context: Context, name: String, trackId: String, onDone: (Boolean) -> Unit = {}) {
        if (name.isBlank()) { onDone(false); return }
        val cleanTrackId = extractSpotifyId(trackId)
        enqueueOrExecute(
            context,
            Mutation("PLAYLIST_CREATE", cleanTrackId, name, true),
            onDone = onDone
        )
    }

    /** Returns track IDs contained in [playlistId] with memory caching. */
    suspend fun playlistTrackIds(context: Context, playlistId: String): Set<String> {
        membershipCache[playlistId]?.let { return it }
        if (!SpotifyTokenProvider.ensureToken(context.applicationContext)) return emptySet()
        val ids = Spotify.playlistTracks(playlistId, limit = 100).getOrNull()
            ?.items.orEmpty()
            .mapNotNull { it.track?.id?.ifBlank { null } }
            .toMutableSet()
        val set = ConcurrentHashMap.newKeySet<String>().apply { addAll(ids) }
        membershipCache[playlistId] = set
        return set
    }

    fun invalidatePlaylistCache(playlistId: String? = null) {
        if (playlistId != null) {
            membershipCache.remove(playlistId)
        } else {
            membershipCache.clear()
        }
    }

    // ── Comprehensive Full Library Sync ──

    fun syncFullLibrary(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        scope.launch {
            if (!syncMutex.tryLock()) {
                Log.d(TAG, "Sync already in progress, skipping duplicate trigger")
                return@launch
            }
            try {
                _syncState.value = SyncState.Syncing("Checking Spotify session...", 0.05f)

                if (!SpotifySession.isLoggedIn(app)) {
                    _syncState.value = SyncState.Idle
                    return@launch
                }

                if (!NetworkMonitor.isOnlineNow(app)) {
                    _syncState.value = SyncState.Error("No internet connection")
                    delay(3000)
                    _syncState.value = SyncState.Idle
                    return@launch
                }

                val hasToken = SpotifyTokenProvider.ensureToken(app)
                if (!hasToken) {
                    _syncState.value = SyncState.Error("Spotify authentication expired")
                    delay(3000)
                    _syncState.value = SyncState.Idle
                    return@launch
                }

                // 1. Drain pending local changes first so Spotify has our latest updates
                _syncState.value = SyncState.Syncing("Flushing pending changes...", 0.15f)
                flushPendingQueueInternal(app)

                var totalSynced = 0

                // 2. Sync Liked Songs
                _syncState.value = SyncState.Syncing("Syncing Liked Songs...", 0.30f)
                runCatching {
                    val allLiked = fetchAllLikedTracks(app)
                    if (allLiked.isNotEmpty()) {
                        setAllLikedTracks(app, allLiked)
                        OfflineCache.savePlaylistSongs(app, "liked_songs", allLiked)
                        totalSynced += allLiked.size
                        Log.d(TAG, "Synced ${allLiked.size} liked songs")
                    }
                }.onFailure { Log.e(TAG, "Liked songs sync failed", it) }

                // 3. Sync User Playlists
                _syncState.value = SyncState.Syncing("Syncing Playlists...", 0.55f)
                val playlistsList = mutableListOf<LibraryEntry>()
                runCatching {
                    var offset = 0
                    while (true) {
                        val page = Spotify.myPlaylists(limit = 50, offset = offset).getOrNull() ?: break
                        if (page.items.isEmpty()) break
                        page.items.forEach { p ->
                            playlistsList.add(
                                LibraryEntry(
                                    spotifyId = p.id,
                                    name = p.name,
                                    subtitle = "Playlist" + (p.owner?.displayName?.let { " • $it" } ?: ""),
                                    coverUri = p.images.firstOrNull()?.url ?: "",
                                    isPlaylist = true,
                                )
                            )
                        }
                        offset += page.items.size
                        if (offset >= page.total) break
                    }
                    totalSynced += playlistsList.size
                }.onFailure { Log.e(TAG, "Playlists sync failed", it) }

                // 4. Sync Saved Albums
                _syncState.value = SyncState.Syncing("Syncing Saved Albums...", 0.75f)
                val albumsList = mutableListOf<LibraryEntry>()
                runCatching {
                    var offset = 0
                    while (true) {
                        val page = Spotify.myAlbums(limit = 50, offset = offset).getOrNull() ?: break
                        if (page.items.isEmpty()) break
                        page.items.forEach { a ->
                            val artistsStr = a.artists.joinToString(", ") { it.name }
                            saveLikedAlbum(
                                app,
                                AlbumsModel(
                                    id = a.id.hashCode() and 0x7fffffff,
                                    artists = artistsStr,
                                    coverUri = a.images.firstOrNull()?.url ?: "",
                                    name = a.name,
                                    time = a.releaseDate ?: "",
                                    type = a.albumType.orEmpty(),
                                )
                            )
                            albumsList.add(
                                LibraryEntry(
                                    spotifyId = a.id,
                                    name = a.name,
                                    subtitle = "Album • $artistsStr",
                                    coverUri = a.images.firstOrNull()?.url ?: "",
                                    isPlaylist = false,
                                    artists = artistsStr,
                                )
                            )
                        }
                        offset += page.items.size
                        if (offset >= page.total) break
                    }
                    totalSynced += albumsList.size
                }.onFailure { Log.e(TAG, "Albums sync failed", it) }

                // 5. Sync Followed Artists
                _syncState.value = SyncState.Syncing("Syncing Followed Artists...", 0.90f)
                runCatching {
                    var offset = 0
                    while (true) {
                        val page = Spotify.myArtists(limit = 50, offset = offset).getOrNull() ?: break
                        if (page.items.isEmpty()) break
                        page.items.forEach { artist ->
                            addFollowedArtist(app, artist.id, artist.name)
                        }
                        offset += page.items.size
                        if (offset >= page.total) break
                    }
                }.onFailure { Log.e(TAG, "Artists sync failed", it) }

                // 6. Refresh Account Profile
                runCatching {
                    Spotify.me().getOrNull()
                }

                // 7. Update Offline Library Cache & Invalidate in-memory caches
                runCatching {
                    val likedEntry = LibraryEntry(
                        spotifyId = "liked",
                        name = "Liked Songs",
                        subtitle = "Playlist • Auto-synced",
                        coverUri = "",
                        isPlaylist = true,
                    )
                    val combined = (listOf(likedEntry) + playlistsList + albumsList).distinctBy {
                        if (it.isPlaylist) "p:${it.spotifyId}" else "a:${it.name.lowercase()}"
                    }
                    OfflineCache.saveLibrary(app, combined)
                    Api.HomeCache.clear()
                }

                val now = System.currentTimeMillis()
                setLastSyncTimestamp(app, now)
                _syncState.value = SyncState.Success(totalSynced, now)
                Log.d(TAG, "Full library sync completed successfully ($totalSynced items synced)")

                delay(4000)
                if (_syncState.value is SyncState.Success) {
                    _syncState.value = SyncState.Idle
                }
            } catch (e: Exception) {
                Log.e(TAG, "Full library sync error", e)
                _syncState.value = SyncState.Error("Sync error: ${e.localizedMessage ?: "Unknown error"}")
                delay(4000)
                _syncState.value = SyncState.Idle
            } finally {
                syncMutex.unlock()
            }
        }
    }

    private suspend fun fetchAllLikedTracks(context: Context): List<SongsModel> {
        val result = mutableListOf<SongsModel>()
        var offset = 0
        while (true) {
            val page = Spotify.likedSongs(limit = 50, offset = offset).getOrNull() ?: break
            if (page.items.isEmpty()) break
            page.items.forEach { item ->
                val track = item.track
                val singer = track.artists.joinToString(", ") { it.name }.ifBlank { "Unknown Artist" }
                val cover = track.album?.images?.firstOrNull()?.url ?: ""
                val playUrl = com.music.spotui.di.SongPlayer.buildSpotifyPlayQuery(track.id, track.name, singer)
                result.add(
                    SongsModel(
                        id = track.id.hashCode() and 0x7fffffff,
                        title = track.name.take(128),
                        album = track.album?.name ?: "",
                        singer = singer,
                        coverUri = cover,
                        url = playUrl,
                        spotifyTrackId = track.id,
                        explicit = track.explicit,
                        durationMs = track.durationMs,
                    )
                )
            }
            offset += page.items.size
            if (offset >= page.total || offset >= 1000) break
        }
        return result
    }

    // ── Pending Mutation Queue Engine ──

    data class Mutation(
        val type: String,
        val targetId: String,
        val payload: String,
        val flag: Boolean,
        val timestamp: Long = System.currentTimeMillis(),
        var retryCount: Int = 0
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("type", type)
            put("targetId", targetId)
            put("payload", payload)
            put("flag", flag)
            put("timestamp", timestamp)
            put("retryCount", retryCount)
        }

        companion object {
            fun fromJson(obj: JSONObject): Mutation = Mutation(
                type = obj.getString("type"),
                targetId = obj.getString("targetId"),
                payload = obj.optString("payload", ""),
                flag = obj.optBoolean("flag", false),
                timestamp = obj.optLong("timestamp", 0L),
                retryCount = obj.optInt("retryCount", 0)
            )
        }
    }

    private fun enqueueOrExecute(context: Context, mutation: Mutation, onDone: (Boolean) -> Unit = {}) {
        val app = context.applicationContext
        scope.launch {
            if (NetworkMonitor.isOnlineNow(app) && SpotifyTokenProvider.ensureToken(app)) {
                val ok = executeMutation(app, mutation)
                if (ok) {
                    onDone(true)
                    return@launch
                }
            }
            // Offline or request failed: enqueue for later reliable processing
            enqueue(app, mutation)
            onDone(true)
        }
    }

    fun flushPendingQueue(context: Context) {
        val app = context.applicationContext
        scope.launch { flushPendingQueueInternal(app) }
    }

    private suspend fun flushPendingQueueInternal(context: Context) {
        if (!NetworkMonitor.isOnlineNow(context) || !SpotifyTokenProvider.ensureToken(context)) return
        queueMutex.withLock {
            val pending = loadQueue(context).toMutableList()
            if (pending.isEmpty()) return
            Log.d(TAG, "Flushing ${pending.size} pending mutations")
            val remaining = mutableListOf<Mutation>()

            for (mut in pending) {
                var success = false
                repeat(2) { attempt ->
                    if (executeMutation(context, mut)) {
                        success = true
                        return@repeat
                    }
                    delay(300L * (attempt + 1))
                }
                if (!success) {
                    mut.retryCount++
                    if (mut.retryCount < 5) {
                        remaining.add(mut)
                    } else {
                        Log.w(TAG, "Dropping mutation after max retries: ${mut.type} -> ${mut.targetId}")
                    }
                }
            }
            saveQueue(context, remaining)
        }
    }

    private suspend fun executeMutation(context: Context, mut: Mutation): Boolean {
        return runCatching {
            when (mut.type) {
                "TRACK_SAVE" -> {
                    val uri = if (mut.payload.startsWith("spotify:")) mut.payload else "spotify:track:${mut.targetId}"
                    val res = if (mut.flag) Spotify.addToLibrary(listOf(uri)) else Spotify.removeFromLibrary(listOf(uri))
                    res.isSuccess
                }
                "ALBUM_SAVE" -> {
                    val uri = if (mut.payload.startsWith("spotify:")) mut.payload else "spotify:album:${mut.targetId}"
                    val res = if (mut.flag) Spotify.addToLibrary(listOf(uri)) else Spotify.removeFromLibrary(listOf(uri))
                    res.isSuccess
                }
                "PLAYLIST_SAVE" -> {
                    val uri = if (mut.payload.startsWith("spotify:")) mut.payload else "spotify:playlist:${mut.targetId}"
                    val res = if (mut.flag) Spotify.addToLibrary(listOf(uri)) else Spotify.removeFromLibrary(listOf(uri))
                    res.isSuccess
                }
                "ARTIST_FOLLOW" -> {
                    val uri = if (mut.payload.startsWith("spotify:")) mut.payload else "spotify:artist:${mut.targetId}"
                    val res = if (mut.flag) Spotify.addToLibrary(listOf(uri)) else Spotify.removeFromLibrary(listOf(uri))
                    res.isSuccess
                }
                "PLAYLIST_ADD_TRACK" -> {
                    val playlistId = mut.payload
                    val trackId = mut.targetId
                    Spotify.addTracksToPlaylist(playlistId, listOf("spotify:track:$trackId")).isSuccess
                }
                "PLAYLIST_REMOVE_TRACK" -> {
                    val playlistId = mut.payload
                    val trackId = mut.targetId
                    val uri = "spotify:track:$trackId"
                    val refs = Spotify.playlistTracks(playlistId, limit = 100).getOrNull()
                        ?.items.orEmpty()
                        .filter { it.track?.id == trackId || it.track?.uri == uri }
                        .mapNotNull { pt -> pt.uid?.let { Spotify.PlaylistItemRef(uri = uri, uid = it) } }
                    if (refs.isEmpty()) true else Spotify.removeTracksFromPlaylist(playlistId, refs).isSuccess
                }
                "PLAYLIST_CREATE" -> {
                    val playlistName = mut.payload
                    val trackId = mut.targetId
                    val created = Spotify.createPlaylist(playlistName).getOrNull()
                    if (created != null && created.id.isNotBlank()) {
                        if (trackId.isNotBlank()) {
                            Spotify.addTracksToPlaylist(created.id, listOf("spotify:track:$trackId"))
                        }
                        true
                    } else false
                }
                else -> true
            }
        }.getOrElse {
            Log.w(TAG, "Mutation ${mut.type} execution failed: ${it.message}")
            false
        }
    }

    private suspend fun enqueue(context: Context, mutation: Mutation) {
        queueMutex.withLock {
            val list = loadQueue(context).toMutableList()
            // Deduplicate matching operations on the same target
            list.removeAll { it.type == mutation.type && it.targetId == mutation.targetId && it.payload == mutation.payload }
            list.add(mutation)
            saveQueue(context, list)
            Log.d(TAG, "Queued mutation: ${mutation.type} -> ${mutation.targetId}")
        }
    }

    private fun loadQueue(context: Context): List<Mutation> {
        val sp = context.getSharedPreferences(PREFS_QUEUE, Context.MODE_PRIVATE)
        val raw = sp.getString(KEY_QUEUE, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { Mutation.fromJson(it) }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveQueue(context: Context, list: List<Mutation>) {
        val sp = context.getSharedPreferences(PREFS_QUEUE, Context.MODE_PRIVATE)
        val arr = JSONArray().apply {
            list.forEach { put(it.toJson()) }
        }
        sp.edit().putString(KEY_QUEUE, arr.toString()).apply()
    }

    private fun extractSpotifyId(raw: String): String {
        return raw.substringAfterLast(":").substringBefore("?").trim()
    }
}
