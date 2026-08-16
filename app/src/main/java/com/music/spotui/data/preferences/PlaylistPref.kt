package com.music.spotui.data.preferences

import android.content.Context
import com.music.spotui.data.entity.SongsModel
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private const val PREF_PLAYLISTS = "SavedPlaylists_V1"
private const val KEY_SAVED_PLAYLISTS = "saved_playlists_json"
private const val PREF_CUSTOM_TRACKS = "CustomPlaylistTracks_V1"

data class SavedPlaylistModel(
    val id: String,
    val name: String,
    val coverUri: String = "",
    val subtitle: String = "Playlist",
    val description: String = "",
    val isCustom: Boolean = false,
    val addedAt: Long = System.currentTimeMillis()
)

private fun SavedPlaylistModel.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("name", name)
    put("coverUri", coverUri)
    put("subtitle", subtitle)
    put("description", description)
    put("isCustom", isCustom)
    put("addedAt", addedAt)
}

private fun JSONObject.toSavedPlaylist(): SavedPlaylistModel = SavedPlaylistModel(
    id = optString("id", ""),
    name = optString("name", "Playlist"),
    coverUri = optString("coverUri", ""),
    subtitle = optString("subtitle", "Playlist"),
    description = optString("description", ""),
    isCustom = optBoolean("isCustom", false),
    addedAt = optLong("addedAt", System.currentTimeMillis())
)

private fun SongsModel.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("title", title)
    put("singer", singer)
    put("coverUri", coverUri)
    put("url", url)
    put("album", album)
    put("spotifyTrackId", spotifyTrackId)
    put("explicit", explicit)
    put("durationMs", durationMs)
}

private fun JSONObject.toSongModel(): SongsModel = SongsModel(
    id = optInt("id", 0),
    title = optString("title", ""),
    album = optString("album", ""),
    singer = optString("singer", ""),
    coverUri = optString("coverUri", ""),
    url = optString("url", ""),
    spotifyTrackId = optString("spotifyTrackId", ""),
    explicit = optBoolean("explicit", false),
    durationMs = optInt("durationMs", 0)
)

/** Returns all saved / liked playlists and custom playlists. */
fun getSavedPlaylists(context: Context): List<SavedPlaylistModel> {
    return try {
        val sp = context.getSharedPreferences(PREF_PLAYLISTS, Context.MODE_PRIVATE)
        val raw = sp.getString(KEY_SAVED_PLAYLISTS, null) ?: return emptyList()
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            obj.toSavedPlaylist()
        }
    } catch (e: Exception) {
        emptyList()
    }
}

/** Saves or updates a playlist in the library (e.g. Daily Mix, Spotify playlist, custom playlist). */
fun saveLikedPlaylist(context: Context, playlist: SavedPlaylistModel) {
    if (playlist.id.isBlank() || playlist.name.isBlank()) return
    val current = getSavedPlaylists(context).toMutableList()
    current.removeAll { it.id == playlist.id }
    current.add(0, playlist)
    val arr = JSONArray().apply {
        current.forEach { put(it.toJson()) }
    }
    val sp = context.getSharedPreferences(PREF_PLAYLISTS, Context.MODE_PRIVATE)
    sp.edit().putString(KEY_SAVED_PLAYLISTS, arr.toString()).apply()
}

/** Removes a playlist from saved playlists in the library. */
fun removeLikedPlaylist(context: Context, playlistId: String) {
    if (playlistId.isBlank()) return
    val current = getSavedPlaylists(context).toMutableList()
    current.removeAll { it.id == playlistId }
    val arr = JSONArray().apply {
        current.forEach { put(it.toJson()) }
    }
    val sp = context.getSharedPreferences(PREF_PLAYLISTS, Context.MODE_PRIVATE)
    sp.edit().putString(KEY_SAVED_PLAYLISTS, arr.toString()).apply()
}

/** Checks whether a playlist is saved/liked in library. */
fun isPlaylistLiked(context: Context, playlistId: String): Boolean {
    if (playlistId.isBlank()) return false
    return getSavedPlaylists(context).any { it.id == playlistId }
}

/** Checks if a playlist is a user-created custom playlist. */
fun isCustomPlaylist(context: Context, playlistId: String): Boolean {
    if (playlistId.startsWith("custom_")) return true
    return getSavedPlaylists(context).any { it.id == playlistId && it.isCustom }
}

/** Creates a new custom playlist locally. */
fun createCustomPlaylist(context: Context, name: String, description: String = ""): SavedPlaylistModel {
    val id = "custom_" + UUID.randomUUID().toString().take(12)
    val playlist = SavedPlaylistModel(
        id = id,
        name = name.ifBlank { "My Playlist" },
        subtitle = "Custom Playlist",
        description = description,
        isCustom = true,
        addedAt = System.currentTimeMillis()
    )
    saveLikedPlaylist(context, playlist)
    return playlist
}

/** Updates metadata of an existing custom playlist. */
fun updateCustomPlaylist(context: Context, playlistId: String, newName: String, newDescription: String = "") {
    val current = getSavedPlaylists(context).toMutableList()
    val idx = current.indexOfFirst { it.id == playlistId }
    if (idx != -1) {
        val existing = current[idx]
        val updated = existing.copy(
            name = newName.ifBlank { existing.name },
            description = newDescription
        )
        current[idx] = updated
        val arr = JSONArray().apply {
            current.forEach { put(it.toJson()) }
        }
        val sp = context.getSharedPreferences(PREF_PLAYLISTS, Context.MODE_PRIVATE)
        sp.edit().putString(KEY_SAVED_PLAYLISTS, arr.toString()).apply()
    }
}

/** Deletes a custom playlist and its tracks. */
fun deleteCustomPlaylist(context: Context, playlistId: String) {
    removeLikedPlaylist(context, playlistId)
    val sp = context.getSharedPreferences(PREF_CUSTOM_TRACKS, Context.MODE_PRIVATE)
    sp.edit().remove("tracks_$playlistId").apply()
}

/** Alias for deleteCustomPlaylist */
fun removeCustomPlaylist(context: Context, playlistId: String) = deleteCustomPlaylist(context, playlistId)

/** Returns the list of songs in a custom playlist. */
fun getCustomPlaylistSongs(context: Context, playlistId: String): List<SongsModel> {
    if (playlistId.isBlank()) return emptyList()
    return try {
        val sp = context.getSharedPreferences(PREF_CUSTOM_TRACKS, Context.MODE_PRIVATE)
        val raw = sp.getString("tracks_$playlistId", null) ?: return emptyList()
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            obj.toSongModel()
        }
    } catch (e: Exception) {
        emptyList()
    }
}

/** Adds a song to a custom playlist. */
fun addSongToCustomPlaylist(context: Context, playlistId: String, song: SongsModel): Boolean {
    if (playlistId.isBlank() || song.title.isBlank()) return false
    val current = getCustomPlaylistSongs(context, playlistId).toMutableList()
    if (current.any { it.id == song.id || (it.title == song.title && it.singer == song.singer) }) {
        return false // already exists
    }
    current.add(song)
    val arr = JSONArray().apply {
        current.forEach { put(it.toJson()) }
    }
    val sp = context.getSharedPreferences(PREF_CUSTOM_TRACKS, Context.MODE_PRIVATE)
    sp.edit().putString("tracks_$playlistId", arr.toString()).apply()

    // Update playlist cover if empty
    if (song.coverUri.isNotBlank()) {
        val saved = getSavedPlaylists(context).firstOrNull { it.id == playlistId }
        if (saved != null && saved.coverUri.isBlank()) {
            saveLikedPlaylist(context, saved.copy(coverUri = song.coverUri))
        }
    }

    // Auto-download track if playlist has auto-download enabled
    if (isPlaylistAutoDownload(context, playlistId)) {
        val saved = getSavedPlaylists(context).firstOrNull { it.id == playlistId }
        checkAndAutoDownloadPlaylistNewTracks(
            context = context,
            playlistId = playlistId,
            playlistName = saved?.name ?: "Playlist",
            songs = listOf(song)
        )
    }
    return true
}

/** Removes a song from a custom playlist. */
fun removeSongFromCustomPlaylist(context: Context, playlistId: String, songId: String): Boolean {
    if (playlistId.isBlank() || songId.isBlank()) return false
    val current = getCustomPlaylistSongs(context, playlistId).toMutableList()
    val intId = songId.toIntOrNull()
    val removed = current.removeAll { it.id.toString() == songId || (intId != null && it.id == intId) || it.spotifyTrackId == songId }
    if (removed) {
        val arr = JSONArray().apply {
            current.forEach { put(it.toJson()) }
        }
        val sp = context.getSharedPreferences(PREF_CUSTOM_TRACKS, Context.MODE_PRIVATE)
        sp.edit().putString("tracks_$playlistId", arr.toString()).apply()

        // Update cover
        val saved = getSavedPlaylists(context).firstOrNull { it.id == playlistId }
        if (saved != null) {
            val newCover = current.firstOrNull()?.coverUri.orEmpty()
            saveLikedPlaylist(context, saved.copy(coverUri = newCover))
        }
    }
    return removed
}
