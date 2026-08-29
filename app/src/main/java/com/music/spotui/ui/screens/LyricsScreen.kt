package com.music.spotui.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.music.spotui.R
import com.music.spotui.data.entity.LyricLine
import com.music.spotui.data.entity.Lyrics
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.theme.AppPalette
import com.music.spotui.ui.viewmodel.LyricsViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Polls ExoPlayer's position every 100ms for smooth, real-time lyrics tracking. */
@Composable
private fun rememberPlaybackPositionMs(): State<Long> {
    val pos = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            pos.longValue = SongPlayer.getCurrentPosition().coerceAtLeast(0L)
            delay(100L)
        }
    }
    return pos
}

private fun activeIndexFor(lyrics: Lyrics, positionMs: Long, offsetMs: Long = 0L): Int {
    if (!lyrics.synced) return -1
    val effectivePos = (positionMs + offsetMs).coerceAtLeast(0L)
    return lyrics.lines.indexOfLast { it.timeMs <= effectivePos + 200 }.coerceAtLeast(0)
}

/** Seek to a tapped synced line and ensure playback is active. */
private fun jumpTo(timeMs: Long) {
    SongPlayer.seekTo(timeMs)
    if (!SongPlayer.isPlaying()) SongPlayer.play()
}

private fun formatTime(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val min = totalSec / 60
    val sec = totalSec % 60
    return String.format("%02d:%02d", min, sec)
}

/**
 * Full-screen synced-lyrics view. Real-time scrolling keeps the active line centered,
 * tapping any timestamp jumps to that part of the track, and a bottom playback control bar
 * lets users pause, resume, or seek without leaving the lyrics screen.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun LyricsScreen(
    title: String,
    artist: String,
    album: String,
    accentColor: Color,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val vm: LyricsViewModel = hiltViewModel()

    var offsetMs by remember { mutableLongStateOf(0L) }
    var showCalibration by remember { mutableStateOf(false) }

    LaunchedEffect(title, artist) {
        val durationSec = (SongPlayer.getDuration() / 1000).toInt()
        vm.load(title, artist, album, durationSec)
    }

    val state by vm.state.collectAsState()
    val positionMs by rememberPlaybackPositionMs()
    var isPlaying by remember { mutableStateOf(SongPlayer.isPlaying()) }
    var durationMs by remember { mutableLongStateOf(SongPlayer.getDuration().coerceAtLeast(1L)) }

    LaunchedEffect(positionMs) {
        isPlaying = SongPlayer.isPlaying()
        val dur = SongPlayer.getDuration()
        if (dur > 0) durationMs = dur
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        accentColor.copy(alpha = 0.85f),
                        accentColor.copy(alpha = 0.40f),
                        Color(0xFF0F0F14),
                        Color(0xFF0A0A0E)
                    ),
                )
            )
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── Top Bar ──
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Close",
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "LYRICS",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.5.sp
                        )
                        if (state is LyricsViewModel.State.Loaded) {
                            val isSynced = (state as LyricsViewModel.State.Loaded).lyrics.synced
                            Spacer(Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (isSynced) AppPalette.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.15f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (isSynced) {
                                        AudioEqualizerWave(color = AppPalette)
                                        Spacer(Modifier.width(4.dp))
                                    }
                                    Text(
                                        text = if (isSynced) "SYNCED" else "PLAIN",
                                        color = if (isSynced) AppPalette else Color.White.copy(alpha = 0.7f),
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = title,
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.basicMarquee()
                    )
                    Text(
                        text = artist,
                        color = Color.White.copy(alpha = 0.65f),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Sync offset calibration button
                    IconButton(
                        onClick = { showCalibration = !showCalibration },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = ImageVector.vectorResource(R.drawable.ic_crossfade),
                            contentDescription = "Calibrate sync",
                            tint = if (offsetMs != 0L) AppPalette else Color.White.copy(alpha = 0.8f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    // Refresh lyrics button
                    IconButton(
                        onClick = {
                            val durationSec = (SongPlayer.getDuration() / 1000).toInt()
                            vm.load(title, artist, album, durationSec, forceReload = true)
                            Toast.makeText(context, "Refreshing lyrics...", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Reload lyrics",
                            tint = Color.White.copy(alpha = 0.8f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // ── Sync Calibration Banner (if toggled) ──
            AnimatedVisibility(
                visible = showCalibration,
                enter = slideInVertically() + fadeIn(),
                exit = slideOutVertically() + fadeOut()
            ) {
                Surface(
                    color = Color(0xFF1E1E26),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Sync Offset", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Text(
                                text = if (offsetMs == 0L) "Synchronized" else "${if (offsetMs > 0) "+" else ""}${offsetMs}ms",
                                color = if (offsetMs == 0L) Color.Gray else AppPalette,
                                fontSize = 12.sp
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { offsetMs -= 500L },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A36)),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                shape = RoundedCornerShape(6.dp)
                            ) { Text("-0.5s", color = Color.White, fontSize = 11.sp) }

                            Button(
                                onClick = { offsetMs = 0L },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A36)),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                shape = RoundedCornerShape(6.dp)
                            ) { Text("Reset", color = Color.LightGray, fontSize = 11.sp) }

                            Button(
                                onClick = { offsetMs += 500L },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A36)),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                shape = RoundedCornerShape(6.dp)
                            ) { Text("+0.5s", color = Color.White, fontSize = 11.sp) }
                        }
                    }
                }
            }

            // ── Main Lyrics Body ──
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when (val s = state) {
                    is LyricsViewModel.State.Loading -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = AppPalette, strokeWidth = 3.dp)
                                Spacer(Modifier.height(16.dp))
                                Text(
                                    "Finding synced lyrics...",
                                    color = Color.White.copy(alpha = 0.7f),
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }
                    is LyricsViewModel.State.NotFound -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(32.dp)
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(64.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF22222E))
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_library_big),
                                        contentDescription = null,
                                        tint = Color.Gray,
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                                Spacer(Modifier.height(16.dp))
                                Text(
                                    "No lyrics found for this track",
                                    color = Color.White,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "We couldn't match real-time lyrics for \"$title\".",
                                    color = Color.Gray,
                                    fontSize = 13.sp,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(Modifier.height(20.dp))
                                Button(
                                    onClick = {
                                        val durationSec = (SongPlayer.getDuration() / 1000).toInt()
                                        vm.load(title, artist, album, durationSec, forceReload = true)
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = AppPalette),
                                    shape = RoundedCornerShape(20.dp)
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Retry Search", color = Color.Black, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                    is LyricsViewModel.State.Loaded -> {
                        val lyrics = s.lyrics
                        val activeIndex = activeIndexFor(lyrics, positionMs, offsetMs)
                        val listState = rememberLazyListState()
                        var userScrolledManually by remember { mutableStateOf(false) }

                        // Detect user drag / manual scrolling
                        LaunchedEffect(listState.isScrollInProgress) {
                            if (listState.isScrollInProgress) {
                                userScrolledManually = true
                            }
                        }

                        // Auto-scroll when not manually overridden
                        LaunchedEffect(activeIndex, userScrolledManually) {
                            if (!userScrolledManually && activeIndex >= 0 && lyrics.synced) {
                                listState.animateScrollToItem(
                                    index = activeIndex.coerceAtLeast(0),
                                    scrollOffset = -300
                                )
                            }
                        }

                        // Auto-resume sync after 3.5 seconds of no manual scrolling
                        LaunchedEffect(userScrolledManually) {
                            if (userScrolledManually) {
                                delay(3500L)
                                userScrolledManually = false
                            }
                        }

                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 120.dp),
                            verticalArrangement = Arrangement.spacedBy(22.dp),
                        ) {
                            itemsIndexed(lyrics.lines) { index, line ->
                                val isActive = index == activeIndex && lyrics.synced
                                val isPast = lyrics.synced && index < activeIndex
                                val isUpcoming = lyrics.synced && index > activeIndex

                                LyricLineCard(
                                    line = line,
                                    isActive = isActive,
                                    isPast = isPast,
                                    isUpcoming = isUpcoming,
                                    synced = lyrics.synced,
                                    accentColor = accentColor,
                                    onTap = {
                                        if (lyrics.synced) {
                                            jumpTo(line.timeMs)
                                            userScrolledManually = false
                                        }
                                    },
                                    onCopy = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val clip = ClipData.newPlainText("Lyrics", "\"${line.text}\" - $title by $artist")
                                        clipboard.setPrimaryClip(clip)
                                        Toast.makeText(context, "Lyric copied to clipboard", Toast.LENGTH_SHORT).show()
                                    }
                                )
                            }
                        }

                        // ── Floating "Jump to current" Sync Button ──
                        androidx.compose.animation.AnimatedVisibility(
                            visible = userScrolledManually && lyrics.synced && activeIndex >= 0,
                            enter = slideInVertically { it } + fadeIn(),
                            exit = slideOutVertically { it } + fadeOut(),
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 16.dp)
                        ) {
                            Surface(
                                color = AppPalette,
                                shape = RoundedCornerShape(50),
                                shadowElevation = 8.dp,
                                modifier = Modifier.clickable {
                                    userScrolledManually = false
                                    coroutineScope.launch {
                                        listState.animateScrollToItem(activeIndex.coerceAtLeast(0), scrollOffset = -300)
                                    }
                                }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = ImageVector.vectorResource(R.drawable.ic_player_shuffle),
                                        contentDescription = "Sync",
                                        tint = Color.Black,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = "Sync with music",
                                        color = Color.Black,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ── Bottom Playback Control Bar ──
            Surface(
                color = Color(0xDE111116),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    // Seek Slider
                    val currentPos = positionMs.coerceIn(0L, durationMs)
                    var sliderPos by remember { mutableStateOf(currentPos.toFloat()) }
                    var isUserSeeking by remember { mutableStateOf(false) }

                    LaunchedEffect(positionMs) {
                        if (!isUserSeeking) {
                            sliderPos = currentPos.toFloat()
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = formatTime(if (isUserSeeking) sliderPos.toLong() else currentPos),
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 11.sp
                        )
                        Text(
                            text = formatTime(durationMs),
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 11.sp
                        )
                    }

                    Slider(
                        value = sliderPos,
                        onValueChange = {
                            isUserSeeking = true
                            sliderPos = it
                        },
                        onValueChangeFinished = {
                            SongPlayer.seekTo(sliderPos.toLong())
                            isUserSeeking = false
                        },
                        valueRange = 0f..durationMs.toFloat().coerceAtLeast(1f),
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = Color.White,
                            inactiveTrackColor = Color.White.copy(alpha = 0.2f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(20.dp)
                    )

                    // Controls Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(Color.White)
                                .clickable {
                                    if (isPlaying) SongPlayer.pause()
                                    else SongPlayer.play()
                                    isPlaying = SongPlayer.isPlaying()
                                }
                        ) {
                            Icon(
                                imageVector = if (isPlaying) ImageVector.vectorResource(R.drawable.ic_paused) else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                tint = Color.Black,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Individual Lyric Line with Spotify-style fluid opacity, dynamic scaling,
 * highlight glow, and tap-to-seek support.
 */
@Composable
private fun LyricLineCard(
    line: LyricLine,
    isActive: Boolean,
    isPast: Boolean,
    isUpcoming: Boolean,
    synced: Boolean,
    accentColor: Color,
    onTap: () -> Unit,
    onCopy: () -> Unit
) {
    if (line.text.isBlank()) {
        Spacer(Modifier.height(8.dp))
        return
    }

    val targetAlpha = when {
        !synced -> 0.90f
        isActive -> 1.0f
        isPast -> 0.55f
        else -> 0.35f
    }

    val targetScale = if (isActive) 1.03f else 1.0f

    val alpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing),
        label = "lyricAlpha"
    )

    val scale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing),
        label = "lyricScale"
    )

    val textColor by animateColorAsState(
        targetValue = if (isActive) Color.White else Color.White.copy(alpha = alpha),
        animationSpec = tween(durationMillis = 250),
        label = "lyricTextColor"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isActive) Color.White.copy(alpha = 0.08f) else Color.Transparent
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onTap() }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isActive) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(28.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(AppPalette)
            )
            Spacer(Modifier.width(12.dp))
        }

        Text(
            text = line.text,
            color = textColor,
            fontSize = if (isActive) 25.sp else 22.sp,
            fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.Bold,
            lineHeight = if (isActive) 34.sp else 30.sp,
            modifier = Modifier.weight(1f)
        )

        if (isActive) {
            IconButton(
                onClick = onCopy,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = "Copy lyric line",
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

/** How many lines the inline lyrics card previews before "Show lyrics". */
private const val PREVIEW_LINE_COUNT = 4

/**
 * Inline lyrics card displayed directly in the Player Screen.
 * Tracks playback in real-time, displays synced lyric lines with dynamic highlight,
 * and allows expanding into the immersive full-screen lyrics experience.
 */
@Composable
fun InlineLyrics(
    title: String,
    artist: String,
    album: String,
    accentColor: Color,
    onExpand: () -> Unit,
) {
    val vm: LyricsViewModel = hiltViewModel()
    LaunchedEffect(title, artist) {
        val durationSec = (SongPlayer.getDuration() / 1000).toInt()
        vm.load(title, artist, album, durationSec)
    }
    val state by vm.state.collectAsState()
    val positionMs by rememberPlaybackPositionMs()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.verticalGradient(
                    listOf(
                        accentColor.copy(alpha = 0.65f),
                        accentColor.copy(alpha = 0.25f),
                        Color(0xFF181822)
                    )
                )
            )
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(16.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onExpand() }
            .padding(20.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Lyrics",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                if (state is LyricsViewModel.State.Loaded) {
                    val isSynced = (state as LyricsViewModel.State.Loaded).lyrics.synced
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (isSynced) AppPalette.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isSynced) {
                                AudioEqualizerWave(color = AppPalette)
                                Spacer(Modifier.width(4.dp))
                            }
                            Text(
                                text = if (isSynced) "SYNCED" else "PLAIN",
                                color = if (isSynced) AppPalette else Color.White.copy(alpha = 0.7f),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }
                }
            }

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.15f))
                    .clickable { onExpand() }
            ) {
                Icon(
                    imageVector = ImageVector.vectorResource(R.drawable.ic_down),
                    contentDescription = "Expand lyrics",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        when (val s = state) {
            is LyricsViewModel.State.Loading -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 12.dp)
                ) {
                    CircularProgressIndicator(
                        color = Color.White,
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Loading real-time lyrics…",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 14.sp
                    )
                }
            }
            is LyricsViewModel.State.NotFound -> {
                Text(
                    text = "No lyrics found for this track",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 14.sp,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            is LyricsViewModel.State.Loaded -> {
                val lyrics = s.lyrics
                val activeIndex = activeIndexFor(lyrics, positionMs)

                val windowStart = if (lyrics.synced) {
                    (activeIndex - 1).coerceIn(0, (lyrics.lines.size - PREVIEW_LINE_COUNT).coerceAtLeast(0))
                } else 0

                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    lyrics.lines.drop(windowStart).take(PREVIEW_LINE_COUNT).forEachIndexed { i, line ->
                        val lineIndex = windowStart + i
                        val isActive = lineIndex == activeIndex && lyrics.synced

                        val lineAlpha by animateFloatAsState(
                            targetValue = if (isActive) 1.0f else if (lineIndex < activeIndex) 0.55f else 0.40f,
                            label = "inlineAlpha"
                        )

                        Text(
                            text = line.text,
                            color = if (isActive) Color.White else Color.White.copy(alpha = lineAlpha),
                            fontSize = if (isActive) 19.sp else 16.sp,
                            fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.Medium,
                            lineHeight = 24.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) {
                                    if (lyrics.synced) jumpTo(line.timeMs)
                                    else onExpand()
                                }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color.White)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onExpand() }
                        .padding(horizontal = 18.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "Full lyrics",
                        color = Color.Black,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

/** Animated 3-bar sound wave equalizer for the synced indicator. */
@Composable
private fun AudioEqualizerWave(color: Color) {
    val infiniteTransition = rememberInfiniteTransition(label = "eqWave")
    val h1 by infiniteTransition.animateFloat(
        initialValue = 3f,
        targetValue = 10f,
        animationSpec = infiniteRepeatable(tween(400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "h1"
    )
    val h2 by infiniteTransition.animateFloat(
        initialValue = 8f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(350, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "h2"
    )
    val h3 by infiniteTransition.animateFloat(
        initialValue = 4f,
        targetValue = 11f,
        animationSpec = infiniteRepeatable(tween(450, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "h3"
    )

    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.height(12.dp)
    ) {
        Box(modifier = Modifier.width(2.dp).height(h1.dp).background(color, RoundedCornerShape(1.dp)))
        Box(modifier = Modifier.width(2.dp).height(h2.dp).background(color, RoundedCornerShape(1.dp)))
        Box(modifier = Modifier.width(2.dp).height(h3.dp).background(color, RoundedCornerShape(1.dp)))
    }
}
