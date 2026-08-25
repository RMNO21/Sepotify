package com.music.spotui.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.music.spotui.data.db.entity.PlaylistEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {

    @Query("SELECT * FROM playlists WHERE playlist_id = :playlistId LIMIT 1")
    suspend fun getPlaylist(playlistId: String): PlaylistEntity?

    @Query("SELECT * FROM playlists WHERE playlist_id = :playlistId LIMIT 1")
    fun observePlaylist(playlistId: String): Flow<PlaylistEntity?>

    @Query("SELECT * FROM playlists")
    fun getAllPlaylists(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE is_download_enabled = 1")
    fun getDownloadedPlaylists(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE is_download_enabled = 1")
    suspend fun getDownloadedPlaylistsSync(): List<PlaylistEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Query("UPDATE playlists SET is_download_enabled = :enabled WHERE playlist_id = :playlistId")
    suspend fun setDownloadEnabled(playlistId: String, enabled: Boolean): Int

    @Query("UPDATE playlists SET last_sync_timestamp = :timestamp WHERE playlist_id = :playlistId")
    suspend fun updateSyncTimestamp(playlistId: String, timestamp: Long): Int

    @Query("UPDATE playlists SET snapshot_id = :snapshotId, last_sync_timestamp = :timestamp WHERE playlist_id = :playlistId")
    suspend fun updatePlaylistSnapshot(playlistId: String, snapshotId: String, timestamp: Long): Int

    @Query("DELETE FROM playlists WHERE playlist_id = :playlistId")
    suspend fun deletePlaylist(playlistId: String): Int
}
