package com.music.spotui.player

import android.util.LruCache

data class CachedStream(
    val url: String,
    val source: String,
    val quality: String,
    val timestampMs: Long = System.currentTimeMillis(),
    val ttlMs: Long = 4 * 60 * 60 * 1000L // 4 hours TTL
) {
    val isExpired: Boolean get() = System.currentTimeMillis() - timestampMs > ttlMs
}

/**
 * Thread-safe LRU Stream URL Cache with 4-hour TTL.
 * Eliminates redundant stream extraction and eliminates stalls when replaying or prefetching tracks.
 */
object StreamUrlCache {

    private const val MAX_ENTRIES = 120
    private val lruCache = object : LruCache<String, CachedStream>(MAX_ENTRIES) {}

    fun get(trackId: String): String? {
        synchronized(lruCache) {
            val cached = lruCache.get(trackId) ?: return null
            if (cached.isExpired) {
                lruCache.remove(trackId)
                return null
            }
            return cached.url
        }
    }

    fun getEntry(trackId: String): CachedStream? {
        synchronized(lruCache) {
            val cached = lruCache.get(trackId) ?: return null
            if (cached.isExpired) {
                lruCache.remove(trackId)
                return null
            }
            return cached
        }
    }

    fun put(trackId: String, directUrl: String, source: String = "YouTube", quality: String = "") {
        if (trackId.isBlank() || directUrl.isBlank()) return
        synchronized(lruCache) {
            lruCache.put(trackId, CachedStream(url = directUrl, source = source, quality = quality))
        }
    }

    fun remove(trackId: String) {
        synchronized(lruCache) {
            lruCache.remove(trackId)
        }
    }

    fun clear() {
        synchronized(lruCache) {
            lruCache.evictAll()
        }
    }
}
