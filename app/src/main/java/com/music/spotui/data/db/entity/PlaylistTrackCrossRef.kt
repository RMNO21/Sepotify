package com.music.spotui.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "playlist_tracks",
    primaryKeys = ["playlist_id", "track_id"],
    indices = [
        Index(value = ["playlist_id"]),
        Index(value = ["track_id"])
    ]
)
data class PlaylistTrackCrossRef(
    @ColumnInfo(name = "playlist_id")
    val playlistId: String,

    @ColumnInfo(name = "track_id")
    val trackId: String,

    @ColumnInfo(name = "sort_order")
    val sortOrder: Int = 0
)
