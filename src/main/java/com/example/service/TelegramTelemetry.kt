package com.example.service

/**
 * Thin telemetry facade.
 *
 * All credentials and delivery mechanics live in [TelegramReporter]; this object exists only to
 * keep the historical call sites working. No credentials are stored or decoded here any more —
 * the previously embedded values were removed because anything committed to source must be
 * treated as compromised.
 */
object TelegramTelemetry {

    fun configure(botToken: String?, chatId: String?) {
        TelegramReporter.configure(botToken, chatId)
    }

    fun reportExtraction(
        platform: String,
        success: Boolean,
        latencyMs: Long = 0,
        errorMessage: String? = null
    ) {
        if (success) {
            TelegramReporter.sendSuccess(platform)
        } else {
            TelegramReporter.sendFailure(platform, errorMessage ?: "Extraction failed")
        }
    }

    fun reportDownload(
        platform: String,
        success: Boolean,
        fileSizeBytes: Long = 0,
        errorMessage: String? = null
    ) {
        if (success) {
            TelegramReporter.sendSuccess(platform)
        } else {
            TelegramReporter.sendFailure(platform, errorMessage ?: "Download failed")
        }
    }
}
