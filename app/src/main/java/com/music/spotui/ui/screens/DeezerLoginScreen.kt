package com.music.spotui.ui.screens

import android.content.ClipboardManager
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.music.spotui.R
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
import java.util.concurrent.atomic.AtomicBoolean

private const val DEEZER_LOGIN_URL = "https://www.deezer.com/login"
private const val DEEZER_PURPLE = 0xFFA238FF
private const val CHROME_MOBILE_UA =
    "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

/**
 * Native Deezer Login Screen for Sepotify.
 * Provides explicit input boxes for user credentials (Email & Password), direct Web login, and ARL token input.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeezerLoginScreen(navController: NavController, next: String = "") {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    var deezerEmail by remember { mutableStateOf("") }
    var deezerPassword by remember { mutableStateOf("") }
    var isPasswordVisible by remember { mutableStateOf(false) }

    var arlInput by remember { mutableStateOf(getDeezerArl(context) ?: "") }
    var isProcessing by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("") }
    var hasError by remember { mutableStateOf(false) }
    var isConnected by remember { mutableStateOf(getDeezerArl(context)?.isNotBlank() == true) }
    var currentTier by remember { mutableStateOf(getDeezerTier(context)) }
    var showGuide by remember { mutableStateOf(false) }

    // Mode: 0 = User/Pass & ARL Form, 1 = Direct Web View
    var loginMode by remember { mutableIntStateOf(0) }

    // Web view state
    var webViewLoading by remember { mutableStateOf(true) }
    var webViewProgress by remember { mutableFloatStateOf(0.1f) }
    var deezerWebViewRef by remember { mutableStateOf<WebView?>(null) }
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
                isProcessing = false
                isConnected = true
                currentTier = tier
                statusMessage = "Connected: $tier"
                delay(400)
                completeAndNavigate()
            }
        }
    }

    // Direct background cookie polling while on Deezer screen
    LaunchedEffect(Unit) {
        while (isActive && !isConnected) {
            delay(1200)
            val cookieManager = CookieManager.getInstance()
            val arl = extractDeezerCookie(cookieManager, "arl")
            if (!arl.isNullOrBlank() && captured.compareAndSet(false, true)) {
                arlInput = arl
                withContext(Dispatchers.Main) {
                    executeDeezerAuth(arl)
                }
                break
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
                .padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Top Navigation Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { navController.popBackStack() },
                    modifier = Modifier.testTag("deezer_login_back_button"),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White,
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Deezer HiFi Connection",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Lossless Audio Card
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF191325)),
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
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(Color(DEEZER_PURPLE).copy(alpha = 0.25f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("🎧", fontSize = 18.sp)
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Deezer Lossless Audio",
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = "1411kbps Studio FLAC Quality",
                                    color = Color(DEEZER_PURPLE),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }

                        // Mode switcher
                        Row(
                            modifier = Modifier
                                .background(Color(0xFF261D36), RoundedCornerShape(20.dp))
                                .padding(2.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(if (loginMode == 0) Color(DEEZER_PURPLE) else Color.Transparent)
                                    .clickable { loginMode = 0 }
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "Credentials",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(if (loginMode == 1) Color(DEEZER_PURPLE) else Color.Transparent)
                                    .clickable { loginMode = 1 }
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "Web Portal",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

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

            Spacer(modifier = Modifier.height(16.dp))

            // MODE 0: Credentials & ARL Token Form
            if (loginMode == 0) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF151020)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Deezer User & Password",
                            color = Color(DEEZER_PURPLE),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
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
                                .testTag("deezer_email_field"),
                            shape = RoundedCornerShape(10.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(DEEZER_PURPLE),
                                unfocusedBorderColor = Color(0xFF382A4A),
                                focusedContainerColor = Color(0xFF0F0B17),
                                unfocusedContainerColor = Color(0xFF0F0B17),
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
                                IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                                    Icon(
                                        painter = painterResource(
                                            id = if (isPasswordVisible) R.drawable.visibility else R.drawable.visibility_off
                                        ),
                                        contentDescription = "Toggle password",
                                        tint = Color.Gray,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            },
                            visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("deezer_password_field"),
                            shape = RoundedCornerShape(10.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(DEEZER_PURPLE),
                                unfocusedBorderColor = Color(0xFF382A4A),
                                focusedContainerColor = Color(0xFF0F0B17),
                                unfocusedContainerColor = Color(0xFF0F0B17),
                            ),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = {
                                focusManager.clearFocus()
                                if (deezerEmail.isNotBlank() && deezerPassword.isNotBlank()) {
                                    loginMode = 1
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
                                    loginMode = 1
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
                                    statusMessage = "Please enter both Deezer email and password."
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
                                .testTag("deezer_credentials_submit"),
                        ) {
                            Text("Sign In with Deezer Credentials", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = "Or Deezer ARL Token",
                            color = Color(DEEZER_PURPLE),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(modifier = Modifier.height(6.dp))

                        OutlinedTextField(
                            value = arlInput,
                            onValueChange = { arlInput = it },
                            placeholder = { Text("Paste your 'arl' cookie string…", color = Color(0xFF666677), fontSize = 13.sp) },
                            singleLine = false,
                            maxLines = 3,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("deezer_arl_input_field"),
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

                        Spacer(modifier = Modifier.height(8.dp))

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
                                    .padding(10.dp)
                            ) {
                                Column {
                                    Text("1. Open deezer.com in browser and log in.", color = Color(0xFFCCCCCC), fontSize = 11.sp)
                                    Spacer(modifier = Modifier.height(3.dp))
                                    Text("2. DevTools (F12) → Application → Cookies → https://www.deezer.com.", color = Color(0xFFCCCCCC), fontSize = 11.sp)
                                    Spacer(modifier = Modifier.height(3.dp))
                                    Text("3. Copy 'arl' cookie and paste it above.", color = Color(0xFFCCCCCC), fontSize = 11.sp)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Button(
                            onClick = {
                                focusManager.clearFocus()
                                executeDeezerAuth(arlInput.trim())
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF261D36),
                                contentColor = Color.White,
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                                .testTag("deezer_connect_button"),
                            enabled = !isProcessing,
                        ) {
                            if (isProcessing) {
                                CircularProgressIndicator(
                                    color = Color.White,
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Verifying…", fontWeight = FontWeight.Bold)
                            } else {
                                Text(
                                    text = if (isConnected) "Verify & Save Token" else "Connect Deezer HiFi",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                )
                            }
                        }
                    }
                }
            }

            // MODE 1: Direct Interactive Web Portal
            if (loginMode == 1) {
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

                    if (webViewLoading) {
                        LinearProgressIndicator(
                            progress = { webViewProgress },
                            modifier = Modifier.fillMaxWidth().height(2.dp),
                            color = Color(DEEZER_PURPLE),
                            trackColor = Color.DarkGray,
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(480.dp)
                    ) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                val cookieManager = CookieManager.getInstance()
                                cookieManager.setAcceptCookie(true)

                                WebView(ctx).apply {
                                    deezerWebViewRef = this
                                    setBackgroundColor(android.graphics.Color.parseColor("#12101A"))
                                    cookieManager.setAcceptThirdPartyCookies(this, true)

                                    settings.apply {
                                        javaScriptEnabled = true
                                        domStorageEnabled = true
                                        databaseEnabled = true
                                        loadWithOverviewMode = true
                                        useWideViewPort = true
                                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                        javaScriptCanOpenWindowsAutomatically = true
                                        setSupportMultipleWindows(true)
                                        setSupportZoom(true)
                                        builtInZoomControls = false
                                        displayZoomControls = false
                                        allowFileAccess = false
                                        allowContentAccess = true
                                        cacheMode = WebSettings.LOAD_DEFAULT
                                        userAgentString = CHROME_MOBILE_UA
                                    }

                                    webChromeClient = object : WebChromeClient() {
                                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                            webViewProgress = (newProgress.coerceIn(5, 100)) / 100f
                                            webViewLoading = newProgress < 100
                                        }

                                        override fun onCreateWindow(
                                            view: WebView?,
                                            isDialog: Boolean,
                                            isUserGesture: Boolean,
                                            resultMsg: android.os.Message?
                                        ): Boolean {
                                            val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                                            val tempWebView = WebView(ctx).apply {
                                                webViewClient = object : WebViewClient() {
                                                    override fun shouldOverrideUrlLoading(v: WebView?, req: WebResourceRequest?): Boolean {
                                                        req?.url?.toString()?.let { targetUrl ->
                                                            view?.loadUrl(targetUrl)
                                                        }
                                                        return true
                                                    }
                                                }
                                            }
                                            transport.webView = tempWebView
                                            resultMsg.sendToTarget()
                                            return true
                                        }
                                    }

                                    webViewClient = object : WebViewClient() {
                                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                            val scheme = request?.url?.scheme?.lowercase() ?: ""
                                            if (scheme != "http" && scheme != "https") {
                                                cookieManager.flush()
                                                val extractedArl = extractDeezerCookie(cookieManager, "arl")
                                                if (!extractedArl.isNullOrBlank() && captured.compareAndSet(false, true)) {
                                                    arlInput = extractedArl
                                                    executeDeezerAuth(extractedArl)
                                                }
                                                return true
                                            }
                                            return false
                                        }

                                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                            super.onPageStarted(view, url, favicon)
                                            cookieManager.flush()
                                            val extractedArl = extractDeezerCookie(cookieManager, "arl")
                                            if (!extractedArl.isNullOrBlank() && captured.compareAndSet(false, true)) {
                                                arlInput = extractedArl
                                                executeDeezerAuth(extractedArl)
                                            }
                                        }

                                        override fun onPageFinished(view: WebView?, url: String?) {
                                            super.onPageFinished(view, url)
                                            webViewLoading = false
                                            cookieManager.flush()
                                            val extractedArl = extractDeezerCookie(cookieManager, "arl")
                                            if (!extractedArl.isNullOrBlank() && captured.compareAndSet(false, true)) {
                                                arlInput = extractedArl
                                                executeDeezerAuth(extractedArl)
                                            }
                                        }

                                        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                                            super.onReceivedError(view, request, error)
                                            if (request?.isForMainFrame == true) {
                                                webViewLoading = false
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

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
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
