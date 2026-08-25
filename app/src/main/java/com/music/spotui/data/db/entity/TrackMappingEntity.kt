package com.music.spotui.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "track_mappings")
data class TrackMappingEntity(
    @PrimaryKey
    @ColumnInfo(name = "spotify_track_id")
    val spotifyTrackId: String,

    @ColumnInfo(name = "resolved_source")
    val resolvedSource: String, // "DEEZER", "YTM_SONG", "YTM_VIDEO", "SAAVN", "LOSSLESS", "PIPED"

    @ColumnInfo(name = "source_id")
    val sourceId: String,       // YouTube VideoID, Deezer TrackID, or stream URL

    @ColumnInfo(name = "audio_format")
    val audioFormat: String,    // "FLAC", "OPUS_160", "AAC_128", "MP3_320"

    @ColumnInfo(name = "duration_delta_ms")
    val durationDeltaMs: Long = 0L,

    @ColumnInfo(name = "resolved_at_timestamp")
    val resolvedAtTimestamp: Long = System.currentTimeMillis()
)
