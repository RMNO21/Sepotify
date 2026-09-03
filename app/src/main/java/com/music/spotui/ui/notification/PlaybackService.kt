package com.music.spotui.ui.notification

import android.app.PendingIntent
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.music.spotui.MainActivity
import android.os.Bundle
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.music.spotui.R
import com.music.spotui.data.preferences.addLikedSongId
import com.music.spotui.data.preferences.isSongLiked
import com.music.spotui.data.preferences.removeLikedSongId
import com.music.spotui.data.api.Api
import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.di.CurrentSongState
import com.music.spotui.di.SongPlayer
import com.music.spotui.di.SpotifyWebPlayer
import com.music.spotui.ui.repository.AppRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Hosts a [MediaSession] over the app's single ExoPlayer (owned by [SongPlayer]).
 * This is what surfaces the track in the system notification center / lock screen
 * and routes the notification's transport controls (play/pause/seek/next/prev)
 * back into playback. Next/previous are wired to the in-app queue because our
 * player only ever holds one resolved stream at a time (YouTube URLs are resolved
 * lazily per track), so we advance the queue ourselves rather than via a playlist.
 *
 * It is a [MediaLibraryService] (not just a session service) so Android Auto can
 * browse the library — Liked Songs, Downloads, playlists and albums — and start
 * playback from the car.
 */
@AndroidEntryPoint
class PlaybackService : MediaLibraryService() {

    @Inject lateinit var currentSongState: CurrentSongState
    @Inject lateinit var repository: AppRepository

    private var mediaSession: MediaLibrarySession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Android Auto browse cache: mediaId → track, and mediaId → the list it was
    // browsed from (so playing a track queues its whole playlist/album).
    private val trackById = java.util.concurrent.ConcurrentHashMap<String, SongsModel>()
    private val queueByTrackId = java.util.concurrent.ConcurrentHashMap<String, List<SongsModel>>()
    private var webPlayer: WebMediaPlayer? = null
    private var showingWeb = false

    private var wakeLock: PowerManager.WakeLock? = null
    private var isNoisyReceiverRegistered = false
    private var audioFocusManager: com.music.spotui.audio.AudioFocusManager? = null

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY == intent.action) {
                SongPlayer.pause()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        SongPlayer.ensureCreated(this)
        // Let the player advance the in-app queue itself during a crossfade.
        SongPlayer.bindState(currentSongState)
        val base = SongPlayer.exoPlayer ?: return

        // Register noisy receiver for audio output disconnects (headphones / Bluetooth)
        audioFocusManager = com.music.spotui.audio.AudioFocusManager(
            context = this,
            onPlay = { SongPlayer.play() },
            onPause = { SongPlayer.pause() },
            onDuck = { ducked ->
                if (ducked) SongPlayer.rampVolume(0.2f, 200L)
                else SongPlayer.rampVolume(1.0f, 200L)
            },
            getPlayer = { SongPlayer.exoPlayer }
        )

        try {
            val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(noisyReceiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(noisyReceiver, filter)
            }
            isNoisyReceiverRegistered = true
        } catch (e: Exception) {
            // Ignored if receiver registration fails
        }

        // Observe playing state to manage PARTIAL_WAKE_LOCK and update notification layout reactively
        serviceScope.launch {
            currentSongState.playingStateFlow.collect { playing ->
                updateWakeLock(playing)
                updateCustomNotificationButtons()
                // ExoPlayer manages its own audio focus natively; only manage audio focus
                // manually for Spotify web playback.
                if (SongPlayer.webPlaybackActive()) {
                    if (playing) {
                        audioFocusManager?.requestFocus()
                    } else {
                        audioFocusManager?.abandonFocus()
                    }
                }
            }
        }

        serviceScope.launch {
            currentSongState.repeatFlow.collect { updateCustomNotificationButtons() }
        }

        serviceScope.launch {
            currentSongState.shuffleFlow.collect { updateCustomNotificationButtons() }
        }

        serviceScope.launch {
            currentSongState.likeStateFlow.collect { updateCustomNotificationButtons() }
        }

        // Tapping the notification opens the app (back on the Now Playing screen).
        val activityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val sessionActivity = PendingIntent.getActivity(
            this, 0, activityIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        webPlayer = WebMediaPlayer(mainLooper, currentSongState) { forward -> advance(forward) }

        mediaSession = MediaLibrarySession.Builder(this, wrap(base), LibraryCallback())
            .setSessionActivity(sessionActivity)
            .build()

        updateCustomNotificationButtons()

        // When a crossfade promotes a new ExoPlayer instance, re-bind the session to it
        // (runs on the main thread; setPlayer is the supported way to swap a session's player).
        SongPlayer.onPlayerSwapped = { newPlayer ->
            if (!showingWeb) mediaSession?.player = wrap(newPlayer)
        }

        // As the hidden web player streams, keep the notification in sync and swap
        // the session between the web player (during web playback) and the ExoPlayer.
        SpotifyWebPlayer.onStateChanged = {
            syncSessionPlayer()
            if (showingWeb) {
                webPlayer?.refresh()
                // Reflect the web player's real play/pause state into the in-app UI
                // so the on-screen icon matches after the notification's pause.
                currentSongState.updatePlayingState(SpotifyWebPlayer.isPlaying)
            }
        }
    }

    private fun updateWakeLock(isPlaying: Boolean) {
        if (isPlaying) {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SpotUI:PlaybackWakeLock")
            }
            if (wakeLock?.isHeld == false) {
                try {
                    wakeLock?.acquire(3 * 60 * 60 * 1000L) // 3 hour safety limit
                } catch (e: Exception) {
                    // Ignore wake lock acquire error if permission missing
                }
            }
        } else {
            if (wakeLock?.isHeld == true) {
                try {
                    wakeLock?.release()
                } catch (e: Exception) {
                    // Ignore
                }
            }
        }
    }

    /** Point the media session at whichever engine is currently producing audio. */
    private fun syncSessionPlayer() {
        val wantWeb = SongPlayer.webPlaybackActive()
        if (wantWeb == showingWeb) return
        showingWeb = wantWeb
        val session = mediaSession ?: return
        session.player = if (wantWeb) {
            webPlayer ?: return
        } else {
            wrap(SongPlayer.exoPlayer ?: return)
        }
    }

    /** Wrap an ExoPlayer so the media session routes next/previous to our in-app queue
     *  (the player only ever holds one resolved stream at a time). */
    private fun wrap(base: Player): ForwardingPlayer = object : ForwardingPlayer(base) {
        override fun getAvailableCommands(): Player.Commands =
            super.getAvailableCommands().buildUpon()
                .add(COMMAND_SEEK_TO_NEXT)
                .add(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .add(COMMAND_SEEK_TO_PREVIOUS)
                .add(COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .build()

        override fun isCommandAvailable(command: Int): Boolean = when (command) {
            COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            COMMAND_SEEK_TO_PREVIOUS, COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> true
            else -> super.isCommandAvailable(command)
        }

        override fun hasNextMediaItem() = true
        override fun hasPreviousMediaItem() = true
        override fun seekToNext() = advance(forward = true)
        override fun seekToNextMediaItem() = advance(forward = true)
        override fun seekToPrevious() = advance(forward = false)
        override fun seekToPreviousMediaItem() = advance(forward = false)
    }

    /** Advance the in-app queue one step in the given direction and start it. */
    private fun advance(forward: Boolean) {
        if (forward) {
            SongPlayer.skipToNextTrack(applicationContext, forceNextIfRepeat = true)
        } else {
            SongPlayer.skipToPreviousTrack(applicationContext)
        }
    }

    private val likeCommand = SessionCommand("CUSTOM_ACTION_LIKE", Bundle.EMPTY)
    private val shuffleCommand = SessionCommand("CUSTOM_ACTION_SHUFFLE", Bundle.EMPTY)
    private val repeatCommand = SessionCommand("CUSTOM_ACTION_REPEAT", Bundle.EMPTY)

    fun updateCustomNotificationButtons() {
        val session = mediaSession ?: return
        val songId = currentSongState.songIdFlow.value
        val isLiked = if (songId != 0) isSongLiked(applicationContext, songId.toString()) else currentSongState.likeStateFlow.value
        val isShuffle = currentSongState.shuffleFlow.value
        val isRepeat = currentSongState.repeatFlow.value

        val likeBtn = CommandButton.Builder()
            .setDisplayName("Like")
            .setIconResId(if (isLiked) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline)
            .setSessionCommand(likeCommand)
            .build()

        val shuffleBtn = CommandButton.Builder()
            .setDisplayName("Shuffle")
            .setIconResId(R.drawable.ic_player_shuffle)
            .setSessionCommand(shuffleCommand)
            .build()

        val repeatBtn = CommandButton.Builder()
            .setDisplayName("Repeat")
            .setIconResId(if (isRepeat) R.drawable.ic_repeat_one else R.drawable.ic_repeat)
            .setSessionCommand(repeatCommand)
            .build()

        session.setCustomLayout(listOf(likeBtn, shuffleBtn, repeatBtn))
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = mediaSession

    // ── Android Auto browse tree ──────────────────────────────────────────

    companion object {
        const val ROOT = "root"
        const val NODE_LIKED = "liked"
        const val NODE_DOWNLOADS = "downloads"
        const val NODE_PLAYLISTS = "playlists"
        const val NODE_ALBUMS = "albums"

        @Volatile
        var instance: PlaybackService? = null

        fun updateNotification(context: android.content.Context) {
            instance?.updateCustomNotificationButtons()
        }
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val connectionResult = super.onConnect(session, controller)
            val availableSessionCommands = connectionResult.availableSessionCommands.buildUpon()
                .add(likeCommand)
                .add(shuffleCommand)
                .add(repeatCommand)
                .build()
            return MediaSession.ConnectionResult.accept(
                availableSessionCommands,
                connectionResult.availablePlayerCommands
            )
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                "CUSTOM_ACTION_LIKE" -> {
                    val songId = currentSongState.songId.value
                    if (songId != 0) {
                        val currentlyLiked = isSongLiked(applicationContext, songId.toString())
                        if (currentlyLiked) {
                            removeLikedSongId(applicationContext, songId.toString())
                            currentSongState.updateLikeState(false)
                        } else {
                            addLikedSongId(applicationContext, songId.toString())
                            currentSongState.updateLikeState(true)
                        }
                    }
                    updateCustomNotificationButtons()
                }
                "CUSTOM_ACTION_SHUFFLE" -> {
                    currentSongState.updateShuffleState(!currentSongState.shuffle.value)
                    updateCustomNotificationButtons()
                }
                "CUSTOM_ACTION_REPEAT" -> {
                    currentSongState.updateRepeatState(!currentSongState.repeat.value)
                    updateCustomNotificationButtons()
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(folder(ROOT, "spotui"), params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
            LibraryResult.ofItemList(ImmutableList.copyOf(childrenOf(parentId)), params)
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(
            trackById[mediaId]?.let { LibraryResult.ofItem(playable(it), null) }
                ?: LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE),
        )

        // A browsed track was tapped in the car: queue the list it came from and
        // play through our own engine (streams are resolved lazily per track, so
        // we never hand the session a playlist of URIs).
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val requested = mediaItems.getOrNull(startIndex) ?: mediaItems.firstOrNull()
            val song = requested?.let { trackById[it.mediaId] }
            if (song != null) {
                val queue = queueByTrackId[requested.mediaId] ?: listOf(song)
                currentSongState.updateQueue(queue)
                val idx = queue.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
                currentSongState.updateSongState(
                    song.coverUri, song.title, song.singer, true,
                    song.id, idx, song.album,
                )
                SongPlayer.playSong(song.url, applicationContext)
            }
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(emptyList(), C.INDEX_UNSET, C.TIME_UNSET),
            )
        }
    }

    private suspend fun childrenOf(parentId: String): List<MediaItem> = when {
        parentId == ROOT -> listOf(
            folder(NODE_LIKED, "Liked Songs"),
            folder(NODE_DOWNLOADS, "Downloads"),
            folder(NODE_PLAYLISTS, "Playlists"),
            folder(NODE_ALBUMS, "Albums"),
        )
        parentId == NODE_LIKED ->
            registerTracks(NODE_LIKED, lastSuccess(repository.provideLikedSongs()).orEmpty())
        parentId == NODE_DOWNLOADS ->
            registerTracks(
                NODE_DOWNLOADS,
                com.music.spotui.data.preferences.getDownloadedSongs(applicationContext),
            )
        parentId == NODE_PLAYLISTS ->
            libraryEntries().filter {
                it.isPlaylist && it.spotifyId != Api.LIKED_SONGS_ID && it.spotifyId != Api.DOWNLOADS_ID
            }.map { folder("playlist/${it.spotifyId}", it.name, it.coverUri) }
        parentId == NODE_ALBUMS ->
            libraryEntries().filter { !it.isPlaylist }.map {
                folder(
                    "album/${android.net.Uri.encode(it.name)}/${android.net.Uri.encode(it.artists)}",
                    it.name,
                    it.coverUri,
                )
            }
        parentId.startsWith("playlist/") -> {
            val songs = lastSuccess(repository.providePlaylistSongs(parentId.removePrefix("playlist/")))
            registerTracks(parentId, songs.orEmpty())
        }
        parentId.startsWith("album/") -> {
            val parts = parentId.removePrefix("album/").split('/')
            val name = android.net.Uri.decode(parts.getOrElse(0) { "" })
            val artist = android.net.Uri.decode(parts.getOrElse(1) { "" })
            registerTracks(parentId, lastSuccess(repository.provideAlbumSongs(name, artist)).orEmpty())
        }
        else -> emptyList()
    }

    private suspend fun libraryEntries() =
        lastSuccess(repository.provideLibrary()).orEmpty()

    /** Runs a paged/cached response flow to completion and keeps the final data. */
    private suspend fun <T> lastSuccess(flow: Flow<Response<T>>): T? =
        runCatching { flow.toList() }.getOrNull()
            ?.filterIsInstance<Response.Success<T>>()
            ?.lastOrNull()?.data

    private fun registerTracks(parentId: String, songs: List<SongsModel>): List<MediaItem> {
        songs.forEach { song ->
            trackById["song/${song.id}"] = song
            queueByTrackId["song/${song.id}"] = songs
        }
        return songs.map { playable(it) }
    }

    private fun folder(id: String, title: String, coverUri: String = ""): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .apply { if (coverUri.isNotBlank()) setArtworkUri(android.net.Uri.parse(coverUri)) }
                    .build(),
            )
            .build()

    private fun playable(song: SongsModel): MediaItem =
        MediaItem.Builder()
            .setMediaId("song/${song.id}")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.singer)
                    .setAlbumTitle(song.album)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .apply { if (song.coverUri.isNotBlank()) setArtworkUri(android.net.Uri.parse(song.coverUri)) }
                    .build(),
            )
            .build()

    private fun <T> future(block: suspend () -> T): ListenableFuture<T> {
        val f = SettableFuture.create<T>()
        serviceScope.launch {
            try {
                f.set(block())
            } catch (e: Exception) {
                f.setException(e)
            }
        }
        return f
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Stop playback + tear the service down when the app is swiped away.
        SongPlayer.pause()
        stopSelf()
    }

    override fun onDestroy() {
        if (instance == this) instance = null
        if (isNoisyReceiverRegistered) {
            try {
                unregisterReceiver(noisyReceiver)
            } catch (e: Exception) {
                // Ignore
            }
            isNoisyReceiverRegistered = false
        }
        if (wakeLock?.isHeld == true) {
            try {
                wakeLock?.release()
            } catch (e: Exception) {
                // Ignore
            }
        }
        audioFocusManager?.release()
        audioFocusManager = null
        wakeLock = null
        serviceScope.cancel()
        SongPlayer.onPlayerSwapped = null
        SpotifyWebPlayer.onStateChanged = null
        webPlayer?.release()
        webPlayer = null
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }
}
