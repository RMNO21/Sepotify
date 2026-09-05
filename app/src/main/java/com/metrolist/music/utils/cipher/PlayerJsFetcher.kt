package com.metrolist.music.utils.cipher

import com.metrolist.innertube.YouTube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File

/**
 * Fetches and caches YouTube's player.js for cipher operations.
 *
 * The player.js contains the signature deobfuscation and n-transform functions
 * that are required to access stream URLs on web clients.
 */
object PlayerJsFetcher {
    private const val TAG = "Metrolist_CipherFetcher"
    private const val IFRAME_API_URL = "https://www.youtube.com/iframe_api"
    private const val EMBED_URL = "https://www.youtube.com/embed/dQw4w9WgXcQ"
    private const val MUSIC_URL = "https://music.youtube.com"
    private const val FALLBACK_PLAYER_HASH = "74edf1a3"
    private val PLAYER_JS_URL_TEMPLATES = listOf(
        "https://www.youtube.com/s/player/%s/player_ias.vflset/en_GB/base.js",
        "https://www.youtube.com/s/player/%s/player_ias.vflset/en_US/base.js",
        "https://www.youtube.com/s/player/%s/player_ias.vflset/base.js"
    )
    private const val CACHE_TTL_MS = 6 * 60 * 60 * 1000L // 6 hours

    private val httpClient = OkHttpClient.Builder()
        .proxy(YouTube.proxy)
        .build()

    // Regexes to extract player hash from YouTube web pages
    private val PLAYER_HASH_PATTERNS = listOf(
        Regex("""(?:/|\\/)s(?:/|\\/)player(?:/|\\/)([a-zA-Z0-9_-]{6,16})(?:/|\\/)"""),
        Regex("""player_ias\.vflset/[^/]+/([a-zA-Z0-9_-]{6,16})/"""),
        Regex("""jsUrl['":\s]+[^"']*?/player/([a-zA-Z0-9_-]{6,16})/"""),
        Regex("""/s/player/([a-zA-Z0-9_-]{6,16})/player_ias\.vflset"""),
        Regex("""["']([^"']*?/s/player/([a-zA-Z0-9_-]{6,16})/[^"']*)["']""")
    )

    private fun getCacheDir(): File = File(CipherDeobfuscator.appContext.filesDir, "cipher_cache")

    private fun getCacheFile(hash: String): File = File(getCacheDir(), "player_$hash.js")

    private fun getHashFile(): File = File(getCacheDir(), "current_hash.txt")

    /**
     * Get player.js content and hash.
     *
     * Uses cached version if available and not expired, otherwise fetches fresh.
     * Returns Pair(playerJs, hash) or null if failed.
     */
    suspend fun getPlayerJs(forceRefresh: Boolean = false): Pair<String, String>? = withContext(Dispatchers.IO) {
        Timber.tag(TAG).d("=== GET PLAYER.JS ===")
        Timber.tag(TAG).d("forceRefresh: $forceRefresh")

        try {
            val cacheDir = getCacheDir()
            if (!cacheDir.exists()) {
                Timber.tag(TAG).d("Creating cache directory: ${cacheDir.absolutePath}")
                cacheDir.mkdirs()
            }

            // Check cache first (unless forced refresh)
            if (!forceRefresh) {
                val cached = readFromCache()
                if (cached != null) {
                    Timber.tag(TAG).d("=== CACHE HIT ===")
                    Timber.tag(TAG).d("Using cached player JS (hash=${cached.second}, length=${cached.first.length})")
                    return@withContext cached
                }
                Timber.tag(TAG).d("Cache miss, will fetch fresh")
            }

            // Fetch player hash from web sources
            Timber.tag(TAG).d("Fetching player hash...")
            var hash = fetchPlayerHash()
            if (hash == null) {
                Timber.tag(TAG).w("Failed to extract player hash from web endpoints; trying fallback hash $FALLBACK_PLAYER_HASH")
                hash = FALLBACK_PLAYER_HASH
            }
            Timber.tag(TAG).d("Using player hash: $hash")

            // Download player JS
            Timber.tag(TAG).d("Downloading player JS for hash: $hash...")
            var playerJs = downloadPlayerJs(hash)
            if (playerJs == null && hash != FALLBACK_PLAYER_HASH) {
                Timber.tag(TAG).w("Failed to download for hash $hash; retrying with fallback $FALLBACK_PLAYER_HASH")
                hash = FALLBACK_PLAYER_HASH
                playerJs = downloadPlayerJs(hash)
            }

            if (playerJs == null) {
                Timber.tag(TAG).e("Failed to download player JS for hash=$hash")
                // Check if any expired cache exists as last resort
                val lastResort = readAnyCache()
                if (lastResort != null) {
                    Timber.tag(TAG).w("Using expired cached player JS as emergency fallback")
                    return@withContext lastResort
                }
                return@withContext null
            }

            Timber.tag(TAG).d("=== PLAYER.JS DOWNLOADED ===")
            Timber.tag(TAG).d("hash: $hash, length: ${playerJs.length} chars")

            // Cache the result
            writeToCache(hash, playerJs)

            Pair(playerJs, hash)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "getPlayerJs exception: ${e.message}")
            readAnyCache()
        }
    }

    /**
     * Invalidate the player.js cache.
     * Call this when cipher operations fail to force a fresh fetch.
     */
    fun invalidateCache() {
        Timber.tag(TAG).d("Invalidating cache...")
        try {
            val cacheDir = getCacheDir()
            if (cacheDir.exists()) {
                val files = cacheDir.listFiles()
                Timber.tag(TAG).d("Deleting ${files?.size ?: 0} cache files")
                files?.forEach {
                    Timber.tag(TAG).v("Deleting: ${it.name}")
                    it.delete()
                }
            }
            Timber.tag(TAG).d("Cache invalidated successfully")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to invalidate cache: ${e.message}")
        }
    }

    private fun readFromCache(): Pair<String, String>? {
        Timber.tag(TAG).d("Checking cache...")
        try {
            val hashFile = getHashFile()
            if (!hashFile.exists()) {
                Timber.tag(TAG).d("Hash file does not exist")
                return null
            }

            val hashData = hashFile.readText().split("\n")
            if (hashData.size < 2) {
                Timber.tag(TAG).d("Hash file malformed (expected 2 lines, got ${hashData.size})")
                return null
            }

            val hash = hashData[0].trim()
            val timestamp = hashData[1].trim().toLongOrNull()
            if (timestamp == null) {
                Timber.tag(TAG).d("Could not parse timestamp from hash file")
                return null
            }

            val ageMs = System.currentTimeMillis() - timestamp
            val ageHours = ageMs / (1000 * 60 * 60)
            Timber.tag(TAG).d("Cache age: ${ageHours}h (TTL: ${CACHE_TTL_MS / (1000 * 60 * 60)}h)")

            // Check TTL
            if (ageMs > CACHE_TTL_MS) {
                Timber.tag(TAG).d("Cache expired (hash=$hash, age=${ageHours}h)")
                return null
            }

            val cacheFile = getCacheFile(hash)
            if (!cacheFile.exists()) {
                Timber.tag(TAG).d("Cache file does not exist for hash: $hash")
                return null
            }

            val playerJs = cacheFile.readText()
            if (playerJs.isEmpty()) {
                Timber.tag(TAG).d("Cache file is empty")
                return null
            }

            Timber.tag(TAG).d("Cache valid: hash=$hash, length=${playerJs.length}, age=${ageHours}h")
            return Pair(playerJs, hash)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error reading cache: ${e.message}")
            return null
        }
    }

    private fun readAnyCache(): Pair<String, String>? {
        return try {
            val cacheDir = getCacheDir()
            val files = cacheDir.listFiles()?.filter { it.name.startsWith("player_") && it.name.endsWith(".js") }
            val first = files?.firstOrNull() ?: return null
            val hash = first.name.removePrefix("player_").removeSuffix(".js")
            val content = first.readText()
            if (content.isNotEmpty()) Pair(content, hash) else null
        } catch (e: Exception) {
            null
        }
    }

    private fun writeToCache(hash: String, playerJs: String) {
        Timber.tag(TAG).d("Writing to cache: hash=$hash, length=${playerJs.length}")
        try {
            val cacheDir = getCacheDir()

            // Clean old cache files
            val oldFiles = cacheDir.listFiles()?.filter { it.name.startsWith("player_") }
            oldFiles?.forEach { it.delete() }

            getCacheFile(hash).writeText(playerJs)
            getHashFile().writeText("$hash\n${System.currentTimeMillis()}")

            Timber.tag(TAG).d("Cache written successfully")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error writing cache: ${e.message}")
        }
    }

    private fun fetchPlayerHash(): String? {
        val urls = listOf(IFRAME_API_URL, EMBED_URL, MUSIC_URL)
        for (url in urls) {
            try {
                Timber.tag(TAG).d("Fetching player hash from: $url")
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                    .build()

                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    response.close()
                    continue
                }

                val body = response.body?.string() ?: continue
                for (pattern in PLAYER_HASH_PATTERNS) {
                    val match = pattern.find(body)
                    if (match != null) {
                        val candidate = match.groupValues.lastOrNull { it.length in 6..16 && it.all { c -> c.isLetterOrDigit() || c == '_' || c == '-' } }
                        if (candidate != null) {
                            Timber.tag(TAG).d("Found player hash via $url: $candidate")
                            return candidate
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to fetch player hash from $url")
            }
        }
        return null
    }

    private fun downloadPlayerJs(hash: String): String? {
        for (template in PLAYER_JS_URL_TEMPLATES) {
            val url = template.format(hash)
            try {
                Timber.tag(TAG).d("Downloading player.js from: $url")
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                    .build()

                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (!body.isNullOrEmpty() && body.length > 5000) {
                        Timber.tag(TAG).d("Successfully downloaded player.js from $url (${body.length} chars)")
                        return body
                    }
                } else {
                    response.close()
                }
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to download player.js from $url")
            }
        }
        return null
    }

    /**
     * Debug method: Get cache information
     */
    fun getCacheInfo(): Map<String, Any?> {
        return try {
            val hashFile = getHashFile()
            if (!hashFile.exists()) {
                return mapOf("exists" to false)
            }

            val hashData = hashFile.readText().split("\n")
            val hash = hashData.getOrNull(0)?.trim()
            val timestamp = hashData.getOrNull(1)?.trim()?.toLongOrNull()
            val cacheFile = hash?.let { getCacheFile(it) }

            mapOf(
                "exists" to true,
                "hash" to hash,
                "timestamp" to timestamp,
                "ageMs" to (timestamp?.let { System.currentTimeMillis() - it }),
                "fileExists" to (cacheFile?.exists() == true),
                "fileSize" to (cacheFile?.length()),
            )
        } catch (e: Exception) {
            mapOf("error" to e.message)
        }
    }
}
