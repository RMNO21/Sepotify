package com.music.spotui.data.preferences

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.music.spotui.data.entity.SongsModel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.DecimalFormat

/**
 * Enhanced Downloads manager for Sepotify.
 * Manages offline audio files, playlist associations, storage inspection,
 * and robust multi-key local query resolution for zero-delay offline playback.
 */
private const val PREF = "Downloads"
private const val PREF_PLAYLIST_MAP = "Downloads_Playlists"

data class DownloadMetadata(
    val song: SongsModel,
    val filePath: String,
    val playlistId: String = "",
    val playlistName: String = "",
    val downloadTimeMs: Long = System.currentTimeMillis(),
)

data class DownloadedPlaylistInfo(
    val id: String,
    val name: String,
    val coverUri: String,
    val downloadedTrackCount: Int,
    val totalTrackCount: Int,
    val isFullyDownloaded: Boolean,
)

data class DownloadStorageInfo(
    val internalPath: String,
    val publicPath: String,
    val trackCount: Int,
    val totalSizeBytes: Long,
    val formattedSize: String,
)

private fun normalizeStr(s: String): String =
    s.lowercase().filter { it.isLetterOrDigit() }

private fun DownloadMetadata.toJson(): String = JSONObject().apply {
    put("id", song.id)
    put("title", song.title)
    put("album", song.album)
    put("singer", song.singer)
    put("coverUri", song.coverUri)
    put("url", song.url)
    put("spotifyTrackId", song.spotifyTrackId)
    put("explicit", song.explicit)
    put("durationMs", song.durationMs)
    put("filePath", filePath)
    put("playlistId", playlistId)
    put("playlistName", playlistName)
    put("downloadTimeMs", downloadTimeMs)
}.toString()

private fun parseMeta(json: String): DownloadMetadata? = runCatching {
    val o = JSONObject(json)
    val song = SongsModel(
        id = o.getInt("id"),
        title = o.getString("title"),
        album = o.optString("album"),
        singer = o.getString("singer"),
        coverUri = o.optString("coverUri"),
        url = o.getString("url"),
        spotifyTrackId = o.optString("spotifyTrackId"),
        explicit = o.optBoolean("explicit", false),
        durationMs = o.optInt("durationMs", 0),
    )
    DownloadMetadata(
        song = song,
        filePath = o.optString("filePath"),
        playlistId = o.optString("playlistId"),
        playlistName = o.optString("playlistName"),
        downloadTimeMs = o.optLong("downloadTimeMs", System.currentTimeMillis()),
    )
}.getOrNull()

fun addDownload(
    context: Context,
    song: SongsModel,
    filePath: String,
    playlistId: String = "",
    playlistName: String = "",
) {
    val meta = DownloadMetadata(
        song = song,
        filePath = filePath,
        playlistId = playlistId,
        playlistName = playlistName,
    )
    context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
        .putString(song.id.toString(), meta.toJson())
        .apply()

    if (playlistId.isNotBlank()) {
        val plPrefs = context.getSharedPreferences(PREF_PLAYLIST_MAP, Context.MODE_PRIVATE)
        val existing = plPrefs.getStringSet(playlistId, emptySet()) ?: emptySet()
        plPrefs.edit()
            .putStringSet(playlistId, existing + song.id.toString())
            .putString("${playlistId}_name", playlistName.ifBlank { playlistId })
            .apply()
    }
}

fun removeDownload(context: Context, songId: String) {
    val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    prefs.getString(songId, null)?.let { json ->
        parseMeta(json)?.filePath?.let { path -> runCatching { File(path).delete() } }
    }
    prefs.edit().remove(songId).apply()
}

fun isDownloaded(context: Context, songId: String): Boolean {
    val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    val json = prefs.getString(songId, null) ?: return false
    val meta = parseMeta(json) ?: return false
    return meta.filePath.isNotBlank() && File(meta.filePath).exists()
}

fun getDownloadedSongs(context: Context): List<SongsModel> {
    val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    return prefs.all.values
        .mapNotNull { (it as? String)?.let(::parseMeta) }
        .filter { it.filePath.isNotBlank() && File(it.filePath).exists() }
        .sortedByDescending { it.downloadTimeMs }
        .map { it.song }
}

fun getDownloadedSongsForPlaylist(context: Context, playlistId: String): List<SongsModel> {
    if (playlistId.isBlank()) return emptyList()
    val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    return prefs.all.values
        .mapNotNull { (it as? String)?.let(::parseMeta) }
        .filter { it.playlistId == playlistId && it.filePath.isNotBlank() && File(it.filePath).exists() }
        .map { it.song }
}

fun isPlaylistDownloaded(context: Context, playlistId: String, totalTracks: Int): Boolean {
    if (playlistId.isBlank() || totalTracks <= 0) return false
    val downloadedCount = getDownloadedSongsForPlaylist(context, playlistId).size
    return downloadedCount >= totalTracks && downloadedCount > 0
}

/** Returns list of playlists that contain downloaded tracks with counts. */
fun getDownloadedPlaylists(context: Context): List<DownloadedPlaylistInfo> {
    val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    val allMetas = prefs.all.values
        .mapNotNull { (it as? String)?.let(::parseMeta) }
        .filter { it.filePath.isNotBlank() && File(it.filePath).exists() }

    val byPlaylist = allMetas.filter { it.playlistId.isNotBlank() }.groupBy { it.playlistId }
    return byPlaylist.map { (plId, metas) ->
        val name = metas.firstOrNull()?.playlistName?.takeIf { it.isNotBlank() } ?: "Playlist"
        val cover = metas.firstOrNull()?.song?.coverUri.orEmpty()
        DownloadedPlaylistInfo(
            id = plId,
            name = name,
            coverUri = cover,
            downloadedTrackCount = metas.size,
            totalTrackCount = metas.size,
            isFullyDownloaded = true,
        )
    }
}

/**
 * Super robust local file path resolver for a track query (SongsModel.url, spotifyTrackId, or title/artist).
 * Guarantees instantaneous local file match for offline playback with 0 delay.
 */
fun downloadedPathForQuery(context: Context, query: String): String? {
    if (query.isBlank()) return null
    val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    val metas = prefs.all.values.mapNotNull { (it as? String)?.let(::parseMeta) }

    // 1. Direct song.url exact match
    for (m in metas) {
        if (m.song.url == query && m.filePath.isNotBlank() && File(m.filePath).exists()) {
            return m.filePath
        }
    }

    // 2. Spotify Track ID match
    val spotifyId = if (query.startsWith("spotify:track:")) {
        query.removePrefix("spotify:track:").substringBefore('|').trim()
    } else if (query.contains('|') && query.substringBefore('|').matches(Regex("""[A-Za-z0-9]{22}"""))) {
        query.substringBefore('|').trim()
    } else {
        null
    }

    if (spotifyId != null) {
        for (m in metas) {
            if (m.song.spotifyTrackId == spotifyId && m.filePath.isNotBlank() && File(m.filePath).exists()) {
                return m.filePath
            }
        }
    }

    // 3. Exact title + artist match (or exact title match if artist is also checked)
    val cleanQ = if (query.contains('|')) query.substringAfter('|') else query
    val normQ = normalizeStr(cleanQ)

    if (normQ.isNotBlank()) {
        for (m in metas) {
            val songNorm = normalizeStr("${m.song.title} ${m.song.singer}")
            val songNormRev = normalizeStr("${m.song.singer} ${m.song.title}")
            val titleNorm = normalizeStr(m.song.title)
            val singerNorm = normalizeStr(m.song.singer)
            if (m.filePath.isNotBlank() && File(m.filePath).exists()) {
                if (normQ == songNorm || normQ == songNormRev) {
                    return m.filePath
                }
                if (titleNorm.isNotBlank() && singerNorm.isNotBlank() &&
                    normQ.contains(titleNorm) && normQ.contains(singerNorm)
                ) {
                    return m.filePath
                }
            }
        }
    }

    return null
}

/** Every downloaded track paired with its on-disk file path. */
fun getDownloadedEntries(context: Context): List<Pair<SongsModel, String>> =
    context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        .all.values.mapNotNull { (it as? String)?.let(::parseMeta) }
        .filter { it.filePath.isNotBlank() && File(it.filePath).exists() }
        .map { it.song to it.filePath }

/** Delete every downloaded file and forget all download entries. Returns count removed. */
fun clearAllDownloads(context: Context): Int {
    val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    val entries = getDownloadedEntries(context)
    entries.forEach { (_, path) -> if (path.isNotBlank()) runCatching { File(path).delete() } }
    prefs.edit().clear().apply()
    context.getSharedPreferences(PREF_PLAYLIST_MAP, Context.MODE_PRIVATE).edit().clear().apply()
    return entries.size
}

/** Returns storage directory and total disk space consumed by downloads. */
fun getDownloadStorageInfo(context: Context): DownloadStorageInfo {
    val entries = getDownloadedEntries(context)
    var totalBytes = 0L
    var internalDir = context.filesDir.absolutePath
    entries.forEach { (_, path) ->
        val f = File(path)
        if (f.exists()) {
            totalBytes += f.length()
            internalDir = f.parentFile?.absolutePath ?: internalDir
        }
    }

    val publicDir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        "${Environment.DIRECTORY_MUSIC}/sepotify"
    } else {
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "sepotify").absolutePath
    }

    val formatted = formatSize(totalBytes)
    return DownloadStorageInfo(
        internalPath = internalDir,
        publicPath = publicDir,
        trackCount = entries.size,
        totalSizeBytes = totalBytes,
        formattedSize = formatted,
    )
}

private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0 MB"
    val mb = bytes.toDouble() / (1024 * 1024)
    return if (mb >= 1024) {
        val gb = mb / 1024
        DecimalFormat("#.##").format(gb) + " GB"
    } else {
        DecimalFormat("#.#").format(mb) + " MB"
    }
}

private fun sanitizeFileName(name: String): String =
    name.replace(Regex("[/\\\\:*?\"<>|]"), "_").trim().take(120).ifBlank { "track" }

/**
 * Copy every downloaded track out of the app's private storage into shared Music/sepotify.
 */
fun exportDownloads(context: Context): Pair<Int, String> {
    val entries = getDownloadedEntries(context).filter { it.second.isNotBlank() && File(it.second).exists() }
    if (entries.isEmpty()) return 0 to "No downloaded files to export"
    val count = entries.count { (song, path) -> exportFile(context, song, path) }
    return count to "Music/sepotify"
}

/** Export a single downloaded track to public Music/sepotify. */
fun exportDownload(context: Context, song: SongsModel): Boolean {
    val path = getDownloadedEntries(context).firstOrNull { it.first.id == song.id }?.second
        ?.takeIf { it.isNotBlank() && File(it).exists() } ?: return false
    return exportFile(context, song, path)
}

/** Copy one private download file into shared Music/sepotify as "Artist - Title.<ext>". */
private fun exportFile(context: Context, song: SongsModel, path: String): Boolean = runCatching {
    val src = File(path)
    if (!src.exists()) return false
    val ext = src.extension.ifBlank { "flac" }
    val mime = when (ext.lowercase()) {
        "flac" -> "audio/flac"
        "m4a", "mp4" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        else -> "audio/*"
    }
    val displayName = "${sanitizeFileName("${song.singer} - ${song.title}")}.$ext"
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/sepotify")
        }
        val uri = context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: return false
        context.contentResolver.openOutputStream(uri)?.use { out ->
            src.inputStream().use { it.copyTo(out) }
        } ?: return false
    } else {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "sepotify",
        ).apply { mkdirs() }
        src.inputStream().use { input -> File(dir, displayName).outputStream().use { input.copyTo(it) } }
    }
    true
}.getOrDefault(false)
