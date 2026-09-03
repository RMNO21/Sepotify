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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.material3.LinearProgressIndicator
import kotlinx.coroutines.launch
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.integration.compose.placeholder
import com.music.spotui.R
import com.music.spotui.data.api.Api
import com.music.spotui.data.api.Response
import com.music.spotui.data.api.SpotifySync
import com.music.spotui.data.entity.AlbumsModel
import com.music.spotui.data.preferences.SavedPlaylistModel
import com.music.spotui.data.preferences.isCustomPlaylist
import com.music.spotui.data.preferences.isPlaylistLiked
import com.music.spotui.data.preferences.removeCustomPlaylist
import com.music.spotui.data.preferences.removeLikedPlaylist
import com.music.spotui.data.preferences.removeSongFromCustomPlaylist
import com.music.spotui.data.preferences.saveLikedPlaylist
import com.music.spotui.data.preferences.updateCustomPlaylist
import com.music.spotui.di.Palette
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.components.Loader
import com.music.spotui.ui.navigation.Routes
import com.music.spotui.ui.theme.AppBackground
import com.music.spotui.ui.theme.AppPalette
import com.music.spotui.ui.viewmodel.PlaylistViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalGlideComposeApi::class, ExperimentalFoundationApi::class)
@Composable
fun PlaylistScreen(navController: NavController, playlistId: String, playlistName: String = "") {

    val playlistViewModel: PlaylistViewModel = hiltViewModel()
    val songsResp by playlistViewModel.songs.collectAsState()
    val playlistResp by playlistViewModel.playlist.collectAsState()
    val isRefreshing by playlistViewModel.isRefreshing.collectAsState()
    val context = LocalContext.current

    val isCustom = remember(playlistId) { isCustomPlaylist(context, playlistId) }
    var isLiked by remember(playlistId) { mutableStateOf(isPlaylistLiked(context, playlistId)) }

    var showEditDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    LaunchedEffect(playlistId) {
        playlistViewModel.loadPlaylist(playlistId)
    }

    val songs = (songsResp as? Response.Success)?.data.orEmpty()
    val playlist = (playlistResp as? Response.Success)?.data
        ?: AlbumsModel(
            id = playlistId.hashCode() and 0x7fffffff,
            artists = if (isCustom) "Custom Playlist" else "",
            coverUri = songs.firstOrNull()?.coverUri ?: "",
            name = playlistName,
            time = "",
        )

    var currentName by remember(playlist.name) { mutableStateOf(playlist.name.ifBlank { playlistName }) }
    var currentDesc by remember(playlist.time) { mutableStateOf(playlist.time) }

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
            onDismiss = { menuSong = null },
        )
    }

    // Edit Playlist Dialog
    if (showEditDialog) {
        var editName by remember { mutableStateOf(currentName) }
        var editDesc by remember { mutableStateOf(currentDesc) }

        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            containerColor = Color(0xFF1E1E24),
            title = {
                Text("Edit Playlist", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            },
            text = {
                Column {
                    OutlinedTextField(
                        value = editName,
                        onValueChange = { editName = it },
                        label = { Text("Playlist Name", color = Color.Gray) },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedContainerColor = Color(0xFF2A2A34),
                            unfocusedContainerColor = Color(0xFF2A2A34),
                            cursorColor = AppPalette
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = editDesc,
                        onValueChange = { editDesc = it },
                        label = { Text("Description (optional)", color = Color.Gray) },
                        colors = TextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedContainerColor = Color(0xFF2A2A34),
                            unfocusedContainerColor = Color(0xFF2A2A34),
                            cursorColor = AppPalette
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = editName.trim().ifBlank { "My Playlist" }
                        currentName = trimmed
                        currentDesc = editDesc.trim()
                        updateCustomPlaylist(context, playlistId, trimmed, editDesc.trim())
                        Api.HomeCache.library = null
                        showEditDialog = false
                        android.widget.Toast.makeText(context, "Playlist updated", android.widget.Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppPalette)
                ) {
                    Text("Save", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = false }) {
                    Text("Cancel", color = Color.Gray)
                }
            }
        )
    }

    // Delete Playlist Dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            containerColor = Color(0xFF1E1E24),
            title = {
                Text("Delete Playlist?", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            },
            text = {
                Text("Are you sure you want to delete \"$currentName\"? This action cannot be undone.", color = Color.LightGray, fontSize = 14.sp)
            },
            confirmButton = {
                Button(
                    onClick = {
                        removeCustomPlaylist(context, playlistId)
                        Api.HomeCache.library = null
                        showDeleteDialog = false
                        android.widget.Toast.makeText(context, "Playlist deleted", android.widget.Toast.LENGTH_SHORT).show()
                        navController.popBackStack()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935))
                ) {
                    Text("Delete", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel", color = Color.Gray)
                }
            }
        )
    }

    val pullToRefreshState = rememberPullToRefreshState()
    if (pullToRefreshState.isRefreshing) {
        LaunchedEffect(true) {
            playlistViewModel.refreshPlaylist(playlistId)
        }
    }
    LaunchedEffect(isRefreshing) {
        if (isRefreshing) {
            pullToRefreshState.startRefresh()
        } else {
            pullToRefreshState.endRefresh()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(pullToRefreshState.nestedScrollConnection)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(AppBackground.toArgb()))
        ) {
        if (songsResp is Response.Loading && playlistResp is Response.Loading) {
            Loader()
            return@Surface
        }

        var dominentColor by remember { mutableStateOf(Color(AppBackground.toArgb())) }
        Palette().extractSecondColorFromCoverUrl(context = context, playlist.coverUri) { color ->
            dominentColor = color
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
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    },
                    actions = {
                        if (isCustom) {
                            IconButton(onClick = { showEditDialog = true }) {
                                Icon(Icons.Default.Edit, contentDescription = "Edit Playlist", tint = Color.White)
                            }
                            IconButton(onClick = { showDeleteDialog = true }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete Playlist", tint = Color(0xFFFF6B6B))
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = Color.White,
                    ),
                    title = { Text(text = "") }
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
                            .heightIn(min = 440.dp)
                            .padding(bottom = 8.dp)
                            .background(
                                brush = Brush.verticalGradient(
                                    colors = listOf(dominentColor, Color(AppBackground.toArgb())),
                                    startY = -100f,
                                ),
                            ),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Spacer(modifier = Modifier.padding(25.dp))

                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            if (playlist.coverUri.isNotBlank()) {
                                GlideImage(
                                    modifier = Modifier
                                        .size(230.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                    model = playlist.coverUri,
                                    failure = placeholder(R.drawable.placeholder),
                                    contentDescription = "",
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(230.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFF23232C)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_library_big),
                                        contentDescription = null,
                                        tint = AppPalette,
                                        modifier = Modifier.size(80.dp)
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.padding(5.dp))
                        Text(
                            modifier = Modifier.padding(20.dp, 5.dp, 20.dp, 0.dp),
                            text = currentName,
                            color = Color.White,
                            fontSize = 23.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (currentDesc.isNotBlank()) {
                            Text(
                                modifier = Modifier.padding(20.dp, 4.dp, 20.dp, 0.dp),
                                text = currentDesc,
                                color = Color.Gray,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Text(
                            modifier = Modifier.padding(20.dp, 4.dp, 0.dp, 0.dp),
                            text = if (isCustom) "Custom Playlist • ${songs.size} songs"
                                   else if (playlist.artists.isNotBlank()) "Playlist • ${playlist.artists}"
                                   else "Playlist • ${songs.size} songs",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )

                        val batchDownloads by SongPlayer.batchDownloads.collectAsState()
                        val playlistBatch = batchDownloads[playlistId]
                        val isBatchDownloading = playlistBatch?.isDownloading == true

                        // Check download status
                        val playlistDownloaded = remember(songs, batchDownloads) {
                            songs.isNotEmpty() && SongPlayer.allDownloaded(songs, context)
                        }

                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .padding(20.dp, 0.dp)
                        ) {
                            // Left action: Like / Add to Library
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(if (isLiked) AppPalette.copy(alpha = 0.2f) else Color(0xFF222228))
                                        .clickable {
                                            if (isLiked) {
                                                removeLikedPlaylist(context, playlistId)
                                                if (!isCustom) {
                                                    SpotifySync.setPlaylistSaved(context, playlistId, false)
                                                }
                                                isLiked = false
                                                Api.HomeCache.library = null
                                                android.widget.Toast.makeText(context, "Removed from Library", android.widget.Toast.LENGTH_SHORT).show()
                                            } else {
                                                val toSave = SavedPlaylistModel(
                                                    id = playlistId,
                                                    name = currentName,
                                                    coverUri = playlist.coverUri,
                                                    subtitle = if (isCustom) "Custom Playlist" else "Playlist • ${playlist.artists}",
                                                    description = currentDesc,
                                                    isCustom = isCustom
                                                )
                                                saveLikedPlaylist(context, toSave)
                                                if (!isCustom) {
                                                    SpotifySync.setPlaylistSaved(context, playlistId, true)
                                                }
                                                isLiked = true
                                                Api.HomeCache.library = null
                                                android.widget.Toast.makeText(context, "Added to Library", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                ) {
                                    Icon(
                                        imageVector = if (isLiked) Icons.Default.Check else Icons.Default.Add,
                                        tint = if (isLiked) AppPalette else Color.White,
                                        modifier = Modifier.size(20.dp),
                                        contentDescription = if (isLiked) "In Library" else "Add to Library"
                                    )
                                }

                                if (isCustom) {
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF222228))
                                            .clickable { showEditDialog = true }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Edit,
                                            tint = Color.White,
                                            modifier = Modifier.size(18.dp),
                                            contentDescription = "Edit Playlist"
                                        )
                                    }
                                }
                            }

                            // Right actions: Download, Shuffle, Play
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (songs.isNotEmpty()) {
                                    if (isBatchDownloading) {
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier
                                                .size(28.dp)
                                                .clickable {
                                                    SongPlayer.cancelBatchDownload(playlistId)
                                                }
                                        ) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(24.dp),
                                                color = Color(AppPalette.toArgb()),
                                                strokeWidth = 2.5.dp,
                                                progress = { ((playlistBatch?.progressPercent ?: 0).coerceIn(0, 100)) / 100f }
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
                                            imageVector = if (playlistDownloaded)
                                                Icons.Default.CheckCircle else ImageVector.vectorResource(R.drawable.ic_download),
                                            tint = if (playlistDownloaded) Color(AppPalette.toArgb()) else Color.White,
                                            modifier = Modifier
                                                .size(24.dp)
                                                .clickable(
                                                    interactionSource = remember { MutableInteractionSource() },
                                                    indication = null,
                                                ) {
                                                    if (!playlistDownloaded) {
                                                        com.music.spotui.data.storage.DownloadSyncManager.syncScope.launch {
                                                            com.music.spotui.data.storage.DownloadSyncManager.setPlaylistDownloadEnabled(
                                                                context = context,
                                                                playlistId = playlistId,
                                                                playlistTitle = currentName,
                                                                coverUri = playlist.coverUri,
                                                                songs = songs,
                                                                enabled = true
                                                            )
                                                        }
                                                        SongPlayer.downloadAll(songs, context, playlistId, currentName)
                                                        android.widget.Toast.makeText(
                                                            context,
                                                            "Downloading playlist for offline playback…",
                                                            android.widget.Toast.LENGTH_SHORT,
                                                        ).show()
                                                    }
                                                },
                                            contentDescription = "Download playlist",
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(16.dp))
                                    // Shuffle-play: start the playlist in random order.
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_player_shuffle),
                                        tint = Color.White,
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null,
                                            ) {
                                                val isOffline = !com.music.spotui.data.network.NetworkMonitor.isOnlineNow(context)
                                                val playable = if (isOffline) songs.filter { com.music.spotui.data.preferences.isSongDownloaded(context, it) } else songs
                                                if (playable.isEmpty()) {
                                                    android.widget.Toast.makeText(context, "No downloaded songs available offline", android.widget.Toast.LENGTH_SHORT).show()
                                                } else {
                                                    playlistViewModel.startShuffled(playable)?.let { first ->
                                                        playlistViewModel.updateSongState(
                                                            first.coverUri,
                                                            first.title,
                                                            first.singer,
                                                            true,
                                                            first.id,
                                                            0,
                                                            currentName,
                                                        )
                                                        SongPlayer.playSong(first.url, context)
                                                    }
                                                }
                                            },
                                        contentDescription = "Shuffle play",
                                    )
                                    Spacer(modifier = Modifier.width(16.dp))
                                }
                                // Play / Pause button
                                if (songs.isNotEmpty()) {
                                    val isCurrentSongInPlaylist = songs.any { it.id == playlistViewModel.currentSongId.value }
                                    val isCurrentPlaying = playlistViewModel.currentSongPlayingState.value && isCurrentSongInPlaylist
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
                                                val isOffline = !com.music.spotui.data.network.NetworkMonitor.isOnlineNow(context)
                                                val playable = if (isOffline) songs.filter { com.music.spotui.data.preferences.isSongDownloaded(context, it) } else songs
                                                when {
                                                    isCurrentPlaying -> playlistViewModel.setPlaying(false)
                                                    isCurrentSongInPlaylist -> playlistViewModel.setPlaying(true)
                                                    playable.isEmpty() -> {
                                                        android.widget.Toast.makeText(context, "No downloaded songs available offline", android.widget.Toast.LENGTH_SHORT).show()
                                                    }
                                                    else -> {
                                                        playlistViewModel.updateQueue(playable)
                                                        playlistViewModel.updateSongState(
                                                            playable[0].coverUri,
                                                            playable[0].title,
                                                            playable[0].singer,
                                                            true,
                                                            playable[0].id,
                                                            0,
                                                            currentName
                                                        )
                                                        SongPlayer.playSong(playable[0].url, context)
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
                        }

                        // Batch download progress banner
                        if (isBatchDownloading && playlistBatch != null) {
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
                                            text = "Downloading tracks: ${playlistBatch.completedTracks}/${playlistBatch.totalTracks} (${playlistBatch.progressPercent}%)",
                                            color = Color(AppPalette.toArgb()),
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        if (playlistBatch.failedTracks > 0) {
                                            Text(
                                                text = "${playlistBatch.failedTracks} skipped",
                                                color = Color.LightGray,
                                                fontSize = 11.sp
                                            )
                                        }
                                    }
                                    if (playlistBatch.currentTrackTitle.isNotBlank()) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "Song: ${playlistBatch.currentTrackTitle}",
                                            color = Color.LightGray,
                                            fontSize = 11.sp,
                                            maxLines = 1
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    LinearProgressIndicator(
                                        progress = { (playlistBatch.progressPercent.coerceIn(0, 100)) / 100f },
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

                if (songs.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 40.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = if (isCustom) "This playlist is empty" else "No songs found in this playlist",
                                    color = Color.Gray,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                if (isCustom) {
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Button(
                                        onClick = { navController.navigate(Routes.Search.route) },
                                        colors = ButtonDefaults.buttonColors(containerColor = AppPalette)
                                    ) {
                                        Text("Find songs to add", color = Color.Black, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }

                itemsIndexed(songs, key = { index, song -> "${song.id}_$index" }) { index, song ->
                    val currentColor = if (song.id == playlistViewModel.currentSongId.value)
                        Color(AppPalette.toArgb()) else Color.White
                    val isOffline = !com.music.spotui.data.network.NetworkMonitor.isOnlineNow(context)
                    val isTrackDownloaded = remember(song.id, isOffline) {
                        com.music.spotui.data.preferences.isSongDownloaded(context, song)
                    }
                    val rowAlpha = if (isOffline && !isTrackDownloaded) 0.38f else 1.0f

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
                                    if (isOffline && !isTrackDownloaded) {
                                        android.widget.Toast.makeText(context, "Song is unavailable offline", android.widget.Toast.LENGTH_SHORT).show()
                                    } else {
                                        val playableQueue = if (isOffline) songs.filter { com.music.spotui.data.preferences.isSongDownloaded(context, it) } else songs
                                        val queueIndex = playableQueue.indexOfFirst { it.id == song.id }.let { if (it >= 0) it else index }
                                        playlistViewModel.updateQueue(playableQueue)
                                        playlistViewModel.updateSongState(
                                            song.coverUri,
                                            song.title,
                                            song.singer,
                                            true,
                                            song.id,
                                            queueIndex,
                                            currentName
                                        )
                                        SongPlayer.playSong(song.url, context)
                                    }
                                },
                            )
                            .padding(horizontal = 20.dp, vertical = 8.dp)
                            .alpha(rowAlpha)
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
                        Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                            Text(
                                text = song.title,
                                color = currentColor,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (isTrackDownloaded) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        tint = AppPalette,
                                        modifier = Modifier.size(13.dp).padding(end = 4.dp),
                                        contentDescription = "Downloaded",
                                    )
                                }
                                Text(
                                    text = song.singer,
                                    color = Color.Gray,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        if (isCustom) {
                            IconButton(
                                onClick = {
                                    removeSongFromCustomPlaylist(context, playlistId, song.id.toString())
                                    playlistViewModel.loadPlaylist(playlistId)
                                    android.widget.Toast.makeText(context, "Removed from playlist", android.widget.Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Remove from playlist", tint = Color.Gray, modifier = Modifier.size(16.dp))
                            }
                        }

                        IconButton(
                            onClick = { menuSong = song },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Song options", tint = Color.Gray, modifier = Modifier.size(20.dp))
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(160.dp)) }
            }
        }
        }

        com.music.spotui.ui.components.SpotUIRefreshIndicator(
            isRefreshing = pullToRefreshState.isRefreshing,
            state = pullToRefreshState,
            modifier = Modifier.align(Alignment.TopCenter)
        )
    }
}

