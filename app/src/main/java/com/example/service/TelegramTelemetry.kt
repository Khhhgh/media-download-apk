package com.example.service

/**
 * Telemetry facade.
 *
 * Success reports are handled exclusively by [FileDownloadService] after a video is saved to gallery.
 * Failure reports for extraction are handled directly by [DownloadEngine].
 * Methods here remain for API compatibility without triggering unwanted Telegram notifications.
 */
object TelegramTelemetry {

    @Suppress("UNUSED_PARAMETER")
    fun configure(botToken: String?, chatId: String?) {
        // No-op
    }

    @Suppress("UNUSED_PARAMETER")
    fun reportExtraction(
        platform: String,
        success: Boolean,
        latencyMs: Long = 0,
        errorMessage: String? = null
    ) {
        // Extraction success is intentionally never reported to Telegram.
        // Extraction failure is handled directly in DownloadEngine with strict offline/link validation.
    }

    @Suppress("UNUSED_PARAMETER")
    fun reportDownload(
        platform: String,
        success: Boolean,
        fileSizeBytes: Long = 0,
        errorMessage: String? = null
    ) {
        // Download success is reported exclusively by FileDownloadService after saving to gallery.
        // Download failures are never reported to Telegram per system requirements.
    }
}
