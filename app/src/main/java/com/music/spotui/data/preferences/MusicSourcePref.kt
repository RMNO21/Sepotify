package com.music.spotui.data.preferences

import android.content.Context

enum class MusicSource(val label: String, val subtitle: String) {
    YOUTUBE_MUSIC("YouTube Music", "Default • High-speed streaming, Opus audio up to 160 kbps"),
    DEEZER("Deezer", "Account required • 128 kbps (Free), 320 kbps (Premium), FLAC (HiFi)"),
}

private const val PREF_MUSIC_SOURCE = "music_source_pref"
private const val KEY_PRIMARY_SOURCE = "primary_music_source"

fun getPrimaryMusicSource(context: Context): MusicSource? {
    val sp = context.getSharedPreferences(PREF_MUSIC_SOURCE, Context.MODE_PRIVATE)
    val name = sp.getString(KEY_PRIMARY_SOURCE, null) ?: return null
    return runCatching { MusicSource.valueOf(name) }.getOrNull()
}

fun setPrimaryMusicSource(context: Context, source: MusicSource) {
    val sp = context.getSharedPreferences(PREF_MUSIC_SOURCE, Context.MODE_PRIVATE)
    sp.edit().putString(KEY_PRIMARY_SOURCE, source.name).apply()
}
