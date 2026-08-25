package com.music.spotui.data.storage

import android.content.Context
import android.util.Log
import com.music.spotui.data.db.AppDatabase
import com.music.spotui.data.db.entity.DownloadStatus
import com.music.spotui.data.db.entity.PlaylistEntity
import com.music.spotui.data.db.entity.PlaylistTrackCrossRef
import com.music.spotui.data.db.entity.TrackEntity
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.network.NetworkMonitor
import com.music.spotui.worker.DownloadWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

sealed class PlaylistSyncState {
    object NotDownloaded : PlaylistSyncState()
    data class Syncing(val completed: Int, val total: Int, val currentTrack: String = "") : PlaylistSyncState()
    data class FullySynced(val total: Int) : PlaylistSyncState()
    data class SyncedWithErrors(val failedCount: Int, val completedCount: Int, val total: Int) : PlaylistSyncState()
}

/**
 * Declarative Sync & Relational Reference-Counting Engine for Offline Music.
 *
 * Implements:
 * 1. Track deduplication across multiple downloaded playlists via ref_count.
 * 2. Automatic download triggering when adding to a download-enabled playlist.
 * 3. Garbage collection and safe disk cleanup when ref_count drops to 0.
 * 4. Observable sync states (NotDownloaded, Syncing, FullySynced, SyncedWithErrors).
 */
object DownloadSyncManager {

    const val TAG = "DownloadSyncManager"
    val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scope get() = syncScope
    private val syncMutex = Mutex()

    private val _playlistStates = MutableStateFlow<Map<String, PlaylistSyncState>>(emptyMap())
    val playlistStates = _playlistStates.asStateFlow()

    fun getTrackKey(song: SongsModel): String {
        return when {
            song.spotifyTrackId.isNotBlank() -> song.spotifyTrackId
            song.id != 0 -> "song_${song.id}"
            else -> "${song.title}_${song.singer}".replace(Regex("[^A-Za-z0-9_]"), "_").take(48)
        }
    }

    // ── Scenario 1: Adding a Track to a Downloaded Playlist ──

    suspend fun onTrackAddedToPlaylist(
        context: Context,
        playlistId: String,
        playlistTitle: String,
        song: SongsModel
    ) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val db = AppDatabase.getInstance(app)
        val trackDao = db.trackDao()
        val playlistDao = db.playlistDao()
        val ptDao = db.playlistTrackDao()

        val trackKey = getTrackKey(song)
        ptDao.insertCrossRef(PlaylistTrackCrossRef(playlistId, trackKey, 0))

        val playlist = playlistDao.getPlaylist(playlistId)
        val isDownloadEnabled = playlist?.isDownloadEnabled == true

        if (!isDownloadEnabled) {
            // Track added to non-downloaded playlist, nothing more to do
            return@withContext
        }

        val existingTrack = trackDao.getTrack(trackKey)
        val isFileValid = existingTrack?.localPath?.let { File(it).let { f -> f.exists() && f.length() > 0 } } == true

        if (existingTrack != null && existingTrack.downloadStatus == DownloadStatus.COMPLETED && isFileValid) {
            // Track is already stored locally: increment ref_count, NO network download triggered!
            trackDao.incrementRefCount(trackKey)
            Log.d(TAG, "Track '${song.title}' already cached locally. Incremented ref_count to ${existingTrack.refCount + 1}")
        } else {
            // Not downloaded yet: Set status to QUEUED and trigger background download worker
            val newTrack = (existingTrack ?: TrackEntity.fromSongModel(song, DownloadStatus.QUEUED, refCount = 1)).copy(
                downloadStatus = DownloadStatus.QUEUED,
                refCount = if (existingTrack != null) existingTrack.refCount + 1 else 1
            )
            trackDao.insertTrack(newTrack)
            Log.d(TAG, "Queued track '${song.title}' for playlist '$playlistTitle' download")

            // Trigger background download worker
            DownloadWorker.enqueue(
                context = app,
                song = song,
                playlistId = playlistId,
                playlistName = playlistTitle
            )
        }

        refreshPlaylistState(app, playlistId)
    }

    // ── Scenario 2: Removing a Track from a Downloaded Playlist ──

    suspend fun onTrackRemovedFromPlaylist(
        context: Context,
        playlistId: String,
        trackKey: String
    ) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val db = AppDatabase.getInstance(app)
        val trackDao = db.trackDao()
        val ptDao = db.playlistTrackDao()

        ptDao.deleteCrossRef(playlistId, trackKey)

        val track = trackDao.getTrack(trackKey) ?: return@withContext
        val newRefCount = (track.refCount - 1).coerceAtLeast(0)

        if (newRefCount == 0) {
            // ref_count == 0: Mark file for deletion, purge audio file from disk, reset DB record
            track.localPath?.let { path ->
                val f = File(path)
                if (f.exists()) {
                    f.delete()
                    Log.d(TAG, "Purged local file for '${track.title}' (ref_count reached 0)")
                }
            }
            trackDao.updateDownloadStatus(
                trackId = trackKey,
                status = DownloadStatus.NONE,
                localPath = null,
                fileSize = 0L,
                errorMessage = null,
                time = System.currentTimeMillis()
            )
            trackDao.updateTrack(track.copy(refCount = 0, localPath = null, downloadStatus = DownloadStatus.NONE, fileSize = 0L))
        } else {
            // ref_count > 0: Keep local file intact because another downloaded playlist or Liked Songs references it
            trackDao.decrementRefCount(trackKey)
            Log.d(TAG, "Decremented ref_count for '${track.title}' to $newRefCount (file preserved)")
        }

        refreshPlaylistState(app, playlistId)
    }

    // ── Scenario 3: Deleting or Toggling Off a Downloaded Playlist ──

    suspend fun setPlaylistDownloadEnabled(
        context: Context,
        playlistId: String,
        playlistTitle: String,
        coverUri: String,
        songs: List<SongsModel>,
        enabled: Boolean
    ) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val db = AppDatabase.getInstance(app)
        val playlistDao = db.playlistDao()
        val trackDao = db.trackDao()
        val ptDao = db.playlistTrackDao()

        val playlist = PlaylistEntity(
            playlistId = playlistId,
            title = playlistTitle,
            coverUri = coverUri,
            isDownloadEnabled = enabled,
            lastSyncTimestamp = System.currentTimeMillis()
        )
        playlistDao.insertPlaylist(playlist)

        if (!enabled) {
            // Toggled OFF / Deleted: Decrement ref_count for all tracks, run garbage collection
            val trackIds = ptDao.getTrackIdsForPlaylist(playlistId)
            for (tId in trackIds) {
                val track = trackDao.getTrack(tId) ?: continue
                val newCount = (track.refCount - 1).coerceAtLeast(0)
                if (newCount == 0) {
                    track.localPath?.let { path ->
                        val f = File(path)
                        if (f.exists()) f.delete()
                    }
                    trackDao.updateDownloadStatus(
                        trackId = tId,
                        status = DownloadStatus.NONE,
                        localPath = null,
                        fileSize = 0L,
                        errorMessage = null,
                        time = System.currentTimeMillis()
                    )
                    trackDao.updateTrack(track.copy(refCount = 0, localPath = null, downloadStatus = DownloadStatus.NONE, fileSize = 0L))
                } else {
                    trackDao.decrementRefCount(tId)
                }
            }
            ptDao.deleteCrossRefsForPlaylist(playlistId)
            _playlistStates.value = _playlistStates.value + (playlistId to PlaylistSyncState.NotDownloaded)
            Log.d(TAG, "Toggled off downloads for playlist '$playlistTitle'. Garbage collected unreferenced tracks.")
        } else {
            // Toggled ON: Register cross-refs, increment ref_count for existing or queue downloads
            songs.forEachIndexed { index, song ->
                val trackKey = getTrackKey(song)
                ptDao.insertCrossRef(PlaylistTrackCrossRef(playlistId, trackKey, index))

                val existing = trackDao.getTrack(trackKey)
                val isFileValid = existing?.localPath?.let { File(it).let { f -> f.exists() && f.length() > 0 } } == true

                if (existing != null && existing.downloadStatus == DownloadStatus.COMPLETED && isFileValid) {
                    trackDao.incrementRefCount(trackKey)
                } else {
                    val track = (existing ?: TrackEntity.fromSongModel(song, DownloadStatus.QUEUED, 1)).copy(
                        downloadStatus = DownloadStatus.QUEUED,
                        refCount = (existing?.refCount ?: 0) + 1
                    )
                    trackDao.insertTrack(track)

                    // Enqueue background download
                    DownloadWorker.enqueue(
                        context = app,
                        song = song,
                        playlistId = playlistId,
                        playlistName = playlistTitle
                    )
                }
            }
            refreshPlaylistState(app, playlistId)
        }
    }

    // ── Track Download Callbacks from Engine / Worker ──

    suspend fun onTrackDownloadStarted(context: Context, song: SongsModel, playlistId: String = "") {
        val db = AppDatabase.getInstance(context)
        val trackKey = getTrackKey(song)
        val existing = db.trackDao().getTrack(trackKey) ?: TrackEntity.fromSongModel(song, DownloadStatus.DOWNLOADING, 1)
        db.trackDao().insertTrack(existing.copy(downloadStatus = DownloadStatus.DOWNLOADING))
        if (playlistId.isNotBlank()) refreshPlaylistState(context, playlistId)
    }

    suspend fun onTrackDownloadCompleted(
        context: Context,
        song: SongsModel,
        localFile: File,
        playlistId: String = "",
        playlistTitle: String = ""
    ) {
        val db = AppDatabase.getInstance(context)
        val trackKey = getTrackKey(song)
        val existing = db.trackDao().getTrack(trackKey) ?: TrackEntity.fromSongModel(song, DownloadStatus.COMPLETED, 1)

        db.trackDao().insertTrack(
            existing.copy(
                localPath = localFile.absolutePath,
                downloadStatus = DownloadStatus.COMPLETED,
                fileSize = localFile.length(),
                downloadTimeMs = System.currentTimeMillis(),
                errorMessage = null
            )
        )

        if (playlistId.isNotBlank()) {
            db.playlistTrackDao().insertCrossRef(PlaylistTrackCrossRef(playlistId, trackKey, 0))
            refreshPlaylistState(context, playlistId)
        }
    }

    suspend fun onTrackDownloadFailed(
        context: Context,
        song: SongsModel,
        errorMessage: String,
        playlistId: String = ""
    ) {
        val db = AppDatabase.getInstance(context)
        val trackKey = getTrackKey(song)
        val existing = db.trackDao().getTrack(trackKey) ?: TrackEntity.fromSongModel(song, DownloadStatus.FAILED, 1)
        db.trackDao().insertTrack(
            existing.copy(
                downloadStatus = DownloadStatus.FAILED,
                errorMessage = errorMessage
            )
        )
        if (playlistId.isNotBlank()) refreshPlaylistState(context, playlistId)
    }

    // ── State Computation ──

    suspend fun refreshPlaylistState(context: Context, playlistId: String) = withContext(Dispatchers.IO) {
        val db = AppDatabase.getInstance(context)
        val playlist = db.playlistDao().getPlaylist(playlistId)
        if (playlist == null || !playlist.isDownloadEnabled) {
            _playlistStates.value = _playlistStates.value + (playlistId to PlaylistSyncState.NotDownloaded)
            return@withContext
        }

        val tracks = db.trackDao().getTracksForPlaylistSync(playlistId)
        if (tracks.isEmpty()) {
            _playlistStates.value = _playlistStates.value + (playlistId to PlaylistSyncState.NotDownloaded)
            return@withContext
        }

        val total = tracks.size
        val completed = tracks.count { it.downloadStatus == DownloadStatus.COMPLETED }
        val failed = tracks.count { it.downloadStatus == DownloadStatus.FAILED }
        val inProgress = tracks.count { it.downloadStatus == DownloadStatus.DOWNLOADING || it.downloadStatus == DownloadStatus.QUEUED }

        val newState: PlaylistSyncState = when {
            completed == total -> PlaylistSyncState.FullySynced(total)
            failed > 0 && inProgress == 0 -> PlaylistSyncState.SyncedWithErrors(failed, completed, total)
            inProgress > 0 -> {
                val current = tracks.firstOrNull { it.downloadStatus == DownloadStatus.DOWNLOADING }?.title.orEmpty()
                PlaylistSyncState.Syncing(completed, total, current)
            }
            else -> PlaylistSyncState.NotDownloaded
        }

        _playlistStates.value = _playlistStates.value + (playlistId to newState)
    }

    fun observePlaylistSyncState(playlistId: String): Flow<PlaylistSyncState> {
        return _playlistStates.map { states -> states[playlistId] ?: PlaylistSyncState.NotDownloaded }
    }
}
