package com.music.spotui.player

import android.content.Context
import android.util.Log
import com.music.spotui.data.network.NetworkClientProvider
import com.music.spotui.storage.LocalFileManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * In-App Localhost Proxy Architecture.
 * Embedded high-performance HTTP server running on 127.0.0.1:[port].
 *
 * Implements RFC 7233 HTTP Range requests (206 Partial Content) to standardize ExoPlayer buffering,
 * byte-range seeking, and seamless crossfading.
 *
 * Unified Cache & Stream:
 * 1. If chunk is available on local disk -> serves immediately with zero network latency.
 * 2. If missing -> streams remote bytes directly to ExoPlayer while simultaneously piping to disk cache.
 */
object LocalMediaProxyServer {

    private const val TAG = "LocalMediaProxyServer"
    private const val DEFAULT_PORT = 8088

    private var serverSocket: ServerSocket? = null
    private var actualPort: Int = DEFAULT_PORT
    private var isRunning = false
    private val threadPool = Executors.newFixedThreadPool(8)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var appContext: Context

    private val activeStreams = ConcurrentHashMap<String, Long>()

    @Synchronized
    fun start(context: Context) {
        if (isRunning) return
        appContext = context.applicationContext

        scope.launch {
            try {
                // Find open port on loopback
                var port = DEFAULT_PORT
                var s: ServerSocket? = null
                for (p in port..(port + 50)) {
                    try {
                        s = ServerSocket(p, 50, InetAddress.getByName("127.0.0.1"))
                        port = p
                        break
                    } catch (e: Exception) {
                        // Port in use, try next
                    }
                }

                if (s == null) {
                    s = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
                    port = s.localPort
                }

                serverSocket = s
                actualPort = port
                isRunning = true
                Log.d(TAG, "LocalMediaProxyServer listening on http://127.0.0.1:$actualPort")

                while (isRunning && !s.isClosed) {
                    try {
                        val clientSocket = s.accept()
                        threadPool.execute { handleClient(clientSocket) }
                    } catch (e: Exception) {
                        if (!isRunning) break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start LocalMediaProxyServer: ${e.message}", e)
            }
        }
    }

    fun getProxyUrl(rawUrl: String, trackId: String): String {
        if (rawUrl.isBlank()) return rawUrl
        // If it's already a local file or localhost URL, return as-is
        if (rawUrl.startsWith("file://") || rawUrl.startsWith("content://") || rawUrl.startsWith("http://127.0.0.1")) {
            return rawUrl
        }
        val encodedUrl = URLEncoder.encode(rawUrl, "UTF-8")
        val encodedId = URLEncoder.encode(trackId, "UTF-8")
        return "http://127.0.0.1:$actualPort/stream?id=$encodedId&url=$encodedUrl"
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 15000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val firstLine = reader.readLine() ?: return socket.close()

            val parts = firstLine.split(" ")
            if (parts.size < 2 || parts[0] != "GET") {
                sendError(socket, 400, "Bad Request")
                return
            }

            var rangeHeader: String? = null
            var line: String? = reader.readLine()
            while (!line.isNullOrBlank()) {
                if (line.startsWith("Range:", ignoreCase = true)) {
                    rangeHeader = line.substring(6).trim()
                }
                line = reader.readLine()
            }

            val requestUri = parts[1]
            if (!requestUri.startsWith("/stream")) {
                sendError(socket, 404, "Not Found")
                return
            }

            // Parse query parameters
            val query = requestUri.substringAfter("?", "")
            var rawUrl = ""
            var trackId = ""
            query.split("&").forEach { param ->
                val pair = param.split("=")
                if (pair.size == 2) {
                    val k = URLDecoder.decode(pair[0], "UTF-8")
                    val v = URLDecoder.decode(pair[1], "UTF-8")
                    if (k == "url") rawUrl = v
                    if (k == "id") trackId = v
                }
            }

            if (rawUrl.isBlank()) {
                sendError(socket, 400, "Missing url parameter")
                return
            }

            serveMedia(socket, rawUrl, trackId, rangeHeader)
        } catch (e: Exception) {
            // Client closed connection or timeout
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun serveMedia(socket: Socket, rawUrl: String, trackId: String, rangeHeader: String?) {
        // 1. Check if track is already downloaded locally
        val downloadedFile = if (trackId.isNotBlank()) {
            LocalFileManager.getDownloadedSongFile(appContext, trackId)
        } else null

        val cacheDir = File(appContext.cacheDir, "proxy_cache").apply { mkdirs() }
        val diskCacheFile = if (trackId.isNotBlank()) File(cacheDir, "${trackId}.cache") else null

        val targetFile = when {
            downloadedFile != null && downloadedFile.exists() && downloadedFile.length() > 0 -> downloadedFile
            diskCacheFile != null && diskCacheFile.exists() && diskCacheFile.length() > 0 -> diskCacheFile
            else -> null
        }

        if (targetFile != null && targetFile.exists() && targetFile.length() > 0) {
            serveLocalFile(socket, targetFile, rangeHeader)
            return
        }

        // 2. Stream from upstream and simultaneously write to disk cache
        streamFromUpstream(socket, rawUrl, trackId, diskCacheFile, rangeHeader)
    }

    private fun serveLocalFile(socket: Socket, file: File, rangeHeader: String?) {
        val totalLength = file.length()
        var start: Long = 0
        var end: Long = totalLength - 1

        if (!rangeHeader.isNullOrBlank() && rangeHeader.startsWith("bytes=")) {
            val range = rangeHeader.substring(6).trim()
            val rangeParts = range.split("-")
            start = rangeParts[0].toLongOrNull() ?: 0
            if (rangeParts.size > 1 && rangeParts[1].isNotBlank()) {
                end = rangeParts[1].toLongOrNull() ?: (totalLength - 1)
            }
        }

        start = start.coerceIn(0, totalLength - 1)
        end = end.coerceIn(start, totalLength - 1)
        val contentLength = end - start + 1

        val out = BufferedOutputStream(socket.getOutputStream())
        val isRange = rangeHeader != null

        val responseHeader = buildString {
            if (isRange) {
                append("HTTP/1.1 206 Partial Content\r\n")
                append("Content-Range: bytes $start-$end/$totalLength\r\n")
            } else {
                append("HTTP/1.1 200 OK\r\n")
            }
            append("Accept-Ranges: bytes\r\n")
            append("Content-Length: $contentLength\r\n")
            append("Content-Type: audio/mpeg\r\n")
            append("Connection: close\r\n\r\n")
        }

        out.write(responseHeader.toByteArray(Charsets.US_ASCII))
        out.flush()

        RandomAccessFile(file, "r").use { raf ->
            raf.seek(start)
            val buffer = ByteArray(32 * 1024)
            var bytesToRead = contentLength
            while (bytesToRead > 0) {
                val toRead = minOf(buffer.size.toLong(), bytesToRead).toInt()
                val read = raf.read(buffer, 0, toRead)
                if (read <= 0) break
                out.write(buffer, 0, read)
                bytesToRead -= read
            }
            out.flush()
        }
    }

    private fun streamFromUpstream(
        socket: Socket,
        rawUrl: String,
        trackId: String,
        diskCacheFile: File?,
        rangeHeader: String?
    ) {
        val client = NetworkClientProvider.getOkHttpClient(appContext)
        val requestBuilder = Request.Builder()
            .url(rawUrl)
            .header("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:125.0) Gecko/125.0 Firefox/125.0")

        if (!rangeHeader.isNullOrBlank()) {
            requestBuilder.header("Range", rangeHeader)
        }

        val upstreamResponse = client.newCall(requestBuilder.build()).execute()
        if (!upstreamResponse.isSuccessful && upstreamResponse.code != 206) {
            sendError(socket, upstreamResponse.code, upstreamResponse.message)
            upstreamResponse.close()
            return
        }

        val out = BufferedOutputStream(socket.getOutputStream())
        val body = upstreamResponse.body ?: run {
            sendError(socket, 502, "Bad Gateway: Empty upstream body")
            return
        }

        val isRange = upstreamResponse.code == 206 || rangeHeader != null
        val totalLength = upstreamResponse.header("Content-Length")?.toLongOrNull() ?: -1L
        val contentRange = upstreamResponse.header("Content-Range")
        val contentType = upstreamResponse.header("Content-Type") ?: "audio/mpeg"

        val responseHeader = buildString {
            if (isRange && contentRange != null) {
                append("HTTP/1.1 206 Partial Content\r\n")
                append("Content-Range: $contentRange\r\n")
            } else if (isRange) {
                append("HTTP/1.1 206 Partial Content\r\n")
            } else {
                append("HTTP/1.1 200 OK\r\n")
            }
            append("Accept-Ranges: bytes\r\n")
            if (totalLength > 0) {
                append("Content-Length: $totalLength\r\n")
            }
            append("Content-Type: $contentType\r\n")
            append("Connection: close\r\n\r\n")
        }

        out.write(responseHeader.toByteArray(Charsets.US_ASCII))
        out.flush()

        val saveToDisk = diskCacheFile != null && rangeHeader == null
        val tempCacheFile = if (saveToDisk) File("${diskCacheFile!!.absolutePath}.tmp") else null
        val cacheOutputStream = tempCacheFile?.let { BufferedOutputStream(it.outputStream()) }

        try {
            val input = BufferedInputStream(body.byteStream())
            val buffer = ByteArray(32 * 1024)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                out.write(buffer, 0, read)
                cacheOutputStream?.write(buffer, 0, read)
            }
            out.flush()
            cacheOutputStream?.flush()

            if (tempCacheFile != null && tempCacheFile.exists() && tempCacheFile.length() > 50_000L) {
                tempCacheFile.renameTo(diskCacheFile!!)
                Log.d(TAG, "Cached streamed track $trackId to disk (${diskCacheFile.length()} bytes)")
            }
        } catch (e: Exception) {
            tempCacheFile?.delete()
        } finally {
            cacheOutputStream?.close()
            upstreamResponse.close()
        }
    }

    private fun sendError(socket: Socket, code: Int, message: String) {
        runCatching {
            val out = socket.getOutputStream()
            val resp = "HTTP/1.1 $code $message\r\nContent-Type: text/plain\r\nContent-Length: ${message.length}\r\n\r\n$message"
            out.write(resp.toByteArray(Charsets.US_ASCII))
            out.flush()
        }
    }

    fun stop() {
        isRunning = false
        runCatching { serverSocket?.close() }
        threadPool.shutdown()
    }
}
