package com.music.spotui.ui.screens

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
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
import androidx.compose.ui.draw.alpha
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
import com.music.spotui.data.entity.AlbumsModel
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.api.Api
import com.music.spotui.data.preferences.addLikedAlbumId
import com.music.spotui.data.preferences.addLikedSongId
import com.music.spotui.data.preferences.isAlbumLiked
import com.music.spotui.data.preferences.isSongLiked
import com.music.spotui.data.preferences.removeLikedAlbum
import com.music.spotui.data.preferences.removeLikedAlbumId
import com.music.spotui.data.preferences.removeLikedSongId
import com.music.spotui.data.preferences.saveLikedAlbum
import com.music.spotui.di.Palette
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.components.LikedSongsScreen
import com.music.spotui.ui.components.Loader
import com.music.spotui.ui.components.Snackbar
import com.music.spotui.ui.theme.AppBackground
import com.music.spotui.ui.theme.AppPalette
import com.music.spotui.ui.viewmodel.AlbumViewModel
import kotlinx.coroutines.delay


@Composable
fun AlbumScreen(navController: NavController, albumName: String, artist: String = "") {


    val albumViewModel : AlbumViewModel = hiltViewModel()
    val songs by albumViewModel.songs.collectAsState()
    val albums by albumViewModel.albums.collectAsState()

    // Load this album's actual tracks from Spotify (by name, disambiguated by artist).
    LaunchedEffect(albumName, artist) {
        albumViewModel.loadAlbumSongs(albumName, artist)
    }

    val context = LocalContext.current




    Log.d("check", albumName.toString())

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(AppBackground.toArgb()))
    ) {
        val albumsResponse = (albums as? Response.Success)?.data.orEmpty()
        val songsResponse = (songs as? Response.Success)?.data.orEmpty()

        when {
            albums is Response.Loading && songs is Response.Loading -> {
                Log.d("homeMain", "loading..-albums")
                Loader()
            }

            else -> {
                Log.d("homeMain", "albums ready")
                if (albumName == "Liked Songs"){
                    LikedSongsScreen(albumsResponse, songsResponse, navController, context)
                }
                else{
                    SumUpAlbumScreen(navController = navController,albumViewModel, albumsResponse, songsResponse, albumName, context)
                }
            }
        }
    }

}
@OptIn(ExperimentalMaterial3Api::class, ExperimentalGlideComposeApi::class, ExperimentalFoundationApi::class)
@Composable
fun SumUpAlbumScreen(
    navController: NavController,
    albumViewModel: AlbumViewModel,
    albums: List<AlbumsModel>,
    songs: List<SongsModel>,
    albumName: String,
    context: Context
) {
    // `songs` is already this album's track list (loaded by AlbumViewModel).
    val albumSongs: List<SongsModel> = songs

    // Warm the stream cache for the first few tracks so the first tap plays
    // (near-)instantly instead of resolving YouTube on the tap.
    LaunchedEffect(albumSongs) {
        if (albumSongs.isNotEmpty()) {
            SongPlayer.prefetchList(albumSongs.map { it.url }, context)
        }
    }

    val albumByName : Map<String, List<AlbumsModel>> = albums.groupBy { it.name }
    val rawAlbum = albumByName[albumName]?.firstOrNull()
    val resolvedCover = rawAlbum?.coverUri?.takeIf { it.isNotBlank() }
        ?: albumSongs.firstOrNull()?.coverUri.orEmpty()
    val resolvedArtist = rawAlbum?.artists?.takeIf { it.isNotBlank() }
        ?: albumSongs.firstOrNull()?.singer.orEmpty()
    val currentAlbum = rawAlbum?.copy(coverUri = resolvedCover, artists = resolvedArtist)
        ?: AlbumsModel(
            id = albumName.hashCode() and 0x7fffffff,
            artists = resolvedArtist,
            coverUri = resolvedCover,
            name = albumName,
            time = "",
        )
    val album : List<AlbumsModel> = listOf(currentAlbum)

    var dominentColor by remember {
        mutableStateOf(Color(AppBackground.toArgb()))
    }
    val effectiveCover = currentAlbum.coverUri.ifBlank { albumSongs.firstOrNull()?.coverUri.orEmpty() }
    Palette().extractSecondColorFromCoverUrl(context = context, effectiveCover){ color ->
        dominentColor = color
    }

    var isAlbumLiked by remember(currentAlbum.id) { mutableStateOf(isAlbumLiked(context, currentAlbum.id.toString())) }

    // Auto-update saved album cover if it was saved when cover wasn't ready yet
    LaunchedEffect(effectiveCover) {
        if (effectiveCover.isNotBlank() && isAlbumLiked(context, currentAlbum.id.toString())) {
            saveLikedAlbum(context, currentAlbum.copy(coverUri = effectiveCover))
            Api.HomeCache.library = null
        }
    }

    var snackbarMessage by remember {
        mutableStateOf("")
    }
    var snackbarVisible by remember {
        mutableStateOf(false)
    }
    var menuSong by remember { mutableStateOf<SongsModel?>(null) }
    menuSong?.let { sel ->
        com.music.spotui.ui.components.SongOptionsSheet(
            song = sel,
            navController = navController,
            context = context,
            onDismiss = { menuSong = null },
        )
    }
    LaunchedEffect(snackbarVisible) {
        delay(1500)
        snackbarVisible = false
    }



    Log.d("color", dominentColor.toString())
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
                        ) {
                            navController.navigateUp()
                        },
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "",
                        tint = Color.White)
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = Color.White,
                ),
                title = {
                    Text(text = "")
                }
            )
        }
    ) { innerPadding ->


        Column(modifier = Modifier
            .fillMaxSize()
            .background(Color(AppBackground.toArgb()))
            .consumeWindowInsets(innerPadding)
            .padding(bottom = innerPadding.calculateBottomPadding())
            .verticalScroll(rememberScrollState())
        ) {

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(460.dp)
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(dominentColor, Color(AppBackground.toArgb())),
                            startY = -100f,

                            ),

                        )
                ,
                verticalArrangement = Arrangement.Center,
               // horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.padding(25.dp))

                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    GlideImage(
                        modifier = Modifier.size(230.dp),
                        model = album[0].coverUri,
                        failure = placeholder(R.drawable.placeholder),
                        //loading = placeholder(R.drawable.album),
                        //contentScale = ContentScale.Crop,
                        contentDescription = "",
                    )
                }
                Spacer(modifier = Modifier.padding(5.dp))
                Text(modifier = Modifier
                    .padding(20.dp, 5.dp, 0.dp, 0.dp),
                    text = albumName,
                    color = Color.White,
                    fontSize = 23.sp,
                    fontWeight = FontWeight.Bold)
                Text(modifier = Modifier
                    .padding(20.dp, 0.dp, 0.dp, 0.dp),
                    text = album[0].artists.ifBlank { albumSongs.firstOrNull()?.singer ?: "" },
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium)
                Text(modifier = Modifier
                    .padding(20.dp, 0.dp, 0.dp, 0.dp),
                    text = "Album : ${album[0].time}",
                    color = Color.Gray,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )

                Row(horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .padding(20.dp, 0.dp)
                ){

                    if (snackbarVisible){
                            Snackbar(showMessage = snackbarMessage)
                        }
                    else{
                        // Let the action icons take their natural width — a fixed
                        // 75dp squeezed the add + download buttons together.
                        Row(horizontalArrangement = Arrangement.spacedBy(18.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {

                            GlideImage(
                                modifier = Modifier
                                    .height(60.dp)
                                    .width(32.dp)
                                    .padding(0.dp, 5.dp)
                                    .clip(RoundedCornerShape(5.dp))
                                ,
                                model = album[0].coverUri,
                                failure = placeholder(R.drawable.placeholder),
                                //loading = placeholder(R.drawable.album),
                                contentScale = ContentScale.Crop,
                                contentDescription = "",
                            )
                            Icon(
                                modifier = Modifier
                                    .size(23.dp)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) {
                                        if (isAlbumLiked) {
                                            removeLikedAlbum(context, currentAlbum.id.toString())
                                            snackbarMessage = "Removed from Library"
                                        } else {
                                            saveLikedAlbum(context, currentAlbum)
                                            snackbarMessage = "Added to Library"
                                        }
                                        Api.HomeCache.library = null
                                        isAlbumLiked = isAlbumLiked(context, currentAlbum.id.toString())
                                        snackbarVisible = true

                                    },
                                painter = if (isAlbumLiked){
                                    painterResource(id = R.drawable.added)
                                }
                                else{
                                    painterResource(id = R.drawable.ic_add)
                                }
                                ,
                                tint = if (isAlbumLiked){
                                    Color(AppPalette.toArgb())
                                }
                                else{
                                    Color.White
                                },
                                contentDescription = ""
                            )
                            val batchDownloads by SongPlayer.batchDownloads.collectAsState()
                            val albumBatch = if (album.isNotEmpty()) batchDownloads[album[0].id.toString()] else null
                            val isAlbumDownloading = albumBatch?.isDownloading == true

                            // Download the whole album (all tracks) for offline playback.
                            var albumDownloaded by remember(albumSongs, batchDownloads) {
                                mutableStateOf(SongPlayer.allDownloaded(albumSongs, context))
                            }
                            if (isAlbumDownloading && albumBatch != null) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clickable {
                                            SongPlayer.cancelBatchDownload(album[0].id.toString())
                                        }
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        color = Color(AppPalette.toArgb()),
                                        strokeWidth = 2.5.dp,
                                        progress = { ((albumBatch.progressPercent).coerceIn(0, 100)) / 100f }
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
                                    imageVector = if (albumDownloaded)
                                        Icons.Default.CheckCircle else ImageVector.vectorResource(R.drawable.ic_download),
                                    tint = if (albumDownloaded) Color(AppPalette.toArgb()) else Color.White,
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                        ) {
                                            if (!albumDownloaded && albumSongs.isNotEmpty()) {
                                                SongPlayer.downloadAll(
                                                    albumSongs,
                                                    context,
                                                    album[0].id.toString(),
                                                    albumName,
                                                    isAlbum = true,
                                                )
                                                snackbarMessage = "Downloading ${albumSongs.size} tracks…"
                                                snackbarVisible = true
                                            }
                                        },
                                    contentDescription = "Download album",
                                )
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            // Shuffle-play: start the album in random order.
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
                                        val playable = if (isOffline) albumSongs.filter { com.music.spotui.data.preferences.isSongDownloaded(context, it) } else albumSongs
                                        if (playable.isEmpty()) {
                                            android.widget.Toast.makeText(context, "No downloaded songs available offline", android.widget.Toast.LENGTH_SHORT).show()
                                        } else {
                                            albumViewModel.startShuffled(playable)?.let { first ->
                                                SongPlayer.playSong(first.url, context)
                                                albumViewModel.updateSongState(
                                                    first.coverUri,
                                                    first.title,
                                                    first.singer,
                                                    true,
                                                    first.id,
                                                    0,
                                                    albumName,
                                                )
                                            }
                                        }
                                    },
                                contentDescription = "Shuffle play",
                            )
                        }


                        // Always visible: pause when playing, resume when this
                        // album's track is paused, otherwise start from the top.
                        if (albumSongs.isNotEmpty()) {
                            val isCurrentSongInAlbum = albumSongs.any { it.id == albumViewModel.currentSongId.value }
                            val isCurrentPlaying = albumViewModel.currentSongPlayingState.value && isCurrentSongInAlbum
                            androidx.compose.foundation.layout.Box(
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
                                        val playable = if (isOffline) albumSongs.filter { com.music.spotui.data.preferences.isSongDownloaded(context, it) } else albumSongs
                                        when {
                                            isCurrentPlaying -> albumViewModel.setPlaying(false)
                                            isCurrentSongInAlbum -> albumViewModel.setPlaying(true)
                                            playable.isEmpty() -> {
                                                android.widget.Toast.makeText(context, "No downloaded songs available offline", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                            else -> {
                                                albumViewModel.updateQueue(playable)
                                                SongPlayer.playSong(playable[0].url, context)
                                                albumViewModel.updateSongState(
                                                    playable[0].coverUri,
                                                    playable[0].title,
                                                    playable[0].singer,
                                                    true,
                                                    playable[0].id,
                                                    0,
                                                    albumName
                                                )
                                            }
                                        }
                                    }
                            ) {
                                Icon(
                                    modifier = Modifier
                                        .size(25.dp),
                                    tint = Color.Black,
                                    painter = painterResource(
                                        id = if (isCurrentPlaying) R.drawable.ic_playing else R.drawable.play_svgrepo_com,
                                    ),
                                    contentDescription = if (isCurrentPlaying) "Pause" else "Play")
                            }
                        }
                    }




                }

                val albumBatch = if (album.isNotEmpty()) SongPlayer.getBatchProgress(album[0].id.toString()) else null
                if (albumBatch?.isDownloading == true) {
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
                                    text = "Downloading tracks: ${albumBatch.completedTracks}/${albumBatch.totalTracks} (${albumBatch.progressPercent}%)",
                                    color = Color(AppPalette.toArgb()),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                if (albumBatch.failedTracks > 0) {
                                    Text(
                                        text = "${albumBatch.failedTracks} skipped",
                                        color = Color.LightGray,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                            if (albumBatch.currentTrackTitle.isNotBlank()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Song: ${albumBatch.currentTrackTitle}",
                                    color = Color.LightGray,
                                    fontSize = 11.sp,
                                    maxLines = 1
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { (albumBatch.progressPercent.coerceIn(0, 100)) / 100f },
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

//            Spacer(modifier = Modifier.padding(25.dp))

            if(albumSongs.isNotEmpty()){
                repeat(albumSongs.size) {song ->


                    var isLiked by remember {
                        mutableStateOf(isSongLiked(context, albumSongs[song].id.toString()))
                    }
                    val likeState = albumViewModel.likeState.value
                    LaunchedEffect(likeState){
                        isLiked = isSongLiked(context, albumSongs[song].id.toString())
                    }
                    val songId = albumSongs[song].id
                    val isOffline = !com.music.spotui.data.network.NetworkMonitor.isOnlineNow(context)
                    val isTrackDownloaded = remember(songId, isOffline) {
                        com.music.spotui.data.preferences.isSongDownloaded(context, albumSongs[song])
                    }
                    val rowAlpha = if (isOffline && !isTrackDownloaded) 0.38f else 1.0f

                    val currentPlayingIndicatorColor = if(songId == albumViewModel.currentSongId.value) Color(AppPalette.toArgb()) else Color.White

                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onLongClick = { menuSong = albumSongs[song] },
                                onClick = {
                                    if (isOffline && !isTrackDownloaded) {
                                        android.widget.Toast.makeText(context, "Song is unavailable offline", android.widget.Toast.LENGTH_SHORT).show()
                                    } else {
                                        val playable = if (isOffline) albumSongs.filter { com.music.spotui.data.preferences.isSongDownloaded(context, it) } else albumSongs
                                        val queueIndex = playable.indexOfFirst { it.id == albumSongs[song].id }.let { if (it >= 0) it else song }
                                        albumViewModel.updateQueue(playable)
                                        albumViewModel.updateSongState(
                                            albumSongs[song].coverUri,
                                            albumSongs[song].title,
                                            albumSongs[song].singer,
                                            true,
                                            albumSongs[song].id,
                                            queueIndex,
                                            albumName
                                        )
                                        SongPlayer.playSong(albumSongs[song].url, context)
                                    }
                                },
                            )
                            .padding(20.dp, 8.dp)
                            .alpha(rowAlpha)
                    ) {

                        Row(
                            horizontalArrangement = Arrangement.Start,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.width(200.dp)
                        ) {
                            Column {
                                Text(
                                    text = albumSongs[song].title,
                                    color = currentPlayingIndicatorColor,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
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
                                        text = albumSongs[song].singer,
                                        color = Color.Gray,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        Icon(
                            modifier = Modifier
                                .size(20.dp)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) {
                                    if (isLiked) {
                                        removeLikedSongId(context, songId.toString())
                                    } else {
                                        addLikedSongId(context, songId.toString())
                                    }
                                    isLiked = isSongLiked(context, songId.toString())
                                    albumViewModel.updateLikeState(!albumViewModel.likeState.value)

                                },
                            painter = if (isLiked){
                                painterResource(id = R.drawable.added)
                            }
                            else{
                                painterResource(id = R.drawable.ic_add)
                            }
                            ,
                            tint = if (isLiked){
                                Color.White
                            }else{
                                Color.Gray
                            },
                            contentDescription = ""
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(160.dp))
        }

    }
}
