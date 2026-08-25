package com.music.spotui.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import com.music.spotui.data.api.SpotifySession
import com.music.spotui.data.cache.OfflineCache
import com.music.spotui.data.entity.AccountModel
import com.music.spotui.data.entity.ArtistsModel
import com.music.spotui.data.entity.LibraryEntry
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.preferences.createCustomPlaylist
import com.music.spotui.data.preferences.getCustomPlaylistSongs
import com.music.spotui.data.preferences.getDownloadedSongs
import com.music.spotui.data.preferences.getDownloadedSongsForPlaylist
import com.music.spotui.data.preferences.getLocalSongs
import com.music.spotui.data.preferences.getSavedPlaylists
import com.music.spotui.data.preferences.isLibraryGridView
import com.music.spotui.data.preferences.setLibraryGridView
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.components.Snackbar
import com.music.spotui.ui.components.SongOptionsSheet
import com.music.spotui.ui.navigation.Routes
import com.music.spotui.ui.navigation.albumRoute
import com.music.spotui.ui.navigation.artistRoute
import com.music.spotui.ui.navigation.playlistRoute
import com.music.spotui.ui.navigation.showRoute
import com.music.spotui.ui.theme.AppBackground
import com.music.spotui.ui.theme.AppPalette
import com.music.spotui.ui.viewmodel.LibraryViewModel
import com.music.spotui.ui.viewmodel.PlaylistViewModel
import kotlinx.coroutines.launch

import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.ui.input.nestedscroll.nestedScroll

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(navController: NavController) {

    val libraryViewModel: LibraryViewModel = hiltViewModel()
    val playlistViewModel: PlaylistViewModel = hiltViewModel()
    val entriesResp by libraryViewModel.entries.collectAsState()
    val account by libraryViewModel.account.collectAsState()
    val followedArtists by libraryViewModel.followedArtists.collectAsState()
    val isRefreshing by libraryViewModel.isRefreshing.collectAsState()
    val context = LocalContext.current

    val isOnline by com.music.spotui.data.network.NetworkMonitor.isOnline.collectAsState(initial = true)
    var showAccount by remember { mutableStateOf(false) }
    var gridView by remember { mutableStateOf(isLibraryGridView(context)) }
    var selectedFilter by remember(isOnline) { mutableStateOf(if (isOnline) "All" else "Downloaded") }
    var isSearching by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }

    var menuSong by remember { mutableStateOf<SongsModel?>(null) }
    menuSong?.let { sel ->
        SongOptionsSheet(
            song = sel,
            navController = navController,
            context = context,
            onDismiss = { menuSong = null },
        )
    }

    if (showAccount) {
        AccountSheet(
            account = (account as? Response.Success)?.data ?: AccountModel(),
            navController = navController,
            onDismiss = { showAccount = false },
        )
    }

    // Create Custom Playlist Dialog
    if (showCreatePlaylistDialog) {
        var playlistNameInput by remember { mutableStateOf("") }
        var playlistDescInput by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showCreatePlaylistDialog = false },
            containerColor = Color(0xFF1E1E24),
            title = {
                Text("New Playlist", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            },
            text = {
                Column {
                    OutlinedTextField(
                        value = playlistNameInput,
                        onValueChange = { playlistNameInput = it },
                        label = { Text("Playlist Name", color = Color.Gray) },
                        placeholder = { Text("My Playlist", color = Color.DarkGray) },
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
                        value = playlistDescInput,
                        onValueChange = { playlistDescInput = it },
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
                        val name = playlistNameInput.trim().ifBlank { "My Playlist" }
                        val created = createCustomPlaylist(context, name, playlistDescInput.trim())
                        Api.HomeCache.library = null
                        libraryViewModel.load()
                        showCreatePlaylistDialog = false
                        android.widget.Toast.makeText(context, "Created \"$name\"", android.widget.Toast.LENGTH_SHORT).show()
                        navController.navigate(playlistRoute(created.id, created.name))
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppPalette)
                ) {
                    Text("Create", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreatePlaylistDialog = false }) {
                    Text("Cancel", color = Color.Gray)
                }
            }
        )
    }

    val pullToRefreshState = rememberPullToRefreshState()
    if (pullToRefreshState.isRefreshing) {
        LaunchedEffect(true) {
            libraryViewModel.refresh(context)
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(AppBackground.toArgb()))
                .statusBarsPadding()
        ) {
        // Header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp)
        ) {
            if (isSearching) {
                TextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search in Your Library", color = Color.Gray, fontSize = 14.sp) },
                    singleLine = true,
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(20.dp))
                    },
                    trailingIcon = {
                        IconButton(onClick = {
                            if (searchQuery.isNotEmpty()) searchQuery = ""
                            else isSearching = false
                        }) {
                            Icon(Icons.Default.Close, contentDescription = "Close search", tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                    },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color(0xFF1E1E24),
                        unfocusedContainerColor = Color(0xFF1E1E24),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = AppPalette
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                        .clip(RoundedCornerShape(25.dp))
                )
            } else {
                Text(
                    text = "Your Library",
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    fontSize = 22.sp,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { isSearching = true }) {
                    Icon(Icons.Default.Search, contentDescription = "Search library", tint = Color.White, modifier = Modifier.size(22.dp))
                }
                IconButton(onClick = { showCreatePlaylistDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Create playlist", tint = Color.White, modifier = Modifier.size(26.dp))
                }
                Spacer(modifier = Modifier.width(4.dp))
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF3A3A3A))
                        .clickable { showAccount = true },
                    contentAlignment = Alignment.Center
                ) {
                    val avatar = (account as? Response.Success)?.data?.imageUrl.orEmpty()
                    if (avatar.isNotBlank()) {
                        AccountAvatar(avatar, 34.dp)
                    } else {
                        Icon(Icons.Default.Person, contentDescription = "Account", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }

        // Filter chips: All, Playlists, Albums, Artists, Downloaded
        val filters = listOf("All", "Playlists", "Albums", "Artists", "Downloaded")
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(filters) { filter ->
                val isSelected = filter == selectedFilter
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (isSelected) AppPalette else Color(0xFF222228))
                        .clickable {
                            // Tapping an active filter toggles back to All
                            selectedFilter = if (isSelected && filter != "All") "All" else filter
                        }
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = filter,
                        color = if (isSelected) Color.Black else Color.White,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }

        // Grid/list switch (only in normal browsing mode)
        if (!isSearching || searchQuery.isBlank()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp, 0.dp, 16.dp, 4.dp)
            ) {
                Spacer(modifier = Modifier.weight(1f))
                Icon(
                    painter = painterResource(if (gridView) R.drawable.ic_view_list else R.drawable.ic_view_grid),
                    contentDescription = if (gridView) "Show as list" else "Show as grid",
                    tint = Color.White,
                    modifier = Modifier
                        .size(18.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            gridView = !gridView
                            setLibraryGridView(context, gridView)
                        }
                )
            }
        }

        val allEntries = (entriesResp as? Response.Success)?.data.orEmpty()

        // Handle Active Library Search
        if (isSearching && searchQuery.isNotBlank()) {
            LibrarySearchHierarchy(
                query = searchQuery.trim(),
                allEntries = allEntries,
                followedArtists = followedArtists,
                navController = navController,
                playlistViewModel = playlistViewModel,
                onSongMenu = { menuSong = it }
            )
            return@Column
        }

        // Filter calculation
        val filteredEntries = when (selectedFilter) {
            "Downloaded" -> allEntries.filter {
                it.spotifyId == Api.HomeCache.DOWNLOADS_ID ||
                it.subtitle.contains("Downloaded", ignoreCase = true) ||
                getDownloadedSongsForPlaylist(context, it.spotifyId).isNotEmpty()
            }
            "Playlists" -> allEntries.filter { it.isPlaylist }
            "Albums" -> allEntries.filter {
                !it.isPlaylist && it.spotifyId != Api.HomeCache.DOWNLOADS_ID && it.spotifyId != Api.HomeCache.LIKED_SONGS_ID
            }
            "Artists" -> emptyList() // Handled via artistsToShow
            else -> allEntries
        }

        val artistsToShow = when (selectedFilter) {
            "Artists" -> followedArtists
            "All" -> followedArtists
            else -> emptyList()
        }

        val currentResp = entriesResp
        if (currentResp is Response.Loading && allEntries.isEmpty()) {
            LibrarySkeleton(PaddingValues(0.dp))
        } else {
            if (gridView) {
                LibraryGridScreen(
                    padding = PaddingValues(0.dp),
                    entries = filteredEntries,
                    followedArtists = artistsToShow,
                    selectedFilter = selectedFilter,
                    navController = navController,
                    onCreatePlaylist = { showCreatePlaylistDialog = true }
                )
            } else {
                SumUpLibraryScreen(
                    padding = PaddingValues(0.dp),
                    entries = filteredEntries,
                    followedArtists = artistsToShow,
                    selectedFilter = selectedFilter,
                    navController = navController,
                    onCreatePlaylist = { showCreatePlaylistDialog = true }
                )
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

/**
 * Library Search Hierarchy:
 * 1. Matching Library Containers (Playlists, Albums, Artists)
 * 2. Matching Individual Tracks inside those Containers
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, ExperimentalGlideComposeApi::class)
@Composable
private fun LibrarySearchHierarchy(
    query: String,
    allEntries: List<LibraryEntry>,
    followedArtists: List<ArtistsModel>,
    navController: NavController,
    playlistViewModel: PlaylistViewModel,
    onSongMenu: (SongsModel) -> Unit
) {
    val context = LocalContext.current

    // 1. Matching Containers (Playlists, Albums, Artists)
    val matchingContainers = remember(query, allEntries, followedArtists) {
        val list = mutableListOf<LibraryEntry>()
        allEntries.forEach { e ->
            if (e.name.contains(query, ignoreCase = true) || e.subtitle.contains(query, ignoreCase = true) || e.artists.contains(query, ignoreCase = true)) {
                list.add(e)
            }
        }
        list
    }

    val matchingArtists = remember(query, followedArtists) {
        followedArtists.filter { it.name.contains(query, ignoreCase = true) }
    }

    // 2. Matching Tracks across Library (Downloaded, Liked, Custom Playlists, Local Files, Cached)
    val matchingTracks = remember(query) {
        val pool = mutableListOf<SongsModel>()

        // Downloaded / Liked songs
        pool.addAll(getDownloadedSongs(context))

        // Custom playlists songs
        val customPlaylists = getSavedPlaylists(context)
        customPlaylists.forEach { pl ->
            if (pl.isCustom) {
                pool.addAll(getCustomPlaylistSongs(context, pl.id))
            } else {
                OfflineCache.getPlaylistSongs(context, pl.id)?.let { pool.addAll(it) }
            }
        }

        // Local device audio files
        pool.addAll(getLocalSongs(context))

        // Distinct and filter by query
        pool.distinctBy { if (it.spotifyTrackId.isNotBlank()) it.spotifyTrackId else "${it.title}_${it.singer}" }
            .filter {
                it.title.contains(query, ignoreCase = true) ||
                it.singer.contains(query, ignoreCase = true) ||
                it.album.contains(query, ignoreCase = true)
            }
    }

    if (matchingContainers.isEmpty() && matchingArtists.isEmpty() && matchingTracks.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No results for \"$query\"", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Spacer(modifier = Modifier.height(6.dp))
                Text("Check the spelling or try searching for another artist, song, or playlist.", color = Color.Gray, fontSize = 13.sp, textAlign = TextAlign.Center)
            }
        }
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(top = 10.dp, bottom = 160.dp),
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0B0F))
    ) {
        // Section 1: Matching Containers
        if (matchingContainers.isNotEmpty() || matchingArtists.isNotEmpty()) {
            item {
                Text(
                    text = "Playlists, Albums & Artists",
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
            }

            items(matchingContainers) { entry ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp)
                        .clickable { openLibraryEntry(entry, navController) }
                ) {
                    GlideImage(
                        modifier = Modifier
                            .size(50.dp)
                            .clip(RoundedCornerShape(if (entry.isPlaylist) 6.dp else 4.dp)),
                        model = entry.coverUri,
                        contentScale = ContentScale.Crop,
                        failure = placeholder(R.drawable.placeholder),
                        contentDescription = ""
                    )
                    Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                        Text(
                            text = entry.name,
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = entry.subtitle,
                            color = Color.Gray,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            items(matchingArtists) { artist ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp)
                        .clickable { navController.navigate(artistRoute(artist.name, artist.id)) }
                ) {
                    GlideImage(
                        modifier = Modifier
                            .size(50.dp)
                            .clip(CircleShape),
                        model = artist.coverUri,
                        contentScale = ContentScale.Crop,
                        failure = placeholder(R.drawable.placeholder),
                        contentDescription = ""
                    )
                    Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                        Text(
                            text = artist.name,
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Artist",
                            color = Color.Gray,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        // Section 2: Matching Tracks inside Library Containers
        if (matchingTracks.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Songs in Library",
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
            }

            items(matchingTracks, key = { it.id }) { song ->
                val isCurrent = song.id == playlistViewModel.currentSongId.value
                val titleColor = if (isCurrent) AppPalette else Color.White

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp)
                        .combinedClickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onLongClick = { onSongMenu(song) },
                            onClick = {
                                playlistViewModel.updateQueue(matchingTracks)
                                SongPlayer.playSong(song.url, context)
                                playlistViewModel.updateSongState(
                                    song.coverUri,
                                    song.title,
                                    song.singer,
                                    true,
                                    song.id,
                                    matchingTracks.indexOf(song),
                                    "Library Search"
                                )
                            }
                        )
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
                            color = titleColor,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = song.singer + (if (song.album.isNotBlank()) " • ${song.album}" else ""),
                            color = Color.Gray,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    IconButton(
                        onClick = { onSongMenu(song) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Song options", tint = Color.Gray, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun AccountAvatar(url: String, size: androidx.compose.ui.unit.Dp) {
    GlideImage(
        modifier = Modifier.size(size).clip(CircleShape),
        model = url,
        contentScale = ContentScale.Crop,
        contentDescription = ""
    )
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun SumUpLibraryScreen(
    padding: PaddingValues,
    entries: List<LibraryEntry>,
    followedArtists: List<ArtistsModel>,
    selectedFilter: String,
    navController: NavController,
    onCreatePlaylist: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    if (entries.isEmpty() && followedArtists.isEmpty() && selectedFilter != "Playlists") {
        Box(modifier = Modifier.padding(20.dp, 40.dp)) { Snackbar(showMessage = "No items found in this section") }
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(top = 10.dp, bottom = 160.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .background(Color(0xFF0B0B0F))
    ) {
        // Quick items (History & Local files) only show when filter is "All"
        if (selectedFilter == "All") {
            // Listening history & stats entry
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp, 6.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { navController.navigate(Routes.History.route) }
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(55.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF27856A)),
                    ) {
                        Icon(Icons.Default.DateRange, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
                    }
                    Column(modifier = Modifier.padding(start = 12.dp)) {
                        Text(text = "Listening history", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text(text = "Your plays and stats", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
            // Local files (imported device audio) entry
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp, 6.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { navController.navigate(Routes.LocalFiles.route) }
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(55.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF3B5BA5)),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_library_big),
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                    Column(modifier = Modifier.padding(start = 12.dp)) {
                        Text(text = "Local files", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text(text = "Music imported from this device", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }

        // In Playlists filter: Show "+ Add new playlist" tile at top
        if (selectedFilter == "Playlists" || selectedFilter == "All") {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp, 6.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onCreatePlaylist() }
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(55.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF22222C)),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add playlist", tint = AppPalette, modifier = Modifier.size(28.dp))
                    }
                    Column(modifier = Modifier.padding(start = 12.dp)) {
                        Text(text = "Create playlist", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text(text = "Build your custom playlist", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }

        items(entries) { entry ->
            val hasOfflineTracks = entry.spotifyId == Api.HomeCache.DOWNLOADS_ID ||
                entry.subtitle.contains("Downloaded", ignoreCase = true) ||
                getDownloadedSongsForPlaylist(context, entry.spotifyId).isNotEmpty()

            Row(
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp, 6.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { openLibraryEntry(entry, navController) }
            ) {
                if (entry.spotifyId == Api.HomeCache.DOWNLOADS_ID) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(55.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(AppPalette.copy(alpha = 0.2f))
                    ) {
                        Icon(
                            imageVector = ImageVector.vectorResource(R.drawable.ic_download),
                            contentDescription = "Downloads",
                            tint = AppPalette,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                } else {
                    GlideImage(
                        modifier = Modifier
                            .size(55.dp)
                            .clip(RoundedCornerShape(if (entry.isPlaylist) 6.dp else 4.dp)),
                        model = entry.coverUri,
                        failure = placeholder(R.drawable.placeholder),
                        contentScale = ContentScale.Crop,
                        contentDescription = ""
                    )
                }

                Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = entry.name,
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (hasOfflineTracks) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Downloaded",
                                tint = AppPalette,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                    Text(
                        text = entry.subtitle,
                        color = Color.Gray,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Download Whole Playlist button in library
                if (entry.isPlaylist && entry.spotifyId != Api.HomeCache.DOWNLOADS_ID && entry.spotifyId != Api.HomeCache.LIKED_SONGS_ID) {
                    Box(
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .size(36.dp)
                            .clip(CircleShape)
                            .clickable {
                                coroutineScope.launch {
                                    val api = Api(context)
                                    api.getPlaylistSongs(entry.spotifyId).collect { resp ->
                                        if (resp is Response.Success && resp.data.isNotEmpty()) {
                                            SongPlayer.downloadAll(
                                                resp.data,
                                                context,
                                                playlistId = entry.spotifyId,
                                                playlistName = entry.name
                                            )
                                            android.widget.Toast.makeText(
                                                context,
                                                "Downloading ${resp.data.size} songs from ${entry.name}",
                                                android.widget.Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (hasOfflineTracks) Icons.Default.CheckCircle else ImageVector.vectorResource(R.drawable.ic_download),
                            contentDescription = "Download Playlist",
                            tint = if (hasOfflineTracks) AppPalette else Color.LightGray,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        // Followed Artists
        if (followedArtists.isNotEmpty()) {
            if (selectedFilter == "All") {
                item {
                    Text(
                        text = "Artists you follow",
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(20.dp, 16.dp, 20.dp, 4.dp),
                    )
                }
            }
            items(followedArtists) { artist ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp, 6.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { navController.navigate(artistRoute(artist.name, artist.id)) }
                ) {
                    GlideImage(
                        modifier = Modifier
                            .size(55.dp)
                            .clip(CircleShape),
                        model = artist.coverUri,
                        failure = placeholder(R.drawable.placeholder),
                        contentScale = ContentScale.Crop,
                        contentDescription = ""
                    )
                    Column(modifier = Modifier.padding(start = 12.dp)) {
                        Text(text = artist.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(text = "Artist", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

private fun openLibraryEntry(entry: LibraryEntry, navController: NavController) {
    if (entry.spotifyId == Api.HomeCache.LIKED_SONGS_ID) navController.navigate(Routes.Liked.route)
    else if (entry.spotifyId == Api.HomeCache.DOWNLOADS_ID) navController.navigate(Routes.Downloads.route)
    else if (entry.spotifyId == "your-episodes" || entry.spotifyId == "episodes" || entry.name.equals("Your Episodes", ignoreCase = true)) {
        navController.navigate(showRoute("your-episodes", "Your Episodes"))
    }
    else if (entry.spotifyId.startsWith("show:")) {
        navController.navigate(showRoute(entry.spotifyId, entry.name))
    }
    else if (entry.isPlaylist) navController.navigate(playlistRoute(entry.spotifyId, entry.name))
    else navController.navigate(albumRoute(entry.name, entry.artists))
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun LibraryGridScreen(
    padding: PaddingValues,
    entries: List<LibraryEntry>,
    followedArtists: List<ArtistsModel>,
    selectedFilter: String,
    navController: NavController,
    onCreatePlaylist: () -> Unit
) {
    val context = LocalContext.current
    if (entries.isEmpty() && followedArtists.isEmpty() && selectedFilter != "Playlists") {
        Box(modifier = Modifier.padding(20.dp, 40.dp)) { Snackbar(showMessage = "No items in this section") }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 160.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .background(Color(0xFF0B0B0F))
    ) {
        // Quick history entry only when filter is "All"
        if (selectedFilter == "All") {
            item {
                Column(
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { navController.navigate(Routes.History.route) }
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF27856A)),
                    ) {
                        Icon(Icons.Default.DateRange, contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp))
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(text = "Listening history", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(text = "Plays & stats", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        // Create Playlist card when in Playlists filter or All
        if (selectedFilter == "Playlists" || selectedFilter == "All") {
            item {
                Column(
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onCreatePlaylist() }
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF22222C)),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Create Playlist", tint = AppPalette, modifier = Modifier.size(36.dp))
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(text = "Create playlist", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(text = "New custom playlist", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        items(entries) { entry ->
            val hasOfflineTracks = entry.spotifyId == Api.HomeCache.DOWNLOADS_ID ||
                entry.subtitle.contains("Downloaded", ignoreCase = true) ||
                getDownloadedSongsForPlaylist(context, entry.spotifyId).isNotEmpty()

            Column(
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { openLibraryEntry(entry, navController) }
            ) {
                Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
                    if (entry.spotifyId == Api.HomeCache.DOWNLOADS_ID) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(6.dp))
                                .background(AppPalette.copy(alpha = 0.2f))
                        ) {
                            Icon(
                                imageVector = ImageVector.vectorResource(R.drawable.ic_download),
                                contentDescription = "Downloads",
                                tint = AppPalette,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    } else {
                        GlideImage(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(if (entry.isPlaylist) 6.dp else 4.dp)),
                            model = entry.coverUri,
                            failure = placeholder(R.drawable.placeholder),
                            contentScale = ContentScale.Crop,
                            contentDescription = ""
                        )
                    }

                    if (hasOfflineTracks && entry.spotifyId != Api.HomeCache.DOWNLOADS_ID) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Downloaded",
                            tint = AppPalette,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(4.dp)
                                .size(16.dp)
                                .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(text = entry.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(text = entry.subtitle, color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        if (followedArtists.isNotEmpty()) {
            if (selectedFilter == "All") {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = "Artists you follow",
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            items(followedArtists) { artist ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { navController.navigate(artistRoute(artist.name, artist.id)) }
                ) {
                    GlideImage(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(CircleShape),
                        model = artist.coverUri,
                        failure = placeholder(R.drawable.placeholder),
                        contentScale = ContentScale.Crop,
                        contentDescription = ""
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(text = artist.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    Text(text = "Artist", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

@Composable
private fun LibrarySkeleton(padding: PaddingValues) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .background(Color(0xFF0B0B0F))
    ) {
        Spacer(modifier = Modifier.height(10.dp))
        repeat(8) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp, 6.dp)
            ) {
                Box(modifier = Modifier.size(55.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF1E1E1E)))
                Column(modifier = Modifier.padding(start = 12.dp)) {
                    Box(modifier = Modifier.height(14.dp).width(160.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFF1E1E1E)))
                    Spacer(modifier = Modifier.height(6.dp))
                    Box(modifier = Modifier.height(11.dp).width(90.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFF1E1E1E)))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountSheet(
    account: AccountModel,
    navController: NavController,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF1A1A1A),
    ) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(16.dp, 4.dp, 16.dp, 16.dp)
            ) {
                Box(
                    modifier = Modifier.size(56.dp).clip(CircleShape).background(Color(0xFF3A3A3A)),
                    contentAlignment = Alignment.Center
                ) {
                    if (account.imageUrl.isNotBlank()) AccountAvatar(account.imageUrl, 56.dp)
                    else Icon(Icons.Default.Person, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column {
                    Text(account.name.ifBlank { "Sepotify user" }, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (account.email.isNotBlank()) Text(account.email, color = Color.Gray, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (account.plan.isNotBlank()) Text(account.plan, color = AppPalette, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
            HorizontalDivider(color = Color(0xFF2A2A2A))

            AccountRow("Listening history") {
                onDismiss()
                navController.navigate(Routes.History.route)
            }
            HorizontalDivider(color = Color(0xFF2A2A2A))
            val isSpotifyLoggedIn = SpotifySession.spDc(context).isNotBlank()
            if (isSpotifyLoggedIn) {
                AccountRow("Log out of Spotify", tint = Color(0xFFE57373)) {
                    SpotifySession.setSpDc(context, "")
                    SpotifySession.setGuestMode(context, true)
                    Api.HomeCache.clear()
                    onDismiss()
                    navController.navigate(Routes.Login.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            } else {
                AccountRow("Log in to Spotify", tint = AppPalette) {
                    SpotifySession.setGuestMode(context, false)
                    onDismiss()
                    navController.navigate(Routes.Login.route)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun AccountRow(label: String, tint: Color = Color.White, onClick: () -> Unit) {
    Text(
        text = label,
        color = tint,
        fontSize = 15.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(20.dp, 16.dp)
    )
}
