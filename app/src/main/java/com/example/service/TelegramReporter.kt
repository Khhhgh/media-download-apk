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
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * Isolated, non-blocking Telegram notification reporter.
 *
 * Credential order: runtime [configure] override (remote config) -> build-time
 * `BuildConfig.TELEGRAM_*` (Secrets plugin / `.env`) -> built-in fallback below. `YOUR_*`
 * placeholders from `.env.example` count as "not set", so reporting works out of the box.
 *
 * Anti-flood design (Telegram allows ~30 msg/s per bot but only ~20 msg/min into one group, and
 * answers HTTP 429 beyond that):
 * 1. Failures are the primary signal; successes are sampled ([SUCCESS_SAMPLE_RATE]) in release
 *    builds and always sent in debug builds.
 * 2. Failures are sampled ([FAILURE_SAMPLE_RATE]), de-duplicated per platform+reason, and capped
 *    per device per hour, so one broken engine cannot make every phone report the same thing.
 * 3. On HTTP 429 the reporter goes completely quiet until Telegram's `retry_after` has elapsed.
 *
 * Strict fault isolation: never blocks the UI or download pipelines, hard 2-second call timeout,
 * every error swallowed, the token is never logged.
 */
object TelegramReporter {
    private const val TAG = "TelegramReporter"

    // Fallback credentials, used only when nothing else is configured.
    // WARNING: anything shipped inside an APK can be extracted by anyone. See the notes in the
    // hand-over message: rotate the token if it leaks and prefer remote config / a relay.
    private const val FALLBACK_BOT_TOKEN = "7956531264:AAF5-8IfFQ63265lqke2fS0PUAS8Ka7aaXU"
    private const val FALLBACK_CHAT_ID = "-1004208088275"

    /** Share of successful downloads reported in release builds (0.005 = 0.5%). */
    private const val SUCCESS_SAMPLE_RATE = 0.005

    /** Share of failures reported in release builds. Lower it as the user base grows. */
    private const val FAILURE_SAMPLE_RATE = 0.25

    /** Max reports one device may send per hour, and window in which an identical failure is not repeated. */
    private const val MAX_REPORTS_PER_HOUR = 3
    private const val HOUR_MS = 60 * 60 * 1000L
    private const val DEDUPE_WINDOW_MS = 60 * 60 * 1000L

    private const val DEFAULT_429_BACKOFF_MS = 60_000L
    private const val MAX_429_BACKOFF_MS = 15 * 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .writeTimeout(2, TimeUnit.SECONDS)
        .callTimeout(2, TimeUnit.SECONDS)
        .build()

    @Volatile private var overrideToken: String? = null
    @Volatile private var overrideChatId: String? = null

    /** Epoch millis until which Telegram told us to stop (HTTP 429). */
    @Volatile private var blockedUntil: Long = 0L

    private val sentTimestamps = ArrayDeque<Long>()
    private val recentFailures = HashMap<String, Long>()
    private val lock = Any()

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
        overrideToken?.takeIf { it.isNotBlank() }
            ?: sanitizeCredential(BuildConfig.TELEGRAM_BOT_TOKEN).ifBlank { FALLBACK_BOT_TOKEN }

    private fun chatId(): String =
        overrideChatId?.takeIf { it.isNotBlank() }
            ?: sanitizeCredential(BuildConfig.TELEGRAM_CHAT_ID).ifBlank { FALLBACK_CHAT_ID }

    fun isConfigured(): Boolean = botToken().isNotBlank() && chatId().isNotBlank()

    fun sendSuccess(platformName: String) {
        // Debug builds: every success. Release builds: a tiny random sample only.
        if (!BuildConfig.DEBUG && Random.nextDouble() >= SUCCESS_SAMPLE_RATE) return
        val message = """
            تم تنزيل فيدو بنجاح👍
            نوع المنصه : $platformName
        """.trimIndent()
        sendMessageAsync(message)
    }

    fun sendFailure(platformName: String, reason: String) {
        val cleanReason = reason.take(150).replace("\n", " ")
        if (!BuildConfig.DEBUG) {
            if (Random.nextDouble() >= FAILURE_SAMPLE_RATE) return
            if (!allowFailureReport("$platformName|${cleanReason.take(60)}")) return
        }
        val message = """
            تم فشل فيدو ⚠️
            نوع المنصه : $platformName
            السبب : $cleanReason
        """.trimIndent()
        sendMessageAsync(message)
    }

    /** Per-device dedupe + hourly cap for failure reports. */
    private fun allowFailureReport(key: String): Boolean = synchronized(lock) {
        val now = System.currentTimeMillis()
        recentFailures.entries.removeAll { now - it.value > DEDUPE_WINDOW_MS }
        if (recentFailures.containsKey(key)) return false
        while (sentTimestamps.isNotEmpty() && now - sentTimestamps.first() > HOUR_MS) {
            sentTimestamps.removeFirst()
        }
        if (sentTimestamps.size >= MAX_REPORTS_PER_HOUR) return false
        recentFailures[key] = now
        sentTimestamps.addLast(now)
        true
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
        // Telegram asked us to back off: drop silently, never queue.
        if (System.currentTimeMillis() < blockedUntil) return

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
                    if (response.code == 429) {
                        val retryAfterMs = parseRetryAfterMs(response.peekBody(1024).string())
                        blockedUntil = System.currentTimeMillis() + retryAfterMs
                        Log.d(TAG, "Telegram rate limit hit; reporting paused for ${retryAfterMs / 1000}s")
                    } else {
                        Log.d(TAG, "Telegram report sent with code: ${response.code}")
                    }
                }
            } catch (t: Throwable) {
                // Fully silent: never throw, never affect app execution. Never logs the token.
                Log.d(TAG, "Telegram reporting suppressed: ${t.javaClass.simpleName}")
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
