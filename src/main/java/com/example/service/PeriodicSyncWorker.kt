package com.example.service

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Silent background synchronisation of the Python engines every 3 days.
 *
 * WorkManager only *schedules* this work: actual execution depends on system/battery/network
 * constraints, so it is not guaranteed to run at an exact wall-clock time. The in-app silent
 * check at every launch ([com.example.ui.MainViewModel.silentEngineCheck]) covers the gaps.
 *
 * (The class keeps its historical name so jobs already persisted by older builds still resolve.)
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
                // Transient failures are retried with backoff, but never hammer the CDN.
                if (runAttemptCount >= MAX_ATTEMPTS) Result.success() else Result.retry()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Periodic engine sync crashed: ${e.message}")
            if (runAttemptCount >= MAX_ATTEMPTS) Result.success() else Result.retry()
        }
    }

    companion object {
        private const val TAG = "PeriodicSyncWorker"

        /** Unique-work name of the 3-day schedule. */
        const val PERIODIC_WORK_NAME = "EnginePeriodicSync"

        /** Unique-work names used by older builds (12h schedule / boot check). */
        private const val LEGACY_PERIODIC_NAME = "engine_periodic_sync"
        private const val LEGACY_BOOT_CHECK_NAME = "engine_boot_check"

        private const val INTERVAL_DAYS = 3L
        private const val MAX_ATTEMPTS = 3

        /**
         * Schedules the recurring 3-day sync. KEEP means no duplicate work is ever enqueued;
         * the legacy 12h job from older builds is cancelled so upgraded installs don't keep it.
         */
        fun schedulePeriodic(context: Context) {
            val workManager = WorkManager.getInstance(context)
            workManager.cancelUniqueWork(LEGACY_PERIODIC_NAME)
            workManager.cancelUniqueWork(LEGACY_BOOT_CHECK_NAME)

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<PeriodicSyncWorker>(
                INTERVAL_DAYS, TimeUnit.DAYS,
                6, TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()
            workManager.enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
