package com.music.spotui.data.storage

import android.content.Context
import android.util.Log
import com.music.spotui.data.db.AppDatabase
import com.music.spotui.data.db.entity.DownloadStatus
import com.music.spotui.data.db.entity.TrackEntity
import com.music.spotui.data.preferences.getDownloadedEntries
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.DecimalFormat

object OfflineStorageManager {

    private const val TAG = "OfflineStorageManager"
    private const val PREFS_STORAGE = "spotui_offline_storage_settings"
    private const val KEY_QUOTA_BYTES = "storage_quota_bytes"
    private const val KEY_LRU_ENABLED = "lru_eviction_enabled"

    // Default quota: 10 GB
    const val DEFAULT_QUOTA_BYTES: Long = 10L * 1024L * 1024L * 1024L

    fun getDownloadsDir(context: Context): File {
        val dir = File(context.filesDir, "downloads")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getStorageQuotaBytes(context: Context): Long {
        val sp = context.getSharedPreferences(PREFS_STORAGE, Context.MODE_PRIVATE)
        return sp.getLong(KEY_QUOTA_BYTES, DEFAULT_QUOTA_BYTES)
    }

    fun setStorageQuotaBytes(context: Context, bytes: Long) {
        context.getSharedPreferences(PREFS_STORAGE, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_QUOTA_BYTES, bytes)
            .apply()
    }

    fun isLruEvictionEnabled(context: Context): Boolean {
        val sp = context.getSharedPreferences(PREFS_STORAGE, Context.MODE_PRIVATE)
        return sp.getBoolean(KEY_LRU_ENABLED, false)
    }

    fun setLruEvictionEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_STORAGE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_LRU_ENABLED, enabled)
            .apply()
    }

    /**
     * Verifies storage limits before initiating a new download.
     * If space is insufficient and LRU is enabled, evicts least recently played tracks.
     * Otherwise throws [InsufficientStorageException].
     */
    suspend fun checkStorageQuota(context: Context, estimatedBytes: Long = 10 * 1024 * 1024L) = withContext(Dispatchers.IO) {
        val quota = getStorageQuotaBytes(context)
        if (quota == Long.MAX_VALUE) return@withContext // Unlimited quota

        val db = AppDatabase.getInstance(context)
        val currentUsed = db.trackDao().getTotalStorageUsed()
        val availableOnDevice = getDownloadsDir(context).usableSpace

        if (availableOnDevice < estimatedBytes + (50 * 1024 * 1024L)) {
            throw InsufficientStorageException("Device storage critically low (${formatSize(availableOnDevice)} free).")
        }

        if (currentUsed + estimatedBytes > quota) {
            if (isLruEvictionEnabled(context)) {
                val needed = (currentUsed + estimatedBytes) - quota
                val freed = evictLruTracks(context, needed)
                if (freed < needed) {
                    throw InsufficientStorageException(
                        "Quota reached (${formatSize(quota)}). Could not free enough space via LRU eviction."
                    )
                }
            } else {
                throw InsufficientStorageException(
                    "Offline storage quota limit reached (${formatSize(quota)}). Adjust limit in Settings or delete downloads."
                )
            }
        }
    }

    private suspend fun evictLruTracks(context: Context, neededBytes: Long): Long {
        val db = AppDatabase.getInstance(context)
        val lruTracks = db.trackDao().getLRUCompletedTracks()
        var freed = 0L

        for (t in lruTracks) {
            if (freed >= neededBytes) break
            // Only evict tracks that are not pinned by multiple playlists if possible
            t.localPath?.let { path ->
                val f = File(path)
                val len = if (f.exists()) f.length() else t.fileSize
                if (f.exists()) f.delete()
                freed += len
            }
            db.trackDao().updateDownloadStatus(
                trackId = t.trackId,
                status = DownloadStatus.NONE,
                localPath = null,
                fileSize = 0L,
                errorMessage = "Evicted by LRU policy",
                time = System.currentTimeMillis()
            )
            Log.d(TAG, "LRU evicted track: ${t.title} (freed ${formatSize(t.fileSize)})")
        }
        return freed
    }

    // ── Atomic File Operations ──

    fun createTempFile(context: Context, trackKey: String, extension: String = "tmp"): File {
        val safeKey = trackKey.replace(Regex("[^A-Za-z0-9_]"), "_").take(32)
        val dir = getDownloadsDir(context)
        return File(dir, "temp_${safeKey}_${System.currentTimeMillis()}.$extension")
    }

    fun getFinalFile(context: Context, trackKey: String, extension: String): File {
        val safeKey = trackKey.replace(Regex("[^A-Za-z0-9_]"), "_").take(48)
        val ext = extension.removePrefix(".")
        val dir = getDownloadsDir(context)
        return File(dir, "${safeKey}.$ext")
    }

    /**
     * Atomically commits a temp download file to its final destination after verifying integrity.
     */
    fun commitAtomicFile(
        tempFile: File,
        finalFile: File,
        minBytes: Long = 1024L,
        expectedChecksum: String? = null
    ): Boolean {
        if (!tempFile.exists() || tempFile.length() < minBytes) {
            Log.e(TAG, "Atomic commit failed: temp file invalid (size=${tempFile.length()})")
            tempFile.delete()
            return false
        }

        if (!expectedChecksum.isNullOrBlank()) {
            val actualChecksum = calculateSha256(tempFile)
            if (!actualChecksum.equals(expectedChecksum, ignoreCase = true)) {
                Log.e(TAG, "Checksum verification failed for ${tempFile.name}")
                tempFile.delete()
                return false
            }
        }

        if (finalFile.exists()) {
            finalFile.delete()
        }

        val renamed = tempFile.renameTo(finalFile)
        if (renamed) {
            return true
        }

        // Fallback for cross-filesystem / locked rename
        return try {
            FileInputStream(tempFile).use { input ->
                FileOutputStream(finalFile).use { output ->
                    input.copyTo(output)
                }
            }
            tempFile.delete()
            finalFile.exists() && finalFile.length() >= minBytes
        } catch (e: Exception) {
            Log.e(TAG, "Fallback file copy failed", e)
            tempFile.delete()
            false
        }
    }

    private fun calculateSha256(file: File): String = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(8192)
            var read: Int
            while (fis.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrDefault("")

    // ── Startup Health Check ──

    /**
     * Background verification job on app start:
     * 1. Cross-checks all database rows marked COMPLETED with actual files on disk.
     * 2. If files were deleted externally by user/system, resets row status to NONE.
     * 3. Seamlessly imports legacy download preferences if any exist.
     * 4. Purges dangling .tmp files older than 1 hour.
     */
    suspend fun runStartupHealthCheck(context: Context) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val db = AppDatabase.getInstance(app)
        val trackDao = db.trackDao()

        Log.d(TAG, "Starting storage consistency health check...")

        // 1. Check DB completed tracks against disk
        val completed = trackDao.getAllCompletedTracksSync()
        var fixedCount = 0

        for (track in completed) {
            val path = track.localPath
            val exists = path != null && File(path).let { it.exists() && it.length() > 0 }
            if (!exists) {
                trackDao.updateDownloadStatus(
                    trackId = track.trackId,
                    status = DownloadStatus.NONE,
                    localPath = null,
                    fileSize = 0L,
                    errorMessage = "File missing from disk",
                    time = System.currentTimeMillis()
                )
                fixedCount++
                Log.w(TAG, "Health check: Track '${track.title}' file missing at $path. Reset to NONE.")
            }
        }

        // 2. Seamlessly synchronize legacy SharedPreferences downloads into Room
        runCatching {
            val legacy = getDownloadedEntries(app)
            for ((song, path) in legacy) {
                val f = File(path)
                if (f.exists() && f.length() > 0) {
                    val key = when {
                        song.spotifyTrackId.isNotBlank() -> song.spotifyTrackId
                        song.id != 0 -> "song_${song.id}"
                        else -> "${song.title}_${song.singer}".replace(Regex("[^A-Za-z0-9_]"), "_")
                    }
                    val existing = trackDao.getTrack(key)
                    if (existing == null || existing.downloadStatus != DownloadStatus.COMPLETED) {
                        val entity = TrackEntity.fromSongModel(song, DownloadStatus.COMPLETED, refCount = 1).copy(
                            trackId = key,
                            localPath = path,
                            fileSize = f.length(),
                            downloadTimeMs = f.lastModified()
                        )
                        trackDao.insertTrack(entity)
                    }
                }
            }
        }

        // 3. Purge orphaned .tmp files
        val downloadsDir = getDownloadsDir(app)
        val now = System.currentTimeMillis()
        downloadsDir.listFiles { _, name -> name.startsWith("temp_") || name.endsWith(".tmp") }?.forEach { tmp ->
            if (now - tmp.lastModified() > 3600_000L) {
                tmp.delete()
                Log.d(TAG, "Purged stale temp file: ${tmp.name}")
            }
        }

        val totalBytes = trackDao.getTotalStorageUsed()
        Log.d(TAG, "Storage health check complete. Total offline storage: ${formatSize(totalBytes)}, verified ${completed.size} tracks, repaired $fixedCount.")
    }

    fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 MB"
        val mb = bytes.toDouble() / (1024 * 1024)
        return if (mb >= 1024) {
            val gb = mb / 1024
            DecimalFormat("#.##").format(gb) + " GB"
        } else {
            DecimalFormat("#.#").format(mb) + " MB"
        }
    }
}
