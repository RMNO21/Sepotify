package com.music.spotui.ui.screens

import android.content.ClipboardManager
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import com.music.spotui.data.preferences.getDeezerArl
import com.music.spotui.data.preferences.getDeezerTier
import com.music.spotui.data.preferences.setDeezerArl
import com.music.spotui.data.preferences.setDeezerTier
import com.music.spotui.deezer.DeezerSession
import com.music.spotui.ui.navigation.Routes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

private const val DEEZER_LOGIN_URL = "https://www.deezer.com/login"
private const val DEEZER_PURPLE = 0xFFA238FF

/**
 * Native Deezer Login Screen.
 * Provides explicit input boxes for the Deezer ARL token with clipboard paste support,
 * tier detection (FLAC / 320 / 128), and optional in-app web login sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeezerLoginScreen(navController: NavController, next: String = "") {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    var arlInput by remember { mutableStateOf(getDeezerArl(context) ?: "") }
    var isProcessing by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("") }
    var hasError by remember { mutableStateOf(false) }
    var isConnected by remember { mutableStateOf(getDeezerArl(context)?.isNotBlank() == true) }
    var currentTier by remember { mutableStateOf(getDeezerTier(context)) }
    var showGuide by remember { mutableStateOf(false) }

    // Web view sheet state
    var showWebSheet by remember { mutableStateOf(false) }
    var webViewLoading by remember { mutableStateOf(true) }
    var webViewProgress by remember { mutableFloatStateOf(0.1f) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    val captured = remember { AtomicBoolean(false) }

    fun pasteFromClipboard() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = clipboard?.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).text?.toString()?.trim() ?: ""
            if (text.isNotBlank()) {
                arlInput = text
            }
        }
    }

    fun completeAndNavigate() {
        if (next == "home" || next == "onboarding") {
            if (com.music.spotui.data.preferences.hasSpotiflacSession(context)) {
                navController.navigate(Routes.Home.route) {
                    popUpTo(Routes.DeezerLogin.route) { inclusive = true }
                }
            } else {
                navController.navigate("${Routes.SpotiflacVerify.route}?next=onboarding") {
                    popUpTo(Routes.DeezerLogin.route) { inclusive = true }
                }
            }
        } else {
            navController.popBackStack()
        }
    }

    fun executeDeezerAuth(cleanArl: String) {
        if (cleanArl.isBlank()) {
            hasError = true
            statusMessage = "Please enter your Deezer ARL token."
            return
        }

        isProcessing = true
        hasError = false
        statusMessage = "Authenticating with Deezer…"

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
                isProcessing = false
                isConnected = true
                currentTier = tier
                statusMessage = "Connected: $tier"
                delay(400)
                completeAndNavigate()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D0B12))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Top Navigation Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (navController.previousBackStackEntry != null) {
                    IconButton(
                        onClick = { navController.popBackStack() },
                        modifier = Modifier.testTag("deezer_login_back_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White,
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Deezer HiFi Connection",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Branding & Info Card
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF191325)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(Color(DEEZER_PURPLE).copy(alpha = 0.25f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("🎧", fontSize = 22.sp)
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Lossless FLAC Streaming",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = "1411kbps Studio Master Quality",
                                color = Color(DEEZER_PURPLE),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Deezer provides direct, uncensored audio streams. Enter your Deezer ARL token below to connect your account.",
                        color = Color(0xFFB8B0C8),
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                    )

                    if (isConnected && currentTier.isNotBlank()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = Color(DEEZER_PURPLE),
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Current Status: $currentTier",
                                color = Color(DEEZER_PURPLE),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ARL Token Input Field
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF151020)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = "Deezer ARL Token",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = arlInput,
                        onValueChange = { arlInput = it },
                        placeholder = { Text("Paste your 'arl' cookie string…", color = Color(0xFF666677), fontSize = 13.sp) },
                        singleLine = false,
                        maxLines = 4,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("deezer_arl_input_screen"),
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(DEEZER_PURPLE),
                            unfocusedBorderColor = Color(0xFF382A4A),
                            focusedContainerColor = Color(0xFF0F0B17),
                            unfocusedContainerColor = Color(0xFF0F0B17),
                        ),
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (arlInput.isNotBlank()) {
                                    IconButton(
                                        onClick = { arlInput = "" },
                                        modifier = Modifier.size(28.dp),
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
                                    onClick = { pasteFromClipboard() },
                                    modifier = Modifier.testTag("paste_deezer_arl_btn"),
                                ) {
                                    Text("Paste", color = Color(DEEZER_PURPLE), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            focusManager.clearFocus()
                            executeDeezerAuth(arlInput.trim())
                        })
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // How to find ARL guide expander
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { showGuide = !showGuide }
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
                            text = if (showGuide) "Hide token guide" else "How to obtain your Deezer ARL token",
                            color = Color(0xFF9988AA),
                            fontSize = 12.sp,
                        )
                    }

                    if (showGuide) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF0F0A18), RoundedCornerShape(8.dp))
                                .padding(12.dp)
                        ) {
                            Column {
                                Text("1. Open deezer.com in any web browser and log in.", color = Color(0xFFCCCCCC), fontSize = 12.sp)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("2. Press F12 (DevTools) → Application → Cookies → https://www.deezer.com.", color = Color(0xFFCCCCCC), fontSize = 12.sp)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("3. Copy the 'arl' cookie string (192-character alphanumeric key) and paste it here.", color = Color(0xFFCCCCCC), fontSize = 12.sp)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    // Connect Deezer Button
                    Button(
                        onClick = {
                            focusManager.clearFocus()
                            executeDeezerAuth(arlInput.trim())
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(DEEZER_PURPLE),
                            contentColor = Color.White,
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("deezer_connect_button"),
                        enabled = !isProcessing,
                    ) {
                        if (isProcessing) {
                            CircularProgressIndicator(
                                color = Color.White,
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("Verifying Deezer…", fontWeight = FontWeight.Bold)
                        } else {
                            Icon(
                                imageVector = if (isConnected) Icons.Default.CheckCircle else Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isConnected) "Verify & Save Token" else "Connect Deezer HiFi",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Optional In-app web sign-in
                    Button(
                        onClick = { showWebSheet = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF241B33),
                            contentColor = Color.White,
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .testTag("open_deezer_web_login_button"),
                    ) {
                        Text("Open Deezer Web Sign-In Browser", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Status message
            if (statusMessage.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (hasError) Color(0x33E57373) else Color(0x33A238FF),
                            RoundedCornerShape(10.dp)
                        )
                        .padding(12.dp)
                ) {
                    Text(
                        text = statusMessage,
                        color = if (hasError) Color(0xFFFF8A80) else Color(DEEZER_PURPLE),
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }

        // OPTIONAL: In-App Web Sign-In Modal Sheet for Deezer
        if (showWebSheet) {
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

            ModalBottomSheet(
                onDismissRequest = { showWebSheet = false },
                sheetState = sheetState,
                containerColor = Color(0xFF100B17),
                dragHandle = null,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                ) {
                    // Header
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .background(Color(0xFF1D142A))
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        IconButton(onClick = {
                            val cm = CookieManager.getInstance()
                            cm.removeAllCookies(null)
                            cm.flush()
                            webViewRef?.clearCache(true)
                            webViewRef?.loadUrl(DEEZER_LOGIN_URL)
                        }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Reload", tint = Color.White)
                        }

                        Text(
                            text = "Deezer Web Sign-In",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                        )

                        IconButton(onClick = { showWebSheet = false }) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                        }
                    }

                    if (webViewLoading) {
                        LinearProgressIndicator(
                            progress = { webViewProgress },
                            modifier = Modifier.fillMaxWidth().height(2.dp),
                            color = Color(DEEZER_PURPLE),
                            trackColor = Color.DarkGray,
                        )
                    }

                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
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

                                webChromeClient = object : WebChromeClient() {
                                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                        webViewProgress = (newProgress.coerceIn(5, 100)) / 100f
                                        webViewLoading = newProgress < 100
                                    }
                                }

                                webViewClient = object : WebViewClient() {
                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        webViewLoading = false
                                        cookieManager.flush()
                                        val extractedArl = extractDeezerCookie("arl")
                                        if (!extractedArl.isNullOrBlank() && captured.compareAndSet(false, true)) {
                                            arlInput = extractedArl
                                            showWebSheet = false
                                            executeDeezerAuth(extractedArl)
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

private fun extractDeezerCookie(name: String): String? {
    val all = CookieManager.getInstance().getCookie("https://www.deezer.com") ?: return null
    return all.split(";")
        .mapNotNull {
            val parts = it.trim().split("=", limit = 2)
            if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
        }
        .firstOrNull { it.first == name && it.second.isNotBlank() }
        ?.second
}
