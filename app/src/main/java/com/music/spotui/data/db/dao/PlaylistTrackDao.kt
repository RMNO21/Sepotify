package com.music.spotui.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.music.spotui.data.db.entity.PlaylistTrackCrossRef

@Dao
interface PlaylistTrackDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCrossRef(crossRef: PlaylistTrackCrossRef): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCrossRefs(crossRefs: List<PlaylistTrackCrossRef>): List<Long>

    @Query("DELETE FROM playlist_tracks WHERE playlist_id = :playlistId AND track_id = :trackId")
    suspend fun deleteCrossRef(playlistId: String, trackId: String): Int

    @Query("DELETE FROM playlist_tracks WHERE playlist_id = :playlistId")
    suspend fun deleteCrossRefsForPlaylist(playlistId: String): Int

    @Query("SELECT track_id FROM playlist_tracks WHERE playlist_id = :playlistId ORDER BY sort_order ASC")
    suspend fun getTrackIdsForPlaylist(playlistId: String): List<String>

    @Query("SELECT playlist_id FROM playlist_tracks WHERE track_id = :trackId LIMIT 1")
    suspend fun getPlaylistIdForTrack(trackId: String): String?

    @Query("SELECT COUNT(*) FROM playlist_tracks WHERE track_id = :trackId")
    suspend fun countPlaylistsReferencingTrack(trackId: String): Int
}
