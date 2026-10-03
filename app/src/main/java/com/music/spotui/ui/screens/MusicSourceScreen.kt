package com.music.spotui.ui.screens

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.music.spotui.R
import com.music.spotui.data.preferences.MusicSource
import com.music.spotui.data.preferences.getDeezerArl
import com.music.spotui.data.preferences.getPrimaryMusicSource
import com.music.spotui.data.preferences.setPrimaryMusicSource
import com.music.spotui.ui.navigation.Routes
import com.music.spotui.ui.theme.AppBackground
import com.music.spotui.ui.theme.AppPalette

@Composable
fun MusicSourceScreen(navController: NavController) {
    val context = LocalContext.current
    val currentSource = getPrimaryMusicSource(context) ?: MusicSource.YOUTUBE_MUSIC
    var selectedSource by remember { mutableStateOf(currentSource) }
    val hasDeezer = !getDeezerArl(context).isNullOrBlank()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Spacer(Modifier.height(32.dp))
            Text(
                text = "Choose Music Source",
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Select your preferred primary playback engine. You can change this at any time in Settings.",
                color = Color(0xFFB3B3B3),
                fontSize = 15.sp,
                lineHeight = 22.sp,
            )
            Spacer(Modifier.height(32.dp))

            SourceOptionCard(
                iconVector = Icons.Default.PlayArrow,
                title = "YouTube Music",
                badge = "Recommended",
                description = "Free, high-speed streaming without extra accounts. Uses high-bitrate Opus (up to 160 kbps) with clean audio reproduction.",
                isSelected = selectedSource == MusicSource.YOUTUBE_MUSIC,
                onClick = { selectedSource = MusicSource.YOUTUBE_MUSIC },
            )

            Spacer(Modifier.height(16.dp))

            SourceOptionCard(
                iconRes = R.drawable.ic_playing,
                title = "Deezer",
                badge = if (hasDeezer) "Connected" else "Account required",
                description = "Direct Deezer audio streaming. Quality depends on your account: 128 kbps (Free), 320 kbps (Premium), or FLAC (HiFi).",
                isSelected = selectedSource == MusicSource.DEEZER,
                onClick = { selectedSource = MusicSource.DEEZER },
            )
        }

        Button(
            onClick = {
                setPrimaryMusicSource(context, selectedSource)
                if (selectedSource == MusicSource.DEEZER && !hasDeezer) {
                    navController.navigate("${Routes.DeezerLogin.route}?next=home") {
                        popUpTo(Routes.MusicSource.route) { inclusive = true }
                    }
                } else {
                    navController.navigate(Routes.Home.route) {
                        popUpTo(Routes.MusicSource.route) { inclusive = true }
                    }
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = AppPalette),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            Text(
                text = if (selectedSource == MusicSource.DEEZER && !hasDeezer) "Connect Deezer" else "Continue",
                color = Color.Black,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun SourceOptionCard(
    title: String,
    badge: String,
    description: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    iconVector: ImageVector? = null,
    iconRes: Int? = null,
) {
    val borderColor = if (isSelected) AppPalette else Color(0xFF242429)
    val bgColor = if (isSelected) Color(0xFF16161F) else Color(0xFF121216)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(bgColor)
            .border(1.5.dp, borderColor, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(18.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(if (isSelected) AppPalette.copy(alpha = 0.2f) else Color(0xFF1E1E26)),
            contentAlignment = Alignment.Center,
        ) {
            if (iconVector != null) {
                Icon(
                    imageVector = iconVector,
                    contentDescription = null,
                    tint = if (isSelected) AppPalette else Color.White,
                    modifier = Modifier.size(24.dp),
                )
            } else if (iconRes != null) {
                Icon(
                    painter = painterResource(id = iconRes),
                    contentDescription = null,
                    tint = if (isSelected) AppPalette else Color.White,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) AppPalette.copy(alpha = 0.25f) else Color(0xFF22222A))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = badge,
                        color = if (isSelected) AppPalette else Color(0xFFB3B3B3),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = description,
                color = Color(0xFF9E9EA7),
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )
        }
        if (isSelected) {
            Spacer(Modifier.width(12.dp))
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(AppPalette),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = Color.Black,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}
