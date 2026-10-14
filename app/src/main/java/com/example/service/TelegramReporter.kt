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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Isolated, non-blocking Telegram notification reporter.
 *
 * Credentials are **never** hard-coded. They are injected at build time through the Secrets plugin
 * (`BuildConfig.TELEGRAM_BOT_TOKEN` / `TELEGRAM_CHAT_ID`) and may be overridden at runtime by
 * [configure] using the remote configuration. If no credentials are configured, reporting is a
 * silent no-op.
 *
 * Strict fault isolation:
 * - Never blocks the UI or download pipelines.
 * - 2-second connect/read/write timeouts.
 * - Swallows every network/API error; Telegram failures can never fail a download or an update.
 */
object TelegramReporter {
    private const val TAG = "TelegramReporter"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .writeTimeout(2, TimeUnit.SECONDS)
        .build()

    @Volatile private var overrideToken: String? = null
    @Volatile private var overrideChatId: String? = null

    private val lastCredentialWarning = AtomicBoolean(false)

    /** Overrides credentials at runtime (e.g. from the remote configuration). Blank values are ignored. */
    fun configure(botToken: String?, chatId: String?) {
        if (!botToken.isNullOrBlank()) overrideToken = botToken.trim()
        if (!chatId.isNullOrBlank()) overrideChatId = chatId.trim()
    }

    /**
     * Placeholders shipped in `.env.example` must never be treated as real credentials. The value is
     * considered unset when it is blank or still a `YOUR_*` placeholder.
     */
    private fun sanitizeCredential(value: String?): String {
        val trimmed = value?.trim().orEmpty()
        return if (trimmed.isBlank() || trimmed.startsWith("YOUR_")) "" else trimmed
    }

    private fun botToken(): String =
        overrideToken?.takeIf { it.isNotBlank() } ?: sanitizeCredential(BuildConfig.TELEGRAM_BOT_TOKEN)

    private fun chatId(): String =
        overrideChatId?.takeIf { it.isNotBlank() } ?: sanitizeCredential(BuildConfig.TELEGRAM_CHAT_ID)

    fun isConfigured(): Boolean = botToken().isNotBlank() && chatId().isNotBlank()

    fun sendSuccess(platformName: String) {
        val message = """
            تم تنزيل فيدو بنجاح👍
            نوع المنصه : $platformName
        """.trimIndent()
        sendMessageAsync(message)
    }

    fun sendFailure(platformName: String, reason: String) {
        val cleanReason = reason.take(150).replace("\n", " ")
        val message = """
            تم فشل فيدو ⚠️
            نوع المنصه : $platformName
            السبب : $cleanReason
        """.trimIndent()
        sendMessageAsync(message)
    }

    private fun sendMessageAsync(messageText: String) {
        val token = botToken()
        val chat = chatId()
        if (token.isBlank() || chat.isBlank()) {
            if (lastCredentialWarning.compareAndSet(false, true)) {
                Log.d(TAG, "Telegram reporting disabled (no credentials configured)")
            }
            return
        }

        scope.launch {
            try {
                val formBody = FormBody.Builder()
                    .add("chat_id", chat)
                    .add("text", messageText)
                    .add("disable_web_page_preview", "true")
                    .build()

                val request = Request.Builder()
                    .url("https://api.telegram.org/bot$token/sendMessage")
                    .post(formBody)
                    .build()

                client.newCall(request).execute().use { response ->
                    Log.d(TAG, "Telegram report sent with code: ${response.code}")
                }
            } catch (t: Throwable) {
                // Fully silent: never throw, never affect app execution. Never logs the token.
                Log.d(TAG, "Telegram reporting suppressed: ${t.message}")
            }
        }
    }
}
