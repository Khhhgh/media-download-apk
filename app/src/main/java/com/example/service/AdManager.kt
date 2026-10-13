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
     * Download #4: Resets counter to 0, opens direct ad link in external browser,
     * returns true indicating ad was triggered (download continues concurrently).
     */
    fun onDownloadTrigger(adSettings: AdSettings): Boolean {
        if (!adSettings.enabled) {
            return false
        }

        val current = downloadCount + 1
        val triggerFrequency = if (adSettings.triggerFrequency > 0) adSettings.triggerFrequency else 3

        return if (current >= triggerFrequency) {
            resetCounter()
            openAdInBrowser(adSettings.url)
            true
        } else {
            downloadCount = current
            false
        }
    }

    fun openAdInBrowser(adUrl: String) {
        try {
            val finalUrl = adUrl.ifBlank { "https://asiafilm.org/4/f89ce863226eeb82a6e8daabbe6fd6ce" }
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(finalUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.d("AdManager", "Triggered ad URL: $finalUrl")
        } catch (e: Exception) {
            Log.e("AdManager", "Failed to launch ad intent", e)
        }
    }

    companion object {
        private const val KEY_DOWNLOAD_COUNTER = "download_counter"
    }
}
