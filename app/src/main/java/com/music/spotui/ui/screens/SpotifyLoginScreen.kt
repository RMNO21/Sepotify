package com.music.spotui.ui.screens

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
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
import com.music.spotui.di.SpotifyWebPlayer
import com.music.spotui.ui.navigation.Routes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

private const val SPOTIFY_GREEN = 0xFF1ED760
private const val DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

/**
 * Spotui exact Spotify Login Screen implementation
 * Opens Spotify authentication directly, detects session cookies (sp_dc),
 * verifies the access token, and navigates seamlessly to Home.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SpotifyLoginScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var webView by remember { mutableStateOf<WebView?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var progress by remember { mutableFloatStateOf(0.1f) }
    var isLoggingIn by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("") }
    var showManualTokenDialog by remember { mutableStateOf(false) }
    var manualTokenInput by remember { mutableStateOf("") }

    val handledLogin = remember { AtomicBoolean(false) }

    fun navigateNext() {
        SpotifyWebPlayer.refreshLogin(context)
        val hasDeezer = !com.music.spotui.data.preferences.getDeezerArl(context).isNullOrBlank()
        if (!hasDeezer) {
            navController.navigate("${Routes.DeezerLogin.route}?next=home") {
                popUpTo(Routes.Login.route) { inclusive = true }
            }
        } else {
            navController.navigate(Routes.Home.route) {
                popUpTo(Routes.Login.route) { inclusive = true }
            }
        }
    }

    fun handleSpotifyCookie(spDc: String) {
        val clean = spDc.trim()
        if (clean.isBlank()) return
        if (handledLogin.getAndSet(true)) return

        isLoggingIn = true
        statusText = "Authenticating with Spotify..."

        scope.launch(Dispatchers.IO) {
            SpotifySession.setSpDc(context, clean)
            SpotifySession.setGuestMode(context, false)

            var success = false
            var lastError: Throwable? = null

            for (attempt in 1..3) {
                val result = SpotifyAuth.fetchAccessToken(clean, "")
                result.onSuccess { token ->
                    Spotify.accessToken = token.accessToken
                    SpotifySession.setSpDc(context, clean)
                    success = true
                    withContext(Dispatchers.Main) {
                        statusText = "Connected! Verifying Deezer session..."
                        delay(250)
                        navigateNext()
                    }
                    return@launch
                }.onFailure { e ->
                    lastError = e
                    Timber.e(e, "Spotify Auth attempt $attempt failed")
                    if (attempt < 3) delay(400)
                }
            }

            if (!success) {
                withContext(Dispatchers.Main) {
                    handledLogin.set(false)
                    isLoggingIn = false
                    statusText = "Login error: ${lastError?.localizedMessage ?: "Invalid session"}"
                }
            }
        }
    }

    fun checkCookies(url: String? = null) {
        if (handledLogin.get()) return
        try {
            val cookieManager = CookieManager.getInstance()
            cookieManager.flush()
            val targetUrls = listOfNotNull(
                url,
                "https://accounts.spotify.com",
                "https://open.spotify.com",
                "https://spotify.com",
                "https://.spotify.com"
            )
            for (u in targetUrls) {
                val cookies = cookieManager.getCookie(u) ?: continue
                val parts = cookies.split(";")
                for (p in parts) {
                    val pair = p.trim().split("=", limit = 2)
                    if (pair.size == 2 && pair[0].trim() == "sp_dc" && pair[1].trim().isNotBlank()) {
                        handleSpotifyCookie(pair[1].trim())
                        return
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to check cookies")
        }
    }

    // Poller to ensure cookie capture even on dynamic SPA state changes
    LaunchedEffect(Unit) {
        while (!handledLogin.get()) {
            checkCookies()
            delay(800)
        }
    }

    BackHandler(enabled = webView?.canGoBack() == true) {
        webView?.goBack()
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                webView?.stopLoading()
                webView?.destroy()
                webView = null
            } catch (_: Exception) {}
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
        containerColor = Color(0xFF121212),
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color(SPOTIFY_GREEN).copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_sepotify_logo),
                                contentDescription = null,
                                tint = Color(SPOTIFY_GREEN),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Login to Spotify",
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                navigationIcon = {
                    if (webView?.canGoBack() == true) {
                        IconButton(onClick = { webView?.goBack() }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = Color.White
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = {
                        checkCookies()
                        webView?.reload()
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reload", tint = Color.White)
                    }

                    IconButton(onClick = { showManualTokenDialog = true }) {
                        Icon(Icons.Default.Lock, contentDescription = "Manual Token", tint = Color(SPOTIFY_GREEN))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF181818))
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Color(0xFF121212))
        ) {
            // Main Webview
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("spotify_login_webview"),
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            useWideViewPort = true
                            loadWithOverviewMode = true
                            setSupportZoom(true)
                            builtInZoomControls = true
                            displayZoomControls = false
                            cacheMode = WebSettings.LOAD_DEFAULT
                            userAgentString = DESKTOP_UA
                            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            setSupportMultipleWindows(true)
                            javaScriptCanOpenWindowsAutomatically = true
                        }

                        val cookieManager = CookieManager.getInstance()
                        cookieManager.setAcceptCookie(true)
                        cookieManager.setAcceptThirdPartyCookies(this, true)

                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                progress = (newProgress / 100f).coerceIn(0f, 1f)
                                isLoading = newProgress < 100
                            }
                        }

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                isLoading = true
                                checkCookies(url)
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                isLoading = false
                                checkCookies(url)
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                val u = request?.url?.toString()
                                checkCookies(u)
                                return false
                            }
                        }

                        webView = this
                        loadUrl(SpotifyAuth.LOGIN_URL)
                    }
                },
                update = {}
            )

            // Top linear progress bar
            if (isLoading) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.5.dp)
                        .align(Alignment.TopCenter),
                    color = Color(SPOTIFY_GREEN),
                    trackColor = Color(0xFF222222)
                )
            }

            // Authentication overlay banner when session is captured and verifying
            AnimatedVisibility(
                visible = isLoggingIn || statusText.isNotBlank(),
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
            ) {
                Surface(
                    color = Color(0xFF1E1E1E),
                    shape = RoundedCornerShape(12.dp),
                    shadowElevation = 8.dp,
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(SPOTIFY_GREEN).copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        if (isLoggingIn) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = Color(SPOTIFY_GREEN),
                                strokeWidth = 2.5.dp
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                        }
                        Text(
                            text = statusText.ifEmpty { "Verifying Spotify session..." },
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }

    // Manual sp_dc token dialog
    if (showManualTokenDialog) {
        AlertDialog(
            onDismissRequest = { showManualTokenDialog = false },
            containerColor = Color(0xFF222222),
            title = {
                Text(
                    text = "Manual sp_dc Cookie",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            },
            text = {
                Column {
                    Text(
                        text = "Paste your 'sp_dc' cookie value from Spotify Web to connect directly:",
                        color = Color(0xFFAAAAAA),
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = manualTokenInput,
                        onValueChange = { manualTokenInput = it },
                        placeholder = { Text("sp_dc token...", color = Color.Gray, fontSize = 12.sp) },
                        singleLine = false,
                        maxLines = 3,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("manual_token_input_dialog"),
                        shape = RoundedCornerShape(8.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(SPOTIFY_GREEN),
                            unfocusedBorderColor = Color(0xFF444444)
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (manualTokenInput.isNotBlank()) {
                            showManualTokenDialog = false
                            handleSpotifyCookie(manualTokenInput)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(SPOTIFY_GREEN),
                        contentColor = Color.Black
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Connect", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showManualTokenDialog = false }) {
                    Text("Cancel", color = Color(0xFFAAAAAA))
                }
            }
        )
    }
}
