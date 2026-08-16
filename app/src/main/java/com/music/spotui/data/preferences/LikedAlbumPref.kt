package com.music.spotui.data.preferences

import android.content.Context
import com.music.spotui.data.entity.AlbumsModel
import org.json.JSONArray
import org.json.JSONObject

private const val PREF_NAME_V2 = "LikedAlbums_V2"
private const val KEY_SAVED_ALBUMS = "saved_albums_json"

private fun AlbumsModel.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("name", name)
    put("artists", artists)
    put("coverUri", coverUri)
    put("time", time)
    put("type", type)
}

private fun JSONObject.toAlbumsModel(): AlbumsModel = AlbumsModel(
    id = optInt("id", -1),
    name = optString("name", ""),
    artists = optString("artists", ""),
    coverUri = optString("coverUri", ""),
    time = optString("time", ""),
    type = optString("type", "")
)

fun saveLikedAlbum(context: Context, album: AlbumsModel) {
    if (album.name.isBlank()) return
    val current = getSavedAlbums(context).toMutableList()
    current.removeAll {
        it.id == album.id || (it.name.equals(album.name, ignoreCase = true) &&
                (album.artists.isBlank() || it.artists.isBlank() || it.artists.equals(album.artists, ignoreCase = true)))
    }
    current.add(0, album)
    val arr = JSONArray().apply {
        current.forEach { put(it.toJson()) }
    }
    val sp = context.getSharedPreferences(PREF_NAME_V2, Context.MODE_PRIVATE)
    sp.edit().putString(KEY_SAVED_ALBUMS, arr.toString()).apply()

    // Also keep legacy ID in sync
    addLikedAlbumId(context, album.id.toString())
}

fun removeLikedAlbum(context: Context, albumId: String) {
    val current = getSavedAlbums(context).toMutableList()
    val idInt = albumId.toIntOrNull()
    current.removeAll { it.id.toString() == albumId || (idInt != null && it.id == idInt) }
    val arr = JSONArray().apply {
        current.forEach { put(it.toJson()) }
    }
    val sp = context.getSharedPreferences(PREF_NAME_V2, Context.MODE_PRIVATE)
    sp.edit().putString(KEY_SAVED_ALBUMS, arr.toString()).apply()

    // Also remove legacy ID
    removeLikedAlbumId(context, albumId)
}

fun getSavedAlbums(context: Context): List<AlbumsModel> {
    return try {
        val sp = context.getSharedPreferences(PREF_NAME_V2, Context.MODE_PRIVATE)
        val raw = sp.getString(KEY_SAVED_ALBUMS, null) ?: return emptyList()
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            obj.toAlbumsModel()
        }
    } catch (e: Exception) {
        emptyList()
    }
}

fun addLikedAlbumId(context: Context, albumId: String) {
    val sharedPreferences = context.getSharedPreferences("LikedAlbums", Context.MODE_PRIVATE)
    val editor = sharedPreferences.edit()
    editor.putString(albumId, albumId)
    editor.apply()
}

fun removeLikedAlbumId(context: Context, albumId: String) {
    val sharedPreferences = context.getSharedPreferences("LikedAlbums", Context.MODE_PRIVATE)
    val editor = sharedPreferences.edit()
    editor.remove(albumId)
    editor.apply()
}

fun isAlbumLiked(context: Context, albumId: String): Boolean {
    val sharedPreferences = context.getSharedPreferences("LikedAlbums", Context.MODE_PRIVATE)
    if (sharedPreferences.contains(albumId)) return true
    val idInt = albumId.toIntOrNull()
    return getSavedAlbums(context).any { it.id.toString() == albumId || (idInt != null && it.id == idInt) }
}

fun getLikedAlbumIds(context: Context): Set<Int> {
    val legacy = context.getSharedPreferences("LikedAlbums", Context.MODE_PRIVATE).all.keys.mapNotNull { it.toIntOrNull() }.toSet()
    val saved = getSavedAlbums(context).map { it.id }.toSet()
    return legacy + saved
}

fun getAlbumsByIds(albumIds: Set<Int>, albums: List<AlbumsModel>): List<AlbumsModel> {
    return albums.filter { album -> album.id in albumIds }
}

fun getAlbumsByIds(context: Context, albumIds: Set<Int>, albums: List<AlbumsModel>): List<AlbumsModel> {
    val saved = getSavedAlbums(context)
    val merged = (albums + saved).distinctBy { it.id }
    return merged.filter { album -> album.id in albumIds }
}
