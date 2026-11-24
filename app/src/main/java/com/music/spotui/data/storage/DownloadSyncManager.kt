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
import kotlinx.coroutines.flow.update
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
        syncMutex.withLock {
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
                return@withLock
            }

            val existingTrack = trackDao.getTrack(trackKey)
            val isFileValid = existingTrack?.localPath?.let { File(it).let { f -> f.exists() && f.length() > 0 } } == true

            if (existingTrack != null && existingTrack.downloadStatus == DownloadStatus.COMPLETED && isFileValid) {
                // Track is already stored locally: increment ref_count, NO network download triggered!
                trackDao.incrementRefCount(trackKey)
                Log.d(TAG, "Track '${song.title}' already cached locally. Incremented ref_count to ${existingTrack.refCount + 1}")
            } else {
                // Not downloaded yet: Set status to QUEUED and trigger background download worker
                if (existingTrack != null) {
                    trackDao.updateDownloadStatus(
                        trackId = trackKey,
                        status = DownloadStatus.QUEUED,
                        localPath = existingTrack.localPath,
                        fileSize = existingTrack.fileSize,
                        errorMessage = null,
                        time = System.currentTimeMillis()
                    )
                    trackDao.incrementRefCount(trackKey)
                } else {
                    trackDao.insertTrack(TrackEntity.fromSongModel(song, DownloadStatus.QUEUED, refCount = 1))
                }
                Log.d(TAG, "Queued track '${song.title}' for playlist '$playlistTitle' download")

                // Trigger background download worker
                DownloadWorker.enqueue(
                    context = app,
                    song = song,
                    playlistId = playlistId,
                    playlistName = playlistTitle
                )
            }

            refreshPlaylistStateLocked(app, playlistId)
        }
    }

    // ── Scenario 2: Removing a Track from a Downloaded Playlist ──

    suspend fun onTrackRemovedFromPlaylist(
        context: Context,
        playlistId: String,
        trackKey: String
    ) = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            val app = context.applicationContext
            val db = AppDatabase.getInstance(app)
            val trackDao = db.trackDao()
            val ptDao = db.playlistTrackDao()

            ptDao.deleteCrossRef(playlistId, trackKey)

            val track = trackDao.getTrack(trackKey) ?: return@withLock
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
                // Need to decrement ref_count to 0, which decrementRefCount handles by `CASE WHEN ref_count > 0 THEN ref_count - 1 ELSE 0 END`
                trackDao.decrementRefCount(trackKey)
            } else {
                // ref_count > 0: Keep local file intact because another downloaded playlist or Liked Songs references it
                trackDao.decrementRefCount(trackKey)
                Log.d(TAG, "Decremented ref_count for '${track.title}' to $newRefCount (file preserved)")
            }

            refreshPlaylistStateLocked(app, playlistId)
        }
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
        syncMutex.withLock {
            val app = context.applicationContext
            val db = AppDatabase.getInstance(app)
            val playlistDao = db.playlistDao()
            val trackDao = db.trackDao()
            val ptDao = db.playlistTrackDao()

            val existingPlaylist = playlistDao.getPlaylist(playlistId)
            val playlist = PlaylistEntity(
                playlistId = playlistId,
                title = playlistTitle,
                coverUri = coverUri,
                isDownloadEnabled = enabled,
                lastSyncTimestamp = System.currentTimeMillis(),
                snapshotId = existingPlaylist?.snapshotId ?: ""
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
                        trackDao.decrementRefCount(tId)
                    } else {
                        trackDao.decrementRefCount(tId)
                    }
                }
                ptDao.deleteCrossRefsForPlaylist(playlistId)
                _playlistStates.update { it + (playlistId to PlaylistSyncState.NotDownloaded) }
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
                        if (existing != null) {
                            trackDao.updateDownloadStatus(
                                trackId = trackKey,
                                status = DownloadStatus.QUEUED,
                                localPath = existing.localPath,
                                fileSize = existing.fileSize,
                                errorMessage = null,
                                time = System.currentTimeMillis()
                            )
                            trackDao.incrementRefCount(trackKey)
                        } else {
                            trackDao.insertTrack(TrackEntity.fromSongModel(song, DownloadStatus.QUEUED, 1))
                        }

                        // Enqueue background download
                        DownloadWorker.enqueue(
                            context = app,
                            song = song,
                            playlistId = playlistId,
                            playlistName = playlistTitle
                        )
                    }
                }
                refreshPlaylistStateLocked(app, playlistId)
            }
        }
    }

    // ── Track Download Callbacks from Engine / Worker ──

    suspend fun onTrackDownloadStarted(context: Context, song: SongsModel, playlistId: String = "") {
        syncMutex.withLock {
            val db = AppDatabase.getInstance(context)
            val trackKey = getTrackKey(song)
            val existing = db.trackDao().getTrack(trackKey)
            if (existing != null) {
                db.trackDao().updateDownloadStatus(
                    trackId = trackKey,
                    status = DownloadStatus.DOWNLOADING,
                    localPath = existing.localPath,
                    fileSize = existing.fileSize,
                    errorMessage = null,
                    time = System.currentTimeMillis()
                )
            } else {
                db.trackDao().insertTrack(TrackEntity.fromSongModel(song, DownloadStatus.DOWNLOADING, 1))
            }
            if (playlistId.isNotBlank()) refreshPlaylistStateLocked(context, playlistId)
        }
    }

    suspend fun onTrackDownloadCompleted(
        context: Context,
        song: SongsModel,
        localFile: File,
        playlistId: String = "",
        playlistTitle: String = ""
    ) {
        syncMutex.withLock {
            val db = AppDatabase.getInstance(context)
            val trackKey = getTrackKey(song)
            val existing = db.trackDao().getTrack(trackKey)
            if (existing != null) {
                db.trackDao().updateDownloadStatus(
                    trackId = trackKey,
                    status = DownloadStatus.COMPLETED,
                    localPath = localFile.absolutePath,
                    fileSize = localFile.length(),
                    errorMessage = null,
                    time = System.currentTimeMillis()
                )
            } else {
                val newTrack = TrackEntity.fromSongModel(song, DownloadStatus.COMPLETED, 1).copy(
                    localPath = localFile.absolutePath,
                    fileSize = localFile.length(),
                    downloadTimeMs = System.currentTimeMillis()
                )
                db.trackDao().insertTrack(newTrack)
            }

            if (playlistId.isNotBlank()) {
                val existingCrossRef = db.playlistTrackDao().getCrossRef(playlistId, trackKey)
                if (existingCrossRef == null) {
                    db.playlistTrackDao().insertCrossRef(PlaylistTrackCrossRef(playlistId, trackKey, 0))
                }
                refreshPlaylistStateLocked(context, playlistId)
            }
        }
    }

    suspend fun onTrackDownloadFailed(
        context: Context,
        song: SongsModel,
        errorMessage: String,
        playlistId: String = ""
    ) {
        syncMutex.withLock {
            val db = AppDatabase.getInstance(context)
            val trackKey = getTrackKey(song)
            val existing = db.trackDao().getTrack(trackKey)
            if (existing != null) {
                db.trackDao().updateDownloadStatus(
                    trackId = trackKey,
                    status = DownloadStatus.FAILED,
                    localPath = existing.localPath,
                    fileSize = existing.fileSize,
                    errorMessage = errorMessage,
                    time = System.currentTimeMillis()
                )
            } else {
                val newTrack = TrackEntity.fromSongModel(song, DownloadStatus.FAILED, 1).copy(
                    errorMessage = errorMessage
                )
                db.trackDao().insertTrack(newTrack)
            }
            if (playlistId.isNotBlank()) refreshPlaylistStateLocked(context, playlistId)
        }
    }

    // ── State Computation ──

    suspend fun refreshPlaylistState(context: Context, playlistId: String) = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            refreshPlaylistStateLocked(context, playlistId)
        }
    }

    private suspend fun refreshPlaylistStateLocked(context: Context, playlistId: String) {
        val db = AppDatabase.getInstance(context)
        val playlist = db.playlistDao().getPlaylist(playlistId)
        if (playlist == null || !playlist.isDownloadEnabled) {
            _playlistStates.update { it + (playlistId to PlaylistSyncState.NotDownloaded) }
            return
        }

        val tracks = db.trackDao().getTracksForPlaylistSync(playlistId)
        if (tracks.isEmpty()) {
            _playlistStates.update { it + (playlistId to PlaylistSyncState.NotDownloaded) }
            return
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

        _playlistStates.update { it + (playlistId to newState) }
    }

    fun observePlaylistSyncState(playlistId: String): Flow<PlaylistSyncState> {
        return _playlistStates.map { states -> states[playlistId] ?: PlaylistSyncState.NotDownloaded }
    }
}
