package com.music.spotui.data.preferences

import android.content.Context
import com.music.spotui.data.entity.SongsModel
import org.json.JSONArray
import org.json.JSONObject

// Persists the last playing track + position + playback context + queue so a fresh app
// launch can fully restore the user's session seamlessly.

private const val PREF = "PlaybackState"
private const val KEY_SONG = "song"
private const val KEY_POSITION = "positionMs"
private const val KEY_CONTEXT_NAME = "contextName"
private const val KEY_SONG_INDEX = "songIndex"
private const val KEY_QUEUE = "queue"

data class PlaybackSession(
    val currentSong: SongsModel,
    val positionMs: Long,
    val contextName: String,
    val songIndex: Int,
    val queue: List<SongsModel>,
)

private fun songToJson(song: SongsModel): JSONObject = JSONObject().apply {
    put("id", song.id)
    put("title", song.title)
    put("album", song.album)
    put("singer", song.singer)
    put("coverUri", song.coverUri)
    put("url", song.url)
    put("spotifyTrackId", song.spotifyTrackId)
    put("explicit", song.explicit)
    put("durationMs", song.durationMs)
}

private fun jsonToSong(o: JSONObject): SongsModel? {
    val title = o.optString("title")
    val url = o.optString("url")
    if (title.isBlank() || url.isBlank()) return null
    return SongsModel(
        id = o.optInt("id", -1),
        title = title,
        album = o.optString("album"),
        singer = o.optString("singer"),
        coverUri = o.optString("coverUri"),
        url = url,
        spotifyTrackId = o.optString("spotifyTrackId"),
        explicit = o.optBoolean("explicit", false),
        durationMs = o.optInt("durationMs", 0),
    )
}

/** Saves the full playback session (current track, context name, queue, index). */
fun saveLastPlaybackSession(
    context: Context,
    song: SongsModel,
    contextName: String = "",
    songIndex: Int = 0,
    queue: List<SongsModel> = emptyList(),
) {
    if (song.title.isBlank() || song.url.isBlank()) return
    val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    val prevId = runCatching { p.getString(KEY_SONG, null)?.let { JSONObject(it).optInt("id", -1) } }
        .getOrNull() ?: -1

    val songJson = songToJson(song)
    val queueArray = JSONArray()
    val limitedQueue = if (queue.size > 200) queue.take(200) else queue
    for (item in limitedQueue) {
        if (item.title.isNotBlank() && item.url.isNotBlank()) {
            queueArray.put(songToJson(item))
        }
    }

    p.edit().apply {
        putString(KEY_SONG, songJson.toString())
        putString(KEY_CONTEXT_NAME, contextName.ifBlank { song.album })
        putInt(KEY_SONG_INDEX, songIndex)
        putString(KEY_QUEUE, queueArray.toString())
        if (prevId != song.id) putLong(KEY_POSITION, 0L)
    }.apply()
}

/** Legacy overload for backward compatibility */
fun saveLastPlayback(context: Context, song: SongsModel) {
    saveLastPlaybackSession(context, song)
}

/** Updates just the position of the stored track (called periodically). */
fun saveLastPosition(context: Context, positionMs: Long) {
    if (positionMs <= 0) return
    context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        .edit().putLong(KEY_POSITION, positionMs).apply()
}

/** Loads the complete saved playback session (track, position, context name, queue). */
fun loadLastPlaybackSession(context: Context): PlaybackSession? {
    val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    val raw = p.getString(KEY_SONG, null) ?: return null
    return runCatching {
        val o = JSONObject(raw)
        val song = jsonToSong(o) ?: return null
        val positionMs = p.getLong(KEY_POSITION, 0L)
        val contextName = p.getString(KEY_CONTEXT_NAME, null)?.ifBlank { null } ?: song.album
        val songIndex = p.getInt(KEY_SONG_INDEX, 0)
        val queueRaw = p.getString(KEY_QUEUE, null)
        val queue = mutableListOf<SongsModel>()
        if (!queueRaw.isNullOrBlank()) {
            val arr = JSONArray(queueRaw)
            for (i in 0 until arr.length()) {
                val itemJson = arr.optJSONObject(i) ?: continue
                val item = jsonToSong(itemJson) ?: continue
                queue.add(item)
            }
        }
        PlaybackSession(
            currentSong = song,
            positionMs = positionMs,
            contextName = contextName,
            songIndex = songIndex,
            queue = queue,
        )
    }.getOrNull()
}

/** The last track and position, or null if nothing was ever played. */
fun loadLastPlayback(context: Context): Pair<SongsModel, Long>? {
    return loadLastPlaybackSession(context)?.let { it.currentSong to it.positionMs }
}
