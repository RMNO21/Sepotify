package com.music.spotui.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.music.spotui.data.db.AppDatabase
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.di.SongPlayer

class DownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val songId = inputData.getInt(KEY_SONG_ID, 0)
        val title = inputData.getString(KEY_TITLE) ?: ""
        val singer = inputData.getString(KEY_SINGER) ?: ""
        val album = inputData.getString(KEY_ALBUM) ?: ""
        val coverUri = inputData.getString(KEY_COVER_URI) ?: ""
        val url = inputData.getString(KEY_URL) ?: ""
        val spotifyTrackId = inputData.getString(KEY_SPOTIFY_TRACK_ID) ?: ""
        val playlistId = inputData.getString(KEY_PLAYLIST_ID) ?: ""
        val inputPlaylistName = inputData.getString(KEY_PLAYLIST_NAME) ?: ""

        if (title.isBlank() && url.isBlank()) {
            return Result.failure()
        }

        // Emit initial progress data for UI observing WorkInfo.progress
        runCatching {
            setProgress(
                workDataOf(
                    KEY_PROGRESS to 0,
                    KEY_STATUS to "DOWNLOADING",
                    KEY_SONG_ID to songId,
                    KEY_TITLE to title,
                    KEY_SINGER to singer,
                    KEY_ALBUM to album,
                    KEY_COVER_URI to coverUri,
                    KEY_URL to url,
                    KEY_SPOTIFY_TRACK_ID to spotifyTrackId,
                    KEY_PLAYLIST_ID to playlistId,
                    KEY_PLAYLIST_NAME to inputPlaylistName
                )
            )
        }

        // Query playlist title from the database
        val db = AppDatabase.getInstance(applicationContext)
        var resolvedPlaylistName = inputPlaylistName
        var targetPlaylistId = playlistId

        if (targetPlaylistId.isNotBlank()) {
            val playlistEntity = db.playlistDao().getPlaylist(targetPlaylistId)
            if (playlistEntity != null && playlistEntity.title.isNotBlank()) {
                resolvedPlaylistName = playlistEntity.title
            }
        }

        if (resolvedPlaylistName.isBlank()) {
            val trackKey = spotifyTrackId.ifBlank { if (songId != 0) songId.toString() else url }
            val foundPlaylistId = db.playlistTrackDao().getPlaylistIdForTrack(trackKey)
            if (!foundPlaylistId.isNullOrBlank()) {
                targetPlaylistId = foundPlaylistId
                val playlistEntity = db.playlistDao().getPlaylist(foundPlaylistId)
                if (playlistEntity != null && playlistEntity.title.isNotBlank()) {
                    resolvedPlaylistName = playlistEntity.title
                }
            }
        }

        if (resolvedPlaylistName.isBlank()) {
            resolvedPlaylistName = if (album.isNotBlank()) album else "Tracks"
        }

        val song = SongsModel(
            id = songId,
            title = title,
            singer = singer,
            album = album,
            coverUri = coverUri,
            url = if (url.isNotBlank()) url else "$singer - $title",
            spotifyTrackId = spotifyTrackId
        )

        val success = SongPlayer.downloadSongSync(
            song = song,
            appContext = applicationContext,
            playlistId = targetPlaylistId,
            playlistName = resolvedPlaylistName,
            onProgress = { pct ->
                runCatching {
                    kotlinx.coroutines.runBlocking {
                        setProgress(
                            workDataOf(
                                KEY_PROGRESS to pct.coerceIn(0, 100),
                                KEY_STATUS to if (pct >= 100) "SAVING" else "DOWNLOADING",
                                KEY_SONG_ID to songId,
                                KEY_TITLE to title,
                                KEY_SINGER to singer,
                                KEY_ALBUM to album,
                                KEY_COVER_URI to coverUri,
                                KEY_URL to url,
                                KEY_SPOTIFY_TRACK_ID to spotifyTrackId,
                                KEY_PLAYLIST_ID to targetPlaylistId,
                                KEY_PLAYLIST_NAME to resolvedPlaylistName
                            )
                        )
                    }
                }
            }
        )

        return if (success) {
            runCatching {
                setProgress(
                    workDataOf(
                        KEY_PROGRESS to 100,
                        KEY_STATUS to "COMPLETED",
                        KEY_SONG_ID to songId,
                        KEY_TITLE to title,
                        KEY_SINGER to singer,
                        KEY_ALBUM to album,
                        KEY_COVER_URI to coverUri,
                        KEY_URL to url
                    )
                )
            }
            Result.success(
                workDataOf(
                    KEY_SONG_ID to songId,
                    "downloaded" to true,
                    KEY_PROGRESS to 100
                )
            )
        } else {
            Result.retry()
        }
    }

    companion object {
        const val TAG_DOWNLOAD_WORKER = "download_worker"
        const val KEY_PROGRESS = "key_progress"
        const val KEY_STATUS = "key_status"
        const val KEY_SONG_ID = "key_song_id"
        const val KEY_TITLE = "key_title"
        const val KEY_SINGER = "key_singer"
        const val KEY_ALBUM = "key_album"
        const val KEY_COVER_URI = "key_cover_uri"
        const val KEY_URL = "key_url"
        const val KEY_SPOTIFY_TRACK_ID = "key_spotify_track_id"
        const val KEY_PLAYLIST_ID = "key_playlist_id"
        const val KEY_PLAYLIST_NAME = "key_playlist_name"

        fun enqueue(
            context: Context,
            song: SongsModel,
            playlistId: String = "",
            playlistName: String = ""
        ) {
            val inputData = Data.Builder()
                .putInt(KEY_SONG_ID, song.id)
                .putString(KEY_TITLE, song.title)
                .putString(KEY_SINGER, song.singer)
                .putString(KEY_ALBUM, song.album)
                .putString(KEY_COVER_URI, song.coverUri)
                .putString(KEY_URL, song.url)
                .putString(KEY_SPOTIFY_TRACK_ID, song.spotifyTrackId)
                .putString(KEY_PLAYLIST_ID, playlistId)
                .putString(KEY_PLAYLIST_NAME, playlistName)
                .build()

            val trackKey = if (song.spotifyTrackId.isNotBlank()) song.spotifyTrackId else song.id.toString()
            val workRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(inputData)
                .addTag(TAG_DOWNLOAD_WORKER)
                .addTag("track_$trackKey")
                .build()

            WorkManager.getInstance(context).enqueue(workRequest)
        }
    }
}
