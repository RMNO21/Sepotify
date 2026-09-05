/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.net.ConnectivityManager
import android.net.Uri
import android.util.Log
import androidx.media3.common.PlaybackException
import com.metrolist.innertube.NewPipeExtractor
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.YouTubeClient
import com.metrolist.innertube.models.YouTubeClient.Companion.ANDROID_CREATOR
import com.metrolist.innertube.models.YouTubeClient.Companion.ANDROID_NO_SDK
import com.metrolist.innertube.models.YouTubeClient.Companion.ANDROID_VR_1_61_48
import com.metrolist.innertube.models.YouTubeClient.Companion.ANDROID_VR_NO_AUTH
import com.metrolist.innertube.models.YouTubeClient.Companion.MOBILE
import com.metrolist.innertube.models.YouTubeClient.Companion.TVHTML5
import com.metrolist.innertube.models.YouTubeClient.Companion.TVHTML5_SIMPLY_EMBEDDED_PLAYER
import com.metrolist.innertube.models.YouTubeClient.Companion.WEB
import com.metrolist.innertube.models.YouTubeClient.Companion.WEB_CREATOR
import com.metrolist.innertube.models.YouTubeClient.Companion.WEB_REMIX
import com.metrolist.innertube.models.response.PlayerResponse
import com.metrolist.music.constants.AudioQuality
import com.metrolist.music.utils.cipher.CipherDeobfuscator
import com.metrolist.music.utils.cipher.FunctionNameExtractor
import com.metrolist.music.utils.potoken.PoTokenGenerator
import com.metrolist.music.utils.potoken.PoTokenResult
import com.metrolist.music.utils.sabr.EjsNTransformSolver
import com.music.spotui.debug.PlaybackDebugLogger
import okhttp3.OkHttpClient
import timber.log.Timber
import java.util.concurrent.TimeUnit

object YTPlayerUtils {
    private const val logTag = "YTPlayerUtils"
    private const val TAG = "YTPlayerUtils"

    private val httpClient = OkHttpClient.Builder()
        .proxy(YouTube.proxy)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .fastFallback(true)
        .retryOnConnectionFailure(true)
        .build()

    private val probeClient = OkHttpClient.Builder()
        .proxy(YouTube.proxy)
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .fastFallback(true)
        .build()

    private val poTokenGenerator = PoTokenGenerator()

    private val MAIN_CLIENT: YouTubeClient = ANDROID_VR_NO_AUTH

    private val STREAM_FALLBACK_CLIENTS: Array<YouTubeClient> = arrayOf(
        ANDROID_VR_NO_AUTH,
        ANDROID_VR_1_61_48,
        TVHTML5_SIMPLY_EMBEDDED_PLAYER,
        WEB_REMIX,
        WEB,
        ANDROID_NO_SDK,
        MOBILE,
        ANDROID_CREATOR,
        TVHTML5
    )
    data class PlaybackData(
        val audioConfig: PlayerResponse.PlayerConfig.AudioConfig?,
        val videoDetails: PlayerResponse.VideoDetails?,
        val playbackTracking: PlayerResponse.PlaybackTracking?,
        val format: PlayerResponse.StreamingData.Format,
        val streamUrl: String,
        val streamExpiresInSeconds: Int,
    )
    /**
     * Custom player response intended to use for playback.
     * Metadata like audioConfig and videoDetails are from [MAIN_CLIENT] or fallback.
     * Format & stream can be from [MAIN_CLIENT] or [STREAM_FALLBACK_CLIENTS].
     */
    suspend fun playerResponseForPlayback(
        videoId: String,
        playlistId: String? = null,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
    ): Result<PlaybackData> = runCatching {
        Timber.tag(TAG).d("=== PLAYER RESPONSE FOR PLAYBACK ===")
        Timber.tag(TAG).d("videoId: $videoId")
        Timber.tag(TAG).d("playlistId: $playlistId")
        Timber.tag(TAG).d("audioQuality: $audioQuality")

        PlaybackDebugLogger.i("YTPlayerUtils", "Starting stream resolution for videoId=$videoId (quality=$audioQuality)")
        PlaybackDebugLogger.activeResolvedVideoId = videoId

        val isUploadedTrack = playlistId == "MLPT" || playlistId?.contains("MLPT") == true
        val isLoggedIn = YouTube.cookie != null

        // Only compute signature timestamp lazily if a web client requires it
        var signatureTimestamp: Int? = null

        // Generate PoToken if main client requires it
        var poToken: PoTokenResult? = null
        val sessionId = if (isLoggedIn) YouTube.dataSyncId else YouTube.visitorData
        val mainClientNeedsPoToken = MAIN_CLIENT.useWebPoTokens
        if (mainClientNeedsPoToken && sessionId != null) {
            try {
                poToken = poTokenGenerator.getWebClientPoToken(videoId, sessionId)
                PlaybackDebugLogger.d("YTPlayerUtils", "PoToken generated for $videoId: ${poToken?.playerRequestPoToken?.take(15)}...")
            } catch (e: Exception) {
                PlaybackDebugLogger.w("YTPlayerUtils", "PoToken generation failed: ${e.message}")
                Timber.tag(logTag).e(e, "PoToken generation failed: ${e.message}")
            }
        }
        val skipMainClient = mainClientNeedsPoToken && poToken == null

        // Try MAIN_CLIENT
        var mainPlayerResponse = YouTube.player(videoId, playlistId, MAIN_CLIENT, signatureTimestamp, poToken?.playerRequestPoToken).getOrNull()
        PlaybackDebugLogger.d("YTPlayerUtils", "Client ${MAIN_CLIENT.clientName} status=${mainPlayerResponse?.playabilityStatus?.status ?: "NULL"}")

        var usedAgeRestrictedClient: YouTubeClient? = null
        val wasOriginallyAgeRestricted: Boolean

        val mainStatus = mainPlayerResponse?.playabilityStatus?.status
        val isAgeRestrictedFromResponse = mainStatus in listOf("AGE_CHECK_REQUIRED", "AGE_VERIFICATION_REQUIRED", "LOGIN_REQUIRED", "CONTENT_CHECK_REQUIRED")
        wasOriginallyAgeRestricted = isAgeRestrictedFromResponse

        if (isAgeRestrictedFromResponse && isLoggedIn) {
            val creatorResponse = YouTube.player(videoId, playlistId, WEB_CREATOR, null, null).getOrNull()
            if (creatorResponse?.playabilityStatus?.status == "OK") {
                mainPlayerResponse = creatorResponse
                usedAgeRestrictedClient = WEB_CREATOR
            }
        }

        var selectedFormat: PlayerResponse.StreamingData.Format? = null
        var selectedStreamUrl: String? = null
        var selectedExpiresIn: Int? = null
        var selectedResponse: PlayerResponse? = null
        var streamValidated = false

        val retryMainPlayerResponse: PlayerResponse? = if (usedAgeRestrictedClient != null) mainPlayerResponse else null

        val currentStatus = mainPlayerResponse?.playabilityStatus?.status
        val isAgeRestricted = currentStatus in listOf("AGE_CHECK_REQUIRED", "AGE_VERIFICATION_REQUIRED", "LOGIN_REQUIRED", "CONTENT_CHECK_REQUIRED")

        val isPrivateTrack = mainPlayerResponse?.videoDetails?.musicVideoType == "MUSIC_VIDEO_TYPE_PRIVATELY_OWNED_TRACK"

        val startIndex = when {
            isPrivateTrack -> 1
            isAgeRestricted || mainPlayerResponse?.playabilityStatus?.status != "OK" || skipMainClient -> 0
            else -> -1
        }

        for (clientIndex in (startIndex until STREAM_FALLBACK_CLIENTS.size)) {
            val client: YouTubeClient = if (clientIndex == -1) {
                MAIN_CLIENT
            } else {
                STREAM_FALLBACK_CLIENTS[clientIndex]
            }

            if (clientIndex == -1) {
                Timber.tag(logTag).d("Trying stream from MAIN_CLIENT: ${client.clientName}")
            } else {
                Timber.tag(logTag).d("Trying fallback client ${clientIndex + 1}/${STREAM_FALLBACK_CLIENTS.size}: ${client.clientName}")

                if (client.loginRequired && !isLoggedIn && YouTube.cookie == null) {
                    continue
                }
            }

            val clientPoToken = if (client.useWebPoTokens) poToken?.playerRequestPoToken else null
            val clientSigTimestamp = if (client.useSignatureTimestamp) {
                if (signatureTimestamp == null) {
                    signatureTimestamp = getSignatureTimestampOrNull(videoId).timestamp
                }
                signatureTimestamp
            } else null

            val streamPlayerResponse = if (clientIndex == -1) {
                retryMainPlayerResponse ?: mainPlayerResponse
            } else {
                YouTube.player(videoId, playlistId, client, clientSigTimestamp, clientPoToken).getOrNull()
            }

            val returnedVideoId = streamPlayerResponse?.videoDetails?.videoId
            if (returnedVideoId != null && returnedVideoId != videoId) {
                continue
            }

            if (streamPlayerResponse?.playabilityStatus?.status == "OK") {
                val responseToUse = streamPlayerResponse

                val candidateFormat = findFormat(
                    responseToUse,
                    audioQuality,
                    connectivityManager,
                ) ?: continue

                var candidateUrl = findUrlOrNull(candidateFormat, videoId, responseToUse, skipNewPipe = false) ?: continue

                val currentClient = if (clientIndex == -1) {
                    usedAgeRestrictedClient ?: MAIN_CLIENT
                } else {
                    client
                }

                val isPrivatelyOwnedTrack = streamPlayerResponse.videoDetails?.musicVideoType == "MUSIC_VIDEO_TYPE_PRIVATELY_OWNED_TRACK"
                val needsNTransform = currentClient.useWebPoTokens ||
                    currentClient.clientName in listOf("WEB", "WEB_REMIX", "WEB_CREATOR", "TVHTML5") ||
                    isPrivatelyOwnedTrack

                if (needsNTransform) {
                    try {
                        candidateUrl = CipherDeobfuscator.transformNParamInUrl(candidateUrl)
                        val needsPoToken = (currentClient.useWebPoTokens || isPrivatelyOwnedTrack) && poToken?.streamingDataPoToken != null
                        if (needsPoToken) {
                            val separator = if ("?" in candidateUrl) "&" else "?"
                            candidateUrl = "${candidateUrl}${separator}pot=${Uri.encode(poToken.streamingDataPoToken)}"
                        }
                    } catch (e: Exception) {
                        Timber.tag(TAG).e(e, "N-transform failed: ${e.message}")
                    }
                }

                val expires = streamPlayerResponse.streamingData?.expiresInSeconds ?: 21600

                // Validate or accept stream directly
                val probeOk = validateStatus(candidateUrl, currentClient)
                PlaybackDebugLogger.d("YTPlayerUtils", "Client ${currentClient.clientName} itag=${candidateFormat.itag} bitrate=${candidateFormat.bitrate} probe=$probeOk")
                if (probeOk) {
                    Timber.tag(TAG).i("Playback stream verified: client=${currentClient.clientName}, videoId=$videoId")
                    selectedFormat = candidateFormat
                    selectedStreamUrl = candidateUrl
                    selectedExpiresIn = expires
                    selectedResponse = responseToUse
                    streamValidated = true
                    PlaybackDebugLogger.activeClient = currentClient.clientName
                    PlaybackDebugLogger.activeFormatItag = candidateFormat.itag
                    PlaybackDebugLogger.activeMimeType = candidateFormat.mimeType
                    PlaybackDebugLogger.activeBitrate = candidateFormat.bitrate
                    PlaybackDebugLogger.i("YTPlayerUtils", "Verified stream from ${currentClient.clientName}: itag=${candidateFormat.itag} (${candidateFormat.mimeType})")
                    break
                }
            }
        }

        val effectiveResponse = selectedResponse ?: mainPlayerResponse
        if (!streamValidated || selectedStreamUrl == null || selectedFormat == null) {
            selectedStreamUrl = null
            selectedFormat = null
            PlaybackDebugLogger.i("YTPlayerUtils", "Primary clients unvalidated; switching to NewPipe fallback")
            val npStreams = NewPipeExtractor.newPipePlayer(videoId)
            val bestNpAudio = npStreams.firstOrNull { it.first == 140 || it.first == 251 } ?: npStreams.firstOrNull()
            if (bestNpAudio != null) {
                val itag = bestNpAudio.first
                val url = bestNpAudio.second
                val mimeType = if (itag == 140) "audio/mp4" else "audio/webm"
                val bitrate = if (itag == 140) 128000 else 160000
                PlaybackDebugLogger.i("YTPlayerUtils", "NewPipe fallback ready: itag=$itag, ${mimeType}")
                PlaybackDebugLogger.activeClient = "NewPipe"
                PlaybackDebugLogger.activeFormatItag = itag
                PlaybackDebugLogger.activeMimeType = mimeType
                PlaybackDebugLogger.activeBitrate = bitrate
                Timber.tag(TAG).i("Successfully recovered stream via NewPipeExtractor fallback for videoId=$videoId (itag=$itag)")
                return@runCatching PlaybackData(
                    audioConfig = null,
                    videoDetails = effectiveResponse?.videoDetails,
                    playbackTracking = null,
                    format = PlayerResponse.StreamingData.Format(
                        itag = itag,
                        url = url,
                        mimeType = mimeType,
                        bitrate = bitrate,
                    ),
                    streamUrl = url,
                    streamExpiresInSeconds = 21600,
                )
            }

            PlaybackDebugLogger.i("YTPlayerUtils", "Attempting Piped fallback for videoId=$videoId")
            val pipedStream = com.music.spotui.piped.PipedSource.resolveAudioStream(videoId)
            if (pipedStream != null) {
                PlaybackDebugLogger.i("YTPlayerUtils", "Piped fallback ready for videoId=$videoId")
                PlaybackDebugLogger.activeClient = "Piped"
                PlaybackDebugLogger.activeFormatItag = 251
                PlaybackDebugLogger.activeMimeType = pipedStream.mimeType
                PlaybackDebugLogger.activeBitrate = pipedStream.bitrate
                Timber.tag(TAG).i("Successfully recovered stream via Piped fallback for videoId=$videoId")
                return@runCatching PlaybackData(
                    audioConfig = null,
                    videoDetails = effectiveResponse?.videoDetails,
                    playbackTracking = null,
                    format = PlayerResponse.StreamingData.Format(
                        itag = 251,
                        url = pipedStream.url,
                        mimeType = pipedStream.mimeType,
                        bitrate = pipedStream.bitrate,
                    ),
                    streamUrl = pipedStream.url,
                    streamExpiresInSeconds = 21600,
                )
            }
        }

        if (selectedFormat == null || selectedStreamUrl == null) {
            Timber.tag(logTag).e("Bad stream player response - all clients failed for videoId=$videoId")
            if (isUploadedTrack) {
                Timber.tag(TAG).w("FAILURE: All clients failed for uploaded track videoId=$videoId")
            }
            throw PlaybackException(
                "Unable to resolve playable audio stream for videoId=$videoId",
                null,
                PlaybackException.ERROR_CODE_REMOTE_ERROR
            )
        }

        val audioConfig = effectiveResponse?.playerConfig?.audioConfig
        val videoDetails = effectiveResponse?.videoDetails
        val playbackTracking = effectiveResponse?.playbackTracking
        val expiresIn = selectedExpiresIn ?: effectiveResponse?.streamingData?.expiresInSeconds ?: 21600

        Timber.tag(logTag).d("Successfully obtained playback data with format: ${selectedFormat.mimeType}, bitrate: ${selectedFormat.bitrate}")
        PlaybackData(
            audioConfig = audioConfig,
            videoDetails = videoDetails,
            playbackTracking = playbackTracking,
            format = selectedFormat,
            streamUrl = selectedStreamUrl,
            streamExpiresInSeconds = expiresIn,
        )
    }.onFailure { e ->
        Timber.tag(TAG).e(e, "EXCEPTION during playback for videoId=$videoId: ${e::class.simpleName}: ${e.message}")
    }
    /**
     * Simple player response intended to use for metadata only.
     * Stream URLs of this response might not work so don't use them.
     */
    suspend fun playerResponseForMetadata(
        videoId: String,
        playlistId: String? = null,
    ): Result<PlayerResponse> {
        Timber.tag(logTag).d("Fetching metadata-only player response for videoId: $videoId using MAIN_CLIENT: ${MAIN_CLIENT.clientName}")
        return YouTube.player(videoId, playlistId, client = WEB_REMIX) // ANDROID_VR does not work with history
            .onSuccess { Timber.tag(logTag).d("Successfully fetched metadata") }
            .onFailure { Timber.tag(logTag).e(it, "Failed to fetch metadata") }
    }

    private fun findFormat(
        playerResponse: PlayerResponse,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
    ): PlayerResponse.StreamingData.Format? {
        Timber.tag(logTag).d("Finding format with audioQuality: $audioQuality, network metered: ${connectivityManager.isActiveNetworkMetered}")

        val adaptive = playerResponse.streamingData?.adaptiveFormats.orEmpty()
        val direct = playerResponse.streamingData?.formats.orEmpty()
        val allAudio = (adaptive + direct).filter { it.isAudio || it.mimeType.startsWith("audio/") }

        val originalAudio = allAudio.filter { it.isOriginal }.ifEmpty { allAudio }

        val format = originalAudio.maxByOrNull {
            it.bitrate * when (audioQuality) {
                AudioQuality.AUTO -> if (connectivityManager.isActiveNetworkMetered) -1 else 1
                AudioQuality.HIGH -> 1
                AudioQuality.LOW -> -1
            } + (if (it.mimeType.startsWith("audio/webm") || it.mimeType.contains("opus")) 10240 else 0)
        } ?: allAudio.firstOrNull()

        if (format != null) {
            Timber.tag(logTag).d("Selected format: ${format.mimeType}, bitrate: ${format.bitrate}")
        } else {
            Timber.tag(logTag).d("No suitable audio format found")
        }

        return format
    }
    /**
     * Checks if the stream url returns a successful status.
     *
     * Why the leniency: on slow mobile networks HEAD can time out or be rejected by edge
     * CDNs (405/403/410 on HEAD while GET works). If we treat those as "failed" we skip a
     * stream that actually plays. Rules here:
     *  - 2xx → valid
     *  - 405/403/410 → treat as valid (HEAD may be restricted; ExoPlayer will GET)
     *  - IOException (timeout/reset) → treat as valid; ExoPlayer has its own retry and
     *    killing the client here just cascades us down the fallback chain for no reason
     *  - other HTTP codes (4xx/5xx) → invalid
     */
    /**
     * Checks if the stream url returns a successful status.
     * Uses a lightweight range GET request (0-1023 bytes) matching ExoPlayer's fetch behavior.
     *  - 200 / 206 / 2xx → valid stream
     *  - 403 / 404 / 410 / 4xx / 5xx → invalid stream (triggers fallback client)
     *  - IOException (transient socket timeout/reset) → optimistic acceptance
     */
    private fun validateStatus(url: String, client: YouTubeClient? = null): Boolean {
        if (!url.contains("googlevideo.com")) {
            return true
        }
        val clientParam = (Uri.parse(url).getQueryParameter("c") ?: Uri.parse(url).getQueryParameter("client") ?: client?.clientName)?.uppercase()
        val ua = when {
            clientParam == "IOS" || clientParam == "IPADOS" ->
                "com.google.ios.youtube/19.49.1 (iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X; en_US)"
            clientParam == "ANDROID" || clientParam == "ANDROID_MUSIC" || clientParam == "ANDROID_NO_SDK" ->
                "com.google.android.youtube/19.49.34 (Linux; U; Android 14; en_US; Pixel 8) gzip"
            clientParam == "ANDROID_VR" ->
                "com.google.android.apps.youtube.vr.oculus/1.54.19 (Linux; U; Android 12; en_US; Quest 3; Build/SQ3A.220605.009.A1; Cronet/120.0.6099.199)"
            clientParam == "WEB" || clientParam == "WEB_REMIX" ->
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
            clientParam?.contains("TV") == true ->
                "Mozilla/5.0 (PlayStation; PlayStation 4/12.02) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.4 Safari/605.1.15"
            else ->
                client?.userAgent ?: "com.google.android.apps.youtube.vr.oculus/1.54.19 (Linux; U; Android 12; en_US; Quest 3; Build/SQ3A.220605.009.A1; Cronet/120.0.6099.199)"
        }
        val isWeb = clientParam == "WEB" || clientParam == "WEB_REMIX" || clientParam?.contains("TV") == true

        try {
            val requestBuilder = okhttp3.Request.Builder()
                .get()
                .addHeader("User-Agent", ua)
                .addHeader("Accept", "*/*")
                .addHeader("Accept-Encoding", "identity")
                .addHeader("Connection", "keep-alive")
                .addHeader("Range", "bytes=0-65535")
                .url(url)

            if (isWeb) {
                requestBuilder.addHeader("Origin", "https://www.youtube.com")
                requestBuilder.addHeader("Referer", "https://www.youtube.com/")
            }

            YouTube.cookie?.let { cookie ->
                requestBuilder.addHeader("Cookie", cookie)
            }

            val response = probeClient.newCall(requestBuilder.build()).execute()
            val code = response.code
            val contentLength = response.header("Content-Length")?.toLongOrNull() ?: 0L
            response.close()
            val accepted = (response.isSuccessful || code == 206 || code == 200) &&
                code != 403 && code != 410 && code != 404 && code != 401 && code != 400 && code != 405
            Timber.tag(logTag).d("Stream URL validation code=$code ($contentLength bytes) accepted=$accepted")
            PlaybackDebugLogger.d("YTPlayerUtils", "Probe for ${client?.clientName ?: "Unknown"} (c=$clientParam): HTTP $code ($contentLength bytes) -> accepted=$accepted")
            return accepted
        } catch (e: java.io.IOException) {
            Timber.tag(logTag).d("Stream URL probe IO exception; rejecting (${e.message})")
            PlaybackDebugLogger.d("YTPlayerUtils", "Probe IO rejected: ${e.message}")
            return false
        } catch (e: Exception) {
            Timber.tag(logTag).d("Stream URL validation rejected (${e.message})")
            PlaybackDebugLogger.d("YTPlayerUtils", "Probe rejected: ${e.message}")
            return false
        }
    }
    data class SignatureTimestampResult(
        val timestamp: Int?,
        val isAgeRestricted: Boolean
    )

    private fun getSignatureTimestampOrNull(videoId: String): SignatureTimestampResult {
        Timber.tag(logTag).d("Getting signature timestamp for videoId: $videoId")
        val result = NewPipeExtractor.getSignatureTimestamp(videoId)
        var timestamp = result.getOrNull()
        if (timestamp == null) {
            timestamp = FunctionNameExtractor.getHardcodedConfig("74edf1a3")?.signatureTimestamp ?: 20522
            Timber.tag(logTag).d("Using fallback signature timestamp: $timestamp")
        } else {
            Timber.tag(logTag).d("Signature timestamp obtained via NewPipe: $timestamp")
        }
        val isAgeRestricted = result.exceptionOrNull()?.message?.contains("age-restricted", ignoreCase = true) == true
        return SignatureTimestampResult(timestamp, isAgeRestricted)
    }

    private suspend fun findUrlOrNull(
        format: PlayerResponse.StreamingData.Format,
        videoId: String,
        playerResponse: PlayerResponse,
        skipNewPipe: Boolean = false
    ): String? {
        // First check if format already has a URL
        if (!format.url.isNullOrEmpty()) {
            return format.url
        }

        // Try custom cipher deobfuscation for signatureCipher formats
        val signatureCipher = format.signatureCipher ?: format.cipher
        if (!signatureCipher.isNullOrEmpty()) {
            val customDeobfuscatedUrl = CipherDeobfuscator.deobfuscateStreamUrl(signatureCipher, videoId)
            if (customDeobfuscatedUrl != null) {
                return customDeobfuscatedUrl
            }
            if (!skipNewPipe) {
                try {
                    val newPipeUrl = NewPipeExtractor.getStreamUrl(format, videoId)
                    if (newPipeUrl != null) {
                        return newPipeUrl
                    }
                } catch (e: Exception) {
                    Timber.tag(logTag).w(e, "NewPipe stream deobfuscation fallback failed")
                }
            }
        }

        return null
    }

    fun forceRefreshForVideo(videoId: String) {
        Timber.tag(logTag).d("Force refreshing for videoId: $videoId")
    }
}
