package com.example

import android.app.Application
import com.example.service.PeriodicSyncWorker

/**
 * Application entry point.
 *
 * Schedules the silent 12-hour engine synchronisation. Python is intentionally *not* started
 * here: it is started lazily on first extraction/setup to keep cold start fast.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            PeriodicSyncWorker.schedulePeriodic(this)
            PeriodicSyncWorker.scheduleBootCheckIfStale(this)
        } catch (e: Exception) {
            android.util.Log.w("App", "Unable to schedule engine sync: ${e.message}")
        }
    }
}
