package com.music.spotui.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.RemoteViews
import com.bumptech.glide.Glide
import com.music.spotui.MainActivity
import com.music.spotui.R
import com.music.spotui.data.preferences.addLikedSongId
import com.music.spotui.data.preferences.getSavedAlbums
import com.music.spotui.data.preferences.getSavedPlaylists
import com.music.spotui.data.preferences.isSongLiked
import com.music.spotui.data.preferences.loadLastPlaybackSession
import com.music.spotui.data.preferences.removeLikedSongId
import com.music.spotui.di.CurrentSongState
import com.music.spotui.di.SongPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SpotUIWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        updateAllWidgets(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        runCatching {
            when (intent.action) {
                ACTION_PLAY_PAUSE -> {
                    val state = CurrentSongState.instance
                    val isPlaying = state?.playingState?.value ?: false
                    if (isPlaying) {
                        SongPlayer.pause()
                        state?.updatePlayingState(false)
                    } else {
                        if (state != null && state.songId.value != 0) {
                            SongPlayer.play()
                            state.updatePlayingState(true)
                        } else {
                            playLatestItem(context)
                        }
                    }
                    updateAllWidgets(context)
                }
                ACTION_NEXT -> {
                    SongPlayer.skipToNextTrack(context)
                    updateAllWidgets(context)
                }
                ACTION_PREV -> {
                    SongPlayer.skipToPreviousTrack(context)
                    updateAllWidgets(context)
                }
                ACTION_LIKE -> {
                    val state = CurrentSongState.instance
                    if (state != null) {
                        val songId = state.songId.value
                        if (songId != 0) {
                            val currentlyLiked = isSongLiked(context, songId.toString())
                            if (currentlyLiked) {
                                removeLikedSongId(context, songId.toString())
                                state.updateLikeState(false)
                            } else {
                                addLikedSongId(context, songId.toString())
                                state.updateLikeState(true)
                            }
                        }
                    }
                    updateAllWidgets(context)
                }
                ACTION_SHUFFLE -> {
                    val state = CurrentSongState.instance
                    if (state != null) {
                        state.updateShuffleState(!state.shuffle.value)
                    }
                    updateAllWidgets(context)
                }
                ACTION_REPEAT -> {
                    val state = CurrentSongState.instance
                    if (state != null) {
                        state.updateRepeatState(!state.repeat.value)
                    }
                    updateAllWidgets(context)
                }
                ACTION_PLAY_LATEST -> {
                    playLatestItem(context)
                    updateAllWidgets(context)
                }
                ACTION_UPDATE_WIDGET -> {
                    updateAllWidgets(context)
                }
            }
        }
    }

    private fun playLatestItem(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val session = loadLastPlaybackSession(context)
                if (session != null && session.queue.isNotEmpty()) {
                    val track = session.currentSong
                    val state = CurrentSongState.instance
                    if (state != null) {
                        state.updateQueue(session.queue)
                        state.updateSongState(track.coverUri, track.title, track.singer, true, track.id, session.songIndex, track.album)
                    }
                    withContext(Dispatchers.Main) {
                        SongPlayer.playSong(track.url, context)
                    }
                    updateAllWidgets(context)
                    return@launch
                }

                // Fallback: Latest saved playlist or album
                val playlists = getSavedPlaylists(context)
                if (playlists.isNotEmpty()) {
                    val firstPl = playlists.first()
                    val songs = com.music.spotui.data.preferences.getCustomPlaylistSongs(context, firstPl.id)
                    if (songs.isNotEmpty()) {
                        val firstSong = songs.first()
                        val state = CurrentSongState.instance
                        if (state != null) {
                            state.updateQueue(songs)
                            state.updateSongState(firstSong.coverUri, firstSong.title, firstSong.singer, true, firstSong.id, 0, firstSong.album)
                        }
                        withContext(Dispatchers.Main) {
                            SongPlayer.playSong(firstSong.url, context)
                        }
                        updateAllWidgets(context)
                        return@launch
                    }
                }

                val albums = getSavedAlbums(context)
                if (albums.isNotEmpty()) {
                    val firstAlbum = albums.first()
                    com.music.spotui.data.api.Api(context).getAlbumSongs(firstAlbum.name, firstAlbum.artists).collect { resp ->
                        if (resp is com.music.spotui.data.api.Response.Success && resp.data.isNotEmpty()) {
                            val songs = resp.data
                            val firstSong = songs.first()
                            val state = CurrentSongState.instance
                            if (state != null) {
                                state.updateQueue(songs)
                                state.updateSongState(firstSong.coverUri, firstSong.title, firstSong.singer, true, firstSong.id, 0, firstSong.album)
                            }
                            withContext(Dispatchers.Main) {
                                SongPlayer.playSong(firstSong.url, context)
                            }
                            updateAllWidgets(context)
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION_PLAY_PAUSE = "com.music.spotui.WIDGET_PLAY_PAUSE"
        const val ACTION_NEXT = "com.music.spotui.WIDGET_NEXT"
        const val ACTION_PREV = "com.music.spotui.WIDGET_PREV"
        const val ACTION_LIKE = "com.music.spotui.WIDGET_LIKE"
        const val ACTION_SHUFFLE = "com.music.spotui.WIDGET_SHUFFLE"
        const val ACTION_REPEAT = "com.music.spotui.WIDGET_REPEAT"
        const val ACTION_PLAY_LATEST = "com.music.spotui.WIDGET_PLAY_LATEST"
        const val ACTION_UPDATE_WIDGET = "com.music.spotui.WIDGET_UPDATE"

        fun updateAllWidgets(context: Context) {
            runCatching {
                val appWidgetManager = AppWidgetManager.getInstance(context)
                val componentName = ComponentName(context, SpotUIWidgetProvider::class.java)
                val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
                if (appWidgetIds.isEmpty()) return

                val state = CurrentSongState.instance
                val title = state?.title?.value.orEmpty()
                val artist = state?.singer?.value.orEmpty()
                val coverUri = state?.coverUri?.value.orEmpty()
                val isPlaying = state?.playingState?.value ?: false
                val songId = state?.songId?.value ?: 0
                val isLiked = if (songId != 0) isSongLiked(context, songId.toString()) else (state?.likeState?.value ?: false)
                val isShuffle = state?.shuffle?.value ?: false
                val isRepeat = state?.repeat?.value ?: false

                CoroutineScope(Dispatchers.IO).launch {
                    runCatching {
                        val views = RemoteViews(context.packageName, R.layout.widget_playback)

                        val mainIntent = Intent(context, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        }
                        val mainPending = PendingIntent.getActivity(
                            context, 0, mainIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                        views.setOnClickPendingIntent(R.id.widget_root, mainPending)

                        views.setOnClickPendingIntent(
                            R.id.widget_play_pause,
                            PendingIntent.getBroadcast(
                                context, 1, Intent(context, SpotUIWidgetProvider::class.java).apply { action = ACTION_PLAY_PAUSE },
                                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                            )
                        )

                        views.setOnClickPendingIntent(
                            R.id.widget_next,
                            PendingIntent.getBroadcast(
                                context, 2, Intent(context, SpotUIWidgetProvider::class.java).apply { action = ACTION_NEXT },
                                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                            )
                        )

                        views.setOnClickPendingIntent(
                            R.id.widget_prev,
                            PendingIntent.getBroadcast(
                                context, 3, Intent(context, SpotUIWidgetProvider::class.java).apply { action = ACTION_PREV },
                                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                            )
                        )

                        views.setOnClickPendingIntent(
                            R.id.widget_like,
                            PendingIntent.getBroadcast(
                                context, 4, Intent(context, SpotUIWidgetProvider::class.java).apply { action = ACTION_LIKE },
                                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                            )
                        )

                        views.setOnClickPendingIntent(
                            R.id.widget_shuffle,
                            PendingIntent.getBroadcast(
                                context, 5, Intent(context, SpotUIWidgetProvider::class.java).apply { action = ACTION_SHUFFLE },
                                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                            )
                        )

                        views.setOnClickPendingIntent(
                            R.id.widget_repeat,
                            PendingIntent.getBroadcast(
                                context, 6, Intent(context, SpotUIWidgetProvider::class.java).apply { action = ACTION_REPEAT },
                                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                            )
                        )

                        // Render Like, Shuffle, Repeat button states with color filters
                        if (isShuffle) {
                            views.setInt(R.id.widget_shuffle, "setColorFilter", 0xFF1ED760.toInt())
                        } else {
                            views.setInt(R.id.widget_shuffle, "setColorFilter", 0x88FFFFFF.toInt())
                        }

                        if (isRepeat) {
                            views.setInt(R.id.widget_repeat, "setColorFilter", 0xFF1ED760.toInt())
                            views.setImageViewResource(R.id.widget_repeat, R.drawable.ic_repeat_one)
                        } else {
                            views.setInt(R.id.widget_repeat, "setColorFilter", 0x88FFFFFF.toInt())
                            views.setImageViewResource(R.id.widget_repeat, R.drawable.ic_repeat)
                        }

                        if (isLiked) {
                            views.setInt(R.id.widget_like, "setColorFilter", 0xFF1ED760.toInt())
                            views.setImageViewResource(R.id.widget_like, R.drawable.ic_heart_filled)
                        } else {
                            views.setInt(R.id.widget_like, "setColorFilter", 0x88FFFFFF.toInt())
                            views.setImageViewResource(R.id.widget_like, R.drawable.ic_heart_outline)
                        }

                        views.setInt(R.id.widget_prev, "setColorFilter", 0xFFFFFFFF.toInt())
                        views.setInt(R.id.widget_next, "setColorFilter", 0xFFFFFFFF.toInt())

                        // Playback Progress Bar & Audio Engine Badge (Safely queried on Main thread)
                        val (dur, pos) = runCatching {
                            withContext(Dispatchers.Main) {
                                val d = SongPlayer.getDuration()
                                val p = SongPlayer.getCurrentPosition()
                                d to p
                            }
                        }.getOrDefault(0L to 0L)
                        val progress = if (dur > 0L) ((pos * 100L) / dur).toInt().coerceIn(0, 100) else 0
                        views.setProgressBar(R.id.widget_progress, 100, progress, false)

                        val engineSource = runCatching { SongPlayer.currentSource }.getOrDefault("SPOTIFY").ifBlank { "SPOTIFY" }
                        val engineQuality = runCatching { SongPlayer.currentQuality }.getOrDefault("")
                        val badgeLabel = if (engineQuality.isNotBlank()) "$engineSource $engineQuality".uppercase() else engineSource.uppercase()
                        views.setTextViewText(R.id.widget_badge, badgeLabel)

                        if (title.isNotBlank()) {
                            views.setTextViewText(R.id.widget_title, title)
                            views.setTextViewText(R.id.widget_artist, artist.ifBlank { "SpotUI" })
                            views.setImageViewResource(R.id.widget_play_pause, if (isPlaying) R.drawable.ic_playing else R.drawable.ic_paused)

                            if (coverUri.isNotBlank()) {
                                val bitmap: Bitmap? = runCatching {
                                    Glide.with(context.applicationContext)
                                        .asBitmap()
                                        .load(coverUri)
                                        .transform(com.bumptech.glide.load.resource.bitmap.RoundedCorners(20))
                                        .submit(160, 160)
                                        .get()
                                }.getOrNull()
                                if (bitmap != null) {
                                    views.setImageViewBitmap(R.id.widget_cover, bitmap)
                                } else {
                                    views.setImageViewResource(R.id.widget_cover, R.drawable.placeholder)
                                }
                            } else {
                                views.setImageViewResource(R.id.widget_cover, R.drawable.placeholder)
                            }
                        } else {
                            // Idle state: show latest session or playlist
                            val session = loadLastPlaybackSession(context)
                            if (session != null) {
                                views.setTextViewText(R.id.widget_title, session.currentSong.title)
                                views.setTextViewText(R.id.widget_artist, "Tap to play latest • ${session.contextName.ifBlank { "SpotUI" }}")
                                views.setImageViewResource(R.id.widget_play_pause, R.drawable.ic_paused)
                                views.setTextViewText(R.id.widget_badge, "READY")
                                if (session.currentSong.coverUri.isNotBlank()) {
                                    val bitmap: Bitmap? = runCatching {
                                        Glide.with(context.applicationContext)
                                            .asBitmap()
                                            .load(session.currentSong.coverUri)
                                            .transform(com.bumptech.glide.load.resource.bitmap.RoundedCorners(20))
                                            .submit(160, 160)
                                            .get()
                                    }.getOrNull()
                                    if (bitmap != null) {
                                        views.setImageViewBitmap(R.id.widget_cover, bitmap)
                                    } else {
                                        views.setImageViewResource(R.id.widget_cover, R.drawable.placeholder)
                                    }
                                } else {
                                    views.setImageViewResource(R.id.widget_cover, R.drawable.placeholder)
                                }
                            } else {
                                views.setTextViewText(R.id.widget_title, "SpotUI")
                                views.setTextViewText(R.id.widget_artist, "Tap to play music")
                                views.setTextViewText(R.id.widget_badge, "IDLE")
                                views.setImageViewResource(R.id.widget_play_pause, R.drawable.ic_paused)
                                views.setImageViewResource(R.id.widget_cover, R.drawable.placeholder)
                            }
                        }

                        withContext(Dispatchers.Main) {
                            for (appWidgetId in appWidgetIds) {
                                appWidgetManager.updateAppWidget(appWidgetId, views)
                            }
                        }
                    }
                }
            }
        }
    }
}
