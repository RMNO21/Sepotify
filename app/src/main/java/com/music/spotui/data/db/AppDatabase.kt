package com.music.spotui.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.music.spotui.data.db.dao.PlaylistDao
import com.music.spotui.data.db.dao.PlaylistTrackDao
import com.music.spotui.data.db.dao.RecentSearchQueryDao
import com.music.spotui.data.db.dao.TrackDao
import com.music.spotui.data.db.dao.TrackMappingDao
import com.music.spotui.data.db.entity.PlaylistEntity
import com.music.spotui.data.db.entity.PlaylistTrackCrossRef
import com.music.spotui.data.db.entity.RecentSearchQueryEntity
import com.music.spotui.data.db.entity.TrackEntity
import com.music.spotui.data.db.entity.TrackMappingEntity

@Database(
    entities = [
        TrackEntity::class,
        PlaylistEntity::class,
        PlaylistTrackCrossRef::class,
        TrackMappingEntity::class,
        RecentSearchQueryEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun trackDao(): TrackDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun playlistTrackDao(): PlaylistTrackDao
    abstract fun trackMappingDao(): TrackMappingDao
    abstract fun recentSearchQueryDao(): RecentSearchQueryDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "spotui_offline_music.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
