package com.music.spotui.resolver

import android.content.Context
import android.util.Log
import com.music.spotui.data.network.NetworkClientProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Over-The-Air (OTA) Remote Extraction Engine.
 * Fetches dynamic selector logic, API parameters, and fallback provider sequences over-the-air,
 * preventing breaking API changes from forcing binary application updates.
 */
object RemoteExtractionEngine {

    private const val TAG = "RemoteExtractionEngine"
    private const val PREFS = "ota_extraction_rules"
    private const val KEY_CACHED_JSON = "cached_extraction_rules_json"
    private const val KEY_CACHED_VERSION = "cached_extraction_rules_ver"

    // Default built-in fallback rules
    private const val DEFAULT_CLIENT_VERSION = "1.2026.08"
    private const val DEFAULT_SEARCH_PARAMS_SONGS = "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D"
    private const val DEFAULT_SEARCH_PARAMS_VIDEOS = "EgWKAQIQAWoKEAkQChAFEAMQBA%3D%3D"

    data class DynamicRules(
        val version: Int = 4,
        val clientVersion: String = DEFAULT_CLIENT_VERSION,
        val searchParamsSongs: String = DEFAULT_SEARCH_PARAMS_SONGS,
        val searchParamsVideos: String = DEFAULT_SEARCH_PARAMS_VIDEOS,
        val nTokenEvalScriptUrl: String = "",
        val fallbackProviders: List<String> = listOf("deezer", "saavn", "lossless", "piped")
    )

    @Volatile
    private var currentRules = DynamicRules()

    fun getRules(): DynamicRules = currentRules

    fun init(context: Context) {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cachedJson = sp.getString(KEY_CACHED_JSON, null)
        if (!cachedJson.isNullOrBlank()) {
            try {
                currentRules = parseRules(JSONObject(cachedJson))
                Log.d(TAG, "Loaded cached OTA extraction rules (v${currentRules.version})")
            } catch (e: Exception) {
                Log.w(TAG, "Error loading cached OTA rules: ${e.message}")
            }
        }

        // Fetch fresh rules in background
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            fetchRemoteRules(context)
        }
    }

    suspend fun fetchRemoteRules(context: Context) = withContext(Dispatchers.IO) {
        // Known remote endpoint mirror / raw config endpoints
        val endpoints = listOf(
            "https://raw.githubusercontent.com/spotui/rules/main/extraction_rules.json",
            "https://api.github.com/repos/spotui/rules/contents/extraction_rules.json"
        )

        val client = NetworkClientProvider.getOkHttpClient(context)

        for (url in endpoints) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "SpotUI/2.2")
                    .build()

                client.newCall(req).execute().use { res ->
                    if (res.isSuccessful) {
                        val body = res.body?.string() ?: return@use
                        val json = JSONObject(body)
                        val rules = parseRules(json)
                        currentRules = rules

                        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                            .putString(KEY_CACHED_JSON, body)
                            .putInt(KEY_CACHED_VERSION, rules.version)
                            .apply()

                        Log.d(TAG, "Successfully updated OTA extraction rules to v${rules.version}")
                        return@withContext
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "OTA fetch attempt failed for $url: ${e.message}")
            }
        }
    }

    private fun parseRules(json: JSONObject): DynamicRules {
        val ver = json.optInt("version", 4)
        val innertubeObj = json.optJSONObject("innertube") ?: JSONObject()
        val clientVer = innertubeObj.optString("client_version", DEFAULT_CLIENT_VERSION)
        val searchSongs = innertubeObj.optString("search_params_songs", DEFAULT_SEARCH_PARAMS_SONGS)
        val searchVideos = innertubeObj.optString("search_params_videos", DEFAULT_SEARCH_PARAMS_VIDEOS)
        val nEval = innertubeObj.optString("n_token_eval_script", "")

        val providersArray = json.optJSONArray("fallback_providers")
        val providers = mutableListOf<String>()
        if (providersArray != null) {
            for (i in 0 until providersArray.length()) {
                providers.add(providersArray.optString(i))
            }
        } else {
            providers.addAll(listOf("deezer", "saavn", "lossless", "piped"))
        }

        return DynamicRules(
            version = ver,
            clientVersion = clientVer,
            searchParamsSongs = searchSongs,
            searchParamsVideos = searchVideos,
            nTokenEvalScriptUrl = nEval,
            fallbackProviders = providers
        )
    }
}
