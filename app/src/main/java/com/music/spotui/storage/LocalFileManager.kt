package com.music.spotui.storage

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import com.music.spotui.data.db.AppDatabase
import com.music.spotui.data.db.dao.TrackDao
import com.music.spotui.data.db.entity.DownloadStatus
import com.music.spotui.data.db.entity.TrackEntity
import com.music.spotui.resolver.TrackTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

data class TrackMetadata(
    val trackId: String,
    val title: String,
    val artist: String,
    val albumName: String = "",
    val coverUrl: String = "",
    val durationMs: Long = 0L,
    val explicit: Boolean = false
) {
    fun toTarget(): TrackTarget = TrackTarget(
        title = title,
        artist = artist,
        durationMs = durationMs,
        album = albumName
    )
}

enum class PlaybackSource {
    LOCAL_DISK,
    REMOTE_CDN
}

data class PlaybackResolution(
    val uri: Uri,
    val source: PlaybackSource
)

/**
 * Deterministic File Manager with Self-Healing Runtime Source Gate.
 * Enforces Single Source of Truth (SSOT): physical filesystem state defines playback capabilities;
 * database is an observable projection that self-heals upon mismatch.
 */
class LocalFileManager(
    private val context: Context,
    private val trackDao: TrackDao = AppDatabase.getInstance(context).trackDao()
) {

    companion object {
        private const val TAG = "LocalFileManager"
        const val MIN_AUDIO_FILE_SIZE_BYTES = 100_000L // 100 KB integrity threshold

        @Volatile private var instance: LocalFileManager? = null

        fun getInstance(context: Context): LocalFileManager =
            instance ?: synchronized(this) {
                instance ?: LocalFileManager(context.applicationContext).also { instance = it }
            }

        fun getDownloadedSongFile(context: Context, trackId: String): File? {
            return getInstance(context).getValidLocalFile(trackId)
        }
    }

    private val storageDir: File = run {
        val external = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
        val dir = if (external != null) {
            File(external, "Sepotify")
        } else {
            val publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            if (publicDir != null && publicDir.exists()) {
                File(publicDir, "Sepotify")
            } else {
                File(context.filesDir, "downloads")
            }
        }
        if (!dir.exists()) dir.mkdirs()
        dir
    }

    private val legacyDir: File = File(context.filesDir, "downloads")

    private fun getFileForTrack(trackId: String, extension: String = "mp3"): File {
        val cleanId = trackId.replace(Regex("[^a-zA-Z0-9_.-]"), "_")
        val targetName = "$cleanId.$extension"

        // 0. Check if track local_path in DB points to a valid file
        try {
            val db = AppDatabase.getInstance(context)
            val dbPath = kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                db.trackDao().getTrack(trackId)?.localPath
            }
            if (!dbPath.isNullOrBlank()) {
                val dbFile = File(dbPath)
                if (dbFile.exists() && dbFile.length() >= MIN_AUDIO_FILE_SIZE_BYTES) {
                    return dbFile
                }
            }
        } catch (_: Throwable) {}

        // 1. Direct in storageDir
        val primary = File(storageDir, targetName)
        if (primary.exists() && primary.length() >= MIN_AUDIO_FILE_SIZE_BYTES) return primary

        // 2. Direct in legacyDir
        val legacy = File(legacyDir, targetName)
        if (legacy.exists() && legacy.length() >= MIN_AUDIO_FILE_SIZE_BYTES) return legacy

        // 3. In any playlist/album subdirectory under storageDir
        storageDir.listFiles()?.filter { it.isDirectory }?.forEach { subDir ->
            val subFile = File(subDir, targetName)
            if (subFile.exists() && subFile.length() >= MIN_AUDIO_FILE_SIZE_BYTES) return subFile
            subDir.listFiles()?.forEach { file ->
                if (file.isFile && file.length() >= MIN_AUDIO_FILE_SIZE_BYTES &&
                    (file.name.contains(cleanId, ignoreCase = true) || file.nameWithoutExtension.equals(cleanId, ignoreCase = true))) {
                    return file
                }
            }
        }

        // 4. In any playlist/album subdirectory under legacyDir
        legacyDir.listFiles()?.filter { it.isDirectory }?.forEach { subDir ->
            val subFile = File(subDir, targetName)
            if (subFile.exists() && subFile.length() >= MIN_AUDIO_FILE_SIZE_BYTES) return subFile
        }

        return primary
    }

    /**
     * Check if a valid, uncorrupted downloaded audio file exists on disk.
     */
    fun hasDownloadedFile(trackId: String): Boolean {
        for (ext in listOf("mp3", "m4a", "flac", "opus", "aac")) {
            val f = getFileForTrack(trackId, ext)
            if (f.exists() && f.length() >= MIN_AUDIO_FILE_SIZE_BYTES) {
                return true
            }
        }
        return false
    }

    fun getValidLocalFile(trackId: String): File? {
        for (ext in listOf("mp3", "m4a", "flac", "opus", "aac")) {
            val f = getFileForTrack(trackId, ext)
            if (f.exists() && f.length() >= MIN_AUDIO_FILE_SIZE_BYTES) {
                return f
            }
        }
        return null
    }

    /**
     * Returns a local file [Uri] if the track is fully downloaded and verified on disk.
     */
    fun getValidLocalUri(trackId: String): Uri? {
        for (ext in listOf("mp3", "m4a", "flac", "opus", "aac")) {
            val f = getFileForTrack(trackId, ext)
            if (f.exists() && f.length() >= MIN_AUDIO_FILE_SIZE_BYTES) {
                return Uri.fromFile(f)
            }
        }
        return null
    }

    /**
     * Runtime Source Gate: Verifies actual file on disk before committing to local playback.
     */
    suspend fun resolvePlaybackSource(trackId: String, remoteUrl: String?): PlaybackResolution = withContext(Dispatchers.IO) {
        val validLocal = getValidLocalUri(trackId)
        if (validLocal != null) {
            return@withContext PlaybackResolution(
                uri = validLocal,
                source = PlaybackSource.LOCAL_DISK
            )
        }

        // Self-Healing: If DB says COMPLETED but file is missing or corrupted, reset DB state
        val existingEntity = trackDao.getTrack(trackId)
        if (existingEntity != null && existingEntity.downloadStatus == DownloadStatus.COMPLETED) {
            Log.w(TAG, "Self-healing triggered: DB marked '$trackId' as COMPLETED but file missing/corrupted on disk. Resetting status.")
            trackDao.updateDownloadStatus(
                trackId = trackId,
                status = DownloadStatus.NONE,
                localPath = null,
                fileSize = 0L,
                errorMessage = "File missing or corrupted during runtime gate check",
                time = System.currentTimeMillis()
            )
        }

        // Fallback to Remote CDN
        if (!remoteUrl.isNullOrBlank()) {
            return@withContext PlaybackResolution(
                uri = Uri.parse(remoteUrl),
                source = PlaybackSource.REMOTE_CDN
            )
        }

        throw IllegalStateException("Track $trackId unavailable on disk and no remote URL provided.")
    }

    /**
     * Retrieves track metadata for matching or tag injection.
     */
    suspend fun getTrackMetadata(trackId: String): TrackMetadata = withContext(Dispatchers.IO) {
        val entity = trackDao.getTrack(trackId)
        if (entity != null) {
            return@withContext TrackMetadata(
                trackId = entity.trackId,
                title = entity.title,
                artist = entity.singer,
                albumName = entity.album,
                coverUrl = entity.coverUri,
                durationMs = entity.durationMs.toLong(),
                explicit = entity.explicit
            )
        }
        TrackMetadata(trackId = trackId, title = trackId, artist = "")
    }

    /**
     * Atomic Download Procedure:
     * 1. Write raw bytes to temporary file (.tmp)
     * 2. Validate file size and payload integrity
     * 3. Inject ID3 Tags directly into the temp file
     * 4. Atomic filesystem rename to final file (.mp3)
     * 5. Commit completion transaction to Database
     */
    suspend fun executeAtomicDownload(
        trackId: String,
        metadata: TrackMetadata,
        downloadStream: InputStream,
        extension: String = "mp3"
    ): File = withContext(Dispatchers.IO) {
        val cleanId = trackId.replace(Regex("[^a-zA-Z0-9_.-]"), "_")
        val tempFile = File(storageDir, "$cleanId.tmp")
        val finalFile = File(storageDir, "$cleanId.$extension")

        try {
            trackDao.updateDownloadStatus(
                trackId = trackId,
                status = DownloadStatus.DOWNLOADING,
                localPath = null,
                fileSize = 0L,
                errorMessage = null,
                time = System.currentTimeMillis()
            )

            // Step 1: Write raw bytes to temporary file
            FileOutputStream(tempFile).use { output ->
                downloadStream.copyTo(output)
                output.flush()
            }

            // Step 2: Validate file integrity
            if (!tempFile.exists() || tempFile.length() < MIN_AUDIO_FILE_SIZE_BYTES) {
                throw IOException("Incomplete audio payload received: size=${tempFile.length()} bytes (< ${MIN_AUDIO_FILE_SIZE_BYTES} bytes threshold)")
            }

            // Step 3: Inject ID3 Tags directly into the temp file
            ID3Tagger.writeTags(
                file = tempFile,
                title = metadata.title,
                artist = metadata.artist,
                album = metadata.albumName,
                coverArtUrl = metadata.coverUrl
            )

            // Step 4: Atomic filesystem rename
            if (finalFile.exists()) finalFile.delete()
            val renamed = tempFile.renameTo(finalFile)
            if (!renamed) {
                // Fallback copy if cross-filesystem or locked
                FileInputStream(tempFile).use { fis ->
                    FileOutputStream(finalFile).use { fos ->
                        fis.copyTo(fos)
                    }
                }
                tempFile.delete()
            }

            if (!finalFile.exists() || finalFile.length() < MIN_AUDIO_FILE_SIZE_BYTES) {
                throw IOException("Atomic file creation failed for ${finalFile.name}")
            }

            // Step 5: Commit completion to Database
            val existing = trackDao.getTrack(trackId)
            if (existing == null) {
                trackDao.insertTrack(
                    TrackEntity(
                        trackId = trackId,
                        title = metadata.title,
                        singer = metadata.artist,
                        album = metadata.albumName,
                        coverUri = metadata.coverUrl,
                        durationMs = metadata.durationMs.toInt(),
                        explicit = metadata.explicit,
                        downloadStatus = DownloadStatus.COMPLETED,
                        localPath = finalFile.absolutePath,
                        fileSize = finalFile.length(),
                        downloadTimeMs = System.currentTimeMillis(),
                        refCount = 1
                    )
                )
            } else {
                trackDao.updateDownloadStatus(
                    trackId = trackId,
                    status = DownloadStatus.COMPLETED,
                    localPath = finalFile.absolutePath,
                    fileSize = finalFile.length(),
                    errorMessage = null,
                    time = System.currentTimeMillis()
                )
            }

            Log.d(TAG, "Atomic download complete for '$trackId' -> ${finalFile.absolutePath} (${finalFile.length()} bytes)")
            return@withContext finalFile

        } catch (e: Exception) {
            Log.e(TAG, "Atomic download failed for track $trackId: ${e.message}", e)
            if (tempFile.exists()) tempFile.delete()
            if (finalFile.exists()) finalFile.delete()
            trackDao.updateDownloadStatus(
                trackId = trackId,
                status = DownloadStatus.FAILED,
                localPath = null,
                fileSize = 0L,
                errorMessage = e.message ?: "Download failed",
                time = System.currentTimeMillis()
            )
            throw e
        }
    }
}
