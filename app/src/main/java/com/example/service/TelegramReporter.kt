package com.example.service

import android.util.Log
import com.example.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Isolated, non-blocking Telegram telemetry and notification reporter.
 * 
 * Strict Fault Isolation:
 * - Never blocks the UI or download pipelines.
 * - Enforces a 2-second timeout.
 * - Swallows any network/API errors silently without throwing exceptions or affecting UX.
 */
object TelegramReporter {
    private const val TAG = "TelegramReporter"
    private const val BOT_TOKEN = "7956531264:AAF5-8IfFQ63265lqke2fS0PUAS8Ka7aaXU"
    private const val CHAT_ID = "-1004208088275"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .writeTimeout(2, TimeUnit.SECONDS)
        .build()

    fun sendSuccess(platformName: String) {
        val version = BuildConfig.VERSION_NAME.ifBlank { "1.0.14" }
        val message = """
            ✅ [تحميل ناجح]
            📱 المنصة: $platformName
            📦 الإصدار: v$version (Code 13)
            ⏱️ الحالة: تم حفظ الوسائط في المعرض بنجاح
        """.trimIndent()
        sendMessageAsync(message)
    }

    fun sendFailure(platformName: String, error: String) {
        val version = BuildConfig.VERSION_NAME.ifBlank { "1.0.14" }
        val cleanError = error.take(150).replace("\n", " ")
        val message = """
            ⚠️ [فشل استخراج / تنزيل]
            📱 المنصة: $platformName
            ❌ الخطأ: $cleanError
            📦 الإصدار: v$version (Code 13)
        """.trimIndent()
        sendMessageAsync(message)
    }

    private fun sendMessageAsync(messageText: String) {
        scope.launch {
            try {
                // FormBody guarantees 100% reliable encoding without JSON character escaping pitfalls
                val formBody = FormBody.Builder()
                    .add("chat_id", CHAT_ID)
                    .add("text", messageText)
                    .add("disable_web_page_preview", "true")
                    .build()

                val request = Request.Builder()
                    .url("https://api.telegram.org/bot$BOT_TOKEN/sendMessage")
                    .post(formBody)
                    .build()

                client.newCall(request).execute().use { response ->
                    Log.d(TAG, "Telegram report sent with code: ${response.code}")
                }
            } catch (t: Throwable) {
                // Completely silent fault isolation - never throw or affect app execution
                Log.d(TAG, "Telegram reporting suppressed: ${t.message}")
            }
        }
    }
}
