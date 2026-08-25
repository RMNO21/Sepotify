package com.music.spotui.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.music.spotui.data.db.entity.TrackMappingEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackMappingDao {

    @Query("SELECT * FROM track_mappings WHERE spotify_track_id = :spotifyTrackId LIMIT 1")
    suspend fun getMapping(spotifyTrackId: String): TrackMappingEntity?

    @Query("SELECT * FROM track_mappings WHERE spotify_track_id = :spotifyTrackId LIMIT 1")
    fun observeMapping(spotifyTrackId: String): Flow<TrackMappingEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMapping(mapping: TrackMappingEntity): Long

    @Query("DELETE FROM track_mappings WHERE spotify_track_id = :spotifyTrackId")
    suspend fun deleteMapping(spotifyTrackId: String): Int

    @Query("DELETE FROM track_mappings")
    suspend fun clearAll(): Int
}
