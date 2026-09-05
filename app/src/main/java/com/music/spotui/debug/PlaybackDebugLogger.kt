package com.music.spotui.debug

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

object PlaybackDebugLogger {

    data class LogEntry(
        val timestamp: Long = System.currentTimeMillis(),
        val tag: String,
        val message: String,
        val level: Level = Level.INFO
    ) {
        enum class Level { INFO, WARN, ERROR, DEBUG }
    }

    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val buffer = ConcurrentLinkedQueue<LogEntry>()
    private const val MAX_LOGS = 500

    private val _logsFlow = MutableStateFlow<List<LogEntry>>(emptyList())
    val logsFlow: StateFlow<List<LogEntry>> = _logsFlow.asStateFlow()

    val isDebugEnabledFlow = MutableStateFlow(false)

    fun initDebugMode(context: Context) {
        isDebugEnabledFlow.value = com.music.spotui.data.preferences.isDebugModeEnabled(context)
    }

    @Volatile var activeSongQuery: String = ""
    @Volatile var activeResolvedVideoId: String = ""
    @Volatile var activeClient: String = ""
    @Volatile var activeFormatItag: Int = 0
    @Volatile var activeMimeType: String = ""
    @Volatile var activeBitrate: Int = 0
    @Volatile var activeSource: String = ""
    @Volatile var activeQuality: String = ""
    @Volatile var lastExoPlayerState: String = "IDLE"
    @Volatile var lastExoPlayerError: String = "None"
    @Volatile var lastHttpProbeStatus: String = "N/A"

    fun log(tag: String, message: String, level: LogEntry.Level = LogEntry.Level.INFO) {
        val entry = LogEntry(tag = tag, message = message, level = level)
        buffer.add(entry)
        while (buffer.size > MAX_LOGS) {
            buffer.poll()
        }
        _logsFlow.value = buffer.toList()
        android.util.Log.println(
            when (level) {
                LogEntry.Level.ERROR -> android.util.Log.ERROR
                LogEntry.Level.WARN -> android.util.Log.WARN
                LogEntry.Level.DEBUG -> android.util.Log.DEBUG
                else -> android.util.Log.INFO
            },
            "SpotuiDebug/$tag",
            message
        )
    }

    fun d(tag: String, message: String) = log(tag, message, LogEntry.Level.DEBUG)
    fun i(tag: String, message: String) = log(tag, message, LogEntry.Level.INFO)
    fun w(tag: String, message: String) = log(tag, message, LogEntry.Level.WARN)
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        val extra = throwable?.let { "\n${it.stackTraceToString()}" } ?: ""
        log(tag, "$message$extra", LogEntry.Level.ERROR)
    }

    fun clear() {
        buffer.clear()
        _logsFlow.value = emptyList()
    }

    fun getFullReport(context: Context): String {
        val sb = StringBuilder()
        sb.appendLine("═══════════════════════════════════════════════")
        sb.appendLine("SPOTUI PLAYBACK DIAGNOSTIC REPORT")
        sb.appendLine("Generated: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        sb.appendLine("═══════════════════════════════════════════════")
        sb.appendLine()
        sb.appendLine("── DEVICE & ENVIRONMENT ──")
        sb.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        sb.appendLine("Android OS: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        sb.appendLine("Brand/Product: ${Build.BRAND} / ${Build.PRODUCT}")
        sb.appendLine("Hardware: ${Build.HARDWARE}")
        sb.appendLine("Network Online: ${com.music.spotui.data.network.NetworkMonitor.isOnlineNow(context)}")
        sb.appendLine()
        sb.appendLine("── ACTIVE PLAYBACK SNAPSHOT ──")
        sb.appendLine("Current Song Query: $activeSongQuery")
        sb.appendLine("Source: ${activeSource.ifBlank { "N/A" }}")
        sb.appendLine("Quality: ${activeQuality.ifBlank { "N/A" }}")
        sb.appendLine("Resolved Video ID: $activeResolvedVideoId")
        sb.appendLine("Active InnerTube Client: $activeClient")
        sb.appendLine("Active Format: itag=$activeFormatItag, mime=$activeMimeType, bitrate=$activeBitrate")
        sb.appendLine("ExoPlayer State: $lastExoPlayerState")
        sb.appendLine("Last Player Error: $lastExoPlayerError")
        sb.appendLine("Last HTTP Probe Status: $lastHttpProbeStatus")
        sb.appendLine()
        sb.appendLine("── DETAILED EVENT LOG (${buffer.size} entries) ──")
        buffer.forEach { entry ->
            val time = dateFormat.format(Date(entry.timestamp))
            val lvl = entry.level.name.padEnd(5)
            sb.appendLine("[$time] [$lvl] [${entry.tag}] ${entry.message}")
        }
        sb.appendLine("═══════════════════════════════════════════════")
        sb.appendLine("END OF REPORT")
        sb.appendLine("═══════════════════════════════════════════════")
        return sb.toString()
    }

    fun copyToClipboard(context: Context): String {
        val report = getFullReport(context)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Spotui Diagnostic Logs", report)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "Diagnostic logs copied to clipboard (${buffer.size} events)", Toast.LENGTH_LONG).show()
        return report
    }
}
