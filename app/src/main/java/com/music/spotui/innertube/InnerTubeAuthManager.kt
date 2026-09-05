package com.music.spotui.innertube

import android.content.Context
import android.util.Log
import com.metrolist.innertube.YouTube
import com.music.spotui.data.preferences.getYoutubeCookie
import com.music.spotui.data.preferences.setYoutubeCookie
import com.music.spotui.worker.InnerTubeAuthWorker
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * InnerTube Authentication & Header Lifecycle Manager.
 *
 * Periodically validates and refreshes InnerTube authentication headers,
 * visitor data (X-Goog-Visitor-Id), and session tokens.
 * Automatically recovers from expired cookies by resetting to anonymous
 * playback mode so playback never stalls at 0:00.
 */
object InnerTubeAuthManager {

    private const val TAG = "InnerTubeAuthManager"
    private const val PREFS = "innertube_auth_prefs"
    private const val KEY_LAST_VALIDATION = "last_auth_validation_time"
    private const val KEY_LAST_VISITOR_REFRESH = "last_visitor_refresh_time"

    // Validation interval: every 45 minutes in active app, or via WorkManager
    private val VALIDATION_INTERVAL_MS = TimeUnit.MINUTES.toMillis(45)
    private val VISITOR_REFRESH_INTERVAL_MS = TimeUnit.HOURS.toMillis(12)

    sealed interface AuthState {
        data object Initializing : AuthState
        data object Anonymous : AuthState
        data class Authenticated(val hasSapisid: Boolean) : AuthState
        data class Expired(val reason: String) : AuthState
        data object Refreshing : AuthState
    }

    private val _authState = MutableStateFlow<AuthState>(AuthState.Initializing)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshMutex = Mutex()
    private var periodicJob: Job? = null

    /**
     * Initializes authentication headers, validates saved cookies, and starts background routines.
     */
    fun init(context: Context) {
        val appContext = context.applicationContext
        managerScope.launch {
            validateAndRefreshAuth(appContext, force = true)
            startPeriodicRefresh(appContext)
            InnerTubeAuthWorker.schedule(appContext)
        }
    }

    /**
     * Starts the periodic in-memory refresh loop while the app is active.
     */
    fun startPeriodicRefresh(context: Context) {
        val appContext = context.applicationContext
        periodicJob?.cancel()
        periodicJob = managerScope.launch {
            Log.d(TAG, "InnerTube periodic authentication refresh loop started")
            while (isActive) {
                delay(VALIDATION_INTERVAL_MS)
                try {
                    validateAndRefreshAuth(appContext, force = false)
                } catch (e: Exception) {
                    Log.w(TAG, "Error in periodic auth refresh: ${e.message}")
                }
            }
        }
    }

    /**
     * Validates current YouTube cookies and refreshes headers & visitor data.
     * If cookies are expired or invalid, gracefully resets to anonymous mode
     * to avoid 401/403 stream failures.
     */
    suspend fun validateAndRefreshAuth(context: Context, force: Boolean = false): Boolean = refreshMutex.withLock {
        withContext(Dispatchers.IO) {
            val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val lastValidation = sp.getLong(KEY_LAST_VALIDATION, 0L)

            if (!force && (now - lastValidation) < VALIDATION_INTERVAL_MS) {
                Log.d(TAG, "Auth headers validated recently; skipping redundant check")
                return@withContext true
            }

            _authState.value = AuthState.Refreshing
            val storedCookie = getYoutubeCookie(context).trim()

            // 1. Validate Visitor Data
            refreshVisitorDataInternal(context, force)

            // 2. Validate Cookie if present
            if (storedCookie.isNotBlank()) {
                val hasSapisid = storedCookie.contains("SAPISID")
                if (!hasSapisid) {
                    Log.w(TAG, "Stored YouTube cookie lacks SAPISID; clearing invalid cookie")
                    handleExpiredCookie(context, "Missing SAPISID in cookie")
                    return@withContext false
                }

                YouTube.cookie = storedCookie
                val isValid = runCatching {
                    YouTube.validateSession().getOrDefault(false)
                }.getOrDefault(false)

                if (isValid) {
                    Log.d(TAG, "YouTube authentication cookie successfully validated")
                    sp.edit().putLong(KEY_LAST_VALIDATION, now).apply()
                    _authState.value = AuthState.Authenticated(hasSapisid = true)
                    return@withContext true
                } else {
                    Log.w(TAG, "YouTube cookie validation failed (expired/revoked). Reverting to anonymous mode.")
                    handleExpiredCookie(context, "Session expired or rejected by YouTube")
                    return@withContext false
                }
            } else {
                // Anonymous mode
                YouTube.cookie = null
                sp.edit().putLong(KEY_LAST_VALIDATION, now).apply()
                _authState.value = AuthState.Anonymous
                Log.d(TAG, "InnerTube operating in validated anonymous mode")
                return@withContext true
            }
        }
    }

    /**
     * Invoked when an InnerTube request receives a 401/403 or authentication failure during streaming.
     * Immediately purges stale tokens and switches to clean anonymous mode.
     */
    suspend fun handleAuthFailure(context: Context) = withContext(Dispatchers.IO) {
        Log.w(TAG, "Handling InnerTube authentication failure: resetting credentials to anonymous mode")
        handleExpiredCookie(context, "Request returned 401/403")
        refreshVisitorDataInternal(context, force = true)
    }

    /**
     * Saves and validates a new user-supplied YouTube cookie.
     */
    suspend fun setCookie(context: Context, cookie: String): Boolean = withContext(Dispatchers.IO) {
        val trimmed = cookie.trim()
        setYoutubeCookie(context, trimmed)
        validateAndRefreshAuth(context, force = true)
    }

    /**
     * Clears stored cookie and restores clean anonymous mode.
     */
    suspend fun clearCookie(context: Context) = withContext(Dispatchers.IO) {
        setYoutubeCookie(context, "")
        YouTube.cookie = null
        _authState.value = AuthState.Anonymous
        refreshVisitorDataInternal(context, force = true)
    }

    private suspend fun handleExpiredCookie(context: Context, reason: String) {
        setYoutubeCookie(context, "")
        YouTube.cookie = null
        _authState.value = AuthState.Expired(reason)
        Log.i(TAG, "Cleared expired YouTube cookie ($reason); anonymous streaming active")
    }

    private suspend fun refreshVisitorDataInternal(context: Context, force: Boolean): String? {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val lastRefresh = sp.getLong(KEY_LAST_VISITOR_REFRESH, 0L)

        if (!force && YouTube.visitorData != null && (now - lastRefresh) < VISITOR_REFRESH_INTERVAL_MS) {
            return YouTube.visitorData
        }

        return try {
            val freshVisitor = YouTube.visitorData().getOrNull()
            if (!freshVisitor.isNullOrBlank()) {
                YouTube.visitorData = freshVisitor
                sp.edit()
                    .putString("visitor_data", freshVisitor)
                    .putLong(KEY_LAST_VISITOR_REFRESH, now)
                    .apply()
                Log.d(TAG, "Refreshed InnerTube Visitor ID: ${freshVisitor.take(12)}...")
                freshVisitor
            } else {
                YouTube.visitorData
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to refresh visitor data: ${e.message}")
            YouTube.visitorData
        }
    }
}
