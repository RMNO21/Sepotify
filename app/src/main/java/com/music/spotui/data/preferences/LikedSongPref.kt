package com.music.spotui.data.preferences

import android.content.Context
import com.music.spotui.data.entity.SongsModel

private const val PREF_LIKED_SONGS = "LikedSongs"

fun addLikedSongId(context: Context, songId: String) {
    if (songId.isBlank()) return
    val sharedPreferences = context.getSharedPreferences(PREF_LIKED_SONGS, Context.MODE_PRIVATE)
    sharedPreferences.edit().putString(songId, songId).apply()
}

fun addLikedSong(context: Context, song: SongsModel) {
    val sp = context.getSharedPreferences(PREF_LIKED_SONGS, Context.MODE_PRIVATE)
    val editor = sp.edit()
    if (song.id != 0) {
        editor.putString(song.id.toString(), song.id.toString())
    }
    if (song.spotifyTrackId.isNotBlank()) {
        editor.putString(song.spotifyTrackId, song.spotifyTrackId)
    }
    editor.apply()
}

fun removeLikedSongId(context: Context, songId: String) {
    if (songId.isBlank()) return
    val sharedPreferences = context.getSharedPreferences(PREF_LIKED_SONGS, Context.MODE_PRIVATE)
    sharedPreferences.edit().remove(songId).apply()
}

fun removeLikedSong(context: Context, song: SongsModel) {
    val sp = context.getSharedPreferences(PREF_LIKED_SONGS, Context.MODE_PRIVATE)
    val editor = sp.edit()
    if (song.id != 0) {
        editor.remove(song.id.toString())
    }
    if (song.spotifyTrackId.isNotBlank()) {
        editor.remove(song.spotifyTrackId)
    }
    editor.apply()
}

fun isSongLiked(context: Context, songId: String): Boolean {
    if (songId.isBlank()) return false
    val sharedPreferences = context.getSharedPreferences(PREF_LIKED_SONGS, Context.MODE_PRIVATE)
    return sharedPreferences.contains(songId)
}

fun isSongLiked(context: Context, song: SongsModel): Boolean {
    val sp = context.getSharedPreferences(PREF_LIKED_SONGS, Context.MODE_PRIVATE)
    if (song.id != 0 && sp.contains(song.id.toString())) return true
    if (song.spotifyTrackId.isNotBlank() && sp.contains(song.spotifyTrackId)) return true
    return false
}

fun getLikedSongIds(context: Context): Set<Int> {
    val sharedPreferences = context.getSharedPreferences(PREF_LIKED_SONGS, Context.MODE_PRIVATE)
    return sharedPreferences.all.keys.mapNotNull { it.toIntOrNull() }.toSet()
}

fun getLikedSpotifyTrackIds(context: Context): Set<String> {
    val sharedPreferences = context.getSharedPreferences(PREF_LIKED_SONGS, Context.MODE_PRIVATE)
    return sharedPreferences.all.keys.filter { it.isNotBlank() }.toSet()
}

fun setAllLikedTracks(context: Context, tracks: List<SongsModel>) {
    val sp = context.getSharedPreferences(PREF_LIKED_SONGS, Context.MODE_PRIVATE)
    val editor = sp.edit()
    editor.clear()
    tracks.forEach { song ->
        if (song.id != 0) editor.putString(song.id.toString(), song.id.toString())
        if (song.spotifyTrackId.isNotBlank()) editor.putString(song.spotifyTrackId, song.spotifyTrackId)
    }
    editor.apply()
}

fun getSongsByIds(songIds: Set<Int>, songs: List<SongsModel>): List<SongsModel> {
    return songs.filter { song -> song.id in songIds }
}
