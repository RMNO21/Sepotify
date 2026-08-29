package com.music.spotui.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import com.music.spotui.worker.DownloadWorker
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.integration.compose.placeholder
import com.music.spotui.R
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.navigation.Routes
import com.music.spotui.ui.theme.AppPalette
import java.util.UUID

data class DownloadQueueTrackItem(
    val workId: UUID?,
    val songId: Int,
    val title: String,
    val singer: String,
    val album: String,
    val coverUri: String,
    val url: String,
    val playlistName: String,
    val progress: Int, // 0..100
    val state: WorkInfo.State,
    val statusLabel: String
)

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun DownloadQueueSection(
    navController: NavController,
    modifier: Modifier = Modifier,
    maxItemsToShow: Int = 3
) {
    val context = LocalContext.current
    val workManager = remember { WorkManager.getInstance(context) }
    val workInfos by workManager
        .getWorkInfosByTagFlow(DownloadWorker.TAG_DOWNLOAD_WORKER)
        .collectAsState(initial = emptyList())

    val batchDownloads by SongPlayer.batchDownloads.collectAsState()
    val activeBatches = remember(batchDownloads) { batchDownloads.values.filter { it.isDownloading } }

    // Aggregate active / enqueued tracks from WorkManager Data & SongPlayer live snapshot
    val liveSnapshot = SongPlayer.downloadingSnapshot()
    val queueItems = remember(workInfos, liveSnapshot) {
        val items = mutableListOf<DownloadQueueTrackItem>()
        val seenUrls = mutableSetOf<String>()

        // 1. WorkInfos from WorkManager
        workInfos.filter { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
            .forEach { info ->
                val progressVal = if (info.state == WorkInfo.State.RUNNING) {
                    info.progress.getInt(DownloadWorker.KEY_PROGRESS, 0)
                } else 0
                val title = info.progress.getString(DownloadWorker.KEY_TITLE) ?: ""
                val singer = info.progress.getString(DownloadWorker.KEY_SINGER) ?: ""
                val album = info.progress.getString(DownloadWorker.KEY_ALBUM) ?: ""
                val cover = info.progress.getString(DownloadWorker.KEY_COVER_URI) ?: ""
                val url = info.progress.getString(DownloadWorker.KEY_URL) ?: ""
                val pName = info.progress.getString(DownloadWorker.KEY_PLAYLIST_NAME) ?: ""
                val songId = info.progress.getInt(DownloadWorker.KEY_SONG_ID, 0)

                val displayTitle = if (title.isNotBlank()) title else "Track #${songId.takeIf { it != 0 } ?: ""}"
                val statusText = when (info.state) {
                    WorkInfo.State.RUNNING -> if (progressVal > 0) "Downloading $progressVal%" else "Connecting…"
                    WorkInfo.State.ENQUEUED -> "Queued in background"
                    else -> "Pending"
                }

                if (url.isNotBlank()) seenUrls.add(url)
                items.add(
                    DownloadQueueTrackItem(
                        workId = info.id,
                        songId = songId,
                        title = displayTitle,
                        singer = singer,
                        album = album,
                        coverUri = cover,
                        url = url,
                        playlistName = pName,
                        progress = progressVal,
                        state = info.state,
                        statusLabel = statusText
                    )
                )
            }

        // 2. Supplement with SongPlayer direct downloading tracks if not yet in WorkInfos
        liveSnapshot.forEach { (song, pct) ->
            if (!seenUrls.contains(song.url) && !items.any { it.title.equals(song.title, ignoreCase = true) }) {
                items.add(
                    DownloadQueueTrackItem(
                        workId = null,
                        songId = song.id,
                        title = song.title,
                        singer = song.singer,
                        album = song.album.orEmpty(),
                        coverUri = song.coverUri,
                        url = song.url,
                        playlistName = "",
                        progress = pct,
                        state = WorkInfo.State.RUNNING,
                        statusLabel = if (pct > 0) "Downloading $pct%" else "Connecting…"
                    )
                )
            }
        }
        items
    }

    val totalActiveCount = queueItems.size + activeBatches.size
    val isVisible = totalActiveCount > 0

    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier
    ) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF16161E)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .testTag("download_queue_card")
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                // Header Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(AppPalette.copy(alpha = 0.2f))
                        ) {
                            Icon(
                                imageVector = ImageVector.vectorResource(R.drawable.ic_download),
                                contentDescription = null,
                                tint = AppPalette,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "DOWNLOAD QUEUE",
                                color = AppPalette,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                            Text(
                                text = "$totalActiveCount item${if (totalActiveCount == 1) "" else "s"} in progress",
                                color = Color.LightGray,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    // View All in Downloads screen
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0xFF242430))
                            .clickable { navController.navigate(Routes.Downloads.route) }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                            .testTag("view_all_downloads_button")
                    ) {
                        Text(
                            text = "View",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "Open Downloads",
                            tint = Color.White,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }

                // Active Playlist Batches
                activeBatches.forEach { batch ->
                    Spacer(modifier = Modifier.height(10.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF1E1E28))
                            .padding(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = batch.playlistName,
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${batch.completedTracks}/${batch.totalTracks} tracks • ${batch.progressPercent}%",
                                    color = AppPalette,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Cancel batch download",
                                tint = Color.Gray,
                                modifier = Modifier
                                    .size(18.dp)
                                    .clickable { SongPlayer.cancelBatchDownload(batch.playlistId) }
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        val animatedBatchProgress by animateFloatAsState(
                            targetValue = (batch.progressPercent.coerceIn(0, 100)) / 100f,
                            label = "batch_progress"
                        )
                        LinearProgressIndicator(
                            progress = { animatedBatchProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .testTag("batch_download_progress_bar"),
                            color = AppPalette,
                            trackColor = Color(0xFF2E2E3A)
                        )
                    }
                }

                // Individual Track Progress in Queue
                val displayedTracks = queueItems.take(maxItemsToShow)
                displayedTracks.forEach { track ->
                    Spacer(modifier = Modifier.height(10.dp))
                    DownloadQueueTrackRow(
                        item = track,
                        onCancel = {
                            track.workId?.let { id -> workManager.cancelWorkById(id) }
                        }
                    )
                }

                if (queueItems.size > maxItemsToShow) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "+ ${queueItems.size - maxItemsToShow} more queued track${if (queueItems.size - maxItemsToShow == 1) "" else "s"}",
                        color = Color.Gray,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .clickable { navController.navigate(Routes.Downloads.route) }
                            .padding(vertical = 4.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun DownloadQueueTrackRow(
    item: DownloadQueueTrackItem,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val animatedProgress by animateFloatAsState(
        targetValue = (item.progress.coerceIn(0, 100)) / 100f,
        label = "track_download_progress"
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF1E1E28))
            .padding(8.dp)
            .testTag("download_queue_track_row")
    ) {
        // Thumbnail
        GlideImage(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(6.dp)),
            model = item.coverUri,
            failure = placeholder(R.drawable.placeholder),
            contentScale = ContentScale.Crop,
            contentDescription = "Track cover"
        )

        Spacer(modifier = Modifier.width(10.dp))

        // Info & Progress Bar
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = item.title,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = if (item.state == WorkInfo.State.RUNNING) "${item.progress}%" else "Queued",
                    color = if (item.state == WorkInfo.State.RUNNING) AppPalette else Color.Gray,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 6.dp)
                )
            }

            if (item.singer.isNotBlank() || item.playlistName.isNotBlank()) {
                Text(
                    text = buildString {
                        if (item.singer.isNotBlank()) append(item.singer)
                        if (item.playlistName.isNotBlank()) {
                            if (isNotEmpty()) append(" • ")
                            append(item.playlistName)
                        }
                    },
                    color = Color.LightGray,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(5.dp))

            // Visual Progress Bar reflecting WorkManager progress
            if (item.state == WorkInfo.State.RUNNING) {
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .testTag("track_download_progress_bar"),
                    color = AppPalette,
                    trackColor = Color(0xFF2E2E3A)
                )
            } else {
                // Enqueued - subtle indeterminate or inactive bar
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(1.5.dp))
                        .testTag("track_queued_progress_bar"),
                    color = Color(0xFF4A4A58),
                    trackColor = Color(0xFF2E2E3A)
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Cancel button
        Icon(
            imageVector = Icons.Default.Close,
            contentDescription = "Cancel download",
            tint = Color.Gray,
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .clickable { onCancel() }
                .padding(2.dp)
                .testTag("cancel_download_button")
        )
    }
}
