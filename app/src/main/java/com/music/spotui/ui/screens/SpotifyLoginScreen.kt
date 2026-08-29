package com.music.spotui.ui.screens

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

private const val SPOTIFY_GREEN = 0xFF1ED760
private const val DEEZER_PURPLE = 0xFFA238FF
private const val CHROME_MOBILE_UA =
    "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
private const val CHROME_DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

/**
 * Native Spotify & Deezer Login Screen.
 * Provides explicit input boxes for credentials and session tokens (Spotify `sp_dc` and Deezer `arl`),
 * avoiding blank WebView rendering issues while strictly requiring authentication before entering the app.
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

    // Spotify form fields
    var spotifyEmail by remember { mutableStateOf("") }
    var spotifyPassword by remember { mutableStateOf("") }
    var isPasswordVisible by remember { mutableStateOf(false) }
    var spotifySpDc by remember { mutableStateOf(SpotifySession.spDc(context)) }
    var showSpotifyGuide by remember { mutableStateOf(false) }

    // Deezer form fields
    var deezerArlInput by remember { mutableStateOf(deezerArlStored) }
    var showDeezerGuide by remember { mutableStateOf(false) }

    // UI state
    var isProcessing by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("") }
    var hasError by remember { mutableStateOf(false) }
    var successMessage by remember { mutableStateOf("") }

    // Optional Web View sheet state (for users who prefer web login popup)
    var showWebLoginSheet by remember { mutableStateOf(false) }
    var webViewLoading by remember { mutableStateOf(true) }
    var webViewProgress by remember { mutableFloatStateOf(0.1f) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var isDesktopMode by remember { mutableStateOf(false) }
    val tokenFetchStarted = remember { AtomicBoolean(false) }

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
            statusMessage = "Please enter your Spotify sp_dc session token or credentials."
            return
        }

        isProcessing = true
        hasError = false
        successMessage = ""
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
                        // If Deezer is not logged in yet, prompt Deezer tab
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
                    statusMessage = "Spotify login failed: ${errorCaught?.localizedMessage ?: "Invalid sp_dc cookie. Please verify token."}"
                }
            }
        }
    }

    // Process Deezer Login using ARL
    fun executeDeezerLogin(arlValue: String) {
        val cleanArl = arlValue.trim()
        if (cleanArl.isBlank()) {
            hasError = true
            statusMessage = "Please enter your Deezer ARL token."
            return
        }

        isProcessing = true
        hasError = false
        successMessage = ""
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0C0C10))
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
            Spacer(modifier = Modifier.height(12.dp))

            // App Brand Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
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
                        contentDescription = "SpotUI Logo",
                        tint = Color.White,
                        modifier = Modifier.size(30.dp),
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "SpotUI Music",
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Sign in to your streaming accounts",
                        color = Color(0xFFB3B3B3),
                        fontSize = 13.sp,
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Service status indicator cards
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Spotify status chip
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSpotifyLoggedIn) Color(0xFF132A1C) else Color(0xFF1E1E24)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .border(
                            1.dp,
                            if (isSpotifyLoggedIn) Color(SPOTIFY_GREEN).copy(alpha = 0.6f) else Color(0xFF333333),
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
                                .background(if (isSpotifyLoggedIn) Color(SPOTIFY_GREEN) else Color(0xFFE57373))
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
                                text = if (isSpotifyLoggedIn) "Connected ✓" else "Required",
                                color = if (isSpotifyLoggedIn) Color(SPOTIFY_GREEN) else Color(0xFFE57373),
                                fontSize = 11.sp,
                            )
                        }
                    }
                }

                // Deezer status chip
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (isDeezerLoggedIn) Color(0xFF261536) else Color(0xFF1E1E24)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .border(
                            1.dp,
                            if (isDeezerLoggedIn) Color(DEEZER_PURPLE).copy(alpha = 0.6f) else Color(0xFF333333),
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
                                .background(if (isDeezerLoggedIn) Color(DEEZER_PURPLE) else Color(0xFFE57373))
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
                                text = if (isDeezerLoggedIn) "Connected ✓" else "Required",
                                color = if (isDeezerLoggedIn) Color(DEEZER_PURPLE) else Color(0xFFE57373),
                                fontSize = 11.sp,
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Navigation Tab Row
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color(0xFF16161D),
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
                            "1. Spotify Account",
                            fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal,
                            color = if (selectedTab == 0) Color(SPOTIFY_GREEN) else Color.Gray,
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
                        )
                    },
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            // TAB 0: SPOTIFY LOGIN FORM
            if (selectedTab == 0) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF16161E)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text(
                            text = "Spotify Credentials & Session",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Enter your Spotify session cookie or credentials to access your playlists, artists, and library.",
                            color = Color(0xFF9E9E9E),
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // Spotify sp_dc session token input (Primary reliable method)
                        Text(
                            text = "Spotify Session Token (sp_dc)",
                            color = Color(SPOTIFY_GREEN),
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

                        Spacer(modifier = Modifier.height(10.dp))

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
                                text = if (showSpotifyGuide) "Hide cookie guide" else "How to get your Spotify sp_dc cookie",
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
                                    Text("1. Open open.spotify.com on desktop/browser and log in.", color = Color(0xFFCCCCCC), fontSize = 11.sp)
                                    Spacer(modifier = Modifier.height(3.dp))
                                    Text("2. Press F12 (DevTools) → Application → Cookies → open.spotify.com.", color = Color(0xFFCCCCCC), fontSize = 11.sp)
                                    Spacer(modifier = Modifier.height(3.dp))
                                    Text("3. Copy the 'sp_dc' cookie value and paste it above.", color = Color(0xFFCCCCCC), fontSize = 11.sp)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Alternative: Email & Password
                        Text(
                            text = "Or Sign In with Spotify Credentials",
                            color = Color(0xFFCCCCCC),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(modifier = Modifier.height(8.dp))

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
                                if (spotifySpDc.isNotBlank()) {
                                    executeSpotifyLogin(spotifySpDc)
                                } else if (spotifyEmail.isNotBlank()) {
                                    showWebLoginSheet = true
                                }
                            })
                        )

                        Spacer(modifier = Modifier.height(18.dp))

                        // Submit Spotify Login Button
                        Button(
                            onClick = {
                                focusManager.clearFocus()
                                if (spotifySpDc.isNotBlank()) {
                                    executeSpotifyLogin(spotifySpDc)
                                } else {
                                    showWebLoginSheet = true
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(SPOTIFY_GREEN),
                                contentColor = Color.Black,
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .testTag("spotify_login_submit_button"),
                            enabled = !isProcessing,
                        ) {
                            if (isProcessing && selectedTab == 0) {
                                CircularProgressIndicator(
                                    color = Color.Black,
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text("Verifying…", fontWeight = FontWeight.Bold)
                            } else {
                                Icon(
                                    imageVector = if (isSpotifyLoggedIn) Icons.Default.CheckCircle else Icons.Default.Lock,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (isSpotifyLoggedIn) "Spotify Verified (Re-verify)" else "Log In to Spotify",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Optional Web browser sign-in button
                        Button(
                            onClick = { showWebLoginSheet = true },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF242430),
                                contentColor = Color.White,
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .testTag("open_spotify_web_login_button"),
                        ) {
                            Text("Open In-App Web Sign-In Browser", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            // TAB 1: DEEZER LOGIN FORM
            if (selectedTab == 1) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1424)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(Color(DEEZER_PURPLE).copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("🎵", fontSize = 18.sp)
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Deezer HiFi Connection",
                                    color = Color.White,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = "Required for Lossless FLAC & 320kbps streams",
                                    color = Color(DEEZER_PURPLE),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "SpotUI streams real, uncensored studio audio directly from Deezer. Connect your account token to enable instant HiFi playback.",
                            color = Color(0xFFB3B3B3),
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = "Deezer ARL Token",
                            color = Color(DEEZER_PURPLE),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(modifier = Modifier.height(6.dp))

                        OutlinedTextField(
                            value = deezerArlInput,
                            onValueChange = { deezerArlInput = it },
                            placeholder = { Text("Paste your Deezer 'arl' cookie string…", color = Color(0xFF666666), fontSize = 13.sp) },
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

                        Spacer(modifier = Modifier.height(10.dp))

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
                                    Text("1. Open deezer.com on any browser and log in.", color = Color(0xFFCCCCCC), fontSize = 11.sp)
                                    Spacer(modifier = Modifier.height(3.dp))
                                    Text("2. Open DevTools (F12) → Application → Cookies → https://www.deezer.com.", color = Color(0xFFCCCCCC), fontSize = 11.sp)
                                    Spacer(modifier = Modifier.height(3.dp))
                                    Text("3. Copy the 'arl' cookie value and paste it into the field above.", color = Color(0xFFCCCCCC), fontSize = 11.sp)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        // Submit Deezer Login Button
                        Button(
                            onClick = {
                                focusManager.clearFocus()
                                executeDeezerLogin(deezerArlInput)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(DEEZER_PURPLE),
                                contentColor = Color.White,
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .testTag("deezer_login_submit_button"),
                            enabled = !isProcessing,
                        ) {
                            if (isProcessing && selectedTab == 1) {
                                CircularProgressIndicator(
                                    color = Color.White,
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text("Connecting Deezer…", fontWeight = FontWeight.Bold)
                            } else {
                                Icon(
                                    imageVector = if (isDeezerLoggedIn) Icons.Default.CheckCircle else Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (isDeezerLoggedIn) "Deezer Connected ($deezerTier)" else "Connect Deezer HiFi",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Status feedback message
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
                Spacer(modifier = Modifier.height(16.dp))
            }

            // PRIMARY ACTION: ENTER APP (Strictly gated, no skip)
            val canProceed = isSpotifyLoggedIn
            Button(
                onClick = {
                    if (!isSpotifyLoggedIn) {
                        selectedTab = 0
                        hasError = true
                        statusMessage = "Please log in to Spotify to proceed."
                    } else if (!isDeezerLoggedIn) {
                        selectedTab = 1
                        hasError = true
                        statusMessage = "Please connect your Deezer HiFi account for lossless playback."
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
                    .height(54.dp)
                    .testTag("enter_app_button"),
            ) {
                Text(
                    text = if (isSpotifyLoggedIn && isDeezerLoggedIn) "Enter SpotUI Music"
                           else if (isSpotifyLoggedIn) "Complete Deezer Setup to Continue"
                           else "Log in to Continue",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "SpotUI requires authentication to provide personalized recommendations and HiFi playback. Skipping login is disabled.",
                color = Color(0xFF666666),
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                lineHeight = 15.sp,
            )

            Spacer(modifier = Modifier.height(24.dp))
        }

        // OPTIONAL: In-App Web Sign-In Modal Sheet (if user chooses to use Spotify Web portal)
        if (showWebLoginSheet) {
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

            ModalBottomSheet(
                onDismissRequest = { showWebLoginSheet = false },
                sheetState = sheetState,
                containerColor = Color(0xFF121212),
                dragHandle = null,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                ) {
                    // Top header for webview
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .background(Color(0xFF1E1E24))
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
                            Icon(Icons.Default.Refresh, contentDescription = "Reload", tint = Color.White)
                        }

                        Text(
                            text = "Spotify Web Sign-In",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                        )

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = {
                                isDesktopMode = !isDesktopMode
                                webViewRef?.settings?.userAgentString = if (isDesktopMode) CHROME_DESKTOP_UA else CHROME_MOBILE_UA
                                webViewRef?.reload()
                            }) {
                                Text(if (isDesktopMode) "Mobile" else "Desktop", color = Color.LightGray, fontSize = 11.sp)
                            }
                            IconButton(onClick = { showWebLoginSheet = false }) {
                                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                            }
                        }
                    }

                    if (webViewLoading) {
                        LinearProgressIndicator(
                            progress = { webViewProgress },
                            modifier = Modifier.fillMaxWidth().height(2.dp),
                            color = Color(SPOTIFY_GREEN),
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
                                settings.javaScriptCanOpenWindowsAutomatically = true
                                settings.setSupportMultipleWindows(false)
                                settings.userAgentString = if (isDesktopMode) CHROME_DESKTOP_UA else CHROME_MOBILE_UA

                                webChromeClient = object : WebChromeClient() {
                                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                        webViewProgress = (newProgress.coerceIn(5, 100)) / 100f
                                        webViewLoading = newProgress < 100
                                    }
                                }

                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                        val url = request?.url?.toString() ?: return false
                                        return !(url.startsWith("http://") || url.startsWith("https://"))
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        webViewLoading = false
                                        cookieManager.flush()
                                        val spDc = extractCookie("sp_dc")
                                        if (!spDc.isNullOrBlank() && tokenFetchStarted.compareAndSet(false, true)) {
                                            spotifySpDc = spDc
                                            showWebLoginSheet = false
                                            executeSpotifyLogin(spDc)
                                        }
                                    }

                                    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                                        if (request?.isForMainFrame == true) {
                                            webViewLoading = false
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
