package com.example.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.example.model.AdSettings

class AdManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("ad_manager_prefs", Context.MODE_PRIVATE)

    var downloadCount: Int
        get() = prefs.getInt(KEY_DOWNLOAD_COUNTER, 0)
        private set(value) = prefs.edit().putInt(KEY_DOWNLOAD_COUNTER, value).apply()

    fun resetCounter() {
        downloadCount = 0
    }

    /**
     * Checks if the ad should trigger before processing download.
     * Frequency logic:
     * Downloads #1, #2, #3: Process instantly.
     * Download #4: Resets counter to 0, returns true indicating ad was triggered.
     * The app displays the InAppAdDialog fullscreen modal.
     */
    fun onDownloadTrigger(adSettings: AdSettings): Boolean {
        if (!adSettings.enabled) {
            return false
        }

        val current = downloadCount + 1
        val triggerFrequency = if (adSettings.triggerFrequency > 0) adSettings.triggerFrequency else 3

        return if (current >= triggerFrequency) {
            resetCounter()
            true
        } else {
            downloadCount = current
            false
        }
    }

    companion object {
        private const val KEY_DOWNLOAD_COUNTER = "download_counter"
    }
}
