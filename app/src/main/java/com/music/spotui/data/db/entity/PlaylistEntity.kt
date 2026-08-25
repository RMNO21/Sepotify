package com.music.spotui.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey
    @ColumnInfo(name = "playlist_id")
    val playlistId: String,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "cover_uri")
    val coverUri: String = "",

    @ColumnInfo(name = "is_download_enabled")
    val isDownloadEnabled: Boolean = false,

    @ColumnInfo(name = "last_sync_timestamp")
    val lastSyncTimestamp: Long = 0L,

    @ColumnInfo(name = "snapshot_id")
    val snapshotId: String = ""
)
