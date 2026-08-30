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
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import com.music.spotui.data.preferences.getDeezerArl
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
import java.util.concurrent.atomic.AtomicBoolean

private const val DEEZER_PURPLE = 0xFFA238FF
private const val DEEZER_LOGIN_URL = "https://www.deezer.com/login"
private const val DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

/**
 * Fullscreen Deezer Login Screen for Sepotify (identical UX to Spotify login).
 * Directly opens Deezer web login, captures the session cookie (arl),
 * validates the session with Deezer API, and navigates seamlessly to Home.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DeezerLoginScreen(navController: NavController, next: String = "") {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var webView by remember { mutableStateOf<WebView?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var progress by remember { mutableFloatStateOf(0.1f) }
    var isLoggingIn by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("") }
    var showManualTokenDialog by remember { mutableStateOf(false) }
    var manualTokenInput by remember { mutableStateOf(getDeezerArl(context) ?: "") }

    val handledLogin = remember { AtomicBoolean(false) }
    val isOnboarding = next == "home" || next == "onboarding"

    fun navigateNext() {
        if (isOnboarding) {
            navController.navigate(Routes.Home.route) {
                popUpTo(Routes.DeezerLogin.route) { inclusive = true }
            }
        } else {
            navController.popBackStack()
        }
    }

    fun handleDeezerArl(arl: String) {
        val clean = arl.trim()
        if (clean.isBlank()) return
        if (handledLogin.getAndSet(true)) return

        isLoggingIn = true
        statusText = "Authenticating with Deezer HiFi..."

        scope.launch(Dispatchers.IO) {
            setDeezerArl(context, clean)
            DeezerSession.setArl(clean)
            DeezerSession.authorize()

            val tier = when (DeezerSession.entitledQuality) {
                DeezerSession.QUALITY_FLAC -> "Premium (Lossless FLAC)"
                DeezerSession.QUALITY_MP3_320 -> "Premium (MP3 320)"
                else -> "Free (MP3 128)"
            }
            setDeezerTier(context, tier)

            withContext(Dispatchers.Main) {
                statusText = "Connected! $tier"
                delay(300)
                navigateNext()
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
                "https://www.deezer.com",
                "https://deezer.com",
                "https://.deezer.com",
                "https://www.deezer.com/login"
            )
            for (u in targetUrls) {
                val cookies = cookieManager.getCookie(u) ?: continue
                val parts = cookies.split(";")
                for (p in parts) {
                    val pair = p.trim().split("=", limit = 2)
                    if (pair.size == 2 && pair[0].trim().equals("arl", ignoreCase = true) && pair[1].trim().isNotBlank()) {
                        handleDeezerArl(pair[1].trim())
                        return
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to check Deezer cookies")
        }
    }

    // Poller to ensure cookie capture on dynamic page transitions
    LaunchedEffect(Unit) {
        while (isActive && !handledLogin.get()) {
            checkCookies()
            delay(800)
        }
    }

    BackHandler(enabled = true) {
        if (webView?.canGoBack() == true) {
            webView?.goBack()
        } else if (!isOnboarding) {
            navController.popBackStack()
        } else {
            statusText = "Deezer login is required to use the app."
        }
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
        containerColor = Color(0xFF0D0B12),
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color(DEEZER_PURPLE).copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = Color(DEEZER_PURPLE),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Login to Deezer",
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                navigationIcon = {
                    if (webView?.canGoBack() == true || !isOnboarding) {
                        IconButton(onClick = {
                            if (webView?.canGoBack() == true) {
                                webView?.goBack()
                            } else {
                                navController.popBackStack()
                            }
                        }) {
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
                        Icon(Icons.Default.Lock, contentDescription = "Manual ARL Token", tint = Color(DEEZER_PURPLE))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF161220))
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Color(0xFF0D0B12))
        ) {
            // Fullscreen Deezer WebView
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("deezer_login_webview"),
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
                        loadUrl(DEEZER_LOGIN_URL)
                    }
                },
                update = {}
            )

            // Top linear progress indicator
            if (isLoading) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.5.dp)
                        .align(Alignment.TopCenter),
                    color = Color(DEEZER_PURPLE),
                    trackColor = Color(0xFF222222)
                )
            }

            // Authentication status overlay banner
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
                    color = Color(0xFF1C182A),
                    shape = RoundedCornerShape(12.dp),
                    shadowElevation = 8.dp,
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(DEEZER_PURPLE).copy(alpha = 0.6f))
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
                                color = Color(DEEZER_PURPLE),
                                strokeWidth = 2.5.dp
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                        }
                        Text(
                            text = statusText.ifEmpty { "Verifying Deezer session..." },
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

    // Manual ARL Token Input Dialog
    if (showManualTokenDialog) {
        AlertDialog(
            onDismissRequest = { showManualTokenDialog = false },
            containerColor = Color(0xFF221F2E),
            title = {
                Text(
                    text = "Manual Deezer ARL Token",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            },
            text = {
                Column {
                    Text(
                        text = "Paste your 'arl' cookie value from Deezer Web to connect directly for Lossless FLAC & 320kbps streaming:",
                        color = Color(0xFFAAAAAA),
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = manualTokenInput,
                        onValueChange = { manualTokenInput = it },
                        placeholder = { Text("arl cookie token...", color = Color.Gray, fontSize = 12.sp) },
                        singleLine = false,
                        maxLines = 3,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("manual_deezer_token_input"),
                        shape = RoundedCornerShape(8.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(DEEZER_PURPLE),
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
                            handleDeezerArl(manualTokenInput)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(DEEZER_PURPLE),
                        contentColor = Color.White
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
