package com.music.spotui.data.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

object AppUpdateInstaller {

    private var isDownloading = false

    fun startUpdate(context: Context, info: UpdateChecker.UpdateInfo) {
        val downloadUrl = info.downloadUrl
        if (!downloadUrl.endsWith(".apk", ignoreCase = true)) {
            // Open browser release page
            runCatching {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl)).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            }
            return
        }

        if (isDownloading) {
            Toast.makeText(context, "Update download is already in progress...", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val fileName = "spotui-update-${info.version}.apk"
            val targetFile = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
            if (targetFile.exists()) {
                targetFile.delete()
            }

            val request = DownloadManager.Request(Uri.parse(downloadUrl))
                .setTitle("SpotUI Update ${info.version}")
                .setDescription("Downloading latest update to install over current version...")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationUri(Uri.fromFile(targetFile))
                .setMimeType("application/vnd.android.package-archive")

            val downloadId = dm.enqueue(request)
            isDownloading = true
            Toast.makeText(context, "Downloading update... It will prompt to install automatically.", Toast.LENGTH_LONG).show()

            val onComplete = object : BroadcastReceiver() {
                override fun onReceive(ctxt: Context, intent: Intent) {
                    val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                    if (id == downloadId) {
                        isDownloading = false
                        runCatching { ctxt.unregisterReceiver(this) }
                        installApk(ctxt, targetFile)
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(
                    onComplete,
                    IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                    Context.RECEIVER_EXPORTED
                )
            } else {
                context.registerReceiver(
                    onComplete,
                    IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
                )
            }
        } catch (e: Exception) {
            isDownloading = false
            // Fallback to browser
            runCatching {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl)).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            }
        }
    }

    fun installApk(context: Context, file: File) {
        if (!file.exists()) return
        try {
            val contentUri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(installIntent)
        } catch (e: Exception) {
            Toast.makeText(context, "Could not launch package installer: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
