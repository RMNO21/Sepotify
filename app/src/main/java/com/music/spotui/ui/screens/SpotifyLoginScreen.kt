package com.music.spotui.ui.screens

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import com.metrolist.spotify.Spotify
import com.metrolist.spotify.SpotifyAuth
import com.music.spotui.R
import com.music.spotui.data.api.SpotifySession
import com.music.spotui.ui.navigation.Routes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

private const val SPOTIFY_GREEN = 0xFF1ED760

/**
 * Spotify-style native login. Loads Spotify's login page in a modern
 * compatible WebView with anti-bot WebView header cleanup, automatically captures the `sp_dc`
 * session cookie upon sign in, and provides manual cookie entry and offline continue options.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SpotifyLoginScreen(navController: NavController) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var isProcessing by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("") }
    var hasError by remember { mutableStateOf(false) }
    var showManualCookieDialog by remember { mutableStateOf(false) }
    var manualCookieInput by remember { mutableStateOf("") }

    val pageReady = remember { AtomicBoolean(false) }
    val tokenFetchStarted = remember { AtomicBoolean(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    val navigateToHome: () -> Unit = {
        com.music.spotui.di.SpotifyWebPlayer.refreshLogin(context)
        navController.navigate(Routes.Home.route) {
            popUpTo(Routes.Login.route) { inclusive = true }
        }
    }

    val skipToHome: () -> Unit = {
        SpotifySession.setGuestMode(context, true)
        navController.navigate(Routes.Home.route) {
            popUpTo(Routes.Login.route) { inclusive = true }
        }
    }

    // Poll for the sp_dc cookie — it's set on .spotify.com the moment login succeeds
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            if (tokenFetchStarted.get()) continue
            val spDc = extractCookie("sp_dc")
            if (!spDc.isNullOrBlank() && tokenFetchStarted.compareAndSet(false, true)) {
                finishLogin(
                    webViewRef, context as Activity, scope,
                    setProcessing = { isProcessing = it },
                    setStatus = { statusMessage = it },
                    setError = { hasError = it },
                    tokenFetchStarted = tokenFetchStarted,
                    onSuccess = navigateToHome,
                )
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize().statusBarsPadding().padding(top = 48.dp),
            factory = { ctx ->
                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)

                WebView(ctx).apply {
                    webViewRef = this
                    cookieManager.setAcceptThirdPartyCookies(this, true)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    settings.javaScriptCanOpenWindowsAutomatically = true
                    settings.setSupportMultipleWindows(true)
                    settings.cacheMode = WebSettings.LOAD_DEFAULT

                    // Clean user agent to look like standard Chrome Mobile (remove 'wv' and 'Version/4.0' tokens that trigger Spotify bot blocking)
                    val rawUa = settings.userAgentString
                    val cleanUa = rawUa
                        .replace("; wv", "")
                        .replace("Version/4.0 ", "")
                    settings.userAgentString = if (cleanUa.contains("Chrome/")) cleanUa else
                        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

                    webChromeClient = object : WebChromeClient() {
                        override fun onCreateWindow(
                            view: WebView?,
                            isDialog: Boolean,
                            isUserGesture: Boolean,
                            resultMsg: android.os.Message?
                        ): Boolean {
                            val transport = resultMsg?.obj as? WebView.WebViewTransport
                            transport?.webView = view
                            resultMsg?.sendToTarget()
                            return true
                        }
                    }

                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            pageReady.set(false)
                        }
                        override fun onPageFinished(view: WebView?, url: String?) {
                            pageReady.set(true)
                            cookieManager.flush()
                        }
                    }
                    loadUrl(SpotifyAuth.LOGIN_URL)
                }
            },
        )

        // Top bar: title, refresh, manual token paste, alternate URL, or offline mode
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(52.dp)
                .background(Color(0xFF121212))
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(onClick = {
                val cm = CookieManager.getInstance()
                cm.removeAllCookies(null)
                cm.flush()
                webViewRef?.clearCache(true)
                webViewRef?.loadUrl(SpotifyAuth.LOGIN_URL)
            }) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Reset & Reload",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }

            Text(
                text = if (isProcessing) statusMessage.ifBlank { "Signing in…" } else "Spotify Sign In",
                color = if (hasError) Color(0xFFE22134) else Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
            )

            TextButton(onClick = { showManualCookieDialog = true }) {
                Text("Cookie", color = Color(SPOTIFY_GREEN), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }

            TextButton(onClick = {
                // Allow user to continue straight to the app without signing into Spotify
                skipToHome()
            }) {
                Text("Skip", color = Color(0xFFB3B3B3), fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }

        // Bottom helper bar for alternate login or bypass
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .background(Color(0xE6121212))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = {
                webViewRef?.loadUrl("https://open.spotify.com/")
            }) {
                Text("Try open.spotify.com", color = Color(SPOTIFY_GREEN), fontSize = 12.sp)
            }

            TextButton(onClick = {
                skipToHome()
            }) {
                Text("Continue as Guest / Offline →", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        // Manual sp_dc cookie dialog
        if (showManualCookieDialog) {
            AlertDialog(
                onDismissRequest = { showManualCookieDialog = false },
                title = { Text("Enter Spotify sp_dc Cookie", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
                text = {
                    Column {
                        Text(
                            "If the login page fails or uses external 2FA, you can paste your Spotify 'sp_dc' cookie here directly:",
                            color = Color(0xFFB3B3B3),
                            fontSize = 13.sp,
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = manualCookieInput,
                            onValueChange = { manualCookieInput = it },
                            placeholder = { Text("sp_dc cookie string…", color = Color.Gray, fontSize = 12.sp) },
                            singleLine = false,
                            maxLines = 4,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(SPOTIFY_GREEN),
                                unfocusedBorderColor = Color(0xFF444444),
                                focusedContainerColor = Color(0xFF1E1E1E),
                                unfocusedContainerColor = Color(0xFF1E1E1E),
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val cookie = manualCookieInput.trim()
                            if (cookie.isNotBlank()) {
                                showManualCookieDialog = false
                                finishLoginWithCookie(
                                    cookie,
                                    context as Activity,
                                    scope,
                                    setProcessing = { isProcessing = it },
                                    setStatus = { statusMessage = it },
                                    setError = { hasError = it },
                                    tokenFetchStarted = tokenFetchStarted,
                                    onSuccess = navigateToHome,
                                )
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(SPOTIFY_GREEN), contentColor = Color.Black),
                    ) {
                        Text("Save & Log In", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showManualCookieDialog = false }) {
                        Text("Cancel", color = Color.LightGray)
                    }
                },
                containerColor = Color(0xFF181818),
            )
        }
    }
}

private fun extractCookie(name: String): String? {
    val cookieManager = CookieManager.getInstance()
    val domains = listOf(
        "https://open.spotify.com",
        "https://accounts.spotify.com",
        "https://spotify.com",
        "https://.spotify.com",
        "https://accounts.spotify.com/en/login",
    )
    for (domain in domains) {
        val allCookies = cookieManager.getCookie(domain) ?: continue
        val found = allCookies.split(";")
            .mapNotNull {
                val parts = it.trim().split("=", limit = 2)
                if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
            }
            .firstOrNull { it.first == name && it.second.isNotBlank() }
            ?.second
        if (found != null) return found
    }
    return null
}

private fun finishLoginWithCookie(
    spDc: String,
    activity: Activity,
    scope: kotlinx.coroutines.CoroutineScope,
    setProcessing: (Boolean) -> Unit,
    setStatus: (String) -> Unit,
    setError: (Boolean) -> Unit,
    tokenFetchStarted: AtomicBoolean,
    onSuccess: () -> Unit,
) {
    setProcessing(true)
    setError(false)
    setStatus("Connecting…")

    scope.launch(Dispatchers.IO) {
        SpotifySession.setSpDc(activity, spDc)
        var lastError: Throwable? = null
        repeat(3) { attempt ->
            val result = SpotifyAuth.fetchAccessToken(spDc, "")
            result.onSuccess { token ->
                Spotify.accessToken = token.accessToken
                withContext(Dispatchers.Main) { setStatus("Success!") }
                delay(300)
                withContext(Dispatchers.Main) { onSuccess() }
                return@launch
            }.onFailure { e ->
                lastError = e
                Timber.e(e, "Spotify token fetch failed (attempt ${attempt + 1})")
                if (attempt < 2) delay(800)
            }
        }
        withContext(Dispatchers.Main) {
            setStatus("Login failed: ${lastError?.message ?: "unknown error"}")
            setError(true)
        }
        tokenFetchStarted.set(false)
    }
}

private fun finishLogin(
    view: WebView?,
    activity: Activity,
    scope: kotlinx.coroutines.CoroutineScope,
    setProcessing: (Boolean) -> Unit,
    setStatus: (String) -> Unit,
    setError: (Boolean) -> Unit,
    tokenFetchStarted: AtomicBoolean,
    onSuccess: () -> Unit,
) {
    val spDc = extractCookie("sp_dc")
    val spKey = extractCookie("sp_key") ?: ""
    if (spDc.isNullOrBlank()) {
        setProcessing(true)
        setError(true)
        setStatus("Couldn't read login cookie. Make sure you completed the Spotify login, then try again.")
        tokenFetchStarted.set(false)
        return
    }

    setProcessing(true)
    setError(false)
    setStatus("Connecting…")
    view?.stopLoading()
    view?.loadUrl("about:blank")

    scope.launch(Dispatchers.IO) {
        SpotifySession.setSpDc(activity, spDc)
        var lastError: Throwable? = null
        // Retry a couple times
        repeat(3) { attempt ->
            val result = SpotifyAuth.fetchAccessToken(spDc, spKey)
            result.onSuccess { token ->
                Spotify.accessToken = token.accessToken
                withContext(Dispatchers.Main) { setStatus("Success!") }
                delay(300)
                withContext(Dispatchers.Main) { onSuccess() }
                return@launch
            }.onFailure { e ->
                lastError = e
                Timber.e(e, "Spotify token fetch failed (attempt ${attempt + 1})")
                if (attempt < 2) delay(800)
            }
        }
        withContext(Dispatchers.Main) {
            setStatus("Login failed: ${lastError?.message ?: "unknown error"}")
            setError(true)
        }
        tokenFetchStarted.set(false)
    }
}

