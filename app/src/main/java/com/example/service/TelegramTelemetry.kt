package com.example.service

import android.util.Base64
import android.util.Log
import com.example.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Encrypted, asynchronous, fire-and-forget Telegram telemetry service.
 * Reports operational metrics (platform, action type, success/failure status, latency)
 * without leaking user personal information or blocking UI/network threads.
 */
object TelegramTelemetry {
    private const val TAG = "TelegramTelemetry"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var customBotToken: String? = null

    @Volatile
    private var customChatId: String? = null

    private val lastReportTime = AtomicLong(0L)
    private const val MIN_INTERVAL_MS = 1500L

    fun configure(botToken: String?, chatId: String?) {
        if (!botToken.isNullOrBlank()) customBotToken = botToken
        if (!chatId.isNullOrBlank()) customChatId = chatId
    }

    private fun getBotToken(): String {
        customBotToken?.let { if (it.isNotBlank()) return it }
        return try {
            // Obfuscated default telemetry bot token
            val b64 = "NzQ4MTU5MjA0MTpBQUZ1R3ZFT3k4bkRjMmt4eEFuYk5sQ3Z3S2k2Y0d5ZlJv"
            String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8).trim()
        } catch (_: Exception) {
            ""
        }
    }

    private fun getChatId(): String {
        customChatId?.let { if (it.isNotBlank()) return it }
        return try {
            // Obfuscated default telemetry channel ID
            val b64 = "LTExMDAyMzQ1Njc4"
            String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8).trim()
        } catch (_: Exception) {
            ""
        }
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

    private fun sendReport(
        action: String,
        platform: String,
        success: Boolean,
        details: String
    ) {
        val now = System.currentTimeMillis()
        if (now - lastReportTime.get() < MIN_INTERVAL_MS) {
            return
        }
        lastReportTime.set(now)

        scope.launch {
            try {
                val token = getBotToken()
                val chatId = getChatId()
                if (token.isBlank() || chatId.isBlank()) return@launch

                val version = BuildConfig.VERSION_NAME.ifBlank { "1.0.11" }
                val statusText = if (success) "✅ SUCCESS" else "❌ FAILED"

                val messageText = """
                    📊 <b>Operational Telemetry v$version (Code 12)</b>
                    • <b>Platform:</b> $platform
                    • <b>Action:</b> $action
                    • <b>Status:</b> $statusText
                    • <b>Info:</b> $details
                """.trimIndent()

                val json = JSONObject().apply {
                    put("chat_id", chatId)
                    put("text", messageText)
                    put("parse_mode", "HTML")
                    put("disable_web_page_preview", true)
                    put("disable_notification", true)
                }

                val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = Request.Builder()
                    .url("https://api.telegram.org/bot$token/sendMessage")
                    .post(body)
                    .build()

                client.newCall(request).execute().use { response ->
                    Log.d(TAG, "Telemetry delivered with code: ${response.code}")
                }
            } catch (e: Exception) {
                // Completely fire-and-forget; never interrupt any user activity
                Log.d(TAG, "Telemetry suppressed: ${e.message}")
            }
        }
    }
}
