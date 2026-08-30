package com.music.spotui.ui.screens

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Message
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalFocusManager
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
import com.music.spotui.ui.navigation.Routes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

private const val SPOTIFY_GREEN = 0xFF1ED760
private const val CHROME_MOBILE_UA =
    "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

/**
 * Clean, first-principles Spotify Login Screen modeled directly from Spotui.
 *
 * It uses a standard Android WebView configured with HTML5 DOM storage, third-party cookies,
 * Chrome Mobile User-Agent, and multi-window popup support. Once the user authenticates,
 * the `sp_dc` cookie is automatically captured by CookieManager and verified to complete login.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SpotifyLoginScreen(navController: NavController) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    // Session state
    var isSpotifyLoggedIn by remember { mutableStateOf(SpotifySession.isLoggedIn(context)) }
    var isProcessing by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("") }
    var hasError by remember { mutableStateOf(false) }

    // Manual token input state
    var showManualToken by remember { mutableStateOf(false) }
    var manualSpDc by remember { mutableStateOf(SpotifySession.spDc(context)) }

    // WebView loading & instance state
    var webProgress by remember { mutableFloatStateOf(0.1f) }
    var isWebLoading by remember { mutableStateOf(true) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    val tokenExtracted = remember { AtomicBoolean(false) }

    val navigateToHome: () -> Unit = {
        com.music.spotui.di.SpotifyWebPlayer.refreshLogin(context)
        navController.navigate(Routes.Home.route) {
            popUpTo(Routes.Login.route) { inclusive = true }
        }
    }

    // Process and verify Spotify session using sp_dc cookie
    fun executeSpotifyLogin(spDcValue: String) {
        val cleanCookie = spDcValue.trim()
        if (cleanCookie.isBlank()) return

        isProcessing = true
        hasError = false
        statusMessage = "Spotify session detected! Verifying account…"

        scope.launch(Dispatchers.IO) {
            SpotifySession.setSpDc(context, cleanCookie)
            SpotifySession.setGuestMode(context, false)

            var success = false
            var caughtError: Throwable? = null

            repeat(3) { attempt ->
                val result = SpotifyAuth.fetchAccessToken(cleanCookie, "")
                result.onSuccess { token ->
                    Spotify.accessToken = token.accessToken
                    SpotifySession.setSpDc(context, cleanCookie)
                    success = true
                    withContext(Dispatchers.Main) {
                        isSpotifyLoggedIn = true
                        statusMessage = "Spotify account verified! Entering Sepotify…"
                        isProcessing = false
                        com.music.spotui.di.SpotifyWebPlayer.refreshLogin(context)
                        delay(350)
                        navigateToHome()
                    }
                    return@launch
                }.onFailure { e ->
                    caughtError = e
                    Timber.e(e, "Spotify auth attempt $attempt failed")
                    if (attempt < 2) delay(500)
                }
            }

            if (!success) {
                withContext(Dispatchers.Main) {
                    isProcessing = false
                    hasError = true
                    statusMessage = "Verification failed: ${caughtError?.localizedMessage ?: "Invalid token"}"
                }
            }
        }
    }

    // Helper: Poll cookies from CookieManager
    fun checkAndExtractCookie(cookieManager: CookieManager) {
        if (tokenExtracted.get()) return
        val spDc = extractCookie(cookieManager, "sp_dc")
        if (!spDc.isNullOrBlank() && tokenExtracted.compareAndSet(false, true)) {
            manualSpDc = spDc
            executeSpotifyLogin(spDc)
        }
    }

    // Cleanup WebView when leaving
    DisposableEffect(Unit) {
        onDispose {
            try {
                webViewRef?.stopLoading()
                webViewRef = null
            } catch (_: Exception) {}
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0E))
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header: App Title & Branding
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(SPOTIFY_GREEN).copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_sepotify_logo),
                            contentDescription = "Sepotify Logo",
                            tint = Color(SPOTIFY_GREEN),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Sepotify",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (isSpotifyLoggedIn) "Session Active ✓" else "Log in to Spotify",
                            color = if (isSpotifyLoggedIn) Color(SPOTIFY_GREEN) else Color(0xFF9E9E9E),
                            fontSize = 12.sp
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = { showManualToken = !showManualToken },
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text(
                            text = if (showManualToken) "Hide Token" else "Manual Token",
                            color = Color(SPOTIFY_GREEN),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    IconButton(
                        onClick = {
                            try {
                                val cookieManager = CookieManager.getInstance()
                                cookieManager.removeAllCookies(null)
                                cookieManager.flush()
                                tokenExtracted.set(false)
                                webViewRef?.clearCache(true)
                                webViewRef?.loadUrl(SpotifyAuth.LOGIN_URL)
                                statusMessage = "Cookies reset. Reloading Spotify login…"
                            } catch (_: Exception) {}
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Reset",
                            tint = Color.LightGray,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Main Web Card Container
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF14141C)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Browser URL mini header
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF1C1C26))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(Color(SPOTIFY_GREEN))
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "accounts.spotify.com/en/login",
                                color = Color(0xFFC0C0C0),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        if (isProcessing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = Color(SPOTIFY_GREEN),
                                strokeWidth = 2.dp
                            )
                        }
                    }

                    // Progress indicator
                    if (isWebLoading) {
                        LinearProgressIndicator(
                            progress = { webProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.dp),
                            color = Color(SPOTIFY_GREEN),
                            trackColor = Color(0xFF1C1C26)
                        )
                    }

                    // Embedded Spotify Web View
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(500.dp)
                    ) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                val cookieManager = CookieManager.getInstance()
                                cookieManager.setAcceptCookie(true)

                                WebView(ctx).apply {
                                    webViewRef = this
                                    setLayerType(View.LAYER_TYPE_HARDWARE, null)
                                    cookieManager.setAcceptThirdPartyCookies(this, true)

                                    // Configure WebSettings baseline for Spotify SPA
                                    settings.apply {
                                        javaScriptEnabled = true
                                        domStorageEnabled = true
                                        databaseEnabled = true
                                        loadsImagesAutomatically = true
                                        loadWithOverviewMode = true
                                        useWideViewPort = true
                                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                        javaScriptCanOpenWindowsAutomatically = true
                                        setSupportMultipleWindows(true)
                                        setSupportZoom(false)
                                        builtInZoomControls = false
                                        displayZoomControls = false
                                        allowFileAccess = true
                                        allowContentAccess = true
                                        cacheMode = WebSettings.LOAD_DEFAULT
                                        userAgentString = CHROME_MOBILE_UA

                                        // Disable algorithmic dark mode inversion to avoid canvas collisions
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                            isAlgorithmicDarkeningAllowed = false
                                        }
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                            @Suppress("DEPRECATION")
                                            forceDark = WebSettings.FORCE_DARK_OFF
                                        }
                                    }

                                    webChromeClient = object : WebChromeClient() {
                                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                            webProgress = (newProgress.coerceIn(5, 100)) / 100f
                                            isWebLoading = newProgress < 100

                                            // Check cookies during progress updates
                                            cookieManager.flush()
                                            checkAndExtractCookie(cookieManager)
                                        }

                                        override fun onCreateWindow(
                                            view: WebView?,
                                            isDialog: Boolean,
                                            isUserGesture: Boolean,
                                            resultMsg: Message?
                                        ): Boolean {
                                            val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                                            val popupWebView = WebView(ctx).apply {
                                                settings.apply {
                                                    javaScriptEnabled = true
                                                    domStorageEnabled = true
                                                    databaseEnabled = true
                                                    userAgentString = CHROME_MOBILE_UA
                                                }
                                                cookieManager.setAcceptThirdPartyCookies(this, true)
                                                webViewClient = object : WebViewClient() {
                                                    override fun shouldOverrideUrlLoading(v: WebView?, req: WebResourceRequest?): Boolean {
                                                        req?.url?.toString()?.let { targetUrl ->
                                                            view?.loadUrl(targetUrl)
                                                        }
                                                        return true
                                                    }

                                                    override fun onPageFinished(v: WebView?, url: String?) {
                                                        super.onPageFinished(v, url)
                                                        cookieManager.flush()
                                                        checkAndExtractCookie(cookieManager)
                                                    }
                                                }
                                            }
                                            transport.webView = popupWebView
                                            resultMsg.sendToTarget()
                                            return true
                                        }
                                    }

                                    webViewClient = object : WebViewClient() {
                                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                            cookieManager.flush()
                                            checkAndExtractCookie(cookieManager)
                                            return false
                                        }

                                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                            super.onPageStarted(view, url, favicon)
                                            cookieManager.flush()
                                            checkAndExtractCookie(cookieManager)
                                        }

                                        override fun onPageFinished(view: WebView?, url: String?) {
                                            super.onPageFinished(view, url)
                                            isWebLoading = false
                                            cookieManager.flush()
                                            checkAndExtractCookie(cookieManager)
                                        }

                                        override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                                            super.doUpdateVisitedHistory(view, url, isReload)
                                            cookieManager.flush()
                                            checkAndExtractCookie(cookieManager)
                                        }

                                        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                                            super.onReceivedError(view, request, error)
                                            if (request?.isForMainFrame == true) {
                                                isWebLoading = false
                                            }
                                        }
                                    }

                                    loadUrl(SpotifyAuth.LOGIN_URL)
                                }
                            }
                        )
                    }
                }
            }

            // Optional Manual Token Accordion
            AnimatedVisibility(
                visible = showManualToken,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A24)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "Paste Session Cookie (sp_dc)",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = manualSpDc,
                            onValueChange = { manualSpDc = it },
                            placeholder = { Text("Paste 'sp_dc' cookie string…", color = Color(0xFF666666), fontSize = 12.sp) },
                            singleLine = false,
                            maxLines = 2,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("spotify_sp_dc_input"),
                            shape = RoundedCornerShape(8.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(SPOTIFY_GREEN),
                                unfocusedBorderColor = Color(0xFF33333E),
                                focusedContainerColor = Color(0xFF101016),
                                unfocusedContainerColor = Color(0xFF101016)
                            ),
                            trailingIcon = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (manualSpDc.isNotBlank()) {
                                        IconButton(onClick = { manualSpDc = "" }, modifier = Modifier.size(26.dp)) {
                                            Icon(Icons.Default.Close, contentDescription = "Clear", tint = Color.Gray, modifier = Modifier.size(14.dp))
                                        }
                                    }
                                    TextButton(
                                        onClick = {
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                            val text = clipboard?.primaryClip?.getItemAt(0)?.text?.toString()?.trim() ?: ""
                                            if (text.isNotBlank()) manualSpDc = text
                                        },
                                        modifier = Modifier.testTag("paste_spotify_sp_dc_button")
                                    ) {
                                        Text("Paste", color = Color(SPOTIFY_GREEN), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Button(
                            onClick = {
                                focusManager.clearFocus()
                                executeSpotifyLogin(manualSpDc)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(SPOTIFY_GREEN),
                                contentColor = Color.Black
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(40.dp)
                                .testTag("verify_manual_token_button"),
                            enabled = !isProcessing && manualSpDc.isNotBlank()
                        ) {
                            Text("Verify & Connect", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Status message feedback banner
            if (statusMessage.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (hasError) Color(0x33E57373) else Color(0x331ED760),
                            RoundedCornerShape(10.dp)
                        )
                        .padding(12.dp)
                ) {
                    Text(
                        text = statusMessage,
                        color = if (hasError) Color(0xFFFF8A80) else Color(SPOTIFY_GREEN),
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(modifier = Modifier.height(14.dp))
            }

            // Primary Navigation / Guest Mode Action
            Button(
                onClick = {
                    if (!isSpotifyLoggedIn && !SpotifySession.isGuestMode(context)) {
                        SpotifySession.setGuestMode(context, true)
                    }
                    navigateToHome()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isSpotifyLoggedIn) Color(SPOTIFY_GREEN) else Color(0xFF262634),
                    contentColor = if (isSpotifyLoggedIn) Color.Black else Color.White
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("enter_app_button")
            ) {
                Text(
                    text = if (isSpotifyLoggedIn) "Enter Sepotify" else "Continue as Guest (Instant Access)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Log in with your Spotify account to sync your playlists and library, or continue as guest.",
                color = Color(0xFF7A7A88),
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                lineHeight = 15.sp
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * Extracts a named cookie across all relevant Spotify domains in CookieManager.
 */
private fun extractCookie(cookieManager: CookieManager, name: String): String? {
    val domains = listOf(
        "https://open.spotify.com",
        "https://accounts.spotify.com",
        "https://spotify.com",
        "https://.spotify.com",
        "https://accounts.spotify.com/en/login"
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
