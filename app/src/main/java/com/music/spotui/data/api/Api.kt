package com.music.spotui.data.api

import android.content.Context
import android.util.Log
import com.metrolist.spotify.Spotify
import com.metrolist.spotify.models.SpotifyAlbum
import com.metrolist.spotify.models.SpotifyArtist
import com.metrolist.spotify.models.SpotifyTrack
import com.music.spotui.data.cache.OfflineCache
import com.music.spotui.data.entity.AlbumsModel
import com.music.spotui.data.entity.PodcastModel
import com.music.spotui.data.entity.ArtistOverviewModel
import com.music.spotui.data.entity.ArtistTrackUi
import com.metrolist.spotify.models.SpotifyHomeFeedItem
import com.music.spotui.data.entity.ArtistsModel
import com.music.spotui.data.entity.HomeFeedModel
import com.music.spotui.data.entity.HomeItem
import com.music.spotui.data.entity.HomeSection
import com.music.spotui.data.entity.LibraryEntry
import com.music.spotui.data.entity.SearchResults
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.network.NetworkMonitor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject

/**
 * Spotify-backed data layer with comprehensive Offline-First architecture.
 * Ensures instant startup, cached offline browsing, and seamless zero-delay local playback.
 */
class Api @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private fun stableId(key: String): Int = key.hashCode() and 0x7fffffff

    companion object HomeCache {
        const val LIKED_SONGS_ID = "liked-songs"
        const val DOWNLOADS_ID = "downloaded-offline"

        @Volatile var albums: List<AlbumsModel>? = null
        @Volatile var artists: List<ArtistsModel>? = null
        @Volatile var home: HomeFeedModel? = null
        @Volatile var library: List<LibraryEntry>? = null

        fun clear() {
            albums = null; artists = null; home = null; library = null
        }
    }

    private fun SpotifyTrack.toSongModel(): SongsModel {
        val isEpisode = id.startsWith("episode:") || uri?.contains("episode") == true
        val cleanId = id.removePrefix("episode:")
        val singer = artists.joinToString(", ") { it.name }.ifBlank {
            if (isEpisode) album?.name.orEmpty().ifBlank { "Podcast" } else "Unknown Artist"
        }
        val cover = album?.images?.firstOrNull()?.url ?: ""
        val playUrl = if (isEpisode) {
            "episode:$cleanId|${name.take(60)} $singer"
        } else {
            com.music.spotui.di.SongPlayer.buildSpotifyPlayQuery(cleanId, name, singer)
        }
        return SongsModel(
            id = stableId(if (isEpisode) "episode:$cleanId" else "track:$cleanId"),
            title = name.take(128),
            album = album?.name ?: "",
            singer = singer,
            coverUri = cover,
            url = playUrl,
            spotifyTrackId = cleanId,
            explicit = explicit,
            durationMs = durationMs,
        )
    }

    private fun com.metrolist.spotify.models.SpotifyEpisode.toSongModel(): SongsModel {
        val showName = show?.name.orEmpty()
        val publisher = show?.publisher.orEmpty()
        val singer = if (showName.isNotBlank() && publisher.isNotBlank() && showName != publisher) {
            "$showName • $publisher"
        } else {
            showName.ifBlank { publisher.ifBlank { description?.take(80) ?: "Podcast" } }
        }
        val cover = images.firstOrNull()?.url ?: show?.images?.firstOrNull()?.url ?: ""
        return SongsModel(
            id = stableId("episode:$id"),
            title = name.take(128),
            album = releaseDate ?: showName,
            singer = singer,
            coverUri = cover,
            url = "episode:$id|${name.take(60)} $showName",
            spotifyTrackId = id,
            durationMs = durationMs,
        )
    }

    private fun SpotifyAlbum.toAlbumModel(): AlbumsModel = AlbumsModel(
        id = stableId("album:$id"),
        artists = artists.joinToString(", ") { it.name },
        coverUri = images.firstOrNull()?.url ?: "",
        name = name,
        time = releaseDate ?: "",
        type = albumType.orEmpty(),
    )

    private fun SpotifyArtist.toArtistModel(): ArtistsModel = ArtistsModel(
        name = name,
        coverUri = images.firstOrNull()?.url ?: "",
        id = id,
    )

    suspend fun getAlbums(): Flow<Response<List<AlbumsModel>>> = flow {
        HomeCache.albums?.let { emit(Response.Success(it)) } ?: emit(Response.Loading())
        if (!NetworkMonitor.isOnlineNow(context) || !SpotifyTokenProvider.ensureToken(context)) {
            if (HomeCache.albums == null) {
                // If offline and no cache, emit empty list without blocking error
                emit(Response.Success(emptyList()))
            }
            return@flow
        }
        Spotify.newReleases(limit = 20).fold(
            onSuccess = { resp ->
                val list = resp.albums?.items.orEmpty().map { it.toAlbumModel() }
                HomeCache.albums = list
                emit(Response.Success(list))
            },
            onFailure = {
                Log.e("Api", "getAlbums failed", it)
                if (HomeCache.albums == null) emit(Response.Success(emptyList()))
            },
        )
    }

    suspend fun getFollowedArtists(): List<ArtistsModel> {
        if (!NetworkMonitor.isOnlineNow(context) || !SpotifyTokenProvider.ensureToken(context)) return emptyList()
        return fetchAllPages { offset -> Spotify.myArtists(limit = 50, offset = offset) }
            .map { it.toArtistModel() }
            .also { if (it.isEmpty()) Log.d("Api", "getFollowedArtists: none") }
    }

    suspend fun getArtists(): Flow<Response<List<ArtistsModel>>> = flow {
        HomeCache.artists?.let { emit(Response.Success(it)) } ?: emit(Response.Loading())
        if (!NetworkMonitor.isOnlineNow(context) || !SpotifyTokenProvider.ensureToken(context)) {
            if (HomeCache.artists == null) emit(Response.Success(emptyList()))
            return@flow
        }
        // First try GraphQL myArtists (supported on all token types without REST 429 rate limiting)
        val myArtistsResult = Spotify.myArtists(limit = 20)
        if (myArtistsResult.isSuccess && myArtistsResult.getOrNull()?.items?.isNotEmpty() == true) {
            val list = myArtistsResult.getOrNull()!!.items.map { it.toArtistModel() }
            HomeCache.artists = list
            emit(Response.Success(list))
            return@flow
        }

        // Fallback: topArtists REST endpoint or extracting from home/newReleases
        Spotify.topArtists(limit = 20).fold(
            onSuccess = { paging ->
                val list = paging.items.map { it.toArtistModel() }
                HomeCache.artists = list
                emit(Response.Success(list))
            },
            onFailure = { err ->
                Log.w("Api", "getArtists fallback: ${err.message}")
                val fallbackArtists = HomeCache.home?.sections?.flatMap { it.items }
                    ?.filterIsInstance<HomeItem.Artist>()
                    ?.map { ArtistsModel(name = it.name, coverUri = it.imageUrl, id = it.id) }
                    ?.distinctBy { it.id.ifBlank { it.name } }
                    .orEmpty()

                if (fallbackArtists.isNotEmpty()) {
                    HomeCache.artists = fallbackArtists
                    emit(Response.Success(fallbackArtists))
                } else if (HomeCache.artists == null) {
                    emit(Response.Success(emptyList()))
                }
            },
        )
    }

    suspend fun getSongs(): Flow<Response<List<SongsModel>>> = flow {
        emit(Response.Loading())
        if (!NetworkMonitor.isOnlineNow(context) || !SpotifyTokenProvider.ensureToken(context)) {
            // Offline: emit all downloaded songs
            val downloaded = com.music.spotui.data.preferences.getDownloadedSongs(context)
            emit(Response.Success(downloaded))
            return@flow
        }

        // First try GraphQL likedSongs (fast, reliable, not subject to REST 429 rate limiting)
        val likedResult = Spotify.likedSongs(limit = 50)
        if (likedResult.isSuccess && likedResult.getOrNull()?.items?.isNotEmpty() == true) {
            val list = likedResult.getOrNull()!!.items.mapNotNull { it.track?.toSongModel() }
            if (list.isNotEmpty()) {
                emit(Response.Success(list))
                return@flow
            }
        }

        // Fallback: topTracks REST endpoint, or downloaded songs
        Spotify.topTracks(limit = 50).fold(
            onSuccess = { paging -> emit(Response.Success(paging.items.map { it.toSongModel() })) },
            onFailure = { err ->
                Log.w("Api", "getSongs: topTracks unavailable (${err.message}), serving downloaded/cached songs")
                val downloaded = com.music.spotui.data.preferences.getDownloadedSongs(context)
                emit(Response.Success(downloaded))
            },
        )
    }

    /**
     * Personalized Spotify home feed with Offline Cache support.
     */
    suspend fun getHomeFeed(): Flow<Response<HomeFeedModel>> = flow {
        val cached = HomeCache.home ?: OfflineCache.getHome(context)
        if (cached != null) {
            HomeCache.home = cached
            emit(Response.Success(cached))
        } else {
            emit(Response.Loading())
        }

        if (!NetworkMonitor.isOnlineNow(context) || !SpotifyTokenProvider.ensureToken(context)) {
            if (HomeCache.home == null) {
                // Synthesize an Offline Home Feed from downloads
                val downloadedSongs = com.music.spotui.data.preferences.getDownloadedSongs(context)
                val downloadedPlaylists = com.music.spotui.data.preferences.getDownloadedPlaylists(context)
                val items = mutableListOf<HomeItem>()
                items.add(HomeItem.Playlist(name = "Downloaded", imageUrl = "", subtitle = "Available offline", id = DOWNLOADS_ID))
                downloadedPlaylists.forEach { pl ->
                    items.add(HomeItem.Playlist(name = pl.name, imageUrl = pl.coverUri, subtitle = "${pl.downloadedTrackCount} downloaded songs", id = pl.id))
                }
                val offlineFeed = HomeFeedModel(
                    greeting = "Offline Mode",
                    sections = listOf(HomeSection(title = "Downloaded & Ready", items = items))
                )
                emit(Response.Success(offlineFeed))
            }
            return@flow
        }

        Spotify.home(sectionItemsLimit = 20).fold(
            onSuccess = { feed ->
                val sections = feed.sections.mapNotNull { section ->
                    val items = section.items
                        .distinctBy { it.uri }
                        .mapNotNull { it.toHomeItem() }
                        .distinctBy { it::class.simpleName + "|" + it.name.lowercase() }
                    if (items.isEmpty()) null
                    else HomeSection(title = section.title ?: "", items = items)
                }.distinctBy { it.title.lowercase().ifBlank { it.hashCode().toString() } }
                val model = HomeFeedModel(greeting = feed.greeting ?: "", sections = sections)
                HomeCache.home = model
                OfflineCache.saveHome(context, model)
                emit(Response.Success(model))
            },
            onFailure = {
                Log.e("Api", "getHomeFeed failed", it)
                if (HomeCache.home == null) {
                    val downloadedSongs = com.music.spotui.data.preferences.getDownloadedSongs(context)
                    val items = mutableListOf<HomeItem>()
                    items.add(HomeItem.Playlist(name = "Downloaded", imageUrl = "", subtitle = "Available offline", id = DOWNLOADS_ID))
                    emit(Response.Success(HomeFeedModel(greeting = "Offline Mode", sections = listOf(HomeSection("Downloaded Music", items)))))
                }
            },
        )
    }

    private fun SpotifyHomeFeedItem.toHomeItem(): HomeItem? = when (this) {
        is SpotifyHomeFeedItem.Album -> HomeItem.Album(
            name = name,
            imageUrl = imageUrl ?: "",
            subtitle = artists.joinToString(", ") { it.name }.ifBlank { "Album" },
            artists = artists.joinToString(", ") { it.name },
        )
        is SpotifyHomeFeedItem.Artist -> HomeItem.Artist(
            name = name,
            imageUrl = imageUrl ?: "",
            id = id,
        )
        is SpotifyHomeFeedItem.Playlist -> HomeItem.Playlist(
            name = name,
            imageUrl = imageUrl ?: "",
            subtitle = (madeForUsername ?: ownerName)?.let { "Playlist • $it" } ?: "Playlist",
            id = id,
        )
    }

    suspend fun searchTracks(query: String): Flow<Response<List<SongsModel>>> = flow {
        emit(Response.Loading())
        if (query.isBlank()) {
            emit(Response.Success(emptyList())); return@flow
        }
        if (!NetworkMonitor.isOnlineNow(context) || !SpotifyTokenProvider.ensureToken(context)) {
            // Offline search among downloaded tracks
            val norm = query.lowercase().trim()
            val matches = com.music.spotui.data.preferences.getDownloadedSongs(context)
                .filter { it.title.lowercase().contains(norm) || it.singer.lowercase().contains(norm) || it.album.lowercase().contains(norm) }
            emit(Response.Success(matches))
            return@flow
        }
        Spotify.search(query, types = listOf("track"), limit = 30).fold(
            onSuccess = { res -> emit(Response.Success(res.tracks?.items.orEmpty().map { it.toSongModel() })) },
            onFailure = { Log.e("Api", "searchTracks failed", it); emit(Response.Error(it.message ?: "error")) },
        )
    }

    suspend fun searchEverything(query: String): Flow<Response<SearchResults>> = flow {
        emit(Response.Loading())
        if (query.isBlank()) {
            emit(Response.Success(SearchResults())); return@flow
        }
        val norm = query.lowercase().trim()
        val downloadedMatches = com.music.spotui.data.preferences.getDownloadedSongs(context)
            .filter { it.title.lowercase().contains(norm) || it.singer.lowercase().contains(norm) || it.album.lowercase().contains(norm) }

        if (!NetworkMonitor.isOnlineNow(context) || !SpotifyTokenProvider.ensureToken(context)) {
            emit(Response.Success(SearchResults(songs = downloadedMatches)))
            return@flow
        }
        Spotify.search(query, types = listOf("track", "album", "artist"), limit = 20).fold(
            onSuccess = { res ->
                val podcasts = runCatching { Spotify.searchPodcasts(query, limit = 12).getOrNull() }.getOrNull()
                val apiSongs = res.tracks?.items.orEmpty().map { it.toSongModel() }
                val mergedSongs = (downloadedMatches + apiSongs).distinctBy { it.id }
                emit(Response.Success(SearchResults(
                    songs = mergedSongs,
                    albums = res.albums?.items.orEmpty().map { it.toAlbumModel() },
                    artists = res.artists?.items.orEmpty().map { it.toArtistModel() },
                    shows = podcasts?.shows?.items.orEmpty().map { p ->
                        PodcastModel(
                            id = p.id,
                            name = p.name,
                            publisher = p.publisher.orEmpty(),
                            coverUri = p.images.firstOrNull()?.url ?: "",
                        )
                    },
                )))
            },
            onFailure = { 
                Log.e("Api", "searchEverything failed", it)
                if (downloadedMatches.isNotEmpty()) {
                    emit(Response.Success(SearchResults(songs = downloadedMatches)))
                } else {
                    emit(Response.Error(it.message ?: "error")) 
                }
            },
        )
    }

    suspend fun getShowEpisodes(showId: String): Flow<Response<List<SongsModel>>> = flow {
        emit(Response.Loading())
        val cleanId = showId.removePrefix("show:")
        val cached = OfflineCache.getPlaylistSongs(context, showId) ?: OfflineCache.getPlaylistSongs(context, cleanId)
        val downloaded = com.music.spotui.data.preferences.getDownloadedSongsForPlaylist(context, cleanId)
        val initialList = if (!cached.isNullOrEmpty()) cached else downloaded
        if (initialList.isNotEmpty()) {
            emit(Response.Success(initialList))
        }

        if (!NetworkMonitor.isOnlineNow(context)) {
            if (initialList.isEmpty()) emit(Response.Success(emptyList()))
            return@flow
        }

        if (cleanId == "your-episodes" || cleanId == "episodes" || cleanId.contains("your-episodes", ignoreCase = true)) {
            if (!SpotifyTokenProvider.ensureToken(context)) { 
                if (initialList.isEmpty()) emit(Response.Error("Spotify not authenticated")) 
                return@flow 
            }
            Spotify.myEpisodes(limit = 50).fold(
                onSuccess = { paging ->
                    val songs = paging.items.mapNotNull { it.episode?.toSongModel() }
                    emit(Response.Success(songs))
                    OfflineCache.savePlaylistSongs(context, showId, songs)
                },
                onFailure = { 
                    Log.e("Api", "myEpisodes failed", it)
                    if (initialList.isEmpty()) emit(Response.Error(it.message ?: "error")) 
                },
            )
            return@flow
        }
        if (cleanId.isBlank()) { 
            if (initialList.isEmpty()) emit(Response.Success(emptyList()))
            return@flow 
        }
        if (!SpotifyTokenProvider.ensureToken(context)) { 
            if (initialList.isEmpty()) emit(Response.Error("Spotify not authenticated")) 
            return@flow 
        }
        Spotify.showEpisodes(cleanId, limit = 50).fold(
            onSuccess = { paging ->
                val songs = paging.items.map { ep -> ep.toSongModel() }
                emit(Response.Success(songs))
                OfflineCache.savePlaylistSongs(context, showId, songs)
            },
            onFailure = { 
                Log.e("Api", "getShowEpisodes failed", it)
                if (initialList.isEmpty()) emit(Response.Error(it.message ?: "error")) 
            },
        )
    }

    suspend fun getShow(showId: String): Flow<Response<PodcastModel>> = flow {
        emit(Response.Loading())
        val cleanId = showId.removePrefix("show:")
        if (cleanId == "your-episodes" || cleanId == "episodes" || cleanId.contains("your-episodes", ignoreCase = true)) {
            val episodes = if (SpotifyTokenProvider.ensureToken(context)) {
                Spotify.myEpisodes(limit = 1).getOrNull()?.items.orEmpty()
            } else emptyList()
            val cover = episodes.firstOrNull()?.episode?.images?.firstOrNull()?.url
                ?: "https://misc.scdn.co/your-episodes/your-episodes-640.png"
            emit(Response.Success(PodcastModel(
                id = "your-episodes",
                name = "Your Episodes",
                publisher = "Saved podcast episodes",
                coverUri = cover,
            )))
            return@flow
        }
        if (cleanId.isBlank()) { emit(Response.Error("missing show id")); return@flow }
        if (!SpotifyTokenProvider.ensureToken(context)) { emit(Response.Error("Spotify not authenticated")); return@flow }
        Spotify.show(cleanId).fold(
            onSuccess = { s ->
                emit(Response.Success(PodcastModel(
                    id = s.id,
                    name = s.name,
                    publisher = s.publisher.orEmpty(),
                    coverUri = s.images.firstOrNull()?.url ?: "",
                )))
            },
            onFailure = { Log.e("Api", "getShow failed", it); emit(Response.Error(it.message ?: "error")) },
        )
    }

    suspend fun getRecommendations(seedTrackIds: List<String>): Flow<Response<List<SongsModel>>> = flow {
        emit(Response.Loading())
        val validSeeds = seedTrackIds.filter { it.isNotBlank() }.take(5)
        if (validSeeds.isEmpty()) { emit(Response.Success(emptyList())); return@flow }
        if (!NetworkMonitor.isOnlineNow(context) || !SpotifyTokenProvider.ensureToken(context)) {
            emit(Response.Success(emptyList())); return@flow
        }
        val seedTracks = validSeeds.mapNotNull { id ->
            Spotify.track(id).getOrNull()
        }
        if (seedTracks.isEmpty()) { emit(Response.Success(emptyList())); return@flow }

        val tracks = buildList {
            seedTracks.forEach { seed ->
                addAll(artistRadio(seed))
            }
        }.distinctBy { it.id }.take(30)

        emit(Response.Success(tracks.map { it.toSongModel() }))
    }

    private suspend fun artistRadio(seedTrack: SpotifyTrack): List<SpotifyTrack> {
        val seedArtistId = seedTrack.artists.firstOrNull()?.id ?: return emptyList()
        val out = mutableListOf<SpotifyTrack>()
        val seen = mutableSetOf(seedTrack.id)
        fun add(tracks: List<SpotifyTrack>?, cap: Int) {
            tracks.orEmpty().asSequence()
                .filter { it.id.isNotEmpty() && seen.add(it.id) }
                .take(cap)
                .forEach { out.add(it) }
        }
        add(Spotify.artistTopTracks(seedArtistId).getOrNull()?.tracks, cap = 10)
        Spotify.artistRelatedArtists(seedArtistId).getOrNull().orEmpty().take(6).forEach { related ->
            related.id.takeIf { it.isNotEmpty() }?.let { rid ->
                add(Spotify.artistTopTracks(rid).getOrNull()?.tracks, cap = 5)
            }
        }
        return out.shuffled().take(30)
    }

    suspend fun getCategoryPlaylists(genre: String): Flow<Response<List<LibraryEntry>>> = flow {
        emit(Response.Loading())
        if (genre.isBlank()) { emit(Response.Success(emptyList())); return@flow }
        if (!SpotifyTokenProvider.ensureToken(context)) { emit(Response.Error("Spotify not authenticated")); return@flow }
        Spotify.search(genre, types = listOf("playlist"), limit = 24).fold(
            onSuccess = { res ->
                emit(Response.Success(res.playlists?.items.orEmpty().map { p ->
                    LibraryEntry(
                        spotifyId = p.id,
                        name = p.name,
                        subtitle = "Playlist" + (p.owner?.displayName?.let { " • $it" } ?: ""),
                        coverUri = p.images.firstOrNull()?.url ?: "",
                        isPlaylist = true,
                    )
                }))
            },
            onFailure = { Log.e("Api", "getCategoryPlaylists failed", it); emit(Response.Error(it.message ?: "error")) },
        )
    }

    suspend fun getArtistSongs(artistName: String): Flow<Response<List<SongsModel>>> = flow {
        emit(Response.Loading())
        if (artistName.isBlank()) { emit(Response.Success(emptyList())); return@flow }
        if (!SpotifyTokenProvider.ensureToken(context)) { emit(Response.Error("Spotify not authenticated")); return@flow }
        val artist = Spotify.search(artistName, types = listOf("artist"), limit = 1).getOrNull()
            ?.artists?.items?.firstOrNull()
        val artistId = artist?.id
        if (artistId.isNullOrBlank()) { emit(Response.Success(emptyList())); return@flow }
        val artistCover = artist.images.firstOrNull()?.url ?: ""
        Spotify.artistTopTracks(artistId).fold(
            onSuccess = { resp ->
                emit(Response.Success(resp.tracks.map { track ->
                    val song = track.toSongModel()
                    if (song.coverUri.isBlank()) song.copy(coverUri = artistCover) else song
                }))
            },
            onFailure = { Log.e("Api", "getArtistSongs failed", it); emit(Response.Error(it.message ?: "error")) },
        )
    }

    suspend fun getArtistOverview(artistName: String, knownArtistId: String = ""): Flow<Response<ArtistOverviewModel>> = flow {
        emit(Response.Loading())
        if (artistName.isBlank() && knownArtistId.isBlank()) {
            emit(Response.Success(ArtistOverviewModel(name = artistName))); return@flow
        }
        if (!SpotifyTokenProvider.ensureToken(context)) {
            emit(Response.Error("Spotify not authenticated — set sp_dc cookie")); return@flow
        }
        val artist = if (knownArtistId.isBlank()) {
            val hits = Spotify.search(artistName, types = listOf("artist"), limit = 5).getOrNull()
                ?.artists?.items.orEmpty()
            hits.firstOrNull { it.name.equals(artistName, ignoreCase = true) } ?: hits.firstOrNull()
        } else {
            Spotify.artist(knownArtistId).getOrNull()
        }
        val resolvedId = artist?.id ?: knownArtistId
        if (resolvedId.isBlank()) {
            emit(Response.Success(ArtistOverviewModel(name = artistName))); return@flow
        }
        Spotify.artistOverview(resolvedId).fold(
            onSuccess = { o ->
                val heroImage = o.headerImages.firstOrNull()?.url?.takeIf { it.isNotBlank() }
                    ?: artist?.images?.firstOrNull()?.url.orEmpty()
                val avatar = o.avatarImages.firstOrNull()?.url?.takeIf { it.isNotBlank() }
                    ?: artist?.images?.firstOrNull()?.url.orEmpty()
                emit(Response.Success(ArtistOverviewModel(
                    id = o.id,
                    name = o.name,
                    verified = o.verified,
                    headerImage = heroImage,
                    avatarImage = avatar,
                    monthlyListeners = o.monthlyListeners,
                    biography = o.biography,
                    topTracks = o.topTracks.map { t ->
                        ArtistTrackUi(
                            song = t.track.toSongModel(),
                            playcount = t.playcount,
                        )
                    },
                    popularReleases = o.popularReleases.map { it.toAlbumModel() },
                    appearsOn = o.appearsOn.map { it.toAlbumModel() },
                    relatedArtists = o.relatedArtists.map { it.toArtistModel() },
                )))
            },
            onFailure = { Log.e("Api", "getArtistOverview failed", it); emit(Response.Error(it.message ?: "error")) },
        )
    }

    suspend fun getAlbumSongs(albumName: String, artist: String = ""): Flow<Response<List<SongsModel>>> = flow {
        emit(Response.Loading())
        if (albumName.isBlank()) { emit(Response.Success(emptyList())); return@flow }
        val albumKey = "album_${albumName.lowercase().trim()}"
        val downloaded = com.music.spotui.data.preferences.getDownloadedSongsForAlbum(context, albumName)
        val cachedForAlbum = OfflineCache.getPlaylistSongs(context, albumKey)

        if (!NetworkMonitor.isOnlineNow(context) || !SpotifyTokenProvider.ensureToken(context)) {
            // Check downloaded tracks for this album or cached tracks
            if (cachedForAlbum != null && cachedForAlbum.isNotEmpty()) {
                emit(Response.Success(cachedForAlbum))
            } else if (downloaded.isNotEmpty()) {
                emit(Response.Success(downloaded))
            } else {
                emit(Response.Success(emptyList()))
            }
            return@flow
        }
        val candidates = Spotify.search(
            if (artist.isBlank()) albumName else "$albumName $artist",
            types = listOf("album"),
            limit = 10,
        ).getOrNull()?.albums?.items.orEmpty()
        val albumId = pickAlbum(candidates, albumName, artist)?.id
        if (albumId.isNullOrBlank()) {
            if (cachedForAlbum != null && cachedForAlbum.isNotEmpty()) {
                emit(Response.Success(cachedForAlbum))
            } else if (downloaded.isNotEmpty()) {
                emit(Response.Success(downloaded))
            } else {
                emit(Response.Success(emptyList()))
            }
            return@flow
        }
        Spotify.album(albumId).fold(
            onSuccess = { full ->
                val albumCover = full.images.firstOrNull()?.url.orEmpty()
                val albumArtist = full.artists.joinToString(", ") { it.name }
                val tracks = full.tracks?.items.orEmpty().map { track ->
                    val song = track.toSongModel()
                    song.copy(
                        coverUri = if (song.coverUri.isBlank()) albumCover else song.coverUri,
                        album = if (song.album.isBlank()) full.name else song.album,
                        singer = if (song.singer.isBlank()) albumArtist else song.singer,
                    )
                }
                OfflineCache.savePlaylistSongs(context, albumKey, tracks)
                emit(Response.Success(tracks))
            },
            onFailure = {
                Log.e("Api", "getAlbumSongs failed", it)
                if (cachedForAlbum != null && cachedForAlbum.isNotEmpty()) emit(Response.Success(cachedForAlbum))
                else if (downloaded.isNotEmpty()) emit(Response.Success(downloaded))
                else emit(Response.Success(emptyList()))
            },
        )
    }

    /**
     * Loads the track list for a playlist. Fully offline-aware.
     */
    suspend fun getPlaylistSongs(playlistId: String): Flow<Response<List<SongsModel>>> = flow {
        emit(Response.Loading())
        if (playlistId.isBlank()) { emit(Response.Success(emptyList())); return@flow }

        // Sentinel Downloads playlist
        if (playlistId == DOWNLOADS_ID) {
            val offlineSongs = com.music.spotui.data.preferences.getDownloadedSongs(context)
            emit(Response.Success(offlineSongs))
            return@flow
        }

        // Sentinel Liked Songs playlist
        if (playlistId == LIKED_SONGS_ID) {
            if (!NetworkMonitor.isOnlineNow(context) || !SpotifyTokenProvider.ensureToken(context)) {
                val likedLocal = com.music.spotui.data.preferences.getDownloadedSongs(context)
                emit(Response.Success(likedLocal))
                return@flow
            }
        }

        // Custom or saved playlists (Daily Mixes, liked Spotify playlists, custom playlists)
        if (com.music.spotui.data.preferences.isCustomPlaylist(context, playlistId)) {
            val customSongs = com.music.spotui.data.preferences.getCustomPlaylistSongs(context, playlistId)
            emit(Response.Success(customSongs))
            return@flow
        }

        // Check if we have offline songs for this playlist
        val offlineForPlaylist = com.music.spotui.data.preferences.getDownloadedSongsForPlaylist(context, playlistId)
        val cachedForPlaylist = OfflineCache.getPlaylistSongs(context, playlistId)

        if (!cachedForPlaylist.isNullOrEmpty()) {
            emit(Response.Success(cachedForPlaylist))
        } else if (offlineForPlaylist.isNotEmpty()) {
            emit(Response.Success(offlineForPlaylist))
        }

        if (!NetworkMonitor.isOnlineNow(context) || !SpotifyTokenProvider.ensureToken(context)) {
            if (cachedForPlaylist.isNullOrEmpty() && offlineForPlaylist.isEmpty()) {
                emit(Response.Success(emptyList()))
            }
            return@flow
        }

        if (playlistId == "your-episodes" || playlistId == "episodes" || playlistId.contains("your-episodes", ignoreCase = true)) {
            if (!NetworkMonitor.isOnlineNow(context) || !SpotifyTokenProvider.ensureToken(context)) {
                if (cachedForPlaylist != null && cachedForPlaylist.isNotEmpty()) {
                    emit(Response.Success(cachedForPlaylist))
                } else {
                    emit(Response.Success(emptyList()))
                }
                return@flow
            }
            Spotify.myEpisodes(limit = 50).fold(
                onSuccess = { res ->
                    val songs = res.items.mapNotNull { it.episode?.toSongModel() }
                    emit(Response.Success(songs))
                    OfflineCache.savePlaylistSongs(context, playlistId, songs)
                },
                onFailure = {
                    Log.e("Api", "myEpisodes failed", it)
                    if (cachedForPlaylist != null) emit(Response.Success(cachedForPlaylist))
                    else emit(Response.Error(it.message ?: "Failed to load episodes"))
                }
            )
            return@flow
        }

        Spotify.playlistTracks(playlistId, limit = 100).fold(
            onSuccess = { first ->
                val songs = first.items.mapNotNull { it.track?.toSongModel() }.toMutableList()
                emit(Response.Success(songs.toList()))
                var offset = first.items.size
                while (offset < first.total && first.items.isNotEmpty()) {
                    val page = Spotify.playlistTracks(playlistId, limit = 100, offset = offset).getOrNull() ?: break
                    if (page.items.isEmpty()) break
                    songs += page.items.mapNotNull { it.track?.toSongModel() }
                    offset += page.items.size
                    emit(Response.Success(songs.toList()))
                }
                OfflineCache.savePlaylistSongs(context, playlistId, songs)
            },
            onFailure = {
                Log.e("Api", "getPlaylistSongs failed for $playlistId, trying show fallback", it)
                Spotify.showEpisodes(playlistId.removePrefix("show:"), limit = 50).fold(
                    onSuccess = { paging ->
                        val songs = paging.items.map { ep -> ep.toSongModel() }
                        emit(Response.Success(songs))
                        OfflineCache.savePlaylistSongs(context, playlistId, songs)
                    },
                    onFailure = { _ ->
                        if (offlineForPlaylist.isNotEmpty()) emit(Response.Success(offlineForPlaylist))
                        else if (cachedForPlaylist != null) emit(Response.Success(cachedForPlaylist))
                        else emit(Response.Error(it.message ?: "error"))
                    }
                )
            },
        )
    }

    private suspend fun <T> fetchAllPages(
        fetch: suspend (offset: Int) -> kotlin.Result<com.metrolist.spotify.models.SpotifyPaging<T>>,
    ): List<T> {
        val first = fetch(0).getOrNull() ?: return emptyList()
        val items = first.items.toMutableList()
        var offset = first.items.size
        while (offset < first.total && first.items.isNotEmpty()) {
            val page = fetch(offset).getOrNull() ?: break
            if (page.items.isEmpty()) break
            items += page.items
            offset += page.items.size
        }
        return items
    }

    /**
     * "Your Library" with complete offline persistence and downloaded playlist indicators.
     */
    suspend fun getLibrary(): Flow<Response<List<LibraryEntry>>> = flow {
        val cached = HomeCache.library ?: OfflineCache.getLibrary(context)
        if (cached != null) {
            HomeCache.library = cached
            emit(Response.Success(cached))
        } else {
            emit(Response.Loading())
        }

        // Build base pinned shortcuts
        val liked = LibraryEntry(
            spotifyId = LIKED_SONGS_ID,
            name = "Liked Songs",
            subtitle = "Playlist • Liked songs",
            coverUri = "https://misc.scdn.co/liked-songs/liked-songs-640.png",
            isPlaylist = true,
        )
        val downloaded = LibraryEntry(
            spotifyId = DOWNLOADS_ID,
            name = "Downloaded",
            subtitle = "Available offline",
            coverUri = "",
            isPlaylist = true,
        )

        val localSavedAlbums = com.music.spotui.data.preferences.getSavedAlbums(context).map { a ->
            LibraryEntry(
                spotifyId = a.id.toString(),
                name = a.name,
                subtitle = "Album • " + a.artists,
                coverUri = a.coverUri,
                isPlaylist = false,
                artists = a.artists,
            )
        }

        val savedAlbumIds: Set<String> = (localSavedAlbums.map { it.spotifyId } + com.music.spotui.data.preferences.getSavedAlbums(context).map { it.id.toString() }).toSet()
        val savedAlbumNames: Set<String> = (localSavedAlbums.map { it.name.lowercase().trim() } + com.music.spotui.data.preferences.getSavedAlbums(context).map { it.name.lowercase().trim() }).toSet()

        val downloadedPlaylists = com.music.spotui.data.preferences.getDownloadedPlaylists(context)
            .filter { pl -> pl.id !in savedAlbumIds && pl.name.lowercase().trim() !in savedAlbumNames }
            .map { pl ->
                LibraryEntry(
                    spotifyId = pl.id,
                    name = pl.name,
                    subtitle = "Downloaded Playlist • ${pl.downloadedTrackCount} songs",
                    coverUri = pl.coverUri,
                    isPlaylist = true,
                )
            }

        val localSavedPlaylists = com.music.spotui.data.preferences.getSavedPlaylists(context).map { p ->
            LibraryEntry(
                spotifyId = p.id,
                name = p.name,
                subtitle = p.subtitle,
                coverUri = p.coverUri,
                isPlaylist = true,
            )
        }

        if (!NetworkMonitor.isOnlineNow(context) || !SpotifyTokenProvider.ensureToken(context)) {
            val baseList = (listOf(liked, downloaded) + downloadedPlaylists + localSavedPlaylists + localSavedAlbums).distinctBy { it.spotifyId }
            val existing = cached ?: emptyList()
            val merged = (baseList + existing).distinctBy { it.spotifyId }
            HomeCache.library = merged
            emit(Response.Success(merged))
            return@flow
        }

        val albums = fetchAllPages { offset -> Spotify.myAlbums(limit = 50, offset = offset) }.map { a ->
            LibraryEntry(
                spotifyId = a.id,
                name = a.name,
                subtitle = "Album • " + a.artists.joinToString(", ") { it.name },
                coverUri = a.images.firstOrNull()?.url ?: "",
                isPlaylist = false,
                artists = a.artists.joinToString(", ") { it.name },
            )
        }
        val playlists = fetchAllPages { offset -> Spotify.myPlaylists(limit = 50, offset = offset) }.map { p ->
            LibraryEntry(
                spotifyId = p.id,
                name = p.name,
                subtitle = "Playlist" + (p.owner?.displayName?.let { " • $it" } ?: ""),
                coverUri = p.images.firstOrNull()?.url ?: "",
                isPlaylist = true,
            )
        }

        val merged = (listOf(liked, downloaded) + downloadedPlaylists + localSavedPlaylists + localSavedAlbums + playlists + albums)
            .distinctBy { if (it.isPlaylist) "p:${it.spotifyId}" else "a:${it.name.lowercase()}" }
        HomeCache.library = merged
        OfflineCache.saveLibrary(context, merged)
        emit(Response.Success(merged))
    }

    suspend fun getCanvasUrl(trackId: String): String? {
        if (trackId.isBlank()) return null
        CanvasCache.map[trackId]?.let { return it.value }
        if (!NetworkMonitor.isOnlineNow(context) || !SpotifyTokenProvider.ensureToken(context)) return null
        val url = runCatching { Spotify.canvasUrl(trackId) }.getOrNull()
        CanvasCache.map[trackId] = CanvasCache.Entry(url)
        return url
    }

    private object CanvasCache {
        class Entry(val value: String?)
        val map = java.util.concurrent.ConcurrentHashMap<String, Entry>()
    }

    suspend fun getLikedSongs(): Flow<Response<List<SongsModel>>> = flow {
        emit(Response.Loading())
        if (!NetworkMonitor.isOnlineNow(context) || !SpotifyTokenProvider.ensureToken(context)) {
            val cached = OfflineCache.getPlaylistSongs(context, "liked_songs")
            if (!cached.isNullOrEmpty()) {
                emit(Response.Success(cached))
            } else {
                val offlineSongs = com.music.spotui.data.preferences.getDownloadedSongs(context)
                emit(Response.Success(offlineSongs))
            }
            return@flow
        }
        Spotify.likedSongs(limit = 50).fold(
            onSuccess = { first ->
                val models = first.items.map { it.track.toSongModel() }.toMutableList()
                models.forEach { com.music.spotui.data.preferences.addLikedSong(context, it) }
                emit(Response.Success(models.toList()))
                var offset = first.items.size
                while (offset < first.total && first.items.isNotEmpty()) {
                    val page = Spotify.likedSongs(limit = 50, offset = offset).getOrNull() ?: break
                    if (page.items.isEmpty()) break
                    val pageModels = page.items.map { it.track.toSongModel() }
                    pageModels.forEach { com.music.spotui.data.preferences.addLikedSong(context, it) }
                    models += pageModels
                    offset += page.items.size
                    emit(Response.Success(models.toList()))
                }
                OfflineCache.savePlaylistSongs(context, "liked_songs", models)
            },
            onFailure = {
                Log.e("Api", "getLikedSongs failed", it)
                val cached = OfflineCache.getPlaylistSongs(context, "liked_songs")
                if (!cached.isNullOrEmpty()) {
                    emit(Response.Success(cached))
                } else {
                    val offlineSongs = com.music.spotui.data.preferences.getDownloadedSongs(context)
                    emit(Response.Success(offlineSongs))
                }
            },
        )
    }

    suspend fun getAccount(): Flow<Response<com.music.spotui.data.entity.AccountModel>> = flow {
        emit(Response.Loading())
        if (!NetworkMonitor.isOnlineNow(context) || !SpotifyTokenProvider.ensureToken(context)) {
            emit(Response.Success(com.music.spotui.data.entity.AccountModel(name = "Sepotify User", email = "", imageUrl = "", plan = "Offline Mode")))
            return@flow
        }
        Spotify.me().fold(
            onSuccess = { u ->
                emit(Response.Success(com.music.spotui.data.entity.AccountModel(
                    name = u.displayName ?: u.id,
                    email = u.email ?: "",
                    imageUrl = u.images.firstOrNull()?.url ?: "",
                    plan = u.product?.replaceFirstChar { it.uppercase() } ?: "",
                )))
            },
            onFailure = {
                Log.e("Api", "getAccount failed", it)
                emit(Response.Success(com.music.spotui.data.entity.AccountModel(name = "Sepotify User", email = "", imageUrl = "", plan = "Offline Mode")))
            },
        )
    }

    suspend fun getPlaylist(playlistId: String): Flow<Response<AlbumsModel>> = flow {
        emit(Response.Loading())
        if (playlistId.isBlank()) { emit(Response.Error("missing playlist id")); return@flow }
        if (playlistId == DOWNLOADS_ID) {
            val downloadedSongs = com.music.spotui.data.preferences.getDownloadedSongs(context)
            emit(Response.Success(AlbumsModel(
                id = DOWNLOADS_ID.hashCode() and 0x7fffffff,
                artists = "Offline",
                coverUri = downloadedSongs.firstOrNull()?.coverUri ?: "",
                name = "Downloaded",
                time = "${downloadedSongs.size} tracks available offline",
            )))
            return@flow
        }

        if (playlistId == "your-episodes" || playlistId == "episodes" || playlistId.contains("your-episodes", ignoreCase = true)) {
            val episodes = if (NetworkMonitor.isOnlineNow(context) && SpotifyTokenProvider.ensureToken(context)) {
                Spotify.myEpisodes(limit = 1).getOrNull()?.items.orEmpty()
            } else emptyList()
            val cover = episodes.firstOrNull()?.episode?.images?.firstOrNull()?.url
                ?: "https://misc.scdn.co/your-episodes/your-episodes-640.png"
            emit(Response.Success(AlbumsModel(
                id = playlistId.hashCode() and 0x7fffffff,
                artists = "Spotify Podcasts",
                coverUri = cover,
                name = "Your Episodes",
                time = "Saved podcast episodes",
            )))
            return@flow
        }

        // Check saved / custom playlists
        val savedPl = com.music.spotui.data.preferences.getSavedPlaylists(context).firstOrNull { it.id == playlistId }
        if (savedPl != null && savedPl.isCustom) {
            val songs = com.music.spotui.data.preferences.getCustomPlaylistSongs(context, playlistId)
            emit(Response.Success(AlbumsModel(
                id = playlistId.hashCode() and 0x7fffffff,
                artists = "Custom Playlist",
                coverUri = savedPl.coverUri.ifBlank { songs.firstOrNull()?.coverUri ?: "" },
                name = savedPl.name,
                time = savedPl.description.ifBlank { "${songs.size} songs" },
            )))
            return@flow
        }
        val downloadedSongs = com.music.spotui.data.preferences.getDownloadedSongsForPlaylist(context, playlistId)
        val cachedPl = OfflineCache.getPlaylist(context, playlistId)
            ?: OfflineCache.getLibrary(context)?.firstOrNull { it.spotifyId == playlistId }?.let {
                AlbumsModel(
                    id = stableId("playlist:${it.spotifyId}"),
                    artists = it.subtitle,
                    coverUri = it.coverUri,
                    name = it.name,
                    time = it.subtitle,
                )
            }

        if (!NetworkMonitor.isOnlineNow(context) || !SpotifyTokenProvider.ensureToken(context)) {
            val model = cachedPl ?: AlbumsModel(
                id = playlistId.hashCode() and 0x7fffffff,
                artists = if (downloadedSongs.isNotEmpty()) "Downloaded Playlist" else "Offline Playlist",
                coverUri = downloadedSongs.firstOrNull()?.coverUri ?: "",
                name = if (downloadedSongs.isNotEmpty()) "Downloaded Playlist" else "Playlist",
                time = "${downloadedSongs.size} tracks available offline",
            )
            emit(Response.Success(model))
            return@flow
        }
        Spotify.playlist(playlistId).fold(
            onSuccess = { p ->
                val model = AlbumsModel(
                    id = stableId("playlist:${p.id}"),
                    artists = p.owner?.displayName ?: "",
                    coverUri = p.images.firstOrNull()?.url ?: "",
                    name = p.name,
                    time = stripHtml(p.description),
                )
                OfflineCache.savePlaylist(context, playlistId, model)
                emit(Response.Success(model))
            },
            onFailure = {
                Log.e("Api", "getPlaylist failed for $playlistId, trying show fallback", it)
                Spotify.show(playlistId.removePrefix("show:")).fold(
                    onSuccess = { s ->
                        val model = AlbumsModel(
                            id = stableId("show:${s.id}"),
                            artists = s.publisher.orEmpty().ifBlank { "Podcast" },
                            coverUri = s.images.firstOrNull()?.url ?: "",
                            name = s.name,
                            time = stripHtml(s.description.orEmpty()),
                        )
                        OfflineCache.savePlaylist(context, playlistId, model)
                        emit(Response.Success(model))
                    },
                    onFailure = { _ ->
                        val model = cachedPl ?: AlbumsModel(
                            id = playlistId.hashCode() and 0x7fffffff,
                            artists = if (downloadedSongs.isNotEmpty()) "Downloaded Playlist" else "",
                            coverUri = downloadedSongs.firstOrNull()?.coverUri ?: "",
                            name = "Playlist",
                            time = "${downloadedSongs.size} offline tracks",
                        )
                        emit(Response.Success(model))
                    }
                )
            },
        )
    }

    private fun stripHtml(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return androidx.core.text.HtmlCompat
            .fromHtml(raw, androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY)
            .toString()
            .trim()
    }

    private fun pickAlbum(
        candidates: List<SpotifyAlbum>,
        albumName: String,
        artist: String,
    ): SpotifyAlbum? {
        if (candidates.isEmpty()) return null
        if (artist.isBlank()) {
            return candidates.firstOrNull { it.name.equals(albumName, ignoreCase = true) }
                ?: candidates.first()
        }
        val wantArtists = artist.split(",").map { it.trim().lowercase() }.filter { it.isNotBlank() }
        fun artistMatches(a: SpotifyAlbum): Boolean {
            val names = a.artists.joinToString(" ") { it.name }.lowercase()
            return wantArtists.any { it.isNotBlank() && names.contains(it) }
        }
        val nameMatches = candidates.filter { it.name.equals(albumName, ignoreCase = true) }
        return nameMatches.firstOrNull { artistMatches(it) }
            ?: candidates.firstOrNull { artistMatches(it) }
            ?: nameMatches.firstOrNull()
            ?: candidates.first()
    }
}
