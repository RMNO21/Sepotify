package com.music.spotui.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
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
        val playlistName = inputData.getString(KEY_PLAYLIST_NAME) ?: ""

        if (title.isBlank() && url.isBlank()) {
            return Result.failure()
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
            playlistId = playlistId,
            playlistName = playlistName
        )

        return if (success) {
            Result.success(workDataOf("songId" to songId, "downloaded" to true))
        } else {
            Result.retry()
        }
    }

    companion object {
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

            val workRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(inputData)
                .build()

            WorkManager.getInstance(context).enqueue(workRequest)
        }
    }
}
