package com.music.spotui.storage

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Modern Scoped Storage MediaStore Integration.
 * Writes audio tracks to Android's global MediaStore library with album covers, artist tags,
 * and duration, registering them with system media scanners.
 */
object MediaStoreExporter {

    private const val TAG = "MediaStoreExporter"

    data class ExportTrackMetadata(
        val title: String,
        val artist: String,
        val albumName: String,
        val durationMs: Long = 0L,
        val mimeType: String = "audio/ogg"
    )

    suspend fun exportTrackToMediaStore(
        context: Context,
        sourceFile: File,
        title: String,
        artist: String,
        album: String,
        mimeType: String = "audio/mp4"
    ): Uri? {
        return insertTrackToMediaStore(
            context = context,
            file = sourceFile,
            metadata = ExportTrackMetadata(
                title = title,
                artist = artist,
                albumName = album,
                mimeType = mimeType
            )
        )
    }

    suspend fun insertTrackToMediaStore(
        context: Context,
        file: File,
        metadata: ExportTrackMetadata
    ): Uri? = withContext(Dispatchers.IO) {
        if (!file.exists() || file.length() == 0L) return@withContext null

        try {
            val extension = if (metadata.mimeType.contains("ogg") || metadata.mimeType.contains("opus")) {
                "opus"
            } else if (metadata.mimeType.contains("flac")) {
                "flac"
            } else {
                "mp3"
            }

            val displayName = "${metadata.artist} - ${metadata.title}.$extension"
                .replace("[\\\\/:*?\"<>|]".toRegex(), "_")

            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Audio.Media.TITLE, metadata.title)
                put(MediaStore.Audio.Media.ARTIST, metadata.artist)
                put(MediaStore.Audio.Media.ALBUM, metadata.albumName)
                put(MediaStore.Audio.Media.MIME_TYPE, metadata.mimeType)
                if (metadata.durationMs > 0) {
                    put(MediaStore.Audio.Media.DURATION, metadata.durationMs)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/Sepotify")
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
            }

            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            }

            val uri = context.contentResolver.insert(collection, values) ?: return@withContext null

            context.contentResolver.openOutputStream(uri)?.use { output ->
                file.inputStream().use { input ->
                    input.copyTo(output)
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Audio.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
            } else {
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(file.absolutePath),
                    arrayOf(metadata.mimeType),
                    null
                )
            }

            Log.d(TAG, "Successfully exported track to MediaStore: $displayName -> $uri")
            return@withContext uri
        } catch (e: Exception) {
            Log.e(TAG, "Failed to insert track into MediaStore: ${e.message}", e)
            return@withContext null
        }
    }
}
