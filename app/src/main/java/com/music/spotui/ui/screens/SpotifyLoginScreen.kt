package com.music.spotui.ui.screens

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import com.metrolist.spotify.Spotify
import com.metrolist.spotify.SpotifyAuth
import com.music.spotui.R
import com.music.spotui.data.api.SpotifySession
import com.music.spotui.data.preferences.getDeezerArl
import com.music.spotui.data.preferences.getDeezerTier
import com.music.spotui.data.preferences.setDeezerArl
import com.music.spotui.data.preferences.setDeezerTier
import com.music.spotui.deezer.DeezerSession
import com.music.spotui.ui.navigation.Routes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

private const val SPOTIFY_GREEN = 0xFF1ED760
private const val DEEZER_PURPLE = 0xFFA238FF
private const val CHROME_MOBILE_UA =
    "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
private const val CHROME_DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
private const val DEEZER_LOGIN_URL = "https://www.deezer.com/login"

/**
 * Direct Spotify & Deezer Login Screen for Sepotify.
 * Supports direct in-app web login, direct credential automation, and session token inputs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotifyLoginScreen(navController: NavController) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    // Session state
    var isSpotifyLoggedIn by remember { mutableStateOf(SpotifySession.isLoggedIn(context)) }
    var deezerArlStored by remember { mutableStateOf(getDeezerArl(context) ?: "") }
    var isDeezerLoggedIn by remember { mutableStateOf(deezerArlStored.isNotBlank()) }
    var deezerTier by remember { mutableStateOf(getDeezerTier(context)) }

    // Selected tab (0 = Spotify, 1 = Deezer)
    var selectedTab by remember { mutableIntStateOf(if (!isSpotifyLoggedIn) 0 else 1) }

    // Mode for Spotify: 0 = Direct Interactive Web Login, 1 = Credentials / Token Form
    var spotifyLoginMode by remember { mutableIntStateOf(0) }

    // Mode for Deezer: 0 = Credentials / Token Form, 1 = Direct Interactive Web Login
    var deezerLoginMode by remember { mutableIntStateOf(0) }

    // Spotify form fields
    var spotifyEmail by remember { mutableStateOf("") }
    var spotifyPassword by remember { mutableStateOf("") }
    var isSpotifyPasswordVisible by remember { mutableStateOf(false) }
    var spotifySpDc by remember { mutableStateOf(SpotifySession.spDc(context)) }
    var showSpotifyGuide by remember { mutableStateOf(false) }

    // Deezer form fields
    var deezerEmail by remember { mutableStateOf("") }
    var deezerPassword by remember { mutableStateOf("") }
    var isDeezerPasswordVisible by remember { mutableStateOf(false) }
    var deezerArlInput by remember { mutableStateOf(deezerArlStored) }
    var showDeezerGuide by remember { mutableStateOf(false) }

    // UI state
    var isProcessing by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("") }
    var hasError by remember { mutableStateOf(false) }
    var successMessage by remember { mutableStateOf("") }

    // WebView controls & progress
    var spotifyWebProgress by remember { mutableFloatStateOf(0.1f) }
    var spotifyWebLoading by remember { mutableStateOf(true) }
    var spotifyWebViewRef by remember { mutableStateOf<WebView?>(null) }
    var isSpotifyDesktopMode by remember { mutableStateOf(false) }
    val spotifyTokenExtracted = remember { AtomicBoolean(false) }

    var deezerWebProgress by remember { mutableFloatStateOf(0.1f) }
    var deezerWebLoading by remember { mutableStateOf(true) }
    var deezerWebViewRef by remember { mutableStateOf<WebView?>(null) }
    val deezerTokenExtracted = remember { AtomicBoolean(false) }

    val navigateToHome: () -> Unit = {
        com.music.spotui.di.SpotifyWebPlayer.refreshLogin(context)
        navController.navigate(Routes.Home.route) {
            popUpTo(Routes.Login.route) { inclusive = true }
        }
    }

    // Helper: Paste from clipboard
    fun pasteFromClipboard(onPasted: (String) -> Unit) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = clipboard?.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).text?.toString()?.trim() ?: ""
            if (text.isNotBlank()) {
                onPasted(text)
            }
        }
    }

    // Process Spotify Login using sp_dc cookie
    fun executeSpotifyLogin(cookieValue: String) {
        val cleanCookie = cookieValue.trim()
        if (cleanCookie.isBlank()) {
            hasError = true
            statusMessage = "Please enter your Spotify session token or log in directly."
            return
        }

        isProcessing = true
        hasError = false
        statusMessage = "Verifying Spotify session…"

        scope.launch(Dispatchers.IO) {
            SpotifySession.setSpDc(context, cleanCookie)
            var errorCaught: Throwable? = null
            var success = false

            repeat(3) { attempt ->
                val result = SpotifyAuth.fetchAccessToken(cleanCookie, "")
                result.onSuccess { token ->
                    Spotify.accessToken = token.accessToken
                    success = true
                    withContext(Dispatchers.Main) {
                        isSpotifyLoggedIn = true
                        statusMessage = "Spotify account verified successfully!"
                        successMessage = "Spotify Connected"
                        isProcessing = false
                        if (!isDeezerLoggedIn) {
                            selectedTab = 1
                        }
                    }
                    return@launch
                }.onFailure { e ->
                    errorCaught = e
                    Timber.e(e, "Spotify auth attempt $attempt failed")
                    if (attempt < 2) delay(600)
                }
            }

            if (!success) {
                withContext(Dispatchers.Main) {
                    isProcessing = false
                    hasError = true
                    statusMessage = "Spotify login verification failed: ${errorCaught?.localizedMessage ?: "Invalid token"}"
                }
            }
        }
    }

    // Process Deezer Login using ARL
    fun executeDeezerLogin(arlValue: String) {
        val cleanArl = arlValue.trim()
        if (cleanArl.isBlank()) {
            hasError = true
            statusMessage = "Please enter your Deezer credentials or ARL token."
            return
        }

        isProcessing = true
        hasError = false
        statusMessage = "Authenticating with Deezer HiFi…"

        scope.launch(Dispatchers.IO) {
            setDeezerArl(context, cleanArl)
            DeezerSession.setArl(cleanArl)
            DeezerSession.authorize()

            val tier = when (DeezerSession.entitledQuality) {
                DeezerSession.QUALITY_FLAC -> "Premium (Lossless FLAC)"
                DeezerSession.QUALITY_MP3_320 -> "Premium (MP3 320)"
                else -> "Free (MP3 128)"
            }
            setDeezerTier(context, tier)

            withContext(Dispatchers.Main) {
                isDeezerLoggedIn = true
                deezerArlStored = cleanArl
                deezerTier = tier
                isProcessing = false
                successMessage = "Deezer Connected: $tier"
                statusMessage = "Deezer verified: $tier"
            }
        }
    }

    // Direct background cookie polling while on Spotify Login
    LaunchedEffect(selectedTab, spotifyLoginMode) {
        if (selectedTab == 0 && !isSpotifyLoggedIn) {
            while (isActive && !isSpotifyLoggedIn) {
                delay(1200)
                val cookieManager = CookieManager.getInstance()
                val spDc = extractCookie(cookieManager, "sp_dc")
                if (!spDc.isNullOrBlank() && spotifyTokenExtracted.compareAndSet(false, true)) {
                    spotifySpDc = spDc
                    withContext(Dispatchers.Main) {
                        executeSpotifyLogin(spDc)
                    }
                    break
                }
            }
        }
    }

    // Direct background cookie polling while on Deezer Login
    LaunchedEffect(selectedTab, deezerLoginMode) {
        if (selectedTab == 1 && !isDeezerLoggedIn) {
            while (isActive && !isDeezerLoggedIn) {
                delay(1200)
                val cookieManager = CookieManager.getInstance()
                val arl = extractDeezerCookie(cookieManager, "arl")
                if (!arl.isNullOrBlank() && deezerTokenExtracted.compareAndSet(false, true)) {
                    deezerArlInput = arl
                    withContext(Dispatchers.Main) {
                        executeDeezerLogin(arl)
                    }
                    break
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0E))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // App Brand Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                listOf(Color(SPOTIFY_GREEN), Color(DEEZER_PURPLE))
                            )
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_sepotify_logo),
                        contentDescription = "Sepotify Logo",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp),
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Sepotify",
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Sign in to your music accounts",
                        color = Color(0xFFB0B0B0),
                        fontSize = 13.sp,
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Account status summary chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Spotify status chip
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSpotifyLoggedIn) Color(0xFF13281C) else Color(0xFF1C1C24)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .border(
                            1.dp,
                            if (isSpotifyLoggedIn) Color(SPOTIFY_GREEN).copy(alpha = 0.7f) else Color(0xFF33333E),
                            RoundedCornerShape(12.dp)
                        )
                        .clickable { selectedTab = 0 },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(if (isSpotifyLoggedIn) Color(SPOTIFY_GREEN) else Color(0xFFFFB74D))
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Spotify",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = if (isSpotifyLoggedIn) "Connected ✓" else "Log In",
                                color = if (isSpotifyLoggedIn) Color(SPOTIFY_GREEN) else Color(0xFFFFB74D),
                                fontSize = 11.sp,
                            )
                        }
                    }
                }

                // Deezer status chip
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (isDeezerLoggedIn) Color(0xFF261536) else Color(0xFF1C1C24)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .border(
                            1.dp,
                            if (isDeezerLoggedIn) Color(DEEZER_PURPLE).copy(alpha = 0.7f) else Color(0xFF33333E),
                            RoundedCornerShape(12.dp)
                        )
                        .clickable { selectedTab = 1 },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(if (isDeezerLoggedIn) Color(DEEZER_PURPLE) else Color(0xFFFFB74D))
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Deezer HiFi",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = if (isDeezerLoggedIn) "Connected ✓" else "Optional (HiFi)",
                                color = if (isDeezerLoggedIn) Color(DEEZER_PURPLE) else Color(0xFFB0B0B0),
                                fontSize = 11.sp,
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Main Tab Row (Spotify / Deezer)
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color(0xFF16161F),
                contentColor = Color.White,
                indicator = { tabPositions ->
                    TabRowDefaults.SecondaryIndicator(
                        modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                        color = if (selectedTab == 0) Color(SPOTIFY_GREEN) else Color(DEEZER_PURPLE),
                    )
                },
                modifier = Modifier.clip(RoundedCornerShape(12.dp)),
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = {
                        Text(
                            "1. Spotify Login",
                            fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal,
                            color = if (selectedTab == 0) Color(SPOTIFY_GREEN) else Color.Gray,
                            fontSize = 13.sp,
                        )
                    },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = {
                        Text(
                            "2. Deezer HiFi",
                            fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal,
                            color = if (selectedTab == 1) Color(DEEZER_PURPLE) else Color.Gray,
                            fontSize = 13.sp,
                        )
                    },
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ═════════════════════════════════════════════════════════════════════════
            // TAB 0: SPOTIFY LOGIN
            // ═════════════════════════════════════════════════════════════════════════
            if (selectedTab == 0) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF15151D)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Spotify Direct Login",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                            )

                            // Toggle between Direct Web Login and Credentials/Token input
                            Row(
                                modifier = Modifier
                                    .background(Color(0xFF22222E), RoundedCornerShape(20.dp))
                                    .padding(2.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(18.dp))
                                        .background(if (spotifyLoginMode == 0) Color(SPOTIFY_GREEN) else Color.Transparent)
                                        .clickable { spotifyLoginMode = 0 }
                                        .padding(horizontal = 10.dp, vertical = 4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "Web Form",
                                        color = if (spotifyLoginMode == 0) Color.Black else Color.Gray,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(18.dp))
                                        .background(if (spotifyLoginMode == 1) Color(SPOTIFY_GREEN) else Color.Transparent)
                                        .clickable { spotifyLoginMode = 1 }
                                        .padding(horizontal = 10.dp, vertical = 4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "Credentials",
                                        color = if (spotifyLoginMode == 1) Color.Black else Color.Gray,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (spotifyLoginMode == 0)
                                "Log in directly using Spotify's official web login below (supports Email, Google, Facebook & Apple)."
                            else
                                "Enter your Spotify credentials or session token to authenticate directly.",
                            color = Color(0xFF9E9E9E),
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        // MODE 0: Embedded Direct Interactive Spotify Web View
                        if (spotifyLoginMode == 0) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF0F0F14))
                                    .border(1.dp, Color(0xFF2B2B38), RoundedCornerShape(12.dp))
                            ) {
                                // WebView Toolbar
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFF1E1E28))
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .clip(CircleShape)
                                                .background(Color(SPOTIFY_GREEN))
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            "accounts.spotify.com",
                                            color = Color.LightGray,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        TextButton(
                                            onClick = {
                                                isSpotifyDesktopMode = !isSpotifyDesktopMode
                                                spotifyWebViewRef?.settings?.userAgentString =
                                                    if (isSpotifyDesktopMode) CHROME_DESKTOP_UA else CHROME_MOBILE_UA
                                                spotifyWebViewRef?.reload()
                                            },
                                            modifier = Modifier.height(30.dp)
                                        ) {
                                            Text(
                                                if (isSpotifyDesktopMode) "Mobile" else "Desktop",
                                                color = Color(SPOTIFY_GREEN),
                                                fontSize = 11.sp
                                            )
                                        }

                                        IconButton(
                                            onClick = {
                                                spotifyWebViewRef?.reload()
                                            },
                                            modifier = Modifier.size(30.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Refresh,
                                                contentDescription = "Reload",
                                                tint = Color.White,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }

                                if (spotifyWebLoading) {
                                    LinearProgressIndicator(
                                        progress = { spotifyWebProgress },
                                        modifier = Modifier.fillMaxWidth().height(2.dp),
                                        color = Color(SPOTIFY_GREEN),
                                        trackColor = Color.DarkGray,
                                    )
                                }

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(460.dp)
                                ) {
                                    AndroidView(
                                        modifier = Modifier.fillMaxSize(),
                                        factory = { ctx ->
                                            val cookieManager = CookieManager.getInstance()
                                            cookieManager.setAcceptCookie(true)

                                            WebView(ctx).apply {
                                                spotifyWebViewRef = this
                                                cookieManager.setAcceptThirdPartyCookies(this, true)
                                                settings.apply {
                                                    javaScriptEnabled = true
                                                    domStorageEnabled = true
                                                    databaseEnabled = true
                                                    loadWithOverviewMode = true
                                                    useWideViewPort = true
                                                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                                    javaScriptCanOpenWindowsAutomatically = true
                                                    setSupportMultipleWindows(false)
                                                    cacheMode = WebSettings.LOAD_DEFAULT
                                                    userAgentString = if (isSpotifyDesktopMode) CHROME_DESKTOP_UA else CHROME_MOBILE_UA
                                                }

                                                webChromeClient = object : WebChromeClient() {
                                                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                                        spotifyWebProgress = (newProgress.coerceIn(5, 100)) / 100f
                                                        spotifyWebLoading = newProgress < 100
                                                    }
                                                }

                                                webViewClient = object : WebViewClient() {
                                                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                                        return false
                                                    }

                                                    override fun onPageFinished(view: WebView?, url: String?) {
                                                        spotifyWebLoading = false
                                                        cookieManager.flush()
                                                        val spDc = extractCookie(cookieManager, "sp_dc")
                                                        if (!spDc.isNullOrBlank() && spotifyTokenExtracted.compareAndSet(false, true)) {
                                                            spotifySpDc = spDc
                                                            executeSpotifyLogin(spDc)
                                                        }
                                                    }

                                                    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                                                        if (request?.isForMainFrame == true) {
                                                            spotifyWebLoading = false
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

                        // MODE 1: Direct Credentials & Token Form
                        if (spotifyLoginMode == 1) {
                            Column {
                                Text(
                                    text = "Spotify Account Credentials",
                                    color = Color(SPOTIFY_GREEN),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Spacer(modifier = Modifier.height(6.dp))

                                OutlinedTextField(
                                    value = spotifyEmail,
                                    onValueChange = { spotifyEmail = it },
                                    placeholder = { Text("Spotify Email or Username", color = Color(0xFF666666), fontSize = 13.sp) },
                                    singleLine = true,
                                    leadingIcon = {
                                        Icon(Icons.Default.Person, contentDescription = "Email", tint = Color.Gray, modifier = Modifier.size(18.dp))
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("spotify_email_input"),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White,
                                        focusedBorderColor = Color(SPOTIFY_GREEN),
                                        unfocusedBorderColor = Color(0xFF33333E),
                                        focusedContainerColor = Color(0xFF101016),
                                        unfocusedContainerColor = Color(0xFF101016),
                                    ),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                                )

                                Spacer(modifier = Modifier.height(10.dp))

                                OutlinedTextField(
                                    value = spotifyPassword,
                                    onValueChange = { spotifyPassword = it },
                                    placeholder = { Text("Spotify Password", color = Color(0xFF666666), fontSize = 13.sp) },
                                    singleLine = true,
                                    leadingIcon = {
                                        Icon(Icons.Default.Lock, contentDescription = "Password", tint = Color.Gray, modifier = Modifier.size(18.dp))
                                    },
                                    trailingIcon = {
                                        IconButton(onClick = { isSpotifyPasswordVisible = !isSpotifyPasswordVisible }) {
                                            Icon(
                                                painter = painterResource(
                                                    id = if (isSpotifyPasswordVisible) R.drawable.visibility else R.drawable.visibility_off
                                                ),
                                                contentDescription = "Toggle password",
                                                tint = Color.Gray,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                    },
                                    visualTransformation = if (isSpotifyPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("spotify_password_input"),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White,
                                        focusedBorderColor = Color(SPOTIFY_GREEN),
                                        unfocusedBorderColor = Color(0xFF33333E),
                                        focusedContainerColor = Color(0xFF101016),
                                        unfocusedContainerColor = Color(0xFF101016),
                                    ),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(onDone = {
                                        focusManager.clearFocus()
                                        if (spotifyEmail.isNotBlank() && spotifyPassword.isNotBlank()) {
                                            // Switch to interactive web tab and inject credentials
                                            spotifyLoginMode = 0
                                            spotifyWebViewRef?.evaluateJavascript(
                                                """
                                                (function() {
                                                    var u = document.querySelector('input#login-username, input[data-testid="login-username"]');
                                                    var p = document.querySelector('input#login-password, input[data-testid="login-password"]');
                                                    if (u) { u.value = '${spotifyEmail.replace("'", "\\'")}'; u.dispatchEvent(new Event('input', {bubbles: true})); }
                                                    if (p) { p.value = '${spotifyPassword.replace("'", "\\'")}'; p.dispatchEvent(new Event('input', {bubbles: true})); }
                                                    var btn = document.querySelector('button#login-button, button[data-testid="login-button"]');
                                                    if (btn) btn.click();
                                                })();
                                                """.trimIndent(),
                                                null
                                            )
                                        }
                                    })
                                )

                                Spacer(modifier = Modifier.height(10.dp))

                                Button(
                                    onClick = {
                                        focusManager.clearFocus()
                                        if (spotifyEmail.isNotBlank() && spotifyPassword.isNotBlank()) {
                                            spotifyLoginMode = 0
                                            spotifyWebViewRef?.evaluateJavascript(
                                                """
                                                (function() {
                                                    var u = document.querySelector('input#login-username, input[data-testid="login-username"]');
                                                    var p = document.querySelector('input#login-password, input[data-testid="login-password"]');
                                                    if (u) { u.value = '${spotifyEmail.replace("'", "\\'")}'; u.dispatchEvent(new Event('input', {bubbles: true})); }
                                                    if (p) { p.value = '${spotifyPassword.replace("'", "\\'")}'; p.dispatchEvent(new Event('input', {bubbles: true})); }
                                                    var btn = document.querySelector('button#login-button, button[data-testid="login-button"]');
                                                    if (btn) btn.click();
                                                })();
                                                """.trimIndent(),
                                                null
                                            )
                                        } else {
                                            statusMessage = "Please enter both Spotify email and password."
                                            hasError = true
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(SPOTIFY_GREEN),
                                        contentColor = Color.Black,
                                    ),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(46.dp)
                                        .testTag("spotify_credentials_submit_button"),
                                ) {
                                    Text("Sign In with Spotify Credentials", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                // Or Paste Spotify sp_dc Session Token
                                Text(
                                    text = "Or Session Token (sp_dc)",
                                    color = Color(0xFFB0B0B0),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Spacer(modifier = Modifier.height(6.dp))

                                OutlinedTextField(
                                    value = spotifySpDc,
                                    onValueChange = { spotifySpDc = it },
                                    placeholder = { Text("Paste your 'sp_dc' cookie string…", color = Color(0xFF666666), fontSize = 13.sp) },
                                    singleLine = false,
                                    maxLines = 3,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("spotify_sp_dc_input"),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White,
                                        focusedBorderColor = Color(SPOTIFY_GREEN),
                                        unfocusedBorderColor = Color(0xFF33333E),
                                        focusedContainerColor = Color(0xFF101016),
                                        unfocusedContainerColor = Color(0xFF101016),
                                    ),
                                    trailingIcon = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (spotifySpDc.isNotBlank()) {
                                                IconButton(
                                                    onClick = { spotifySpDc = "" },
                                                    modifier = Modifier.size(28.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Close,
                                                        contentDescription = "Clear",
                                                        tint = Color.Gray,
                                                        modifier = Modifier.size(16.dp),
                                                    )
                                                }
                                            }
                                            TextButton(
                                                onClick = { pasteFromClipboard { spotifySpDc = it } },
                                                modifier = Modifier.testTag("paste_spotify_sp_dc_button")
                                            ) {
                                                Text("Paste", color = Color(SPOTIFY_GREEN), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    },
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(onDone = {
                                        focusManager.clearFocus()
                                        executeSpotifyLogin(spotifySpDc)
                                    })
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                // How to get sp_dc expander
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { showSpotifyGuide = !showSpotifyGuide }
                                        .padding(vertical = 4.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = "Info",
                                        tint = Color(0xFF888888),
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (showSpotifyGuide) "Hide token guide" else "How to obtain your Spotify sp_dc cookie",
                                        color = Color(0xFF888888),
                                        fontSize = 12.sp,
                                    )
                                }

                                if (showSpotifyGuide) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(Color(0xFF0F0F14), RoundedCornerShape(8.dp))
                                            .padding(10.dp)
                                    ) {
                                        Column {
                                            Text("1. Open open.spotify.com in browser and log in.", color = Color(0xFFCCCCCC), fontSize = 11.sp)
                                            Spacer(modifier = Modifier.height(3.dp))
                                            Text("2. DevTools (F12) → Application → Cookies → open.spotify.com.", color = Color(0xFFCCCCCC), fontSize = 11.sp)
                                            Spacer(modifier = Modifier.height(3.dp))
                                            Text("3. Copy 'sp_dc' cookie value and paste it above.", color = Color(0xFFCCCCCC), fontSize = 11.sp)
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                Button(
                                    onClick = {
                                        focusManager.clearFocus()
                                        executeSpotifyLogin(spotifySpDc)
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF242432),
                                        contentColor = Color.White,
                                    ),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(46.dp)
                                        .testTag("spotify_login_token_submit_button"),
                                    enabled = !isProcessing,
                                ) {
                                    Text("Verify & Save Token", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
            }

            // ═════════════════════════════════════════════════════════════════════════
            // TAB 1: DEEZER LOGIN
            // ═════════════════════════════════════════════════════════════════════════
            if (selectedTab == 1) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF181324)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(CircleShape)
                                        .background(Color(DEEZER_PURPLE).copy(alpha = 0.2f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text("🎵", fontSize = 16.sp)
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Deezer HiFi Audio",
                                    color = Color.White,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }

                            // Toggle between Credentials/Token and Direct Web Login
                            Row(
                                modifier = Modifier
                                    .background(Color(0xFF261D36), RoundedCornerShape(20.dp))
                                    .padding(2.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(18.dp))
                                        .background(if (deezerLoginMode == 0) Color(DEEZER_PURPLE) else Color.Transparent)
                                        .clickable { deezerLoginMode = 0 }
                                        .padding(horizontal = 10.dp, vertical = 4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "Login / Form",
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(18.dp))
                                        .background(if (deezerLoginMode == 1) Color(DEEZER_PURPLE) else Color.Transparent)
                                        .clickable { deezerLoginMode = 1 }
                                        .padding(horizontal = 10.dp, vertical = 4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "Web Form",
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Sepotify streams uncensored studio audio directly from Deezer. Connect with your credentials, web portal, or ARL token for instant HiFi playback.",
                            color = Color(0xFFB8B0C8),
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        // DEEZER MODE 0: Direct Email & Password + ARL Token Input
                        if (deezerLoginMode == 0) {
                            Column {
                                Text(
                                    text = "Deezer User & Password",
                                    color = Color(DEEZER_PURPLE),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Spacer(modifier = Modifier.height(6.dp))

                                OutlinedTextField(
                                    value = deezerEmail,
                                    onValueChange = { deezerEmail = it },
                                    placeholder = { Text("Deezer Email or Username", color = Color(0xFF666677), fontSize = 13.sp) },
                                    singleLine = true,
                                    leadingIcon = {
                                        Icon(Icons.Default.Person, contentDescription = "Email", tint = Color.Gray, modifier = Modifier.size(18.dp))
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("deezer_email_input"),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White,
                                        focusedBorderColor = Color(DEEZER_PURPLE),
                                        unfocusedBorderColor = Color(0xFF443355),
                                        focusedContainerColor = Color(0xFF130E1B),
                                        unfocusedContainerColor = Color(0xFF130E1B),
                                    ),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                                )

                                Spacer(modifier = Modifier.height(10.dp))

                                OutlinedTextField(
                                    value = deezerPassword,
                                    onValueChange = { deezerPassword = it },
                                    placeholder = { Text("Deezer Password", color = Color(0xFF666677), fontSize = 13.sp) },
                                    singleLine = true,
                                    leadingIcon = {
                                        Icon(Icons.Default.Lock, contentDescription = "Password", tint = Color.Gray, modifier = Modifier.size(18.dp))
                                    },
                                    trailingIcon = {
                                        IconButton(onClick = { isDeezerPasswordVisible = !isDeezerPasswordVisible }) {
                                            Icon(
                                                painter = painterResource(
                                                    id = if (isDeezerPasswordVisible) R.drawable.visibility else R.drawable.visibility_off
                                                ),
                                                contentDescription = "Toggle password",
                                                tint = Color.Gray,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                    },
                                    visualTransformation = if (isDeezerPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("deezer_password_input"),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White,
                                        focusedBorderColor = Color(DEEZER_PURPLE),
                                        unfocusedBorderColor = Color(0xFF443355),
                                        focusedContainerColor = Color(0xFF130E1B),
                                        unfocusedContainerColor = Color(0xFF130E1B),
                                    ),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(onDone = {
                                        focusManager.clearFocus()
                                        if (deezerEmail.isNotBlank() && deezerPassword.isNotBlank()) {
                                            deezerLoginMode = 1
                                            deezerWebViewRef?.evaluateJavascript(
                                                """
                                                (function() {
                                                    var m = document.querySelector('input#mail_login, input[type="email"]');
                                                    var p = document.querySelector('input#password_login, input[type="password"]');
                                                    if (m) { m.value = '${deezerEmail.replace("'", "\\'")}'; m.dispatchEvent(new Event('input', {bubbles: true})); }
                                                    if (p) { p.value = '${deezerPassword.replace("'", "\\'")}'; p.dispatchEvent(new Event('input', {bubbles: true})); }
                                                    var btn = document.querySelector('button#login_form_submit, button[type="submit"]');
                                                    if (btn) btn.click();
                                                })();
                                                """.trimIndent(),
                                                null
                                            )
                                        }
                                    })
                                )

                                Spacer(modifier = Modifier.height(10.dp))

                                Button(
                                    onClick = {
                                        focusManager.clearFocus()
                                        if (deezerEmail.isNotBlank() && deezerPassword.isNotBlank()) {
                                            deezerLoginMode = 1
                                            deezerWebViewRef?.evaluateJavascript(
                                                """
                                                (function() {
                                                    var m = document.querySelector('input#mail_login, input[type="email"]');
                                                    var p = document.querySelector('input#password_login, input[type="password"]');
                                                    if (m) { m.value = '${deezerEmail.replace("'", "\\'")}'; m.dispatchEvent(new Event('input', {bubbles: true})); }
                                                    if (p) { p.value = '${deezerPassword.replace("'", "\\'")}'; p.dispatchEvent(new Event('input', {bubbles: true})); }
                                                    var btn = document.querySelector('button#login_form_submit, button[type="submit"]');
                                                    if (btn) btn.click();
                                                })();
                                                """.trimIndent(),
                                                null
                                            )
                                        } else {
                                            statusMessage = "Please enter your Deezer email and password."
                                            hasError = true
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(DEEZER_PURPLE),
                                        contentColor = Color.White,
                                    ),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(46.dp)
                                        .testTag("deezer_credentials_submit_button"),
                                ) {
                                    Text("Sign In with Deezer Credentials", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                // Or Paste Deezer ARL Token
                                Text(
                                    text = "Or Deezer ARL Token",
                                    color = Color(DEEZER_PURPLE),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Spacer(modifier = Modifier.height(6.dp))

                                OutlinedTextField(
                                    value = deezerArlInput,
                                    onValueChange = { deezerArlInput = it },
                                    placeholder = { Text("Paste your 'arl' cookie string…", color = Color(0xFF666666), fontSize = 13.sp) },
                                    singleLine = false,
                                    maxLines = 3,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("deezer_arl_input"),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White,
                                        focusedBorderColor = Color(DEEZER_PURPLE),
                                        unfocusedBorderColor = Color(0xFF443355),
                                        focusedContainerColor = Color(0xFF130E1B),
                                        unfocusedContainerColor = Color(0xFF130E1B),
                                    ),
                                    trailingIcon = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (deezerArlInput.isNotBlank()) {
                                                IconButton(
                                                    onClick = { deezerArlInput = "" },
                                                    modifier = Modifier.size(28.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Close,
                                                        contentDescription = "Clear",
                                                        tint = Color.Gray,
                                                        modifier = Modifier.size(16.dp),
                                                    )
                                                }
                                            }
                                            TextButton(
                                                onClick = { pasteFromClipboard { deezerArlInput = it } },
                                                modifier = Modifier.testTag("paste_deezer_arl_button")
                                            ) {
                                                Text("Paste", color = Color(DEEZER_PURPLE), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    },
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(onDone = {
                                        focusManager.clearFocus()
                                        executeDeezerLogin(deezerArlInput)
                                    })
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                // How to get Deezer ARL expander
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { showDeezerGuide = !showDeezerGuide }
                                        .padding(vertical = 4.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = "Info",
                                        tint = Color(0xFF9988AA),
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (showDeezerGuide) "Hide Deezer guide" else "How to get your Deezer ARL cookie",
                                        color = Color(0xFF9988AA),
                                        fontSize = 12.sp,
                                    )
                                }

                                if (showDeezerGuide) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(Color(0xFF100B17), RoundedCornerShape(8.dp))
                                            .padding(10.dp)
                                    ) {
                                        Column {
                                            Text("1. Open deezer.com in any browser and log in.", color = Color(0xFFCCCCCC), fontSize = 11.sp)
                                            Spacer(modifier = Modifier.height(3.dp))
                                            Text("2. Press F12 (DevTools) → Application → Cookies → https://www.deezer.com.", color = Color(0xFFCCCCCC), fontSize = 11.sp)
                                            Spacer(modifier = Modifier.height(3.dp))
                                            Text("3. Copy the 'arl' cookie value and paste it into the field above.", color = Color(0xFFCCCCCC), fontSize = 11.sp)
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                Button(
                                    onClick = {
                                        focusManager.clearFocus()
                                        executeDeezerLogin(deezerArlInput)
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF261D36),
                                        contentColor = Color.White,
                                    ),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(46.dp)
                                        .testTag("deezer_login_token_submit_button"),
                                    enabled = !isProcessing,
                                ) {
                                    Text("Verify & Save Token", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                }
                            }
                        }

                        // DEEZER MODE 1: Embedded Interactive Deezer Web View
                        if (deezerLoginMode == 1) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF100B17))
                                    .border(1.dp, Color(0xFF382A4A), RoundedCornerShape(12.dp))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFF201530))
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .clip(CircleShape)
                                                .background(Color(DEEZER_PURPLE))
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            "deezer.com/login",
                                            color = Color.LightGray,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }

                                    IconButton(
                                        onClick = {
                                            deezerWebViewRef?.reload()
                                        },
                                        modifier = Modifier.size(30.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Refresh,
                                            contentDescription = "Reload",
                                            tint = Color.White,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }

                                if (deezerWebLoading) {
                                    LinearProgressIndicator(
                                        progress = { deezerWebProgress },
                                        modifier = Modifier.fillMaxWidth().height(2.dp),
                                        color = Color(DEEZER_PURPLE),
                                        trackColor = Color.DarkGray,
                                    )
                                }

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(460.dp)
                                ) {
                                    AndroidView(
                                        modifier = Modifier.fillMaxSize(),
                                        factory = { ctx ->
                                            val cookieManager = CookieManager.getInstance()
                                            cookieManager.setAcceptCookie(true)

                                            WebView(ctx).apply {
                                                deezerWebViewRef = this
                                                cookieManager.setAcceptThirdPartyCookies(this, true)
                                                settings.apply {
                                                    javaScriptEnabled = true
                                                    domStorageEnabled = true
                                                    databaseEnabled = true
                                                    loadWithOverviewMode = true
                                                    useWideViewPort = true
                                                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                                    javaScriptCanOpenWindowsAutomatically = true
                                                    setSupportMultipleWindows(false)
                                                    cacheMode = WebSettings.LOAD_DEFAULT
                                                    userAgentString = CHROME_MOBILE_UA
                                                }

                                                webChromeClient = object : WebChromeClient() {
                                                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                                        deezerWebProgress = (newProgress.coerceIn(5, 100)) / 100f
                                                        deezerWebLoading = newProgress < 100
                                                    }
                                                }

                                                webViewClient = object : WebViewClient() {
                                                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                                        return false
                                                    }

                                                    override fun onPageFinished(view: WebView?, url: String?) {
                                                        deezerWebLoading = false
                                                        cookieManager.flush()
                                                        val arl = extractDeezerCookie(cookieManager, "arl")
                                                        if (!arl.isNullOrBlank() && deezerTokenExtracted.compareAndSet(false, true)) {
                                                            deezerArlInput = arl
                                                            executeDeezerLogin(arl)
                                                        }
                                                    }

                                                    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                                                        if (request?.isForMainFrame == true) {
                                                            deezerWebLoading = false
                                                        }
                                                    }
                                                }

                                                loadUrl(DEEZER_LOGIN_URL)
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Status message box
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
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(modifier = Modifier.height(14.dp))
            }

            // PRIMARY ACTION: ENTER SEPOTIFY
            val canProceed = isSpotifyLoggedIn
            Button(
                onClick = {
                    if (!isSpotifyLoggedIn) {
                        selectedTab = 0
                        hasError = true
                        statusMessage = "Please log in to Spotify to proceed."
                    } else {
                        navigateToHome()
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (canProceed) Color(SPOTIFY_GREEN) else Color(0xFF2E2E38),
                    contentColor = if (canProceed) Color.Black else Color(0xFF888888),
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("enter_app_button"),
            ) {
                Text(
                    text = if (isSpotifyLoggedIn) "Enter Sepotify" else "Log In to Continue",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Sepotify streams all tracks with HiFi lossless quality and personalized recommendations.",
                color = Color(0xFF666666),
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                lineHeight = 15.sp,
            )

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

private fun extractCookie(cookieManager: CookieManager, name: String): String? {
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

private fun extractDeezerCookie(cookieManager: CookieManager, name: String): String? {
    val domains = listOf(
        "https://www.deezer.com",
        "https://deezer.com",
        "https://.deezer.com",
        "https://www.deezer.com/login",
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
