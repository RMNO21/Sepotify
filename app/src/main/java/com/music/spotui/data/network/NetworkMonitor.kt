package com.music.spotui.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.HttpURLConnection

/**
 * Monitors network and internet reachability in real-time.
 * Uses both system NetworkCallbacks and active socket reachability probes
 * to guarantee instantaneous and accurate online/offline detection (turning the status dot red when offline).
 */
object NetworkMonitor {

    private const val TAG = "NetworkMonitor"

    private val _isOnline = MutableStateFlow(true)
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var probeJob: Job? = null
    private var pollingJob: Job? = null

    @Volatile
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            initialized = true
        }

        val appContext = context.applicationContext
        val connectivityManager =
            appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

        // Immediate initial check based on system active network
        val systemOnline = checkSystemConnectivity(appContext)
        _isOnline.value = systemOnline

        if (connectivityManager == null) {
            _isOnline.value = true
            return
        }

        // Register system network callbacks
        registerNetworkCallbacks(appContext, connectivityManager)

        // Start background polling and immediate active socket reachability check
        startReachabilityMonitoring(appContext)
    }

    private fun registerNetworkCallbacks(
        appContext: Context,
        connectivityManager: ConnectivityManager
    ) {
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "Network available: $network")
                triggerProbe(appContext, immediateOnline = true)
            }

            override fun onLost(network: Network) {
                Log.d(TAG, "Network lost: $network")
                val isStillConnected = checkSystemConnectivity(appContext)
                if (!isStillConnected) {
                    _isOnline.value = false
                }
                triggerProbe(appContext)
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities
            ) {
                val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                val isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                Log.d(TAG, "Network capabilities changed: hasInternet=$hasInternet, isValidated=$isValidated")

                if (!hasInternet) {
                    _isOnline.value = false
                } else if (isValidated) {
                    updateOnlineState(true, appContext)
                } else {
                    triggerProbe(appContext)
                }
            }

            override fun onUnavailable() {
                Log.d(TAG, "Network unavailable")
                _isOnline.value = false
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                connectivityManager.registerDefaultNetworkCallback(networkCallback)
            } else {
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                connectivityManager.registerNetworkCallback(request, networkCallback)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register default network callback, falling back to request callback", e)
            try {
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                connectivityManager.registerNetworkCallback(request, networkCallback)
            } catch (ex: Exception) {
                Log.e(TAG, "Failed to register network callback fallback", ex)
            }
        }
    }

    private fun startReachabilityMonitoring(appContext: Context) {
        // Immediate active probe
        triggerProbe(appContext)

        // Periodic background poll
        pollingJob?.cancel()
        pollingJob = monitorScope.launch {
            while (isActive) {
                val delayTimeMs = if (_isOnline.value) 10_000L else 3_000L
                delay(delayTimeMs)

                val systemConnected = checkSystemConnectivity(appContext)
                if (!systemConnected) {
                    if (_isOnline.value) {
                        Log.d(TAG, "System connectivity check reported disconnected -> marking offline")
                        _isOnline.value = false
                    }
                } else {
                    val reachable = checkActualInternetAccess()
                    updateOnlineState(reachable, appContext)
                }
            }
        }
    }

    fun triggerProbe(appContext: Context, immediateOnline: Boolean = false) {
        if (immediateOnline) {
            _isOnline.value = true
        }
        probeJob?.cancel()
        probeJob = monitorScope.launch {
            val systemConnected = checkSystemConnectivity(appContext)
            if (!systemConnected) {
                _isOnline.value = false
                return@launch
            }
            val reachable = checkActualInternetAccess()
            updateOnlineState(reachable, appContext)
        }
    }

    private fun updateOnlineState(online: Boolean, appContext: Context) {
        val previousState = _isOnline.value
        _isOnline.value = online
        if (!previousState && online) {
            Log.d(TAG, "Network restored -> flushing sync queues")
            com.music.spotui.data.api.SpotifySync.flushPendingQueue(appContext)
        }
    }

    /**
     * Fast TCP socket ping to known public DNS IPs (8.8.8.8 and 1.1.1.1) on port 53.
     * Raw IP connection avoids DNS lookup delays, returning reachability status in < 100ms.
     */
    private suspend fun checkActualInternetAccess(): Boolean = withContext(Dispatchers.IO) {
        // 1. Primary probe: Google DNS (8.8.8.8:53)
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("8.8.8.8", 53), 1500)
                return@withContext true
            }
        } catch (_: Exception) { }

        // 2. Secondary probe: Cloudflare DNS (1.1.1.1:53)
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("1.1.1.1", 53), 1500)
                return@withContext true
            }
        } catch (_: Exception) { }

        // 3. Tertiary probe: HTTP 204 endpoint
        try {
            val url = URL("https://clients3.google.com/generate_204")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 1500
            conn.readTimeout = 1500
            conn.instanceFollowRedirects = false
            val code = conn.responseCode
            conn.disconnect()
            return@withContext (code == 204 || code == 200)
        } catch (_: Exception) { }

        return@withContext false
    }

    /**
     * Fast synchronous check based on system ConnectivityManager state.
     */
    private fun checkSystemConnectivity(context: Context): Boolean {
        val connectivityManager =
            context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false

        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false

        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * Synchronously checks if the device is currently online.
     */
    fun isOnlineNow(context: Context): Boolean {
        val systemConnected = checkSystemConnectivity(context)
        if (!systemConnected) {
            _isOnline.value = false
            return false
        }
        return _isOnline.value
    }

    /**
     * Call when a network call succeeds to immediately ensure online state.
     */
    fun reportNetworkSuccess(context: Context? = null) {
        if (!_isOnline.value) {
            _isOnline.value = true
            context?.let { com.music.spotui.data.api.SpotifySync.flushPendingQueue(it) }
        }
    }

    /**
     * Call when a network call fails due to host resolution / network down.
     */
    fun reportNetworkFailure() {
        _isOnline.value = false
    }
}
