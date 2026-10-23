package com.example.data

import android.content.Context
import android.util.Log

class AdManager(context: Context) {
    private val prefs = context.getSharedPreferences("adsterra_prefs", Context.MODE_PRIVATE)

    companion object {
        const val ADSTERRA_SMARTLINK_URL = "https://asiafilm.org/4/f89ce863226eeb82a6e8daabbe6fd6ce"
        const val START_CLICK_TRIGGER_FREQUENCY = 3
        private const val KEY_START_CLICK_COUNT = "start_button_click_counter"
    }

    /**
     * Increments the "ابدا" button click counter and returns true on the 3rd click.
     * Click 1 -> false (no ad)
     * Click 2 -> false (no ad)
     * Click 3 -> true (triggers In-App WebView ad), resets counter to 0.
     */
    fun shouldShowInAppAd(): Boolean {
        return try {
            val count = prefs.getInt(KEY_START_CLICK_COUNT, 0) + 1
            if (count >= START_CLICK_TRIGGER_FREQUENCY) {
                prefs.edit().putInt(KEY_START_CLICK_COUNT, 0).apply()
                true
            } else {
                prefs.edit().putInt(KEY_START_CLICK_COUNT, count).apply()
                false
            }
        } catch (e: Exception) {
            Log.e("AdManager", "Error evaluating start click ad: ${e.message}")
            false
        }
    }
}
