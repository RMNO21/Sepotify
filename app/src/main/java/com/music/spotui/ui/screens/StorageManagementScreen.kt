package com.music.spotui.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.preferences.clearAllDownloads
import com.music.spotui.data.preferences.formatBytesHumanReadable
import com.music.spotui.data.preferences.getDownloadStorageInfo
import com.music.spotui.data.preferences.getDownloadedEntries
import com.music.spotui.data.preferences.getMediaCacheMaxMb
import com.music.spotui.data.preferences.isGaplessPlaybackEnabled
import com.music.spotui.data.preferences.isPreloadEnabled
import com.music.spotui.data.preferences.removeDownload
import com.music.spotui.data.preferences.setGaplessPlaybackEnabled
import com.music.spotui.data.preferences.setMediaCacheMaxMb
import com.music.spotui.data.preferences.setPreloadEnabled
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.theme.AppBackground
import com.music.spotui.ui.viewmodel.PlayerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class DownloadedItemInfo(
    val song: SongsModel,
    val filePath: String,
    val fileSizeBytes: Long,
    val format: String,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalGlideComposeApi::class, ExperimentalLayoutApi::class)
@Composable
fun StorageManagementScreen(
    navController: NavController,
    playerViewModel: PlayerViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var mediaCacheBytes by remember { mutableLongStateOf(0L) }
    var imageCacheBytes by remember { mutableLongStateOf(0L) }
    var downloadedBytes by remember { mutableLongStateOf(0L) }
    var freeDeviceBytes by remember { mutableLongStateOf(0L) }
    var totalDeviceBytes by remember { mutableLongStateOf(0L) }
    var downloadedTracks by remember { mutableStateOf<List<DownloadedItemInfo>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var storagePath by remember { mutableStateOf("") }

    var gaplessEnabled by remember { mutableStateOf(isGaplessPlaybackEnabled(context)) }
    var preloadEnabled by remember { mutableStateOf(isPreloadEnabled(context)) }
    var selectedMaxCacheMb by remember { mutableIntStateOf(getMediaCacheMaxMb(context)) }

    var showClearMediaCacheDialog by remember { mutableStateOf(false) }
    var showClearAllDownloadsDialog by remember { mutableStateOf(false) }
    var showClearImageCacheDialog by remember { mutableStateOf(false) }
    var trackToDelete by remember { mutableStateOf<DownloadedItemInfo?>(null) }

    fun refreshStats() {
        scope.launch(Dispatchers.IO) {
            val mCache = SongPlayer.getMediaCacheSizeBytes(context)
            val iCache = SongPlayer.getImageCacheSizeBytes(context)
            val dlInfo = getDownloadStorageInfo(context)
            val entries = getDownloadedEntries(context)
            val tracks = entries.map { (song, path) ->
                val f = File(path)
                val len = if (f.exists()) f.length() else 0L
                val ext = f.extension.uppercase().ifBlank { "AUDIO" }
                DownloadedItemInfo(song, path, len, ext)
            }.sortedByDescending { it.fileSizeBytes }

            val free = runCatching { context.filesDir.usableSpace }.getOrDefault(0L)
            val total = runCatching { context.filesDir.totalSpace }.getOrDefault(0L)

            withContext(Dispatchers.Main) {
                mediaCacheBytes = mCache
                imageCacheBytes = iCache
                downloadedBytes = dlInfo.totalSizeBytes
                storagePath = dlInfo.internalPath
                freeDeviceBytes = free
                totalDeviceBytes = total
                downloadedTracks = tracks
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshStats()
    }

    val filteredTracks = remember(downloadedTracks, searchQuery) {
        if (searchQuery.isBlank()) downloadedTracks
        else downloadedTracks.filter {
            it.song.title.contains(searchQuery, ignoreCase = true) ||
                it.song.singer.contains(searchQuery, ignoreCase = true) ||
                it.song.album.contains(searchQuery, ignoreCase = true)
        }
    }

    val accentGreen = Color(0xFF1DB954)
    val streamCyan = Color(0xFF29B6F6)
    val imageAmber = Color(0xFFFFA726)
    val cardBg = Color(0xFF1A1A20)
    val cardBorder = Color(0xFF262632)

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(AppBackground.toArgb())),
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                CenterAlignedTopAppBar(
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = Color(AppBackground.toArgb()),
                        titleContentColor = Color.White,
                    ),
                    navigationIcon = {
                        IconButton(onClick = { navController.navigateUp() }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = Color.White,
                            )
                        }
                    },
                    title = {
                        Text(
                            text = "Storage & Cache",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                        )
                    },
                    actions = {
                        IconButton(onClick = { refreshStats() }) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Refresh",
                                tint = Color.White,
                            )
                        }
                    }
                )
            }
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .consumeWindowInsets(innerPadding)
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // ── 1. Storage Overview Visual Card ──
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = cardBg),
                        border = androidx.compose.foundation.BorderStroke(1.dp, cardBorder),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    ) {
                        Column(modifier = Modifier.padding(18.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_settings),
                                        contentDescription = null,
                                        tint = accentGreen,
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Device Storage Overview",
                                        color = Color.White,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                                val appTotal = downloadedBytes + mediaCacheBytes + imageCacheBytes
                                Text(
                                    text = "App: ${formatBytesHumanReadable(appTotal)}",
                                    color = accentGreen,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            // Segmented bar
                            val totalAppSpace = (downloadedBytes + mediaCacheBytes + imageCacheBytes).coerceAtLeast(1L)
                            val dlFraction = (downloadedBytes.toFloat() / totalAppSpace).coerceIn(0f, 1f)
                            val streamFraction = (mediaCacheBytes.toFloat() / totalAppSpace).coerceIn(0f, 1f)
                            val imageFraction = (imageCacheBytes.toFloat() / totalAppSpace).coerceIn(0f, 1f)

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(10.dp)
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(Color(0xFF2E2E38))
                            ) {
                                Row(modifier = Modifier.fillMaxSize()) {
                                    if (dlFraction > 0.01f) {
                                        Box(
                                            modifier = Modifier
                                                .weight(dlFraction)
                                                .fillMaxSize()
                                                .background(accentGreen)
                                        )
                                    }
                                    if (streamFraction > 0.01f) {
                                        Box(
                                            modifier = Modifier
                                                .weight(streamFraction)
                                                .fillMaxSize()
                                                .background(streamCyan)
                                        )
                                    }
                                    if (imageFraction > 0.01f) {
                                        Box(
                                            modifier = Modifier
                                                .weight(imageFraction)
                                                .fillMaxSize()
                                                .background(imageAmber)
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            // Legend Grid
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                StorageLegendItem(
                                    color = accentGreen,
                                    label = "Downloads",
                                    sizeStr = formatBytesHumanReadable(downloadedBytes),
                                )
                                StorageLegendItem(
                                    color = streamCyan,
                                    label = "Audio Cache",
                                    sizeStr = formatBytesHumanReadable(mediaCacheBytes),
                                )
                                StorageLegendItem(
                                    color = imageAmber,
                                    label = "Covers Art",
                                    sizeStr = formatBytesHumanReadable(imageCacheBytes),
                                )
                                StorageLegendItem(
                                    color = Color.LightGray,
                                    label = "Free Disk",
                                    sizeStr = formatBytesHumanReadable(freeDeviceBytes),
                                )
                            }
                        }
                    }
                }

                // ── 2. Streaming Audio Cache Management ──
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = cardBg),
                        border = androidx.compose.foundation.BorderStroke(1.dp, cardBorder),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(18.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_playing),
                                        contentDescription = null,
                                        tint = streamCyan,
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Streaming Media Cache",
                                        color = Color.White,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                                Text(
                                    text = formatBytesHumanReadable(mediaCacheBytes),
                                    color = streamCyan,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Cached stream chunks allow instantaneous playback, zero buffering stutter, and instant rewinding without consuming extra data.",
                                color = Color(0xFFA0A0B0),
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                            )

                            Spacer(modifier = Modifier.height(14.dp))
                            Text(
                                text = "Cache Size Limit:",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(modifier = Modifier.height(6.dp))

                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                listOf(512 to "512 MB", 1024 to "1 GB", 2048 to "2 GB", 4096 to "4 GB").forEach { (mb, label) ->
                                    val isSelected = selectedMaxCacheMb == mb
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = {
                                            selectedMaxCacheMb = mb
                                            setMediaCacheMaxMb(context, mb)
                                            Toast.makeText(context, "Max cache limit set to $label", Toast.LENGTH_SHORT).show()
                                        },
                                        label = { Text(label, fontSize = 12.sp) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = streamCyan.copy(alpha = 0.25f),
                                            selectedLabelColor = streamCyan,
                                            containerColor = Color(0xFF24242E),
                                            labelColor = Color.LightGray,
                                        ),
                                        border = FilterChipDefaults.filterChipBorder(
                                            enabled = true,
                                            selected = isSelected,
                                            borderColor = if (isSelected) streamCyan else Color.Transparent,
                                        )
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF262632))
                                    .clickable { showClearMediaCacheDialog = true }
                                    .padding(vertical = 10.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = null,
                                    tint = streamCyan,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Clear Audio Stream Cache",
                                    color = streamCyan,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }

                // ── 3. Gapless Playback & Buffer Optimization ──
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = cardBg),
                        border = androidx.compose.foundation.BorderStroke(1.dp, cardBorder),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(18.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_crossfade),
                                    contentDescription = null,
                                    tint = accentGreen,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Gapless & Buffering Engine",
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // Gapless playback switch
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                    Text(
                                        text = "Gapless Playback",
                                        color = Color.White,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        text = "Sample-accurate seamless song transitions with zero silence gap between consecutive tracks.",
                                        color = Color(0xFFA0A0B0),
                                        fontSize = 12.sp,
                                        lineHeight = 16.sp,
                                    )
                                }
                                Switch(
                                    checked = gaplessEnabled,
                                    onCheckedChange = {
                                        gaplessEnabled = it
                                        setGaplessPlaybackEnabled(context, it)
                                        Toast.makeText(
                                            context,
                                            if (it) "Gapless playback enabled" else "Gapless playback disabled",
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.White,
                                        checkedTrackColor = accentGreen,
                                        uncheckedThumbColor = Color.LightGray,
                                        uncheckedTrackColor = Color(0xFF33333E),
                                    )
                                )
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            // Lookahead pre-buffering switch
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                    Text(
                                        text = "Lookahead Audio Pre-buffering",
                                        color = Color.White,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        text = "Pre-loads next track intro segment into memory early so upcoming tracks start playing instantly.",
                                        color = Color(0xFFA0A0B0),
                                        fontSize = 12.sp,
                                        lineHeight = 16.sp,
                                    )
                                }
                                Switch(
                                    checked = preloadEnabled,
                                    onCheckedChange = {
                                        preloadEnabled = it
                                        setPreloadEnabled(context, it)
                                        Toast.makeText(
                                            context,
                                            if (it) "Lookahead pre-buffering enabled" else "Lookahead pre-buffering disabled",
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.White,
                                        checkedTrackColor = accentGreen,
                                        uncheckedThumbColor = Color.LightGray,
                                        uncheckedTrackColor = Color(0xFF33333E),
                                    )
                                )
                            }
                        }
                    }
                }

                // ── 4. Image & Album Art Cache ──
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = cardBg),
                        border = androidx.compose.foundation.BorderStroke(1.dp, cardBorder),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(18.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_devices),
                                        contentDescription = null,
                                        tint = imageAmber,
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Album Art & Image Cache",
                                        color = Color.White,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                                Text(
                                    text = formatBytesHumanReadable(imageCacheBytes),
                                    color = imageAmber,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Cached artist banners and high-resolution album cover artwork for fast visual browsing.",
                                color = Color(0xFFA0A0B0),
                                fontSize = 12.sp,
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF262632))
                                    .clickable { showClearImageCacheDialog = true }
                                    .padding(vertical = 10.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = null,
                                    tint = imageAmber,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Clear Album Art Cache",
                                    color = imageAmber,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }

                // ── 5. Downloaded Offline Music Files & Detailed Manager ──
                item {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_download),
                                    contentDescription = null,
                                    tint = accentGreen,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Offline Music Files (${downloadedTracks.size})",
                                    color = Color.White,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            if (downloadedTracks.isNotEmpty()) {
                                Text(
                                    text = "Delete All",
                                    color = Color(0xFFE57373),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color(0xFF2E2428))
                                        .clickable { showClearAllDownloadsDialog = true }
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                )
                            }
                        }

                        if (storagePath.isNotBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Path: $storagePath",
                                color = Color.Gray,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        if (downloadedTracks.size > 3) {
                            Spacer(modifier = Modifier.height(10.dp))
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                placeholder = { Text("Filter downloaded tracks...", color = Color.Gray, fontSize = 13.sp) },
                                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color.Gray) },
                                trailingIcon = {
                                    if (searchQuery.isNotEmpty()) {
                                        IconButton(onClick = { searchQuery = "" }) {
                                            Icon(Icons.Default.Clear, contentDescription = "Clear search", tint = Color.Gray)
                                        }
                                    }
                                },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = Color(0xFF16161D),
                                    unfocusedContainerColor = Color(0xFF16161D),
                                    focusedBorderColor = accentGreen,
                                    unfocusedBorderColor = Color(0xFF2A2A36),
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }

                if (filteredTracks.isEmpty()) {
                    item {
                        Card(
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = cardBg),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_playing),
                                    contentDescription = null,
                                    tint = Color.Gray,
                                    modifier = Modifier.size(36.dp),
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = if (searchQuery.isBlank()) "No downloaded music files" else "No matching tracks found",
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = if (searchQuery.isBlank()) "Download tracks from the track menu for instant offline playback." else "Try a different search query.",
                                    color = Color.Gray,
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp,
                                )
                            }
                        }
                    }
                } else {
                    items(filteredTracks, key = { it.song.id }) { trackInfo ->
                        DownloadedTrackRow(
                            trackInfo = trackInfo,
                            onPlay = {
                                val allSongs = downloadedTracks.map { it.song }
                                val idx = allSongs.indexOfFirst { it.id == trackInfo.song.id }.coerceAtLeast(0)
                                playerViewModel.updateQueue(allSongs)
                                SongPlayer.playSong(trackInfo.song.url, context)
                                playerViewModel.updateSongState(
                                    coverUri = trackInfo.song.coverUri,
                                    title = trackInfo.song.title,
                                    singer = trackInfo.song.singer,
                                    playingState = true,
                                    songId = trackInfo.song.id,
                                    songIndex = idx,
                                    album = "Downloaded",
                                )
                                Toast.makeText(context, "Playing offline: ${trackInfo.song.title}", Toast.LENGTH_SHORT).show()
                            },
                            onDelete = {
                                trackToDelete = trackInfo
                            }
                        )
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(120.dp))
                }
            }
        }
    }

    // ── Confirmation Dialogs ──

    // Clear Audio Cache Dialog
    if (showClearMediaCacheDialog) {
        AlertDialog(
            onDismissRequest = { showClearMediaCacheDialog = false },
            title = { Text("Clear Streaming Audio Cache", fontWeight = FontWeight.Bold) },
            text = { Text("This will clear ${formatBytesHumanReadable(mediaCacheBytes)} of cached audio chunks. This frees space immediately. Streamed tracks will re-cache as you listen.") },
            confirmButton = {
                TextButton(onClick = {
                    showClearMediaCacheDialog = false
                    scope.launch(Dispatchers.IO) {
                        val freed = SongPlayer.clearMediaCache(context)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Freed ${formatBytesHumanReadable(freed)} of streaming cache", Toast.LENGTH_SHORT).show()
                            refreshStats()
                        }
                    }
                }) {
                    Text("Clear Cache", color = streamCyan, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearMediaCacheDialog = false }) {
                    Text("Cancel", color = Color.Gray)
                }
            },
            containerColor = Color(0xFF1E1E28),
            titleContentColor = Color.White,
            textContentColor = Color.LightGray,
        )
    }

    // Clear Image Cache Dialog
    if (showClearImageCacheDialog) {
        AlertDialog(
            onDismissRequest = { showClearImageCacheDialog = false },
            title = { Text("Clear Album Art Cache", fontWeight = FontWeight.Bold) },
            text = { Text("This will remove ${formatBytesHumanReadable(imageCacheBytes)} of cached album art images. Artwork will reload automatically when connected to the internet.") },
            confirmButton = {
                TextButton(onClick = {
                    showClearImageCacheDialog = false
                    scope.launch(Dispatchers.IO) {
                        val freed = SongPlayer.clearImageCache(context)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Freed ${formatBytesHumanReadable(freed)} of image cache", Toast.LENGTH_SHORT).show()
                            refreshStats()
                        }
                    }
                }) {
                    Text("Clear Artwork", color = imageAmber, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearImageCacheDialog = false }) {
                    Text("Cancel", color = Color.Gray)
                }
            },
            containerColor = Color(0xFF1E1E28),
            titleContentColor = Color.White,
            textContentColor = Color.LightGray,
        )
    }

    // Clear All Downloads Dialog
    if (showClearAllDownloadsDialog) {
        AlertDialog(
            onDismissRequest = { showClearAllDownloadsDialog = false },
            title = { Text("Delete All Downloaded Songs?", fontWeight = FontWeight.Bold) },
            text = { Text("This will permanently remove ${downloadedTracks.size} downloaded tracks (${formatBytesHumanReadable(downloadedBytes)}) from your device storage.") },
            confirmButton = {
                TextButton(onClick = {
                    showClearAllDownloadsDialog = false
                    scope.launch(Dispatchers.IO) {
                        val count = clearAllDownloads(context)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Removed $count downloaded tracks", Toast.LENGTH_SHORT).show()
                            refreshStats()
                        }
                    }
                }) {
                    Text("Delete All", color = Color(0xFFE57373), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllDownloadsDialog = false }) {
                    Text("Cancel", color = Color.Gray)
                }
            },
            containerColor = Color(0xFF1E1E28),
            titleContentColor = Color.White,
            textContentColor = Color.LightGray,
        )
    }

    // Delete Single Track Dialog
    trackToDelete?.let { itemInfo ->
        AlertDialog(
            onDismissRequest = { trackToDelete = null },
            title = { Text("Delete Track?", fontWeight = FontWeight.Bold) },
            text = { Text("Remove \"${itemInfo.song.title}\" (${formatBytesHumanReadable(itemInfo.fileSizeBytes)}) from local storage?") },
            confirmButton = {
                TextButton(onClick = {
                    trackToDelete = null
                    scope.launch(Dispatchers.IO) {
                        removeDownload(context, itemInfo.song.id.toString())
                        if (itemInfo.song.url.isNotBlank()) removeDownload(context, itemInfo.song.url)
                        if (itemInfo.song.spotifyTrackId.isNotBlank()) removeDownload(context, itemInfo.song.spotifyTrackId)
                        if (itemInfo.filePath.isNotBlank()) runCatching { File(itemInfo.filePath).delete() }
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Removed ${itemInfo.song.title}", Toast.LENGTH_SHORT).show()
                            refreshStats()
                        }
                    }
                }) {
                    Text("Delete", color = Color(0xFFE57373), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { trackToDelete = null }) {
                    Text("Cancel", color = Color.Gray)
                }
            },
            containerColor = Color(0xFF1E1E28),
            titleContentColor = Color.White,
            textContentColor = Color.LightGray,
        )
    }
}

@Composable
private fun StorageLegendItem(
    color: Color,
    label: String,
    sizeStr: String,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                color = Color.Gray,
                fontSize = 11.sp,
            )
        }
        Text(
            text = sizeStr,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun DownloadedTrackRow(
    trackInfo: DownloadedItemInfo,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF191922)),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF22222E)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlideImage(
                model = trackInfo.song.coverUri,
                contentDescription = trackInfo.song.title,
                failure = placeholder(R.drawable.placeholder),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onPlay() },
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            ) {
                Text(
                    text = trackInfo.song.title,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = trackInfo.song.singer,
                    color = Color.Gray,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = trackInfo.format,
                        color = Color(0xFF1DB954),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color(0xFF1DB954).copy(alpha = 0.15f))
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = formatBytesHumanReadable(trackInfo.fileSizeBytes),
                        color = Color(0xFFA0A0B0),
                        fontSize = 11.sp,
                    )
                }
            }

            IconButton(
                onClick = onPlay,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Play",
                    tint = Color(0xFF1DB954),
                )
            }

            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = Color(0xFFE57373),
                )
            }
        }
    }
}
