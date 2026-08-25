package com.music.spotui.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.music.spotui.data.db.entity.DownloadStatus
import com.music.spotui.data.db.entity.TrackEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {

    @Query("SELECT * FROM tracks WHERE track_id = :trackId LIMIT 1")
    suspend fun getTrack(trackId: String): TrackEntity?

    @Query("SELECT * FROM tracks WHERE song_id = :songId LIMIT 1")
    suspend fun getTrackBySongId(songId: Int): TrackEntity?

    @Query("SELECT * FROM tracks WHERE spotify_track_id = :spotifyTrackId LIMIT 1")
    suspend fun getTrackBySpotifyId(spotifyTrackId: String): TrackEntity?

    @Query("SELECT * FROM tracks WHERE track_id = :trackId LIMIT 1")
    fun observeTrack(trackId: String): Flow<TrackEntity?>

    @Query("SELECT * FROM tracks WHERE download_status = 'COMPLETED' ORDER BY download_time_ms DESC")
    fun getAllCompletedTracksFlow(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE download_status = 'COMPLETED' ORDER BY download_time_ms DESC")
    suspend fun getAllCompletedTracksSync(): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE download_status = :status")
    suspend fun getTracksByStatus(status: DownloadStatus): List<TrackEntity>

    @Query("SELECT * FROM tracks")
    suspend fun getAllTracks(): List<TrackEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrack(track: TrackEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTracks(tracks: List<TrackEntity>): List<Long>

    @Update
    suspend fun updateTrack(track: TrackEntity): Int

    @Query("""
        UPDATE tracks 
        SET download_status = :status, 
            local_path = :localPath, 
            file_size = :fileSize, 
            error_message = :errorMessage, 
            download_time_ms = :time
        WHERE track_id = :trackId
    """)
    suspend fun updateDownloadStatus(
        trackId: String,
        status: DownloadStatus,
        localPath: String?,
        fileSize: Long,
        errorMessage: String?,
        time: Long
    ): Int

    @Query("UPDATE tracks SET ref_count = ref_count + 1 WHERE track_id = :trackId")
    suspend fun incrementRefCount(trackId: String): Int

    @Query("UPDATE tracks SET ref_count = CASE WHEN ref_count > 0 THEN ref_count - 1 ELSE 0 END WHERE track_id = :trackId")
    suspend fun decrementRefCount(trackId: String): Int

    @Query("UPDATE tracks SET last_played_time_ms = :timestamp WHERE track_id = :trackId")
    suspend fun updateLastPlayed(trackId: String, timestamp: Long): Int

    @Query("DELETE FROM tracks WHERE track_id = :trackId")
    suspend fun deleteTrack(trackId: String): Int

    @Query("SELECT COALESCE(SUM(file_size), 0) FROM tracks WHERE download_status = 'COMPLETED'")
    suspend fun getTotalStorageUsed(): Long

    @Query("SELECT COALESCE(SUM(file_size), 0) FROM tracks WHERE download_status = 'COMPLETED'")
    fun observeTotalStorageUsed(): Flow<Long>

    @Query("""
        SELECT tracks.* FROM tracks 
        INNER JOIN playlist_tracks ON tracks.track_id = playlist_tracks.track_id 
        WHERE playlist_tracks.playlist_id = :playlistId 
        ORDER BY playlist_tracks.sort_order ASC
    """)
    fun getTracksForPlaylist(playlistId: String): Flow<List<TrackEntity>>

    @Query("""
        SELECT tracks.* FROM tracks 
        INNER JOIN playlist_tracks ON tracks.track_id = playlist_tracks.track_id 
        WHERE playlist_tracks.playlist_id = :playlistId 
        ORDER BY playlist_tracks.sort_order ASC
    """)
    suspend fun getTracksForPlaylistSync(playlistId: String): List<TrackEntity>

    @Query("""
        SELECT tracks.* FROM tracks 
        INNER JOIN playlist_tracks ON tracks.track_id = playlist_tracks.track_id 
        WHERE playlist_tracks.playlist_id = :playlistId 
          AND tracks.download_status = 'COMPLETED'
        ORDER BY playlist_tracks.sort_order ASC
    """)
    suspend fun getCompletedTracksForPlaylistSync(playlistId: String): List<TrackEntity>

    /**
     * Offline shuffle engine query: Randomizes solely among completed tracks for the playlist.
     */
    @Query("""
        SELECT tracks.* FROM tracks 
        INNER JOIN playlist_tracks ON tracks.track_id = playlist_tracks.track_id 
        WHERE playlist_tracks.playlist_id = :playlistId 
          AND tracks.download_status = 'COMPLETED'
        ORDER BY RANDOM()
    """)
    suspend fun getCompletedTracksForPlaylistRandom(playlistId: String): List<TrackEntity>

    /**
     * All completed offline tracks in random order for global offline shuffle.
     */
    @Query("SELECT * FROM tracks WHERE download_status = 'COMPLETED' ORDER BY RANDOM()")
    suspend fun getAllCompletedTracksRandom(): List<TrackEntity>

    /**
     * LRU query: Tracks ordered by oldest played, then oldest downloaded.
     */
    @Query("SELECT * FROM tracks WHERE download_status = 'COMPLETED' ORDER BY last_played_time_ms ASC, download_time_ms ASC")
    suspend fun getLRUCompletedTracks(): List<TrackEntity>
}
