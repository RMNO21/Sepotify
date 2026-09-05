package com.music.spotui.worker

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.music.spotui.innertube.InnerTubeAuthManager
import java.util.concurrent.TimeUnit

/**
 * Background WorkManager worker for periodic InnerTube authentication and header refresh.
 * Ensures YouTube cookies and visitor data stay valid in the background.
 */
class InnerTubeAuthWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Log.d(TAG, "Starting periodic InnerTube authentication validation worker")
        return try {
            val success = InnerTubeAuthManager.validateAndRefreshAuth(applicationContext, force = false)
            if (success) {
                Log.d(TAG, "InnerTube auth headers successfully validated in background worker")
                Result.success()
            } else {
                Log.w(TAG, "InnerTube auth validation encountered issue; fallback applied")
                Result.success()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in InnerTubeAuthWorker", e)
            if (runAttemptCount < 3) Result.retry() else Result.success()
        }
    }

    companion object {
        private const val TAG = "InnerTubeAuthWorker"
        private const val WORK_NAME = "innertube_auth_refresh_periodic"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<InnerTubeAuthWorker>(
                1, TimeUnit.HOURS,
                15, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
            Log.d(TAG, "Scheduled periodic InnerTube authentication refresh worker")
        }
    }
}
