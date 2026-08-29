package com.music.spotui.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.music.spotui.data.db.entity.RecentSearchQueryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RecentSearchQueryDao {

    @Query("SELECT * FROM recent_search_queries ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentQueries(limit: Int = 20): Flow<List<RecentSearchQueryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertQuery(entity: RecentSearchQueryEntity)

    @Query("DELETE FROM recent_search_queries WHERE query = :query")
    suspend fun deleteQuery(query: String)

    @Query("DELETE FROM recent_search_queries")
    suspend fun clearAllQueries()
}
