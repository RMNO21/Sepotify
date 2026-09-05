package com.music.spotui.ui.repository

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import com.music.spotui.data.api.Api
import com.music.spotui.data.network.NetworkMonitor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppRepository @Inject constructor(
    private val api: Api,
    @param:ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "AppRepository"
    }

    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val _isOnline = MutableStateFlow(checkInitialConnectivity())
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    val isOffline: StateFlow<Boolean> = _isOnline
        .map { !it }
        .stateIn(repositoryScope, SharingStarted.Eagerly, !_isOnline.value)

    init {
        // Initialize NetworkMonitor with context
        NetworkMonitor.init(context)

        // Register ConnectivityManager observer
        registerConnectivityObserver()

        // Synchronize with NetworkMonitor reactive stream
        repositoryScope.launch {
            NetworkMonitor.isOnline.collect { online ->
                if (_isOnline.value != online) {
                    _isOnline.value = online
                }
            }
        }
    }

    private fun checkInitialConnectivity(): Boolean {
        return try {
            val cm = connectivityManager ?: return true
            val activeNetwork = cm.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ||
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
        } catch (e: Exception) {
            Log.w(TAG, "Error checking initial connectivity", e)
            true
        }
    }

    private fun registerConnectivityObserver() {
        val cm = connectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "ConnectivityManager onAvailable: $network")
                updateOnlineState(true)
                NetworkMonitor.triggerProbe(context, immediateOnline = true)
            }

            override fun onLost(network: Network) {
                Log.d(TAG, "ConnectivityManager onLost: $network")
                val stillConnected = checkInitialConnectivity()
                updateOnlineState(stillConnected)
                NetworkMonitor.triggerProbe(context)
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities
            ) {
                val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                val isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                Log.d(TAG, "ConnectivityManager onCapabilitiesChanged: internet=$hasInternet, validated=$isValidated")
                if (!hasInternet) {
                    updateOnlineState(false)
                } else if (isValidated) {
                    updateOnlineState(true)
                } else {
                    NetworkMonitor.triggerProbe(context)
                }
            }

            override fun onUnavailable() {
                Log.d(TAG, "ConnectivityManager onUnavailable")
                updateOnlineState(false)
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                cm.registerDefaultNetworkCallback(callback)
            } else {
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                cm.registerNetworkCallback(request, callback)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register ConnectivityManager default callback", e)
            try {
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                cm.registerNetworkCallback(request, callback)
            } catch (ex: Exception) {
                Log.e(TAG, "Failed to register fallback network callback", ex)
            }
        }
    }

    private fun updateOnlineState(online: Boolean) {
        if (_isOnline.value != online) {
            _isOnline.value = online
            if (online) {
                NetworkMonitor.reportNetworkSuccess(context)
            } else {
                NetworkMonitor.reportNetworkFailure()
            }
        }
    }

    suspend fun provideAlbums() = api.getAlbums()

    suspend fun provideHomeFeed() = api.getHomeFeed()

    suspend fun provideArtists() = api.getArtists()

    suspend fun provideSongs() = api.getSongs()

    suspend fun searchSongs(query: String) = api.searchTracks(query)

    suspend fun searchEverything(query: String) = api.searchEverything(query)

    suspend fun provideAlbumSongs(albumName: String, artist: String = "") = api.getAlbumSongs(albumName, artist)

    suspend fun provideArtistSongs(artistName: String) = api.getArtistSongs(artistName)

    suspend fun provideArtistOverview(artistName: String, artistId: String = "") = api.getArtistOverview(artistName, artistId)

    suspend fun providePlaylistSongs(playlistId: String) = api.getPlaylistSongs(playlistId)

    suspend fun providePlaylist(playlistId: String) = api.getPlaylist(playlistId)

    suspend fun provideShowEpisodes(showId: String) = api.getShowEpisodes(showId)

    suspend fun provideShow(showId: String) = api.getShow(showId)

    suspend fun provideLibrary() = api.getLibrary()

    suspend fun provideFollowedArtists() = api.getFollowedArtists()

    suspend fun provideCategoryPlaylists(genre: String) = api.getCategoryPlaylists(genre)

    suspend fun provideRecommendations(seedTrackIds: List<String>) = api.getRecommendations(seedTrackIds)

    suspend fun provideLikedSongs() = api.getLikedSongs()

    suspend fun provideAccount() = api.getAccount()

    suspend fun provideCanvasUrl(trackId: String) = api.getCanvasUrl(trackId)
}