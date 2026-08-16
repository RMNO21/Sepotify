package com.music.spotui.data.cache

import android.content.Context
import com.music.spotui.data.entity.HomeFeedModel
import com.music.spotui.data.entity.HomeItem
import com.music.spotui.data.entity.HomeSection
import com.music.spotui.data.entity.LibraryEntry
import com.music.spotui.data.entity.SongsModel
import org.json.JSONArray
import org.json.JSONObject

object OfflineCache {
    private const val PREFS = "sepotify_offline_cache"
    private const val KEY_HOME = "cached_home"
    private const val KEY_LIBRARY = "cached_library"
    private const val KEY_PLAYLIST_PREFIX = "cached_pl_"
    private const val KEY_PLAYLIST_META_PREFIX = "cached_pl_meta_"
    private const val PREFS_BROWSE_TILES = "sepotify_browse_tiles"

    fun savePlaylist(context: Context, playlistId: String, playlist: com.music.spotui.data.entity.AlbumsModel) {
        if (playlistId.isBlank()) return
        runCatching {
            val json = JSONObject().apply {
                put("id", playlist.id)
                put("name", playlist.name)
                put("artists", playlist.artists)
                put("coverUri", playlist.coverUri)
                put("time", playlist.time)
                put("type", playlist.type)
            }.toString()
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString("$KEY_PLAYLIST_META_PREFIX$playlistId", json)
                .apply()
        }
    }

    fun getPlaylist(context: Context, playlistId: String): com.music.spotui.data.entity.AlbumsModel? = runCatching {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("$KEY_PLAYLIST_META_PREFIX$playlistId", null) ?: return null
        val obj = JSONObject(raw)
        com.music.spotui.data.entity.AlbumsModel(
            id = obj.optInt("id", playlistId.hashCode() and 0x7fffffff),
            name = obj.optString("name", "Playlist"),
            artists = obj.optString("artists", ""),
            coverUri = obj.optString("coverUri", ""),
            time = obj.optString("time", ""),
            type = obj.optString("type", ""),
        )
    }.getOrNull()

    fun saveBrowseTile(context: Context, genre: String, url: String) {
        if (genre.isBlank() || url.isBlank()) return
        runCatching {
            context.getSharedPreferences(PREFS_BROWSE_TILES, Context.MODE_PRIVATE)
                .edit()
                .putString(genre.lowercase().trim(), url)
                .apply()
        }
    }

    fun getBrowseTile(context: Context, genre: String): String? = runCatching {
        context.getSharedPreferences(PREFS_BROWSE_TILES, Context.MODE_PRIVATE)
            .getString(genre.lowercase().trim(), null)
    }.getOrNull()

    fun getAllBrowseTiles(context: Context): Map<String, String> = runCatching {
        context.getSharedPreferences(PREFS_BROWSE_TILES, Context.MODE_PRIVATE)
            .all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }
            .toMap()
    }.getOrDefault(emptyMap())

    fun saveHome(context: Context, feed: HomeFeedModel) {
        runCatching {
            val json = JSONObject().apply {
                put("greeting", feed.greeting)
                val sectionsArray = JSONArray()
                feed.sections.forEach { sec ->
                    val secObj = JSONObject().apply {
                        put("title", sec.title)
                        val itemsArray = JSONArray()
                        sec.items.forEach { item ->
                            val itemObj = JSONObject().apply {
                                put("type", item.javaClass.simpleName)
                                put("name", item.name)
                                put("imageUrl", item.imageUrl)
                                when (item) {
                                    is HomeItem.Album -> {
                                        put("subtitle", item.subtitle)
                                        put("artists", item.artists)
                                    }
                                    is HomeItem.Artist -> {
                                        put("id", item.id)
                                    }
                                    is HomeItem.Playlist -> {
                                        put("subtitle", item.subtitle)
                                        put("id", item.id)
                                    }
                                }
                            }
                            itemsArray.put(itemObj)
                        }
                        put("items", itemsArray)
                    }
                    sectionsArray.put(secObj)
                }
                put("sections", sectionsArray)
            }.toString()

            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_HOME, json)
                .apply()
        }
    }

    fun getHome(context: Context): HomeFeedModel? = runCatching {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_HOME, null) ?: return null
        val obj = JSONObject(raw)
        val greeting = obj.optString("greeting", "Good day")
        val sectionsArray = obj.getJSONArray("sections")
        val sections = mutableListOf<HomeSection>()

        for (i in 0 until sectionsArray.length()) {
            val secObj = sectionsArray.getJSONObject(i)
            val title = secObj.getString("title")
            val itemsArray = secObj.getJSONArray("items")
            val items = mutableListOf<HomeItem>()

            for (j in 0 until itemsArray.length()) {
                val itObj = itemsArray.getJSONObject(j)
                val type = itObj.optString("type")
                val name = itObj.optString("name")
                val imageUrl = itObj.optString("imageUrl")
                val subtitle = itObj.optString("subtitle")
                val id = itObj.optString("id")
                val artists = itObj.optString("artists")

                when (type) {
                    "Album" -> items.add(HomeItem.Album(name = name, imageUrl = imageUrl, subtitle = subtitle, artists = artists))
                    "Artist" -> items.add(HomeItem.Artist(name = name, imageUrl = imageUrl, id = id))
                    else -> items.add(HomeItem.Playlist(name = name, imageUrl = imageUrl, subtitle = subtitle, id = id))
                }
            }
            sections.add(HomeSection(title, items))
        }
        HomeFeedModel(greeting = greeting, sections = sections)
    }.getOrNull()

    fun saveLibrary(context: Context, entries: List<LibraryEntry>) {
        runCatching {
            val array = JSONArray()
            entries.forEach { entry ->
                array.put(JSONObject().apply {
                    put("spotifyId", entry.spotifyId)
                    put("name", entry.name)
                    put("subtitle", entry.subtitle)
                    put("coverUri", entry.coverUri)
                    put("isPlaylist", entry.isPlaylist)
                    put("artists", entry.artists)
                })
            }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LIBRARY, array.toString())
                .apply()
        }
    }

    fun getLibrary(context: Context): List<LibraryEntry>? = runCatching {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LIBRARY, null) ?: return null
        val array = JSONArray(raw)
        val list = mutableListOf<LibraryEntry>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            list.add(
                LibraryEntry(
                    spotifyId = obj.getString("spotifyId"),
                    name = obj.getString("name"),
                    subtitle = obj.optString("subtitle"),
                    coverUri = obj.optString("coverUri"),
                    isPlaylist = obj.optBoolean("isPlaylist", true),
                    artists = obj.optString("artists"),
                )
            )
        }
        list
    }.getOrNull()

    fun savePlaylistSongs(context: Context, playlistId: String, songs: List<SongsModel>) {
        if (playlistId.isBlank() || songs.isEmpty()) return
        runCatching {
            val array = JSONArray()
            songs.forEach { s ->
                array.put(JSONObject().apply {
                    put("id", s.id)
                    put("title", s.title)
                    put("album", s.album)
                    put("singer", s.singer)
                    put("coverUri", s.coverUri)
                    put("url", s.url)
                    put("spotifyTrackId", s.spotifyTrackId)
                    put("explicit", s.explicit)
                    put("durationMs", s.durationMs)
                })
            }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString("$KEY_PLAYLIST_PREFIX$playlistId", array.toString())
                .apply()
        }
    }

    fun getPlaylistSongs(context: Context, playlistId: String): List<SongsModel>? = runCatching {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("$KEY_PLAYLIST_PREFIX$playlistId", null) ?: return null
        val array = JSONArray(raw)
        val list = mutableListOf<SongsModel>()
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            list.add(
                SongsModel(
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
            )
        }
        list
    }.getOrNull()
}
