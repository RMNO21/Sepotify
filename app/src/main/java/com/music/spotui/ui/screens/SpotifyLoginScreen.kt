package com.music.spotui.ui.screens

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Message
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.text.font.FontFamily
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

private const val SPOTIFY_GREEN = 0xFF1ED760
private const val CHROME_MOBILE_UA =
    "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
private const val DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

data class DiagnosticLogItem(
    val timestamp: String,
    val level: String, // INFO, WARN, ERROR, COOKIE, JS, NET
    val message: String,
    val details: String = ""
)

/**
 * Clean, first-principles Spotify Login Screen with full real-time Diagnostics / عیب‌یابی mode.
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

    // Diagnostic Mode state
    var isDiagnosticsOpen by remember { mutableStateOf(false) }
    var useDesktopUa by remember { mutableStateOf(false) }
    var currentUrl by remember { mutableStateOf(SpotifyAuth.LOGIN_URL) }
    var lastCookiesSummary by remember { mutableStateOf("No cookies scanned yet") }
    var spDcFoundStatus by remember { mutableStateOf("sp_dc: NOT FOUND") }
    val diagnosticLogs = remember { mutableStateListOf<DiagnosticLogItem>() }

    fun addDiagLog(level: String, message: String, details: String = "") {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        if (diagnosticLogs.size > 200) {
            diagnosticLogs.removeAt(0)
        }
        diagnosticLogs.add(DiagnosticLogItem(time, level, message, details))
        Timber.tag("SpotifyDiag").d("[$level] $message: $details")
    }

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
        addDiagLog("NET", "Starting token verification", "Cookie length=${cleanCookie.length}, prefix=${cleanCookie.take(8)}...")

        scope.launch(Dispatchers.IO) {
            SpotifySession.setSpDc(context, cleanCookie)
            SpotifySession.setGuestMode(context, false)

            var success = false
            var caughtError: Throwable? = null

            repeat(3) { attempt ->
                addDiagLog("NET", "fetchAccessToken attempt #${attempt + 1}")
                val result = SpotifyAuth.fetchAccessToken(cleanCookie, "")
                result.onSuccess { token ->
                    Spotify.accessToken = token.accessToken
                    SpotifySession.setSpDc(context, cleanCookie)
                    success = true
                    addDiagLog("NET", "Token verify SUCCESS", "Access token acquired (length=${token.accessToken.length})")
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
                    addDiagLog("ERROR", "Token verify failed on attempt #${attempt + 1}", "${e.message}")
                    Timber.e(e, "Spotify auth attempt $attempt failed")
                    if (attempt < 2) delay(500)
                }
            }

            if (!success) {
                withContext(Dispatchers.Main) {
                    isProcessing = false
                    hasError = true
                    val errDesc = caughtError?.localizedMessage ?: "Invalid token"
                    statusMessage = "Verification failed: $errDesc"
                    addDiagLog("ERROR", "All 3 verification attempts failed", errDesc)
                }
            }
        }
    }

    // Helper: Poll cookies from CookieManager
    fun checkAndExtractCookie(cookieManager: CookieManager, eventSource: String) {
        val domains = listOf(
            "https://open.spotify.com",
            "https://accounts.spotify.com",
            "https://spotify.com",
            "https://.spotify.com",
            "https://accounts.spotify.com/en/login"
        )
        val allFoundKeys = mutableSetOf<String>()
        var foundSpDc: String? = null

        for (domain in domains) {
            val c = cookieManager.getCookie(domain) ?: continue
            val pairs = c.split(";").mapNotNull {
                val p = it.trim().split("=", limit = 2)
                if (p.size == 2) p[0].trim() to p[1].trim() else null
            }
            pairs.forEach { (k, v) ->
                allFoundKeys.add(k)
                if (k == "sp_dc" && v.isNotBlank()) {
                    foundSpDc = v
                }
            }
        }

        val keysStr = if (allFoundKeys.isEmpty()) "None" else allFoundKeys.joinToString(", ")
        lastCookiesSummary = "Cookies found (${allFoundKeys.size}): $keysStr"

        if (foundSpDc != null) {
            spDcFoundStatus = "sp_dc: FOUND! (Length ${foundSpDc?.length})"
            if (!tokenExtracted.get()) {
                addDiagLog("COOKIE", "Captured sp_dc cookie via $eventSource", "Length=${foundSpDc?.length}, Keys=[$keysStr]")
            }
            if (tokenExtracted.compareAndSet(false, true)) {
                manualSpDc = foundSpDc!!
                executeSpotifyLogin(foundSpDc!!)
            }
        } else {
            spDcFoundStatus = "sp_dc: NOT FOUND (${allFoundKeys.size} other cookies present)"
        }
    }

    // Cleanup WebView when leaving
    DisposableEffect(Unit) {
        addDiagLog("INFO", "SpotifyLoginScreen initialized", "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        onDispose {
            try {
                webViewRef?.stopLoading()
                webViewRef = null
                addDiagLog("INFO", "SpotifyLoginScreen disposed")
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
            // Header: App Title, Branding & Diagnostics Toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
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
                    // Diagnostics Toggle Button
                    Button(
                        onClick = { isDiagnosticsOpen = !isDiagnosticsOpen },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isDiagnosticsOpen) Color(0xFFE57373) else Color(0xFF262634),
                            contentColor = if (isDiagnosticsOpen) Color.Black else Color(0xFFFFB74D)
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.height(32.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            Icons.Default.Build,
                            contentDescription = "Diagnostics",
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isDiagnosticsOpen) "بستن عیب‌یابی" else "حالت عیب‌یابی",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

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
                                addDiagLog("INFO", "User clicked Reset / Clear cookies")
                                val cookieManager = CookieManager.getInstance()
                                cookieManager.removeAllCookies(null)
                                cookieManager.flush()
                                tokenExtracted.set(false)
                                webViewRef?.clearCache(true)
                                webViewRef?.loadUrl(SpotifyAuth.LOGIN_URL)
                                statusMessage = "Cookies reset. Reloading Spotify login…"
                            } catch (e: Exception) {
                                addDiagLog("ERROR", "Reset failed", "${e.message}")
                            }
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

            // REAL-TIME DIAGNOSTIC CONSOLE PANEL (حالت عیب‌یابی دقیق)
            AnimatedVisibility(
                visible = isDiagnosticsOpen,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF13131A)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .border(1.dp, Color(0xFFFFB74D).copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        // Title row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Build,
                                    contentDescription = "Diagnostics",
                                    tint = Color(0xFFFFB74D),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "پنل عیب‌یابی زنده (Live Diagnostics)",
                                    color = Color(0xFFFFB74D),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            TextButton(
                                onClick = {
                                    val logDump = buildString {
                                        appendLine("=== SPOTUI DIAGNOSTIC REPORT ===")
                                        appendLine("Current URL: $currentUrl")
                                        appendLine("Loading Progress: ${(webProgress * 100).toInt()}% (isLoading=$isWebLoading)")
                                        appendLine("User Agent: ${if (useDesktopUa) DESKTOP_UA else CHROME_MOBILE_UA}")
                                        appendLine("Cookie Status: $spDcFoundStatus")
                                        appendLine("All Cookies: $lastCookiesSummary")
                                        appendLine("Is Logged In: $isSpotifyLoggedIn")
                                        appendLine("\n--- LIVE LOGS (${diagnosticLogs.size}) ---")
                                        diagnosticLogs.forEach {
                                            appendLine("[${it.timestamp}] [${it.level}] ${it.message} - ${it.details}")
                                        }
                                    }
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                    clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("SpotUIDiagnostics", logDump))
                                    statusMessage = "گزارش کامل عیب‌یابی در کلیپ‌بورد کپی شد!"
                                    addDiagLog("INFO", "Diagnostics copied to clipboard")
                                },
                                modifier = Modifier.height(28.dp)
                            ) {
                                Text("📋 کپی گزارش", color = Color(SPOTIFY_GREEN), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        Divider(color = Color(0xFF282836), modifier = Modifier.padding(vertical = 8.dp))

                        // Real-time Status Pills
                        Text(
                            text = "URL: $currentUrl",
                            color = Color(0xFFE0E0E0),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 2
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "وضعیت کوکی: $spDcFoundStatus",
                            color = if (spDcFoundStatus.contains("FOUND!")) Color(SPOTIFY_GREEN) else Color(0xFFFF8A80),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = lastCookiesSummary,
                            color = Color(0xFF9E9E9E),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Diagnostics Action Toolbar
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Button(
                                onClick = {
                                    useDesktopUa = !useDesktopUa
                                    webViewRef?.settings?.userAgentString = if (useDesktopUa) DESKTOP_UA else CHROME_MOBILE_UA
                                    webViewRef?.reload()
                                    addDiagLog("INFO", "Switched UA to ${if (useDesktopUa) "DESKTOP" else "MOBILE"}")
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF242434)),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.height(30.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(if (useDesktopUa) "حالت دسکتاپ (فعال)" else "تغییر به Desktop UA", fontSize = 10.sp, color = Color.White)
                            }

                            Button(
                                onClick = {
                                    val cookieManager = CookieManager.getInstance()
                                    cookieManager.flush()
                                    checkAndExtractCookie(cookieManager, "MANUAL_SCAN")
                                    addDiagLog("INFO", "Manual cookie scan triggered")
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF242434)),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.height(30.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("اسکن کوکی‌ها", fontSize = 10.sp, color = Color.White)
                            }

                            Button(
                                onClick = {
                                    webViewRef?.loadUrl("https://accounts.spotify.com/en/login?continue=https%3A%2F%2Fopen.spotify.com%2F")
                                    addDiagLog("INFO", "Navigating to accounts.spotify.com login")
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF242434)),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.height(30.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("accounts.spotify.com", fontSize = 10.sp, color = Color.White)
                            }

                            Button(
                                onClick = {
                                    webViewRef?.loadUrl("https://open.spotify.com")
                                    addDiagLog("INFO", "Navigating to open.spotify.com")
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF242434)),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.height(30.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("open.spotify.com", fontSize = 10.sp, color = Color.White)
                            }

                            Button(
                                onClick = {
                                    try {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(SpotifyAuth.LOGIN_URL)).apply {
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        context.startActivity(intent)
                                        addDiagLog("INFO", "Opened Chrome/Default Browser intent")
                                    } catch (e: Exception) {
                                        addDiagLog("ERROR", "Failed to launch external browser", "${e.message}")
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF242434)),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.height(30.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("تست در مرورگر اصلی", fontSize = 10.sp, color = Color.White)
                            }

                            Button(
                                onClick = { diagnosticLogs.clear() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF242434)),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.height(30.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("پاکسازی لاگ", fontSize = 10.sp, color = Color.Gray)
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Scrollable Live Log Box
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF0A0A0E))
                                .border(1.dp, Color(0xFF262634), RoundedCornerShape(8.dp))
                                .padding(8.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                            ) {
                                if (diagnosticLogs.isEmpty()) {
                                    Text(
                                        text = "در انتظار رویدادها و پاسخ‌ها...",
                                        color = Color.Gray,
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                } else {
                                    diagnosticLogs.reversed().forEach { log ->
                                        val color = when (log.level) {
                                            "ERROR" -> Color(0xFFFF5252)
                                            "WARN" -> Color(0xFFFFB74D)
                                            "COOKIE" -> Color(SPOTIFY_GREEN)
                                            "NET" -> Color(0xFF64B5F6)
                                            "JS" -> Color(0xFFBA68C8)
                                            else -> Color(0xFFB0B0B0)
                                        }
                                        Text(
                                            text = "[${log.timestamp}] [${log.level}] ${log.message}${if (log.details.isNotBlank()) " -> " + log.details else ""}",
                                            color = color,
                                            fontSize = 9.5.sp,
                                            fontFamily = FontFamily.Monospace,
                                            lineHeight = 13.sp
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

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

                                    // Configure WebSettings baseline for Spotify SPA & reCAPTCHA Enterprise
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
                                        allowFileAccessFromFileURLs = true
                                        allowUniversalAccessFromFileURLs = true
                                        mediaPlaybackRequiresUserGesture = false
                                        setGeolocationEnabled(true)
                                        cacheMode = WebSettings.LOAD_DEFAULT
                                        userAgentString = if (useDesktopUa) DESKTOP_UA else CHROME_MOBILE_UA

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
                                        override fun onPermissionRequest(request: PermissionRequest?) {
                                            try {
                                                addDiagLog("INFO", "Granting PermissionRequest: ${request?.resources?.joinToString()}")
                                                request?.grant(request.resources)
                                            } catch (e: Exception) {
                                                addDiagLog("WARN", "PermissionRequest grant failed: ${e.message}")
                                            }
                                        }

                                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                            webProgress = (newProgress.coerceIn(5, 100)) / 100f
                                            isWebLoading = newProgress < 100

                                            // Check cookies during progress updates
                                            cookieManager.flush()
                                            checkAndExtractCookie(cookieManager, "PROGRESS_$newProgress")
                                        }

                                        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                                            consoleMessage?.let {
                                                val level = when (it.messageLevel()) {
                                                    ConsoleMessage.MessageLevel.ERROR -> "ERROR"
                                                    ConsoleMessage.MessageLevel.WARNING -> "WARN"
                                                    else -> "JS"
                                                }
                                                addDiagLog(level, "JS: ${it.message()}", "line ${it.lineNumber()} of ${it.sourceId()}")
                                            }
                                            return true
                                        }

                                        override fun onCreateWindow(
                                            view: WebView?,
                                            isDialog: Boolean,
                                            isUserGesture: Boolean,
                                            resultMsg: Message?
                                        ): Boolean {
                                            addDiagLog("INFO", "onCreateWindow popup requested")
                                            val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                                            val popupWebView = WebView(ctx).apply {
                                                settings.apply {
                                                    javaScriptEnabled = true
                                                    domStorageEnabled = true
                                                    databaseEnabled = true
                                                    allowFileAccess = true
                                                    allowContentAccess = true
                                                    allowFileAccessFromFileURLs = true
                                                    allowUniversalAccessFromFileURLs = true
                                                    mediaPlaybackRequiresUserGesture = false
                                                    userAgentString = if (useDesktopUa) DESKTOP_UA else CHROME_MOBILE_UA
                                                }
                                                cookieManager.setAcceptThirdPartyCookies(this, true)
                                                webChromeClient = object : WebChromeClient() {
                                                    override fun onPermissionRequest(request: PermissionRequest?) {
                                                        try {
                                                            request?.grant(request.resources)
                                                        } catch (_: Exception) {}
                                                    }
                                                }
                                                webViewClient = object : WebViewClient() {
                                                    override fun shouldOverrideUrlLoading(v: WebView?, req: WebResourceRequest?): Boolean {
                                                        req?.url?.toString()?.let { targetUrl ->
                                                            addDiagLog("NET", "Popup redirecting to: $targetUrl")
                                                            view?.loadUrl(targetUrl)
                                                        }
                                                        return true
                                                    }

                                                    override fun onPageFinished(v: WebView?, url: String?) {
                                                        super.onPageFinished(v, url)
                                                        addDiagLog("NET", "Popup onPageFinished: $url")
                                                        cookieManager.flush()
                                                        checkAndExtractCookie(cookieManager, "POPUP_FINISHED")
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
                                            val targetUrl = request?.url?.toString() ?: ""
                                            currentUrl = targetUrl
                                            addDiagLog("NET", "shouldOverrideUrlLoading: $targetUrl")
                                            cookieManager.flush()
                                            checkAndExtractCookie(cookieManager, "OVERRIDE_URL")
                                            return false
                                        }

                                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                            super.onPageStarted(view, url, favicon)
                                            currentUrl = url ?: ""
                                            addDiagLog("NET", "onPageStarted: $url")
                                            cookieManager.flush()
                                            checkAndExtractCookie(cookieManager, "PAGE_STARTED")
                                        }

                                        override fun onPageFinished(view: WebView?, url: String?) {
                                            super.onPageFinished(view, url)
                                            currentUrl = url ?: ""
                                            isWebLoading = false
                                            addDiagLog("NET", "onPageFinished: $url")
                                            cookieManager.flush()
                                            checkAndExtractCookie(cookieManager, "PAGE_FINISHED")
                                        }

                                        override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                                            super.doUpdateVisitedHistory(view, url, isReload)
                                            currentUrl = url ?: ""
                                            cookieManager.flush()
                                            checkAndExtractCookie(cookieManager, "HISTORY_UPDATE")
                                        }

                                        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                                            super.onReceivedError(view, request, error)
                                            val desc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) error?.description?.toString() ?: "" else ""
                                            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) error?.errorCode ?: 0 else 0
                                            val isMain = request?.isForMainFrame == true
                                            addDiagLog("ERROR", "WebResourceError [Code $code] (MainFrame=$isMain)", "Desc: $desc, URL: ${request?.url}")
                                            if (isMain) {
                                                isWebLoading = false
                                                statusMessage = "خطای بارگذاری صفحه: $desc (کد $code)"
                                                hasError = true
                                            }
                                        }

                                        override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                                            super.onReceivedHttpError(view, request, errorResponse)
                                            val statusCode = errorResponse?.statusCode ?: 0
                                            val reason = errorResponse?.reasonPhrase ?: ""
                                            val url = request?.url?.toString() ?: ""
                                            if (request?.isForMainFrame == true || statusCode in listOf(401, 403, 429, 500, 502, 503)) {
                                                addDiagLog("WARN", "HTTP Error $statusCode $reason", "URL: $url")
                                            }
                                        }

                                        override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                                            addDiagLog("ERROR", "SSL Error: ${error?.primaryError}", "URL: ${error?.url}")
                                            super.onReceivedSslError(view, handler, error)
                                        }
                                    }

                                    addDiagLog("NET", "Loading initial URL: ${SpotifyAuth.LOGIN_URL}")
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
