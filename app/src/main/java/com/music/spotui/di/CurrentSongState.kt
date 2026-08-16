package com.music.spotui.di

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.music.spotui.data.entity.SongsModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CurrentSongState @Inject constructor() {

    init {
        instance = this
    }

    companion object {
        @Volatile
        var instance: CurrentSongState? = null
    }

    private val _titleFlow = MutableStateFlow("")
    val titleFlow: StateFlow<String> = _titleFlow.asStateFlow()
    private val _title: MutableState<String> = mutableStateOf("")
    val title: State<String> get() = _title

    private val _albumFlow = MutableStateFlow("")
    val albumFlow: StateFlow<String> = _albumFlow.asStateFlow()
    private val _album: MutableState<String> = mutableStateOf("")
    val album : State<String> get() = _album

    private val _singerFlow = MutableStateFlow("")
    val singerFlow: StateFlow<String> = _singerFlow.asStateFlow()
    private val _singer: MutableState<String> = mutableStateOf("")
    val singer: State<String> get() = _singer

    private val _coverUriFlow = MutableStateFlow("")
    val coverUriFlow: StateFlow<String> = _coverUriFlow.asStateFlow()
    private val _coverUri: MutableState<String> = mutableStateOf("")
    val coverUri: State<String> get() = _coverUri

    private val _playingStateFlow = MutableStateFlow(false)
    val playingStateFlow: StateFlow<Boolean> = _playingStateFlow.asStateFlow()
    private val _playingState: MutableState<Boolean> = mutableStateOf(false)
    val playingState: State<Boolean> get() = _playingState

    private val _songIndexFlow = MutableStateFlow(0)
    val songIndexFlow: StateFlow<Int> = _songIndexFlow.asStateFlow()
    private val _songIndex: MutableState<Int> = mutableStateOf(0)
    val songIndex : State<Int> get() = _songIndex

    private val _songIdFlow = MutableStateFlow(0)
    val songIdFlow: StateFlow<Int> = _songIdFlow.asStateFlow()
    private val _songId: MutableState<Int> = mutableStateOf(0)
    val songId : State<Int> get() = _songId

    // The actual list the user is playing (album tracks, search results, liked
    // songs…). Next/previous operate on THIS, not on a re-derived global feed.
    private val _queueFlow = MutableStateFlow<List<SongsModel>>(emptyList())
    val queueFlow: StateFlow<List<SongsModel>> = _queueFlow.asStateFlow()
    private val _queue: MutableState<List<SongsModel>> = mutableStateOf(emptyList())
    val queue: State<List<SongsModel>> get() = _queue

    fun updateQueue(songs: List<SongsModel>) {
        _queueFlow.value = songs
        _queue.value = songs
        // Seed the lossless resolver: map each track's play query → its Spotify id so
        // SongPlayer can resolve a FLAC stream from a play site that only has the query.
        SongPlayer.registerLossless(songs.map { it.url to it.spotifyTrackId })
        SongPlayer.registerAlternativeKeys(songs.map {
            it.url to com.music.spotui.data.preferences.alternativeStreamKey(it)
        })
        // Seed explicit flags so the YouTube fallback picks the matching edit.
        SongPlayer.registerExplicit(songs.map { it.url to it.explicit })
        // Seed durations so the YouTube match can reject same-title wrong-artist
        // songs (they almost always have a different length).
        SongPlayer.registerDuration(songs.mapNotNull { s -> if (s.durationMs > 0) s.url to s.durationMs else null })
        // Seed exact title/artist/album metadata so the YouTube fallback can score
        // candidates against the actual Spotify track instead of only the search
        // query string.
        SongPlayer.registerMetadata(songs.map {
            it.url to SongPlayer.TrackMatchMetadata(
                title = it.title,
                artist = it.singer,
                album = it.album,
            )
        })
        // Seed the lyrics resolver with track ids so it can use Spotify's own
        // color-lyrics endpoint (exact synced lyrics) instead of LRCLIB matching.
        com.music.spotui.data.api.LyricsApi.registerTracks(songs)
    }

    private val _shuffleFlow = MutableStateFlow(false)
    val shuffleFlow: StateFlow<Boolean> = _shuffleFlow.asStateFlow()
    val shuffle = mutableStateOf(false)

    private val _repeatFlow = MutableStateFlow(false)
    val repeatFlow: StateFlow<Boolean> = _repeatFlow.asStateFlow()
    val repeat = mutableStateOf(false)

    private val _likeStateFlow = MutableStateFlow(false)
    val likeStateFlow: StateFlow<Boolean> = _likeStateFlow.asStateFlow()
    val likeState = mutableStateOf(false)

    // Original queue order, kept while shuffle is on so turning it off restores
    // the list instead of leaving it permanently scrambled.
    private var unshuffledQueue: List<SongsModel>? = null

    private fun <T> List<T>.fisherYatesShuffled(): List<T> {
        val list = this.toMutableList()
        val random = java.util.Random()
        for (i in list.size - 1 downTo 1) {
            val j = random.nextInt(i + 1)
            val temp = list[i]
            list[i] = list[j]
            list[j] = temp
        }
        return list
    }

    /**
     * Toggling shuffle reorders the queue using Spotify-style non-destructive Fisher-Yates shuffle:
     * the current track stays at index 0 and the rest are Fisher-Yates randomized.
     * Toggling off restores the exact original unshuffled list order in memory.
     */
    fun updateShuffleState(newShuffleState: Boolean) {
        if (newShuffleState == shuffle.value) return
        _shuffleFlow.value = newShuffleState
        shuffle.value = newShuffleState
        val q = _queue.value
        if (newShuffleState) {
            if (unshuffledQueue == null) {
                unshuffledQueue = q
            }
            val curIdx = q.indexOfFirst { it.id == _songId.value }
            if (curIdx >= 0) {
                val currentTrack = q[curIdx]
                val remainingTracks = q.filterIndexed { i, _ -> i != curIdx }.fisherYatesShuffled()
                val newQ = listOf(currentTrack) + remainingTracks
                _queueFlow.value = newQ
                _queue.value = newQ
                _songIndexFlow.value = 0
                _songIndex.value = 0
            } else {
                val newQ = q.fisherYatesShuffled()
                _queueFlow.value = newQ
                _queue.value = newQ
            }
        } else {
            val original = unshuffledQueue
            unshuffledQueue = null
            if (original != null && original.any { it.id == _songId.value }) {
                val appended = q.filter { s -> original.none { it.id == s.id } }
                val restored = original + appended
                _queueFlow.value = restored
                _queue.value = restored
                val idx = restored.indexOfFirst { it.id == _songId.value }
                if (idx >= 0) {
                    _songIndexFlow.value = idx
                    _songIndex.value = idx
                }
            }
        }
        com.music.spotui.ui.widget.SpotUIWidgetProvider.updateAllWidgets(com.music.spotui.MyApplication.instance)
        com.music.spotui.ui.notification.PlaybackService.updateNotification(com.music.spotui.MyApplication.instance)
    }

    fun startShuffled(songs: List<SongsModel>): SongsModel? {
        if (songs.isEmpty()) return null
        unshuffledQueue = songs
        val shuffledSongs = songs.fisherYatesShuffled()
        updateQueue(shuffledSongs)
        _shuffleFlow.value = true
        shuffle.value = true
        com.music.spotui.ui.widget.SpotUIWidgetProvider.updateAllWidgets(com.music.spotui.MyApplication.instance)
        com.music.spotui.ui.notification.PlaybackService.updateNotification(com.music.spotui.MyApplication.instance)
        return _queue.value.firstOrNull()
    }

    fun updateRepeatState(newRepeatState : Boolean){
        _repeatFlow.value = newRepeatState
        repeat.value = newRepeatState
        com.music.spotui.ui.widget.SpotUIWidgetProvider.updateAllWidgets(com.music.spotui.MyApplication.instance)
        com.music.spotui.ui.notification.PlaybackService.updateNotification(com.music.spotui.MyApplication.instance)
    }

    /** Sync the play/pause state without touching the rest of the now-playing
     *  metadata — used to reflect the web player's real state (e.g. after the
     *  system notification's pause button) back into the in-app UI. */
    fun updatePlayingState(playing: Boolean) {
        _playingStateFlow.value = playing
        _playingState.value = playing
        com.music.spotui.ui.widget.SpotUIWidgetProvider.updateAllWidgets(com.music.spotui.MyApplication.instance)
        com.music.spotui.ui.notification.PlaybackService.updateNotification(com.music.spotui.MyApplication.instance)
    }

    fun updateLikeState(newLikeState : Boolean){
        _likeStateFlow.value = newLikeState
        likeState.value = newLikeState
        com.music.spotui.ui.widget.SpotUIWidgetProvider.updateAllWidgets(com.music.spotui.MyApplication.instance)
        com.music.spotui.ui.notification.PlaybackService.updateNotification(com.music.spotui.MyApplication.instance)
    }

    fun updateSongState(coverUri: String, title: String, singer: String, playingState: Boolean, songId : Int, songIndex : Int, album : String) {
        _coverUriFlow.value = coverUri
        _coverUri.value = coverUri
        _titleFlow.value = title
        _title.value = title
        _albumFlow.value = album
        _album.value = album
        _singerFlow.value = singer
        _singer.value = singer
        _playingStateFlow.value = playingState
        _playingState.value = playingState
        _songIndexFlow.value = songIndex
        _songIndex.value = songIndex
        _songIdFlow.value = songId
        _songId.value = songId
        // Feed the system media notification (MediaSession) with the current track.
        SongPlayer.setNowPlayingMeta(title, singer, coverUri)

        // Preload next 3 tracks in queue for gapless playback transitions
        val q = _queue.value
        if (q.isNotEmpty() && songIndex >= 0) {
            val next1 = q.getOrNull(songIndex + 1)
            val next2 = q.getOrNull(songIndex + 2)
            val next3 = q.getOrNull(songIndex + 3)
            next1?.let { SongPlayer.prefetch(it.url, com.music.spotui.MyApplication.instance) }
            next2?.let { SongPlayer.prefetch(it.url, com.music.spotui.MyApplication.instance) }
            next3?.let { SongPlayer.prefetch(it.url, com.music.spotui.MyApplication.instance) }
        }

        // Notify Android AppWidget and Notification
        com.music.spotui.ui.widget.SpotUIWidgetProvider.updateAllWidgets(com.music.spotui.MyApplication.instance)
        com.music.spotui.ui.notification.PlaybackService.updateNotification(com.music.spotui.MyApplication.instance)

        // Persist the current track and playback context so a fresh launch can restore the full session.
        if (playingState && title.isNotBlank()) {
            val track = _queue.value.firstOrNull { it.id == songId } ?: SongsModel(
                id = songId,
                title = title,
                album = album,
                singer = singer,
                coverUri = coverUri,
                url = "",
            )
            com.music.spotui.data.preferences.saveLastPlaybackSession(
                com.music.spotui.MyApplication.instance,
                song = track,
                contextName = album,
                songIndex = songIndex,
                queue = _queue.value,
            )
        }
        // Warm the lyrics cache in the background so they're already loaded by the
        // time the user opens the player / scrolls to the lyrics card.
        if (playingState && title.isNotBlank()) {
            com.music.spotui.data.api.LyricsApi.prefetch(title, singer, album)
            // Log the play into the local listening history (History & stats screen).
            com.music.spotui.data.preferences.addListeningHistory(
                com.music.spotui.MyApplication.instance,
                com.music.spotui.data.preferences.HistoryEntry(
                    ts = System.currentTimeMillis(),
                    songId = songId,
                    title = title,
                    singer = singer,
                    album = album,
                    image = coverUri,
                ),
            )
        }
    }
}
