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
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NavController
import com.metrolist.spotify.Spotify
import com.metrolist.spotify.SpotifyAuth
import com.music.spotui.R
import com.music.spotui.data.api.SpotifySession
import com.music.spotui.ui.navigation.Routes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
    val level: String,
    val message: String,
    val details: String = ""
)

/**
 * Enhanced Spotify Login Screen featuring:
 * 1. Dedicated Fullscreen In-App Web Browser modal with active redirection & cookie interception
 * 2. Real-time background cookie poller that triggers instantly when Spotify redirects to open.spotify.com
 * 3. Quick Email/Password autofill injector
 * 4. Manual sp_dc token paste & diagnostics console
 */
@OptIn(ExperimentalMaterial3Api::class)
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

    // In-App Internal Web Browser Dialog State
    var isBrowserOpen by remember { mutableStateOf(false) }

    // Quick Autofill state
    var emailInput by remember { mutableStateOf("") }
    var passwordInput by remember { mutableStateOf("") }

    // Manual token input state
    var showManualToken by remember { mutableStateOf(false) }
    var manualSpDc by remember { mutableStateOf(SpotifySession.spDc(context)) }

    // Diagnostics State
    var isDiagnosticsOpen by remember { mutableStateOf(false) }
    var useDesktopUa by remember { mutableStateOf(false) }
    var currentUrl by remember { mutableStateOf(SpotifyAuth.LOGIN_URL) }
    var lastCookiesSummary by remember { mutableStateOf("No cookies scanned yet") }
    var spDcFoundStatus by remember { mutableStateOf("sp_dc: NOT FOUND") }
    val diagnosticLogs = remember { mutableStateListOf<DiagnosticLogItem>() }

    // WebView controller reference
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var webProgress by remember { mutableFloatStateOf(0.1f) }
    var isWebLoading by remember { mutableStateOf(true) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    val tokenExtracted = remember { AtomicBoolean(false) }

    fun addDiagLog(level: String, message: String, details: String = "") {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        if (diagnosticLogs.size > 200) {
            diagnosticLogs.removeAt(0)
        }
        diagnosticLogs.add(DiagnosticLogItem(time, level, message, details))
        Timber.tag("SpotifyDiag").d("[$level] $message: $details")
    }

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
        statusMessage = "کوکی لاگین اسپاتیفای دریافت شد! در حال اعتبارسنجی حساب..."
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
                        statusMessage = "حساب اسپاتیفای با موفقیت متصل شد! در حال ورود..."
                        isProcessing = false
                        isBrowserOpen = false
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
                    statusMessage = "خطا در تایید توکن: $errDesc"
                    addDiagLog("ERROR", "All 3 verification attempts failed", errDesc)
                }
            }
        }
    }

    // Scan cookies across all relevant Spotify domains
    fun checkAndExtractCookie(cookieManager: CookieManager, eventSource: String): Boolean {
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
        lastCookiesSummary = "کوکی‌ها (${allFoundKeys.size}): $keysStr"

        if (foundSpDc != null) {
            spDcFoundStatus = "sp_dc: یافت شد! (طول ${foundSpDc?.length})"
            if (!tokenExtracted.get()) {
                addDiagLog("COOKIE", "کوکی sp_dc دریافت شد ($eventSource)", "Length=${foundSpDc?.length}")
            }
            if (tokenExtracted.compareAndSet(false, true)) {
                manualSpDc = foundSpDc!!
                executeSpotifyLogin(foundSpDc!!)
            }
            return true
        } else {
            spDcFoundStatus = "sp_dc: یافت نشد (${allFoundKeys.size} کوکی دیگر موجود است)"
            return false
        }
    }

    // Background continuous poller while browser or screen is active
    LaunchedEffect(isBrowserOpen) {
        if (isBrowserOpen) {
            tokenExtracted.set(false)
        }
        while (isActive) {
            try {
                val cookieManager = CookieManager.getInstance()
                cookieManager.flush()
                checkAndExtractCookie(cookieManager, "BACKGROUND_POLLER")
            } catch (_: Exception) {}
            delay(800)
        }
    }

    // Auto-inject credentials into active WebView
    fun injectCredentials() {
        if (emailInput.isBlank() || passwordInput.isBlank()) {
            statusMessage = "لطفاً ایمیل و رمز عبور را وارد کنید"
            return
        }
        val script = SpotifyAuth.getCredentialInjectionScript(emailInput, passwordInput)
        webViewRef?.evaluateJavascript(script) { result ->
            addDiagLog("JS", "Credential script injected", "Result: $result")
            statusMessage = "اطلاعات ورود به فرم اسپاتیفای تزریق شد..."
        }
    }

    // Cleanup WebView
    DisposableEffect(Unit) {
        addDiagLog("INFO", "SpotifyLoginScreen initialized")
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
            // Header: App Title, Branding & Diagnostics Toggle
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
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color(SPOTIFY_GREEN).copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_sepotify_logo),
                            contentDescription = "Sepotify Logo",
                            tint = Color(SPOTIFY_GREEN),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Sepotify",
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (isSpotifyLoggedIn) "حساب متصل است ✓" else "اتصال حساب کاربری اسپاتیفای",
                            color = if (isSpotifyLoggedIn) Color(SPOTIFY_GREEN) else Color(0xFF9E9E9E),
                            fontSize = 12.sp
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Diagnostics Toggle
                    Button(
                        onClick = { isDiagnosticsOpen = !isDiagnosticsOpen },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isDiagnosticsOpen) Color(0xFFE57373) else Color(0xFF20202E),
                            contentColor = if (isDiagnosticsOpen) Color.Black else Color(0xFFFFB74D)
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.height(32.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Default.Build, contentDescription = "Diagnostics", modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = if (isDiagnosticsOpen) "بستن" else "عیب‌یابی", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    TextButton(
                        onClick = { showManualToken = !showManualToken },
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text(
                            text = if (showManualToken) "مخفی‌سازی توکن" else "توکن دستی",
                            color = Color(SPOTIFY_GREEN),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // PRIMARY HERO ACTION: Open Dedicated Fullscreen In-App Web Browser
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF141420)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(SPOTIFY_GREEN).copy(alpha = 0.3f), RoundedCornerShape(16.dp))
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "ورود از طریق مرورگر داخلی (In-App Browser)",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "مرورگر داخلی اسپاتیفای را باز می‌کند و به محض ورود، کوکی‌ها را مستقیماً دریافت کرده و برنامه را باز می‌کند.",
                        color = Color(0xFFA0A0B0),
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 17.sp
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = {
                            isBrowserOpen = true
                            statusMessage = "در حال بارگذاری مرورگر داخلی اسپاتیفای..."
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(SPOTIFY_GREEN),
                            contentColor = Color.Black
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("open_in_app_browser_button")
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_sepotify_logo),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = Color.Black
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "باز کردن مرورگر داخلی و ورود به اسپاتیفای",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // QUICK AUTOFILL LOGIN CARD (ورود مستقیم و سریع با ایمیل/پسورد)
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF14141C)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "تزریق سریع اطلاعات کاربری (Autofill Helper)",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "در صورتی که کیبورد داخل سایت مشکل دارد، اطلاعات را اینجا وارد کنید و دکمه ورود را بزنید:",
                        color = Color(0xFF888899),
                        fontSize = 11.sp
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = emailInput,
                        onValueChange = { emailInput = it },
                        placeholder = { Text("ایمیل یا نام کاربری اسپاتیفای", color = Color(0xFF666677), fontSize = 12.sp) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("spotify_email_input"),
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(SPOTIFY_GREEN),
                            unfocusedBorderColor = Color(0xFF2C2C3C),
                            focusedContainerColor = Color(0xFF0D0D14),
                            unfocusedContainerColor = Color(0xFF0D0D14)
                        )
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = { passwordInput = it },
                        placeholder = { Text("رمز عبور اسپاتیفای", color = Color(0xFF666677), fontSize = 12.sp) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            focusManager.clearFocus()
                            isBrowserOpen = true
                            scope.launch {
                                delay(1200)
                                injectCredentials()
                            }
                        }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("spotify_password_input"),
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(SPOTIFY_GREEN),
                            unfocusedBorderColor = Color(0xFF2C2C3C),
                            focusedContainerColor = Color(0xFF0D0D14),
                            unfocusedContainerColor = Color(0xFF0D0D14)
                        )
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = {
                            focusManager.clearFocus()
                            isBrowserOpen = true
                            scope.launch {
                                delay(1500)
                                injectCredentials()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF282838),
                            contentColor = Color(SPOTIFY_GREEN)
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(42.dp),
                        enabled = emailInput.isNotBlank() && passwordInput.isNotBlank()
                    ) {
                        Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("ورود خودکار در مرورگر داخلی", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }

            // OPTIONAL MANUAL TOKEN PASTE ACCORDION
            AnimatedVisibility(
                visible = showManualToken,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF14141E)),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .border(1.dp, Color(0xFF2B2B3E), RoundedCornerShape(14.dp))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "جای‌گذاری مستقیم توکن (sp_dc)",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "اگر قبلاً کوکی sp_dc را از مرورگر رایانه یا ابزار توسعه استخراج کرده‌اید، مستقیماً اینجا پیست کنید:",
                            color = Color(0xFF888899),
                            fontSize = 11.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = manualSpDc,
                            onValueChange = { manualSpDc = it },
                            placeholder = { Text("متن کوکی 'sp_dc' را اینجا جای‌گذاری کنید...", color = Color(0xFF555566), fontSize = 12.sp) },
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
                                focusedContainerColor = Color(0xFF0E0E16),
                                unfocusedContainerColor = Color(0xFF0E0E16)
                            ),
                            trailingIcon = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (manualSpDc.isNotBlank()) {
                                        IconButton(onClick = { manualSpDc = "" }, modifier = Modifier.size(26.dp)) {
                                            Icon(Icons.Default.Close, contentDescription = "پاک کردن", tint = Color.Gray, modifier = Modifier.size(14.dp))
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
                                        Text("پیست", color = Color(SPOTIFY_GREEN), fontSize = 11.sp, fontWeight = FontWeight.Bold)
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
                            Text("اعتبارسنجی و اتصال حساب", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            }

            // DIAGNOSTICS CONSOLE PANEL
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
                        .padding(vertical = 12.dp)
                        .border(1.dp, Color(0xFFFFB74D).copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Build, contentDescription = null, tint = Color(0xFFFFB74D), modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("پنل عیب‌یابی زنده", color = Color(0xFFFFB74D), fontSize = 13.sp, fontWeight = FontWeight.Bold)
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
                                },
                                modifier = Modifier.height(28.dp)
                            ) {
                                Text("📋 کپی گزارش", color = Color(SPOTIFY_GREEN), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        Divider(color = Color(0xFF282836), modifier = Modifier.padding(vertical = 8.dp))

                        Text(text = "آدرس فعلی: $currentUrl", color = Color(0xFFE0E0E0), fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, maxLines = 2)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "وضعیت کوکی: $spDcFoundStatus",
                            color = if (spDcFoundStatus.contains("یافت شد")) Color(SPOTIFY_GREEN) else Color(0xFFFF8A80),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(text = lastCookiesSummary, color = Color(0xFF9E9E9E), fontSize = 10.sp, fontFamily = FontFamily.Monospace)

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
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF242434)),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.height(30.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("اسکن مجدد کوکی‌ها", fontSize = 10.sp, color = Color.White)
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
                                .height(140.dp)
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
                                    Text(text = "در انتظار رویدادها...", color = Color.Gray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
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

            // Guest Mode / Instant Access Action
            Button(
                onClick = {
                    if (!isSpotifyLoggedIn && !SpotifySession.isGuestMode(context)) {
                        SpotifySession.setGuestMode(context, true)
                    }
                    navigateToHome()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isSpotifyLoggedIn) Color(SPOTIFY_GREEN) else Color(0xFF222230),
                    contentColor = if (isSpotifyLoggedIn) Color.Black else Color.White
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("enter_app_button")
            ) {
                Text(
                    text = if (isSpotifyLoggedIn) "ورود به سپاتیفای" else "ورود به صورت مهمان (دسترسی فوری)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "با ورود به حساب اسپاتیفای، پلی‌لیست‌ها و سابقه شما همگام‌سازی می‌شود.",
                color = Color(0xFF7A7A88),
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                lineHeight = 15.sp
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // =========================================================================
    // DEDICATED FULLSCREEN IN-APP WEB BROWSER MODAL (مرورگر داخلی پیشرفته)
    // =========================================================================
    if (isBrowserOpen) {
        Dialog(
            onDismissRequest = { isBrowserOpen = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false
            )
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0D0D12))
                    .statusBarsPadding()
                    .navigationBarsPadding(),
                color = Color(0xFF0D0D12)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Browser Top App Bar & Controls
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF161622))
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { isBrowserOpen = false },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "بستن مرورگر", tint = Color.White)
                        }

                        IconButton(
                            onClick = { webViewRef?.goBack() },
                            enabled = canGoBack,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "بازگشت",
                                tint = if (canGoBack) Color.White else Color(0xFF555566)
                            )
                        }

                        IconButton(
                            onClick = { webViewRef?.goForward() },
                            enabled = canGoForward,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = "جلو",
                                tint = if (canGoForward) Color.White else Color(0xFF555566)
                            )
                        }

                        // URL Display Box
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .height(34.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF222230))
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Lock,
                                contentDescription = "Secure SSL",
                                tint = Color(SPOTIFY_GREEN),
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = currentUrl.replace("https://", ""),
                                color = Color(0xFFE0E0E0),
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        IconButton(
                            onClick = { webViewRef?.reload() },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "بارگذاری مجدد", tint = Color.White)
                        }
                    }

                    // Loading Progress Indicator
                    if (isWebLoading) {
                        LinearProgressIndicator(
                            progress = { webProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.5.dp),
                            color = Color(SPOTIFY_GREEN),
                            trackColor = Color(0xFF161622)
                        )
                    }

                    // Status Ribbon / Quick Action Banner
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF1B1B28))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            if (isProcessing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    color = Color(SPOTIFY_GREEN),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(Color(SPOTIFY_GREEN))
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isProcessing) "در حال تایید ورود و اتصال..." else "در حال رصد لاگین اسپاتیفای...",
                                color = Color(SPOTIFY_GREEN),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        // Manual Trigger: "بررسی ورود و خروج"
                        Button(
                            onClick = {
                                val cookieManager = CookieManager.getInstance()
                                cookieManager.flush()
                                val found = checkAndExtractCookie(cookieManager, "MANUAL_BROWSER_ACTION")
                                if (!found) {
                                    statusMessage = "کوکی لاگین هنوز دریافت نشده است. لطفاً ابتدا در اسپاتیفای وارد شوید."
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(SPOTIFY_GREEN),
                                contentColor = Color.Black
                            ),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.height(28.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                        ) {
                            Text("بررسی ورود ✓", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    // Navigation Quick-Links
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF12121A))
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedButton(
                            onClick = { webViewRef?.loadUrl(SpotifyAuth.LOGIN_URL) },
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.height(26.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) {
                            Text("صفحه ورود اصلی", fontSize = 10.sp, color = Color.White)
                        }

                        OutlinedButton(
                            onClick = { webViewRef?.loadUrl("https://open.spotify.com") },
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.height(26.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) {
                            Text("open.spotify.com", fontSize = 10.sp, color = Color.White)
                        }

                        if (emailInput.isNotBlank() && passwordInput.isNotBlank()) {
                            Button(
                                onClick = { injectCredentials() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E2E44)),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.height(26.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) {
                                Text("تزریق ایمیل و پسورد ⚡", fontSize = 10.sp, color = Color(SPOTIFY_GREEN), fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    // Full-Height In-App Browser View
                    Box(modifier = Modifier.fillMaxSize()) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                val cookieManager = CookieManager.getInstance()
                                cookieManager.setAcceptCookie(true)

                                WebView(ctx).apply {
                                    webViewRef = this
                                    setLayerType(View.LAYER_TYPE_HARDWARE, null)
                                    cookieManager.setAcceptThirdPartyCookies(this, true)

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
                                        setSupportZoom(true)
                                        builtInZoomControls = true
                                        displayZoomControls = false
                                        allowFileAccess = true
                                        allowContentAccess = true
                                        allowFileAccessFromFileURLs = true
                                        allowUniversalAccessFromFileURLs = true
                                        mediaPlaybackRequiresUserGesture = false
                                        setGeolocationEnabled(true)
                                        cacheMode = WebSettings.LOAD_DEFAULT
                                        userAgentString = if (useDesktopUa) DESKTOP_UA else CHROME_MOBILE_UA

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
                                            canGoBack = view?.canGoBack() ?: false
                                            canGoForward = view?.canGoForward() ?: false

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

                                            // If Spotify redirects to open.spotify.com, wait briefly and capture cookies
                                            if (targetUrl.startsWith("https://open.spotify.com")) {
                                                scope.launch {
                                                    delay(500)
                                                    cookieManager.flush()
                                                    checkAndExtractCookie(cookieManager, "REDIRECT_OPEN_SPOTIFY")
                                                }
                                            }
                                            return false
                                        }

                                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                            super.onPageStarted(view, url, favicon)
                                            currentUrl = url ?: ""
                                            canGoBack = view?.canGoBack() ?: false
                                            canGoForward = view?.canGoForward() ?: false
                                            addDiagLog("NET", "onPageStarted: $url")
                                            cookieManager.flush()
                                            checkAndExtractCookie(cookieManager, "PAGE_STARTED")
                                        }

                                        override fun onPageFinished(view: WebView?, url: String?) {
                                            super.onPageFinished(view, url)
                                            currentUrl = url ?: ""
                                            isWebLoading = false
                                            canGoBack = view?.canGoBack() ?: false
                                            canGoForward = view?.canGoForward() ?: false
                                            addDiagLog("NET", "onPageFinished: $url")
                                            cookieManager.flush()
                                            checkAndExtractCookie(cookieManager, "PAGE_FINISHED")

                                            // If redirected to open.spotify.com, verify cookies
                                            if (url?.startsWith("https://open.spotify.com") == true) {
                                                scope.launch {
                                                    delay(400)
                                                    cookieManager.flush()
                                                    checkAndExtractCookie(cookieManager, "FINISHED_OPEN_SPOTIFY")
                                                }
                                            }
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
        }
    }
}
