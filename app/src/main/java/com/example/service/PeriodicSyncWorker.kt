package com.example.service

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Silent background synchronisation of the Python engines every 12 hours.
 *
 * WorkManager only *schedules* this work: actual execution depends on system/battery/network
 * constraints, so it is not guaranteed to run at an exact wall-clock time.
 */
class PeriodicSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val manager = EngineUpdateManager(applicationContext)
        return try {
            val result = manager.checkAndUpdate()
            if (result.success) {
                Log.i(TAG, "Periodic engine sync ok (updated=${result.updated} v=${result.activeVersion})")
                Result.success()
            } else {
                Log.w(TAG, "Periodic engine sync failed: ${result.error}")
                // Transient failures (network/HTTP) are retried with the configured backoff.
                Result.retry()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Periodic engine sync crashed: ${e.message}")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "PeriodicSyncWorker"
        const val PERIODIC_WORK_NAME = "engine_periodic_sync"
        const val BOOT_CHECK_WORK_NAME = "engine_boot_check"
        private const val INTERVAL_HOURS = 12L

        /** Schedules the recurring 12h sync. Uses KEEP so no duplicate work is ever enqueued. */
        fun schedulePeriodic(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<PeriodicSyncWorker>(
                INTERVAL_HOURS, TimeUnit.HOURS,
                15, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    androidx.work.BackoffPolicy.EXPONENTIAL,
                    30, TimeUnit.MINUTES
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        /** Runs a single check at startup if the last successful check is older than 12h. */
        fun scheduleBootCheckIfStale(context: Context) {
            val manager = EngineUpdateManager(context)
            val elapsed = System.currentTimeMillis() - manager.lastCheckTime
            if (elapsed < TimeUnit.HOURS.toMillis(INTERVAL_HOURS)) return

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<PeriodicSyncWorker>()
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                BOOT_CHECK_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}
