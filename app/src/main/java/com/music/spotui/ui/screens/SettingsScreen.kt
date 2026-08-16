package com.music.spotui.ui.screens

import android.annotation.SuppressLint
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavController
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import com.metrolist.spotify.Spotify
import com.metrolist.spotify.SpotifyAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.music.spotui.data.preferences.*
import com.music.spotui.ui.theme.AppBackground
import com.music.spotui.ui.theme.AppPalette

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navController: NavController) {
    val context = LocalContext.current

    var wifiQ by remember { mutableStateOf(getWifiQuality(context)) }
    var cellQ by remember { mutableStateOf(getCellularQuality(context)) }
    var dlQ by remember { mutableStateOf(getDownloadQuality(context)) }
    var crossfadeMs by remember { mutableStateOf(getCrossfadeMs(context).toFloat()) }
    var videoFallback by remember { mutableStateOf(isVideoFallbackEnabled(context)) }
    // Read fresh each composition so returning from the Deezer login reflects it.
    val deezerConnected = com.music.spotui.data.preferences.getDeezerArl(context) != null
    val deezerTier = com.music.spotui.data.preferences.getDeezerTier(context)
    var deezerEnabled by remember { mutableStateOf(com.music.spotui.data.preferences.isDeezerEnabled(context)) }
    val scope = rememberCoroutineScope()
    var showSpotifyCookieDialog by remember { mutableStateOf(false) }
    var spotifyCookieInput by remember { mutableStateOf("") }
    var spotifySessionKey by remember { mutableStateOf(com.music.spotui.data.api.SpotifySession.spDc(context)) }
    val isSpotifyLoggedIn = spotifySessionKey.isNotBlank()

    Scaffold(
        containerColor = AppBackground,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Settings", color = Color.White, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .size(26.dp)
                            .clickable { navController.popBackStack() }
                    )
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = AppBackground)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            SectionTitle("Audio quality")
            QualityPicker(
                title = "Streaming over Wi-Fi",
                selected = wifiQ,
            ) { wifiQ = it; setWifiQuality(context, it) }

            QualityPicker(
                title = "Streaming over cellular",
                selected = cellQ,
            ) { cellQ = it; setCellularQuality(context, it) }

            QualityPicker(
                title = "Download quality",
                selected = dlQ,
            ) { dlQ = it; setDownloadQuality(context, it) }

            // Lossless FLAC comes from the Tidal community backend (no login) and, if
            // connected, Deezer HiFi. Falls back to best-quality YouTube on a miss.
            val losslessNote = if (deezerConnected) {
                "Lossless: Tidal (free) + Deezer${if (deezerTier.isNotBlank()) " $deezerTier" else ""} — real FLAC"
            } else {
                "Lossless: Tidal community FLAC (no login needed) — or add Deezer HiFi below"
            }
            Text(
                losslessNote,
                color = Color(0xFFB3B3B3),
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 4.dp, top = 6.dp),
            )

            Spacer(Modifier.height(12.dp))
            SectionTitle("Matching")
            SettingsSwitchRow(
                title = "Allow video fallback",
                subtitle = "Use regular YouTube videos only after Music song results fail",
                checked = videoFallback,
            ) {
                videoFallback = it
                setVideoFallbackEnabled(context, it)
            }

            Spacer(Modifier.height(12.dp))
            SectionTitle("Crossfade")
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Crossfade", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(
                    if (crossfadeMs <= 0f) "Off" else "${(crossfadeMs / 1000f).let { String.format("%.0f", it) }}s",
                    color = if (crossfadeMs <= 0f) Color(0xFFB3B3B3) else AppPalette,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                "Blend the end of a song into the start of the next",
                color = Color(0xFFB3B3B3),
                fontSize = 13.sp,
            )
            Slider(
                value = crossfadeMs,
                onValueChange = { crossfadeMs = it },
                onValueChangeFinished = { setCrossfadeMs(context, crossfadeMs.toInt()) },
                valueRange = 0f..CROSSFADE_MAX_MS.toFloat(),
                steps = (CROSSFADE_MAX_MS / 1000) - 1, // 1-second stops
                colors = SliderDefaults.colors(
                    thumbColor = AppPalette,
                    activeTrackColor = AppPalette,
                    inactiveTrackColor = Color(0xFF333333),
                ),
            )
            Spacer(Modifier.height(12.dp))
            SectionTitle("Deezer")
            SettingsSwitchRow(
                title = "Use Deezer",
                subtitle = "Stream from Deezer first, fall back to YouTube",
                checked = deezerEnabled,
            ) {
                deezerEnabled = it
                com.music.spotui.data.preferences.setDeezerEnabled(context, it)
                com.music.spotui.di.SongPlayer.deezerEnabled = it
            }
            Text(
                text = if (deezerConnected) {
                    "Connected" + if (deezerTier.isNotBlank()) " — $deezerTier" else ""
                } else "Not connected",
                color = Color(0xFFB3B3B3),
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp),
            )
            Text(
                text = if (deezerConnected) "Reconnect / switch account" else "Log in to Deezer",
                color = AppPalette,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable {
                        navController.navigate(com.music.spotui.ui.navigation.Routes.DeezerLogin.route)
                    }
                    .padding(vertical = 14.dp),
            )
            if (deezerConnected) {
                Text(
                    text = "Disconnect Deezer",
                    color = Color(0xFFE57373),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            com.music.spotui.data.preferences.clearDeezer(context)
                            navController.navigate(com.music.spotui.ui.navigation.Routes.Settings.route) {
                                popUpTo(com.music.spotui.ui.navigation.Routes.Settings.route) { inclusive = true }
                            }
                        }
                        .padding(vertical = 12.dp),
                )
            }

            Spacer(Modifier.height(12.dp))
            SectionTitle("SpotiFLAC (experimental)")
            val sfConnected = com.music.spotui.data.preferences.hasSpotiflacSession(context)
            Text(
                text = "Gives access to SpotiFLAC's own FLAC servers. You solve one quick check yourself — no auto-bypass. Experimental: may fail if their servers change.",
                color = Color(0xFFB3B3B3),
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 6.dp),
            )
            Text(
                text = if (sfConnected) "Re-verify SpotiFLAC" else "Set up SpotiFLAC verification",
                color = AppPalette,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable {
                        navController.navigate(com.music.spotui.ui.navigation.Routes.SpotiflacVerify.route)
                    }
                    .padding(vertical = 14.dp),
            )
            if (sfConnected) {
                Text(
                    text = "Session active ✓",
                    color = Color(0xFF00C7B7),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }

            Spacer(Modifier.height(12.dp))
            SectionTitle("Downloads & Storage")
            val storageInfo = remember { com.music.spotui.data.preferences.getDownloadStorageInfo(context) }
            var currentStorage by remember { mutableStateOf(storageInfo) }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF1A1A20))
                    .padding(14.dp)
            ) {
                Text(
                    text = "Storage Usage",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${currentStorage.trackCount} offline songs • ${currentStorage.formattedSize} used",
                    color = AppPalette,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(10.dp))

                Text(
                    text = "App Storage Path:",
                    color = Color.Gray,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = currentStorage.internalPath,
                    color = Color(0xFFDDDDDD),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Normal
                )

                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Shared Export Location:",
                    color = Color.Gray,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = currentStorage.publicPath,
                    color = Color(0xFFDDDDDD),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Normal
                )

                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Export to Music",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF2E2E38))
                            .clickable {
                                val (count, path) = com.music.spotui.data.preferences.exportDownloads(context)
                                android.widget.Toast.makeText(
                                    context,
                                    "Exported $count tracks to $path",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    )

                    Text(
                        text = "Clear Downloads",
                        color = Color(0xFFE57373),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF2E2E38))
                            .clickable {
                                val n = com.music.spotui.data.preferences.clearAllDownloads(context)
                                currentStorage = com.music.spotui.data.preferences.getDownloadStorageInfo(context)
                                android.widget.Toast.makeText(
                                    context,
                                    "Cleared $n downloaded tracks",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            SectionTitle("About Sepotify")
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF1A1A20))
                    .padding(14.dp)
            ) {
                Text(
                    text = "Sepotify v2.0",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "High-Fidelity • Zero-Delay Offline Client",
                    color = Color.Gray,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "github.com/RMNO21",
                    color = AppPalette,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable {
                        runCatching {
                            val intent = android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://github.com/RMNO21")
                            )
                            context.startActivity(intent)
                        }
                    }
                )
            }

            Spacer(Modifier.height(12.dp))
            SectionTitle("Spotify Account")
            Text(
                text = if (isSpotifyLoggedIn) "Connected to Spotify account" else "Not logged in (Guest mode)",
                color = if (isSpotifyLoggedIn) Color(0xFF00C7B7) else Color(0xFFB3B3B3),
                fontSize = 13.sp,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 6.dp),
            )

            Text(
                text = if (isSpotifyLoggedIn) "Switch Spotify account / Web Re-login" else "Log in to Spotify (Web)",
                color = AppPalette,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable {
                        com.music.spotui.data.api.SpotifySession.setGuestMode(context, false)
                        navController.navigate(com.music.spotui.ui.navigation.Routes.Login.route)
                    }
                    .padding(vertical = 14.dp),
            )

            Text(
                text = "Enter Spotify Cookie (sp_dc)",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable {
                        spotifyCookieInput = spotifySessionKey
                        showSpotifyCookieDialog = true
                    }
                    .padding(vertical = 12.dp),
            )

            if (isSpotifyLoggedIn) {
                Text(
                    text = "Log out of Spotify",
                    color = Color(0xFFE57373),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            com.music.spotui.data.api.SpotifySession.setSpDc(context, "")
                            com.music.spotui.data.api.SpotifySession.setGuestMode(context, true)
                            com.music.spotui.data.api.Api.HomeCache.clear()
                            spotifySessionKey = ""
                            android.widget.Toast.makeText(context, "Logged out of Spotify", android.widget.Toast.LENGTH_SHORT).show()
                        }
                        .padding(vertical = 12.dp),
                )
            }
            Spacer(Modifier.height(160.dp))
        }

        if (showSpotifyCookieDialog) {
            AlertDialog(
                onDismissRequest = { showSpotifyCookieDialog = false },
                title = { Text("Spotify Session Cookie", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
                text = {
                    Column {
                        Text(
                            "Paste your Spotify 'sp_dc' cookie to connect your account without using web login:",
                            color = Color(0xFFB3B3B3),
                            fontSize = 13.sp,
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = spotifyCookieInput,
                            onValueChange = { spotifyCookieInput = it },
                            placeholder = { Text("sp_dc cookie string…", color = Color.Gray, fontSize = 12.sp) },
                            singleLine = false,
                            maxLines = 4,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = AppPalette,
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
                            val cookie = spotifyCookieInput.trim()
                            if (cookie.isNotBlank()) {
                                showSpotifyCookieDialog = false
                                com.music.spotui.data.api.SpotifySession.setSpDc(context, cookie)
                                com.music.spotui.data.api.SpotifySession.setGuestMode(context, false)
                                spotifySessionKey = cookie
                                com.music.spotui.data.api.Api.HomeCache.clear()
                                scope.launch(Dispatchers.IO) {
                                    val res = SpotifyAuth.fetchAccessToken(cookie, "")
                                    res.onSuccess {
                                        Spotify.accessToken = it.accessToken
                                    }
                                    withContext(Dispatchers.Main) {
                                        android.widget.Toast.makeText(context, "Spotify session updated!", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AppPalette, contentColor = Color.Black),
                    ) {
                        Text("Save Cookie", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showSpotifyCookieDialog = false }) {
                        Text("Cancel", color = Color.LightGray)
                    }
                },
                containerColor = Color(0xFF181818),
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        color = AppPalette,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
    )
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Color(0xFFB3B3B3), fontSize = 12.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = AppPalette,
                uncheckedThumbColor = Color(0xFFB3B3B3),
                uncheckedTrackColor = Color(0xFF333333),
            ),
        )
    }
}

@Composable
private fun QualityPicker(
    title: String,
    selected: StreamQuality,
    onSelect: (StreamQuality) -> Unit
) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        StreamQuality.values().forEach { q ->
            val isSel = q == selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onSelect(q) }
                    .background(if (isSel) Color(0xFF1A1A20) else Color.Transparent)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(q.label, color = Color.White, fontSize = 15.sp)
                    Text(q.detail, color = Color(0xFFB3B3B3), fontSize = 12.sp)
                }
                if (isSel) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = "Selected",
                        tint = AppPalette,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
        }
    }
}
