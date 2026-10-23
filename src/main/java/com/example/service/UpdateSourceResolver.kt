package com.example.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Dynamic pointer resolver.
 *
 * The app NEVER contains a GitHub / jsDelivr address. The only hard-coded address is the GitLab
 * pointer file [POINTER_URL], whose single line holds the *current* project location. If the GitHub
 * account is ever suspended, edit `source_url.txt` on GitLab and every installed app follows.
 *
 * Accepted pointer content (first non-empty, non-`#` line):
 *  - `https://github.com/<owner>/<repo>` (optionally `/tree/<branch>`; default branch `main`):
 *    converted at runtime to the jsDelivr CDN base `https://cdn.jsdelivr.net/gh/<owner>/<repo>@<branch>/downapp`
 *    so that GitHub itself never receives per-user traffic.
 *  - any other `https://` URL: used as-is as the directory that contains `version.txt`,
 *    `manifest.json`, `app_version.json` and the engine files (trailing `/` removed).
 *
 * Failure policy (always silent): pointer unreachable / blank / invalid -> fall back to the last
 * base URL that was proven valid; none stored -> `null`, and callers keep the bundled engines.
 */
object UpdateSourceResolver {

    private const val TAG = "UpdateSourceResolver"

    /** The one and only fixed address: a GitLab raw file that tells the app where the project is now. */
    const val POINTER_URL = "https://gitlab.com/msms22315/media-download-apk/-/raw/main/source_url.txt"

    private const val PREFS = "update_source_prefs"
    private const val KEY_LAST_VALID = "last_valid_base_url"

    /** One pointer request per process every [MEMORY_TTL_MS]; engine + app checks share it. */
    private const val MEMORY_TTL_MS = 30 * 60 * 1000L
    private const val MAX_POINTER_BYTES = 2048L

    private val mutex = Mutex()
    @Volatile private var memoryBase: String? = null
    @Volatile private var memoryAt: Long = 0L

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /**
     * Returns the base URL to use for this check, or `null` when no source is known at all.
     * Never throws.
     */
    suspend fun getBaseUrl(context: Context): String? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val now = System.currentTimeMillis()
            val cached = memoryBase
            if (cached != null && now - memoryAt < MEMORY_TTL_MS) return@withLock cached

            val fromPointer = try {
                fetchPointer()?.let { normalize(it) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "Pointer unreachable: ${e.message}")
                null
            }

            if (fromPointer != null) {
                memoryBase = fromPointer
                memoryAt = now
                fromPointer
            } else {
                // Pointer failed: reuse the last proven-good source (not memory-cached, so the
                // next check tries the pointer again).
                lastValidBaseUrl(context)
            }
        }
    }

    /** Last base URL that answered a real update request successfully (survives restarts). */
    fun lastValidBaseUrl(context: Context): String? =
        prefs(context).getString(KEY_LAST_VALID, null)?.takeIf { it.isNotBlank() }

    /** Call only after the base URL served a valid `version.txt` / `app_version.json`. */
    fun markValid(context: Context, baseUrl: String) {
        if (baseUrl.isBlank()) return
        if (prefs(context).getString(KEY_LAST_VALID, null) != baseUrl) {
            prefs(context).edit().putString(KEY_LAST_VALID, baseUrl).apply()
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun fetchPointer(): String? {
        val request = Request.Builder()
            .url(POINTER_URL)
            .header("User-Agent", EngineUpdateManager.USER_AGENT)
            .header("Cache-Control", "no-cache")
            .header("Pragma", "no-cache")
            .cacheControl(CacheControl.FORCE_NETWORK)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.d(TAG, "Pointer HTTP ${response.code}")
                return null
            }
            return response.peekBody(MAX_POINTER_BYTES).string().trim()
        }
    }

    /** Turns the pointer text into a base URL, or `null` when it is unusable. Visible for tests. */
    internal fun normalize(raw: String): String? {
        val line = raw.removePrefix("\uFEFF")
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
            ?: return null

        val url = line.toHttpUrlOrNull() ?: return null
        if (url.scheme != "https") return null

        val host = url.host.lowercase()
        if (host == "github.com" || host == "www.github.com") {
            val segments = url.pathSegments.filter { it.isNotEmpty() }
            if (segments.size < 2) return null
            val owner = segments[0]
            val repo = segments[1].removeSuffix(".git")
            if (owner.isBlank() || repo.isBlank()) return null
            val branch = if (segments.size >= 4 && segments[2] == "tree") segments[3] else "main"
            return "https://cdn.jsdelivr.net/gh/$owner/$repo@$branch/downapp"
        }
        return url.toString().trimEnd('/')
    }
}
