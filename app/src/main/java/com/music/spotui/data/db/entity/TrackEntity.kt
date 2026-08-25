package com.music.spotui.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.music.spotui.data.entity.SongsModel

@Entity(
    tableName = "tracks",
    indices = [
        Index(value = ["download_status"]),
        Index(value = ["song_id"]),
        Index(value = ["spotify_track_id"])
    ]
)
data class TrackEntity(
    @PrimaryKey
    @ColumnInfo(name = "track_id")
    val trackId: String,

    @ColumnInfo(name = "song_id")
    val songId: Int = 0,

    @ColumnInfo(name = "spotify_track_id")
    val spotifyTrackId: String = "",

    @ColumnInfo(name = "title")
    val title: String = "",

    @ColumnInfo(name = "singer")
    val singer: String = "",

    @ColumnInfo(name = "album")
    val album: String = "",

    @ColumnInfo(name = "cover_uri")
    val coverUri: String = "",

    @ColumnInfo(name = "stream_url")
    val streamUrl: String = "",

    @ColumnInfo(name = "duration_ms")
    val durationMs: Int = 0,

    @ColumnInfo(name = "explicit")
    val explicit: Boolean = false,

    @ColumnInfo(name = "local_path")
    val localPath: String? = null,

    @ColumnInfo(name = "download_status")
    val downloadStatus: DownloadStatus = DownloadStatus.NONE,

    @ColumnInfo(name = "file_size")
    val fileSize: Long = 0L,

    @ColumnInfo(name = "ref_count")
    val refCount: Int = 0,

    @ColumnInfo(name = "download_time_ms")
    val downloadTimeMs: Long = 0L,

    @ColumnInfo(name = "last_played_time_ms")
    val lastPlayedTimeMs: Long = 0L,

    @ColumnInfo(name = "error_message")
    val errorMessage: String? = null
) {
    fun toSongModel(): SongsModel {
        return SongsModel(
            id = songId.takeIf { it != 0 } ?: (trackId.hashCode() and 0x7fffffff),
            title = title,
            album = album,
            singer = singer,
            coverUri = coverUri,
            url = streamUrl.ifBlank { com.music.spotui.di.SongPlayer.buildSpotifyPlayQuery(spotifyTrackId.ifBlank { trackId }, title, singer) },
            spotifyTrackId = spotifyTrackId.ifBlank { if (trackId.matches(Regex("[A-Za-z0-9]{22}"))) trackId else "" },
            explicit = explicit,
            durationMs = durationMs
        )
    }

    companion object {
        fun fromSongModel(song: SongsModel, status: DownloadStatus = DownloadStatus.NONE, refCount: Int = 1): TrackEntity {
            val resolvedKey = when {
                song.spotifyTrackId.isNotBlank() -> song.spotifyTrackId
                song.id != 0 -> "song_${song.id}"
                else -> "${song.title}_${song.singer}".replace(Regex("[^A-Za-z0-9_]"), "_").take(48)
            }
            return TrackEntity(
                trackId = resolvedKey,
                songId = song.id,
                spotifyTrackId = song.spotifyTrackId,
                title = song.title,
                singer = song.singer,
                album = song.album,
                coverUri = song.coverUri,
                streamUrl = song.url,
                durationMs = song.durationMs,
                explicit = song.explicit,
                downloadStatus = status,
                refCount = refCount
            )
        }
    }
}
