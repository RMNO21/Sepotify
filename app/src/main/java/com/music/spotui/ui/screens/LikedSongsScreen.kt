package com.music.spotui.ui.screens

import android.annotation.SuppressLint
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.integration.compose.placeholder
import com.music.spotui.R
import com.music.spotui.data.api.Response
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.components.Loader
import com.music.spotui.ui.theme.AppBackground
import com.music.spotui.ui.theme.AppPalette
import com.music.spotui.ui.viewmodel.LikedSongsViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalGlideComposeApi::class, ExperimentalFoundationApi::class)
@Composable
fun LikedSongsScreen(navController: NavController) {

    val likedSongsViewModel: LikedSongsViewModel = hiltViewModel()
    val songsResp by likedSongsViewModel.songs.collectAsState()
    val context = LocalContext.current

    val songs = (songsResp as? Response.Success)?.data.orEmpty()

    LaunchedEffect(songs) {
        if (songs.isNotEmpty()) {
            SongPlayer.prefetchList(songs.map { it.url }, context)
        }
    }

    var menuSong by remember { mutableStateOf<com.music.spotui.data.entity.SongsModel?>(null) }
    menuSong?.let { sel ->
        com.music.spotui.ui.components.SongOptionsSheet(
            song = sel,
            navController = navController,
            context = context,
            onDismiss = {
                // If the song was unliked in the menu, drop it from the list right
                // away (the Spotify-side removal is already in flight).
                if (!com.music.spotui.data.preferences.isSongLiked(context, sel.id.toString())) {
                    likedSongsViewModel.removeLocally(sel.id)
                }
                menuSong = null
            },
        )
    }

    // Spotify's Liked Songs uses a purple → dark gradient.
    val likedColor = Color(0xFF5038A0)

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(AppBackground.toArgb()))
    ) {
        if (songsResp is Response.Loading) {
            Loader()
            return@Surface
        }

        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                CenterAlignedTopAppBar(
                    modifier = Modifier.padding(16.dp, 0.dp),
                    navigationIcon = {
                        Icon(
                            modifier = Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { navController.navigateUp() },
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "",
                            tint = Color.White
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = Color.White,
                    ),
                    title = { Text(text = "") },
                    actions = {
                        val syncState by com.music.spotui.data.api.SpotifySync.syncState.collectAsState()
                        val isSyncing = syncState is com.music.spotui.data.api.SyncState.Syncing
                        if (isSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .padding(end = 12.dp)
                                    .size(20.dp),
                                color = Color(AppPalette.toArgb()),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Sync Liked Songs",
                                tint = Color.White,
                                modifier = Modifier
                                    .padding(end = 8.dp)
                                    .size(24.dp)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) {
                                        likedSongsViewModel.refresh(context)
                                    }
                            )
                        }
                    }
                )
            }
        ) { innerPadding ->
            LazyColumn(
                contentPadding = innerPadding,
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(AppBackground.toArgb()))
                    .consumeWindowInsets(innerPadding)
            ) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(440.dp)
                            .background(
                                brush = Brush.verticalGradient(
                                    colors = listOf(likedColor, Color(AppBackground.toArgb())),
                                    startY = -100f,
                                ),
                            ),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Spacer(modifier = Modifier.padding(25.dp))

                        Box(
                            modifier = Modifier
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(230.dp)
                                    .background(
                                        brush = Brush.linearGradient(
                                            colors = listOf(Color(0xFF8E6FE0), Color(0xFF3B2A82)),
                                        )
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Favorite,
                                    contentDescription = "",
                                    tint = Color.White,
                                    modifier = Modifier.size(90.dp),
                                )
                            }
                        }
                        Spacer(modifier = Modifier.padding(5.dp))
                        Text(
                            modifier = Modifier.padding(20.dp, 5.dp, 0.dp, 0.dp),
                            text = "Liked Songs",
                            color = Color.White,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            modifier = Modifier.padding(20.dp, 4.dp, 20.dp, 0.dp),
                            text = "${songs.size} songs",
                            color = Color.Gray,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )

                        val batchDownloads by SongPlayer.batchDownloads.collectAsState()
                        val likedBatch = batchDownloads["liked_songs"]
                        val isLikedDownloading = likedBatch?.isDownloading == true

                        // Download all liked songs for offline playback.
                        var likedDownloaded by remember(songs, batchDownloads) {
                            mutableStateOf(songs.isNotEmpty() && SongPlayer.allDownloaded(songs, context))
                        }

                        Row(
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .padding(20.dp, 0.dp)
                        ) {
                            if (songs.isNotEmpty()) {
                                if (isLikedDownloading && likedBatch != null) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clickable {
                                                SongPlayer.cancelBatchDownload("liked_songs")
                                            }
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(24.dp),
                                            color = Color(AppPalette.toArgb()),
                                            strokeWidth = 2.5.dp,
                                            progress = { ((likedBatch.progressPercent).coerceIn(0, 100)) / 100f }
                                        )
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            tint = Color.White,
                                            modifier = Modifier.size(12.dp),
                                            contentDescription = "Cancel download",
                                        )
                                    }
                                } else {
                                    Icon(
                                        imageVector = if (likedDownloaded)
                                            Icons.Default.CheckCircle else ImageVector.vectorResource(R.drawable.ic_download),
                                        tint = if (likedDownloaded) Color(AppPalette.toArgb()) else Color.White,
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null,
                                            ) {
                                                if (!likedDownloaded) {
                                                    SongPlayer.downloadAll(songs, context, "liked_songs", "Liked Songs")
                                                    android.widget.Toast.makeText(
                                                        context,
                                                        "Downloading ${songs.size} tracks…",
                                                        android.widget.Toast.LENGTH_SHORT,
                                                    ).show()
                                                }
                                            },
                                        contentDescription = "Download liked songs",
                                    )
                                }
                                Spacer(modifier = Modifier.width(16.dp))
                                // Shuffle-play: start liked songs in random order.
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_player_shuffle),
                                    tint = Color.White,
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                        ) {
                                            likedSongsViewModel.startShuffled(songs)?.let { first ->
                                                SongPlayer.playSong(first.url, context)
                                                likedSongsViewModel.updateSongState(
                                                    first.coverUri,
                                                    first.title,
                                                    first.singer,
                                                    true,
                                                    first.id,
                                                    0,
                                                    "Liked Songs",
                                                )
                                            }
                                        },
                                    contentDescription = "Shuffle play",
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                            }
                            // Always visible: pause when playing, resume when this
                            // list's track is paused, otherwise start from the top.
                            if (songs.isNotEmpty()) {
                                val isCurrentSongInLiked = songs.any { it.id == likedSongsViewModel.currentSongId.value }
                                val isCurrentPlaying = likedSongsViewModel.currentSongPlayingState.value && isCurrentSongInLiked
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(52.dp)
                                        .clip(RoundedCornerShape(100.dp))
                                        .background(Color.White)
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null
                                        ) {
                                            when {
                                                isCurrentPlaying -> likedSongsViewModel.setPlaying(false)
                                                isCurrentSongInLiked -> likedSongsViewModel.setPlaying(true)
                                                else -> {
                                                    likedSongsViewModel.updateQueue(songs)
                                                    SongPlayer.playSong(songs[0].url, context)
                                                    likedSongsViewModel.updateSongState(
                                                        songs[0].coverUri,
                                                        songs[0].title,
                                                        songs[0].singer,
                                                        true,
                                                        songs[0].id,
                                                        0,
                                                        "Liked Songs"
                                                    )
                                                }
                                            }
                                        }
                                ) {
                                    Icon(
                                        modifier = Modifier.size(25.dp),
                                        tint = Color.Black,
                                        painter = painterResource(
                                            id = if (isCurrentPlaying) R.drawable.ic_playing else R.drawable.play_svgrepo_com,
                                        ),
                                        contentDescription = if (isCurrentPlaying) "Pause" else "Play"
                                    )
                                }
                            }
                        }

                        // Batch download progress banner
                        if (isLikedDownloading && likedBatch != null) {
                            Card(
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E24)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 8.dp)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Downloading tracks: ${likedBatch.completedTracks}/${likedBatch.totalTracks} (${likedBatch.progressPercent}%)",
                                            color = Color(AppPalette.toArgb()),
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        if (likedBatch.failedTracks > 0) {
                                            Text(
                                                text = "${likedBatch.failedTracks} skipped",
                                                color = Color.LightGray,
                                                fontSize = 11.sp
                                            )
                                        }
                                    }
                                    if (likedBatch.currentTrackTitle.isNotBlank()) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "Song: ${likedBatch.currentTrackTitle}",
                                            color = Color.LightGray,
                                            fontSize = 11.sp,
                                            maxLines = 1
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    LinearProgressIndicator(
                                        progress = { (likedBatch.progressPercent.coerceIn(0, 100)) / 100f },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(4.dp)
                                            .clip(RoundedCornerShape(2.dp)),
                                        color = Color(AppPalette.toArgb()),
                                        trackColor = Color(0xFF333333),
                                    )
                                }
                            }
                        }
                    }
                }

                itemsIndexed(songs, key = { index, song -> "${song.id}_$index" }) { index, song ->
                    val currentColor = if (song.id == likedSongsViewModel.currentSongId.value)
                        Color(AppPalette.toArgb()) else Color.White

                    Row(
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onLongClick = { menuSong = song },
                                onClick = {
                                    likedSongsViewModel.updateQueue(songs)
                                    likedSongsViewModel.updateSongState(
                                        song.coverUri,
                                        song.title,
                                        song.singer,
                                        true,
                                        song.id,
                                        index,
                                        "Liked Songs"
                                    )
                                    SongPlayer.playSong(song.url, context)
                                },
                            )
                            .padding(20.dp, 8.dp)
                    ) {
                        GlideImage(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            model = song.coverUri,
                            failure = placeholder(R.drawable.placeholder),
                            contentScale = ContentScale.Crop,
                            contentDescription = ""
                        )
                        Column(modifier = Modifier.padding(start = 12.dp).width(280.dp)) {
                            Text(
                                text = song.title,
                                color = currentColor,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1
                            )
                            Text(
                                text = song.singer,
                                color = Color.Gray,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1
                            )
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(160.dp)) }
            }
        }
    }
}
