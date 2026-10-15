package com.example.service

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Isolated, non-blocking Telegram notification reporter.
 *
 * Rules:
 * 1. Credentials (token and idgroup) come strictly from SharedPreferences (downloaded from downapp/token.txt).
 * 2. If token or idgroup is blank/missing: sending is completely disabled and silent.
 * 3. Rate limit: at most ONE message (either success or failure across all platforms combined) every 24 hours.
 * 4. Last sent timestamp is updated in SharedPreferences ONLY after Telegram responds with HTTP 200.
 * 5. On HTTP 429 the reporter honors retry_after.
 * 6. Hard 2-second timeout, runs asynchronously in background, all errors swallowed.
 */
object TelegramReporter {
    private const val TAG = "TelegramReporter"

    private const val PREFS_NAME = "telegram_reporter_prefs"
    private const val KEY_TOKEN = "tg_token"
    private const val KEY_IDGROUP = "tg_idgroup"
    private const val KEY_LAST_SENT_MS = "tg_last_sent_ms"

    private const val RATE_LIMIT_WINDOW_MS = 24 * 60 * 60 * 1000L // 24 hours

    private const val DEFAULT_429_BACKOFF_MS = 60_000L
    private const val MAX_429_BACKOFF_MS = 15 * 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .writeTimeout(2, TimeUnit.SECONDS)
        .callTimeout(2, TimeUnit.SECONDS)
        .build()

    @Volatile private var appContext: Context? = null
    @Volatile private var blockedUntil: Long = 0L

    /** Initialize application context if available. */
    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
    }

    /**
     * Updates credentials in SharedPreferences (called by EngineUpdateManager when downapp/token.txt is fetched).
     * If token or idgroup is null or blank, credentials are wiped and sending is stopped.
     */
    fun updateCredentials(context: Context, token: String?, idgroup: String?) {
        init(context)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cleanToken = token?.trim().orEmpty()
        val cleanIdGroup = idgroup?.trim().orEmpty()
        if (cleanToken.isBlank() || cleanIdGroup.isBlank()) {
            prefs.edit().remove(KEY_TOKEN).remove(KEY_IDGROUP).apply()
            Log.d(TAG, "Telegram credentials cleared; reporting disabled")
        } else {
            prefs.edit()
                .putString(KEY_TOKEN, cleanToken)
                .putString(KEY_IDGROUP, cleanIdGroup)
                .apply()
            Log.d(TAG, "Telegram credentials updated successfully")
        }
    }

    /** No-op: remote config overrides are deprecated; credentials come only from token.txt. */
    @Suppress("UNUSED_PARAMETER")
    fun configure(botToken: String?, chatId: String?) {
        // Deliberately no-op
    }

    private fun getPrefs(context: Context?): SharedPreferences? {
        val ctx = context?.applicationContext ?: appContext ?: return null
        if (appContext == null) appContext = ctx
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun botToken(context: Context?): String {
        return getPrefs(context)?.getString(KEY_TOKEN, "")?.trim().orEmpty()
    }

    private fun chatId(context: Context?): String {
        return getPrefs(context)?.getString(KEY_IDGROUP, "")?.trim().orEmpty()
    }

    fun isConfigured(context: Context? = null): Boolean {
        return botToken(context).isNotBlank() && chatId(context).isNotBlank()
    }

    /**
     * Sends success report once a video is saved into the gallery successfully.
     */
    fun sendSuccess(platformName: String, context: Context? = null) {
        val message = """
            تم تنزيل فيدو بنجاح👍
            نوع المنصه : $platformName
        """.trimIndent()
        sendMessageAsync(message, context)
    }

    /**
     * Sends failure report only when extraction fails (not due to invalid link or offline).
     */
    fun sendFailure(platformName: String, reason: String, context: Context? = null) {
        val cleanReason = reason.trim().replace("\n", " ").replace("\r", " ").take(80)
        val message = """
            فشل استخراج رابط
            نوع المنصه : $platformName
            السبب : $cleanReason
        """.trimIndent()
        sendMessageAsync(message, context)
    }

    private fun sendMessageAsync(messageText: String, context: Context?) {
        val token = botToken(context)
        val chat = chatId(context)
        if (token.isBlank() || chat.isBlank()) {
            return
        }

        // Backoff if Telegram told us HTTP 429
        if (System.currentTimeMillis() < blockedUntil) {
            return
        }

        val prefs = getPrefs(context) ?: return
        val lastSent = prefs.getLong(KEY_LAST_SENT_MS, 0L)
        val now = System.currentTimeMillis()
        if (now - lastSent < RATE_LIMIT_WINDOW_MS) {
            // Rate limit active: 1 message per user per 24 hours
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
                    if (response.code == 200) {
                        // Crucial: Only persist timestamp after Telegram returns HTTP 200
                        prefs.edit().putLong(KEY_LAST_SENT_MS, System.currentTimeMillis()).apply()
                        Log.d(TAG, "Telegram report delivered with 200 OK")
                    } else if (response.code == 429) {
                        val retryAfterMs = parseRetryAfterMs(response.peekBody(1024).string())
                        blockedUntil = System.currentTimeMillis() + retryAfterMs
                        Log.d(TAG, "Telegram rate limit hit; paused for ${retryAfterMs / 1000}s")
                    } else {
                        Log.d(TAG, "Telegram report HTTP response: ${response.code}")
                    }
                }
            } catch (t: Throwable) {
                // Silently swallowed: never throw, never affect app or download execution.
                Log.d(TAG, "Telegram report suppressed: ${t.javaClass.simpleName}")
            }
        }
    }

    private fun parseRetryAfterMs(body: String): Long {
        return try {
            val seconds = JSONObject(body).optJSONObject("parameters")?.optLong("retry_after", 0L) ?: 0L
            if (seconds > 0) (seconds * 1000L).coerceAtMost(MAX_429_BACKOFF_MS) else DEFAULT_429_BACKOFF_MS
        } catch (_: Exception) {
            DEFAULT_429_BACKOFF_MS
        }
    }
}
