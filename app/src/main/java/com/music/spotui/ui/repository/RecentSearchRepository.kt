package com.music.spotui.ui.repository

import com.music.spotui.data.db.dao.RecentSearchQueryDao
import com.music.spotui.data.db.entity.RecentSearchQueryEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecentSearchRepository @Inject constructor(
    private val recentSearchQueryDao: RecentSearchQueryDao
) {
    val recentQueries: Flow<List<RecentSearchQueryEntity>> = recentSearchQueryDao.getRecentQueries()

    suspend fun saveQuery(query: String) {
        val trimmed = query.trim()
        if (trimmed.isNotBlank()) {
            recentSearchQueryDao.insertQuery(
                RecentSearchQueryEntity(
                    query = trimmed,
                    timestamp = System.currentTimeMillis()
                )
            )
        }
    }

    suspend fun deleteQuery(query: String) {
        val trimmed = query.trim()
        if (trimmed.isNotBlank()) {
            recentSearchQueryDao.deleteQuery(trimmed)
        }
    }

    suspend fun clearAll() {
        recentSearchQueryDao.clearAllQueries()
    }
}
