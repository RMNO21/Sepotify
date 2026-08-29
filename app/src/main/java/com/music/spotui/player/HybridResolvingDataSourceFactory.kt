package com.music.spotui.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import com.metrolist.music.constants.AudioQuality
import com.metrolist.music.utils.YTPlayerUtils
import com.music.spotui.resolver.TrackResolver
import com.music.spotui.storage.LocalFileManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class TrackNotFoundException(message: String) : Exception(message)

/**
 * Media3 ResolvingDataSource Architecture
 * Automatically resolves virtual URIs or un-resolved queries into valid local disk URIs
 * or direct CDN streams without stalling the player buffer.
 */
@OptIn(UnstableApi::class)
class HybridResolvingDataSourceFactory(
    private val context: Context,
    private val trackResolver: TrackResolver = TrackResolver(),
    private val localFileManager: LocalFileManager = LocalFileManager.getInstance(context),
    upstreamFactory: DataSource.Factory = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)
        .setKeepPostFor302Redirects(true)
        .setConnectTimeoutMs(15000)
        .setReadTimeoutMs(20000)
        .setUserAgent("Mozilla/5.0 (Linux; Android 14; Pixel) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
        .setDefaultRequestProperties(
            mapOf(
                "Accept" to "*/*",
                "Accept-Encoding" to "identity",
                "Connection" to "keep-alive",
            )
        )
) : DataSource.Factory {

    private val resolvingFactory = ResolvingDataSource.Factory(
        upstreamFactory,
        object : ResolvingDataSource.Resolver {
            override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
                val uri = dataSpec.uri
                val trackId = uri.getQueryParameter("trackId") ?: uri.lastPathSegment
                if (trackId.isNullOrBlank() || (!uri.scheme.equals("spotui", ignoreCase = true) && !uri.toString().contains("resolveTrack"))) {
                    return dataSpec
                }

                // 1. Check Local Storage First (SSOT)
                val localAudioUri = localFileManager.getValidLocalUri(trackId)
                if (localAudioUri != null) {
                    return dataSpec.buildUpon().setUri(localAudioUri).build()
                }

                // 2. Check Stream URL Cache (4-hr TTL)
                StreamUrlCache.get(trackId)?.let { cachedUrl ->
                    return dataSpec.buildUpon().setUri(Uri.parse(cachedUrl)).build()
                }

                // 3. Blocking Resolution inside worker thread for downstream player buffer
                return runBlocking(Dispatchers.IO) {
                    val metadata = localFileManager.getTrackMetadata(trackId)
                    val videoId = trackResolver.resolveTrack(metadata.toTarget())
                        ?: throw TrackNotFoundException("No valid source found for: $trackId")

                    val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                    val playback = YTPlayerUtils.playerResponseForPlayback(
                        videoId = videoId,
                        audioQuality = AudioQuality.AUTO,
                        connectivityManager = connectivityManager
                    ).getOrNull()

                    val directStreamUrl = playback?.streamUrl
                        ?: throw TrackNotFoundException("Unable to extract direct stream URL for videoId=$videoId")

                    StreamUrlCache.put(trackId, directStreamUrl, source = "YouTube")
                    dataSpec.buildUpon().setUri(Uri.parse(directStreamUrl)).build()
                }
            }

            override fun resolveReportedUri(uri: Uri): Uri {
                val trackId = uri.getQueryParameter("trackId") ?: uri.lastPathSegment
                if (trackId.isNullOrBlank() || (!uri.scheme.equals("spotui", ignoreCase = true) && !uri.toString().contains("resolveTrack"))) {
                    return uri
                }
                val localAudioUri = localFileManager.getValidLocalUri(trackId)
                if (localAudioUri != null) return localAudioUri
                StreamUrlCache.get(trackId)?.let { return Uri.parse(it) }
                return uri
            }
        }
    )

    override fun createDataSource(): DataSource {
        return resolvingFactory.createDataSource()
    }
}
