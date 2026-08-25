package com.music.spotui.worker

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.music.spotui.data.db.AppDatabase
import com.music.spotui.data.db.entity.DownloadStatus
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.di.SongPlayer
import java.util.concurrent.TimeUnit

/**
 * Background WorkManager sync worker.
 * Periodically checks playlists marked for offline download (is_download_enabled = true),
 * diffs remote playlist songs against local database entries, and enqueues missing tracks.
 */
class PlaylistSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Log.d(TAG, "Starting periodic playlist background sync")
        val db = AppDatabase.getInstance(applicationContext)
        val playlistDao = db.playlistDao()
        val trackDao = db.trackDao()

        try {
            val autoSyncPlaylists = playlistDao.getDownloadedPlaylistsSync()
            Log.d(TAG, "Found ${autoSyncPlaylists.size} playlists marked for background offline sync")

            val api = com.music.spotui.data.api.Api(applicationContext)

            for (pl in autoSyncPlaylists) {
                // Fetch remote tracks if possible to perform differential sync
                runCatching {
                    val remoteTracks = mutableListOf<SongsModel>()
                    api.getPlaylistSongs(pl.playlistId).collect { resp ->
                        if (resp is com.music.spotui.data.api.Response.Success) {
                            remoteTracks.addAll(resp.data)
                        }
                    }
                    if (remoteTracks.isNotEmpty()) {
                        com.music.spotui.data.storage.DeltaPlaylistSyncEngine.syncPlaylistDelta(
                            context = applicationContext,
                            playlistId = pl.playlistId,
                            playlistTitle = pl.title,
                            playlistCoverUri = pl.coverUri,
                            remoteSnapshotId = "${remoteTracks.size}_${remoteTracks.firstOrNull()?.id}",
                            remoteTracks = remoteTracks
                        )
                    }
                }

                val tracks = trackDao.getTracksForPlaylistSync(pl.playlistId)
                for (t in tracks) {
                    if (t.downloadStatus != DownloadStatus.COMPLETED) {
                        Log.d(TAG, "Auto-syncing missing track '${t.title}' in playlist '${pl.title}'")
                        val song = SongsModel(
                            id = t.songId,
                            title = t.title,
                            singer = t.singer,
                            album = t.album,
                            coverUri = t.coverUri,
                            url = "${t.singer} - ${t.title}",
                            spotifyTrackId = t.spotifyTrackId
                        )
                        DownloadWorker.enqueue(
                            context = applicationContext,
                            song = song,
                            playlistId = pl.playlistId,
                            playlistName = pl.title
                        )
                    }
                }
            }

            return Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Playlist background sync encountered error: ${e.message}", e)
            return Result.retry()
        }
    }

    companion object {
        private const val TAG = "PlaylistSyncWorker"
        private const val WORK_NAME = "periodic_playlist_sync_work"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED) // Wi-Fi preferred
                .setRequiresBatteryNotLow(true)
                .build()

            val syncRequest = PeriodicWorkRequestBuilder<PlaylistSyncWorker>(12, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                syncRequest
            )
            Log.d(TAG, "Scheduled periodic playlist sync work (12h cycle on unmetered network)")
        }
    }
}
