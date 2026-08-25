package com.music.spotui.data.storage

import android.content.Context
import android.util.Log
import com.music.spotui.data.db.AppDatabase
import com.music.spotui.data.db.entity.DownloadStatus
import com.music.spotui.data.db.entity.PlaylistEntity
import com.music.spotui.data.db.entity.PlaylistTrackCrossRef
import com.music.spotui.data.db.entity.TrackEntity
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.worker.DownloadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Differential Playlist Sync Engine (Delta Sync).
 *
 * 1. Snapshot ID Verification: Compares local stored snapshot ID against remote Spotify snapshot_id.
 *    If identical, sync is skipped entirely (zero bandwidth & CPU overhead).
 * 2. Two-Way Set Difference: Computes T_remote \ T_local (added) and T_local \ T_remote (removed).
 * 3. Bounded Concurrency: Uses a Semaphore bounded to max 4 concurrent jobs to avoid device throttling.
 */
object DeltaPlaylistSyncEngine {

    private const val TAG = "DeltaPlaylistSyncEngine"
    private const val MAX_CONCURRENT_WORKERS = 4

    data class DeltaResult(
        val skippedUnchanged: Boolean,
        val addedCount: Int,
        val removedCount: Int,
        val totalTracks: Int
    )

    suspend fun syncPlaylistDelta(
        context: Context,
        playlistId: String,
        playlistTitle: String,
        playlistCoverUri: String,
        remoteSnapshotId: String,
        remoteTracks: List<SongsModel>
    ): DeltaResult = withContext(Dispatchers.IO) {
        val db = AppDatabase.getInstance(context)
        val playlistDao = db.playlistDao()
        val trackDao = db.trackDao()
        val crossRefDao = db.playlistTrackDao()

        val localPlaylist = playlistDao.getPlaylist(playlistId)

        // 1. Snapshot ID Verification
        if (localPlaylist != null &&
            localPlaylist.snapshotId.isNotBlank() &&
            localPlaylist.snapshotId == remoteSnapshotId &&
            remoteSnapshotId.isNotBlank()
        ) {
            Log.d(TAG, "Snapshot ID matches ($remoteSnapshotId) for '$playlistTitle' -> skipping sync")
            return@withContext DeltaResult(
                skippedUnchanged = true,
                addedCount = 0,
                removedCount = 0,
                totalTracks = remoteTracks.size
            )
        }

        Log.d(TAG, "Initiating delta sync for '$playlistTitle' (local snapshot: '${localPlaylist?.snapshotId}', remote: '$remoteSnapshotId')")

        // Ensure playlist entity exists in DB
        val updatedPlaylist = PlaylistEntity(
            playlistId = playlistId,
            title = playlistTitle,
            coverUri = playlistCoverUri,
            isDownloadEnabled = localPlaylist?.isDownloadEnabled ?: true,
            lastSyncTimestamp = System.currentTimeMillis(),
            snapshotId = remoteSnapshotId
        )
        playlistDao.insertPlaylist(updatedPlaylist)

        // 2. Compute Two-Way Set Difference
        val localTrackIds = crossRefDao.getTrackIdsForPlaylist(playlistId).toSet()
        val remoteTrackIds = remoteTracks.map { it.id.toString() }.toSet()

        val addedTrackIds = remoteTrackIds - localTrackIds
        val removedTrackIds = localTrackIds - remoteTrackIds

        Log.d(TAG, "Delta computed: +${addedTrackIds.size} added, -${removedTrackIds.size} removed, total ${remoteTracks.size}")

        // 3. Remove deleted tracks
        for (removedId in removedTrackIds) {
            crossRefDao.deleteCrossRef(playlistId, removedId)
            // If track is not in any other playlist, we can check if cleanup is needed
            val refCount = crossRefDao.countPlaylistsReferencingTrack(removedId)
            if (refCount == 0) {
                Log.d(TAG, "Track $removedId no longer referenced in any playlist")
            }
        }

        // 4. Batch Process added tracks with bounded concurrency (max 4 parallel workers)
        val semaphore = Semaphore(MAX_CONCURRENT_WORKERS)
        val addedTracks = remoteTracks.filter { it.id.toString() in addedTrackIds }

        coroutineScope {
            val jobs = addedTracks.mapIndexed { index, song ->
                async {
                    semaphore.withPermit {
                        // Insert track entity if not present
                        val trackEntity = TrackEntity(
                            trackId = song.id.toString(),
                            songId = song.id,
                            title = song.title,
                            singer = song.singer,
                            album = song.album,
                            coverUri = song.coverUri,
                            spotifyTrackId = song.spotifyTrackId,
                            downloadStatus = DownloadStatus.QUEUED
                        )
                        trackDao.insertTrack(trackEntity)

                        // Insert cross-ref
                        crossRefDao.insertCrossRef(
                            PlaylistTrackCrossRef(
                                playlistId = playlistId,
                                trackId = song.id.toString(),
                                sortOrder = index
                            )
                        )

                        // Enqueue background offline download
                        if (updatedPlaylist.isDownloadEnabled) {
                            DownloadWorker.enqueue(
                                context = context,
                                song = song,
                                playlistId = playlistId,
                                playlistName = playlistTitle
                            )
                        }
                    }
                }
            }
            jobs.awaitAll()
        }

        // Update playlist snapshot in database
        playlistDao.updatePlaylistSnapshot(playlistId, remoteSnapshotId, System.currentTimeMillis())

        DeltaResult(
            skippedUnchanged = false,
            addedCount = addedTrackIds.size,
            removedCount = removedTrackIds.size,
            totalTracks = remoteTracks.size
        )
    }
}
