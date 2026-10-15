package com.example.service

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Progress snapshot emitted while installing/updating the engines. */
data class EngineUpdateProgress(
    val stage: String,
    val percent: Int,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val bytesPerSecond: Long = 0L
)

data class EngineUpdateResult(
    val success: Boolean,
    val updated: Boolean,
    val activeVersion: Int,
    val error: String? = null
)

/**
 * Over-the-air (OTA) sync of the Python engine tree.
 *
 * Source resolution (no hard-coded GitHub / jsDelivr address):
 * - The base URL comes from [UpdateSourceResolver]: a tiny GitLab pointer file names the current
 *   project, which is then read through the jsDelivr CDN. `api.github.com` and
 *   `raw.githubusercontent.com` are never contacted by the app.
 * - Pointer unreachable -> last valid base URL (SharedPreferences). Nothing known, or the source
 *   answers 404 / garbage / is offline -> the update is skipped silently and the bundled/local
 *   engines keep running. No error is ever shown to the user.
 *
 * CDN friendliness (1M-user scale):
 * - No per-request timestamps (`?t=`): the natural, stable CDN cache is used. Freshness is
 *   guaranteed by the CI purge step that runs on every push to `downapp/**`.
 * - Engine files are requested as `<file>?v=<remoteVersion>` so each release has its own URL.
 *
 * Safety rules:
 * - Download goes to `cacheDir/temp_engines`; nothing touches the live tree until every file
 *   is downloaded and (when a consistent manifest exists) SHA-256 verified.
 * - Activation is a same-directory rename swap with rollback to the previous known-good tree.
 * - HASH_MISMATCH / 5xx / 429 during CDN propagation are never shown to the user: the previous
 *   trusted tree stays active and a background retry (WorkManager, bounded back-off) is queued.
 */
class EngineUpdateManager(private val context: Context) {

    private val tag = "EngineUpdateManager"
    private val prefs = context.getSharedPreferences("engine_update_prefs", Context.MODE_PRIVATE)

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Last base URL proven valid (empty when none yet). Resolved dynamically via the GitLab pointer. */
    val updateBaseUrl: String get() = UpdateSourceResolver.lastValidBaseUrl(context).orEmpty()

    val lastCheckTime: Long get() = prefs.getLong(KEY_LAST_CHECK, 0L)
    val lastUpdateTime: Long get() = prefs.getLong(KEY_LAST_UPDATE, 0L)

    fun recordCheck() = prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()

    /**
     * Checks the CDN and installs a newer engine version when `remote > local`.
     *
     * @param force install the remote tree even when it is not newer (used by first-run
     *              provisioning so a fresh install is always taken from the network).
     */
    suspend fun checkAndUpdate(
        onProgress: (suspend (EngineUpdateProgress) -> Unit)? = null,
        force: Boolean = false
    ): EngineUpdateResult = withContext(Dispatchers.IO) {
        updateMutex.withLock {
            EngineStorage.recoverInterruptedSwap(context)
            val localVersion = EngineStorage.readActiveVersion(context)

            try {
                onProgress?.invoke(EngineUpdateProgress("التحقق من أحدث إصدار", 2))
                val baseUrl = UpdateSourceResolver.getBaseUrl(context)
                if (baseUrl == null) {
                    Log.i(tag, "No update source (pointer unreachable, nothing cached); using local engines")
                    return@withLock silentSkip(localVersion, force, "لا يوجد مصدر تحديث")
                }
                val remoteVersion = fetchRemoteVersion(baseUrl)
                if (remoteVersion <= 0) {
                    return@withLock silentSkip(localVersion, force, "رقم إصدار غير صالح من المصدر")
                }
                // The source answered with a valid version: remember it for pointer outages.
                UpdateSourceResolver.markValid(context, baseUrl)

                val needsInstall = force || remoteVersion > localVersion || !EngineStorage.isInstalled(context)
                if (!needsInstall) {
                    Log.i(tag, "Engines up to date (local=$localVersion remote=$remoteVersion)")
                    prefs.edit().putInt(KEY_BG_RETRIES, 0).apply()
                    recordCheck()
                    onProgress?.invoke(EngineUpdateProgress("لا توجد تحديثات جديدة", 100))
                    return@withLock EngineUpdateResult(true, false, localVersion, null)
                }

                val entries = resolveEntries(baseUrl, remoteVersion)
                val knownTotal = entries.sumOf { it.size.coerceAtLeast(0L) }

                val staging = EngineStorage.stagingDir(context)
                if (staging.exists()) staging.deleteRecursively()
                if (!staging.mkdirs() && !staging.isDirectory) {
                    return@withLock EngineUpdateResult(false, false, localVersion, "تعذر إنشاء مجلد مؤقت")
                }
                val usable = staging.usableSpace
                if (knownTotal > 0 && usable in 1L until knownTotal * 2) {
                    cleanupStaging()
                    return@withLock EngineUpdateResult(false, false, localVersion, "مساحة التخزين غير كافية")
                }

                var downloaded = 0L
                var lastEmit = 0L
                val startTime = System.currentTimeMillis()
                val stagingRoot = staging.canonicalPath + File.separator

                for ((index, entry) in entries.withIndex()) {
                    val target = File(staging, entry.name)
                    // Path-traversal guard: a manifest entry must never escape the staging dir.
                    if (!target.canonicalPath.startsWith(stagingRoot)) {
                        throw IOException("مسار ملف غير آمن: ${entry.name}")
                    }
                    target.parentFile?.mkdirs()

                    var serverRetries = 0
                    var hashRetries = 0
                    while (true) {
                        val before = downloaded
                        try {
                            downloadToFile(fileUrl(baseUrl, entry.name, remoteVersion, hashRetries), target) { delta ->
                                downloaded += delta
                                val now = System.currentTimeMillis()
                                if (onProgress != null && now - lastEmit >= 120L) {
                                    lastEmit = now
                                    val elapsed = (now - startTime).coerceAtLeast(1L)
                                    val percent = if (knownTotal > 0) {
                                        10 + ((downloaded * 70L) / knownTotal).toInt().coerceAtMost(70)
                                    } else {
                                        10 + (index * 70) / entries.size
                                    }
                                    onProgress(
                                        EngineUpdateProgress(
                                            stage = "تنزيل المحركات (${index + 1}/${entries.size})",
                                            percent = percent,
                                            downloadedBytes = downloaded,
                                            totalBytes = knownTotal,
                                            bytesPerSecond = (downloaded * 1000L) / elapsed
                                        )
                                    )
                                }
                            }
                        } catch (e: IOException) {
                            // Transient CDN 5xx / 429 while the edge syncs: wait quietly and retry.
                            if (isServerBusy(e) && serverRetries < MAX_SERVER_RETRIES) {
                                downloaded = before
                                serverRetries++
                                delay(1_500L * serverRetries)
                                continue
                            }
                            throw e
                        }
                        if (!target.isFile || target.length() == 0L) {
                            throw IOException("ملف فارغ أو ناقص: ${entry.name}")
                        }
                        val expected = entry.sha256
                        if (expected == null || sha256(target).equals(expected, ignoreCase = true)) break
                        // Likely a stale CDN edge (purge still propagating): retry via a fresh cache key.
                        downloaded = before
                        if (++hashRetries > MAX_HASH_RETRIES) throw IOException(HASH_MISMATCH + entry.name)
                        delay(1_000L * hashRetries)
                    }
                }

                onProgress?.invoke(EngineUpdateProgress("التحقق من سلامة الملفات", 82))
                if (!File(staging, "runner.py").isFile) {
                    throw IOException("ملف ناقص: runner.py")
                }
                // The version marker is written from the freshly fetched number (never from a
                // possibly cached copy) and only now that every file is valid.
                File(staging, EngineStorage.ENGINE_VERSION_FILE).writeText(remoteVersion.toString())

                onProgress?.invoke(EngineUpdateProgress("تفعيل الإصدار الجديد", 90))
                activate(staging)

                // Results cached from the previous engines must not mask the new ones.
                ScaleArchitecture.mediaCache.clear()

                prefs.edit()
                    .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
                    .putLong(KEY_LAST_UPDATE, System.currentTimeMillis())
                    .putInt(KEY_BG_RETRIES, 0)
                    .apply()
                Log.i(tag, "Engines updated: v$localVersion -> v$remoteVersion")
                onProgress?.invoke(EngineUpdateProgress("اكتمل التحديث", 100))
                EngineUpdateResult(true, true, remoteVersion, null)
            } catch (e: kotlinx.coroutines.CancellationException) {
                cleanupStaging()
                throw e
            } catch (e: Exception) {
                // Silent by design: the live (previous trusted) tree was never touched.
                Log.w(tag, "Engine update failed, keeping current version: ${friendlyError(e)}")
                cleanupStaging()
                recordCheck()
                if (isTransient(e)) scheduleBackgroundRetry()
                silentSkip(EngineStorage.readActiveVersion(context), force, friendlyError(e))
            }
        }
    }

    /** Reads the live version number (a ~1-byte request served from the stable CDN cache). */
    suspend fun fetchRemoteVersion(baseUrl: String): Int = withContext(Dispatchers.IO) {
        val body = httpGet("$baseUrl/${EngineStorage.ENGINE_VERSION_FILE}")
        body.trim().lineSequence().firstOrNull()?.trim()?.toIntOrNull() ?: 0
    }

    /**
     * Result for every "nothing to do / could not update" path. On a device that already has a
     * working tree this is a quiet success (never a user-visible failure); only a first-run
     * provisioning (`force`) or a device without any tree reports `false`, so the caller can fall
     * back to the bundled engines.
     */
    private fun silentSkip(localVersion: Int, force: Boolean, reason: String?): EngineUpdateResult {
        val ok = !force && EngineStorage.isInstalled(context)
        return EngineUpdateResult(ok, false, localVersion, reason)
    }

    /**
     * File list for a release. Prefers the manifest (gives SHA-256 verification) but only when it
     * describes exactly this release; a missing/stale/invalid manifest must never block updates,
     * so the built-in list is used (without hashes) instead.
     */
    private suspend fun resolveEntries(baseUrl: String, remoteVersion: Int): List<ManifestEntry> {
        val fromManifest = try {
            fetchManifestEntries(baseUrl, remoteVersion)
        } catch (e: Exception) {
            Log.w(tag, "Manifest unavailable (${e.message}); using built-in file list")
            null
        }
        return fromManifest ?: FALLBACK_FILES.map { ManifestEntry(it, null, -1L) }
    }

    private suspend fun fetchManifestEntries(baseUrl: String, remoteVersion: Int): List<ManifestEntry>? {
        val body = httpGet("$baseUrl/${EngineStorage.ENGINE_MANIFEST_FILE}")
        val json = JSONObject(body)
        val manifestVersion = json.optInt("version", -1)
        if (manifestVersion != remoteVersion) {
            Log.w(tag, "manifest.json is v$manifestVersion but version.txt is v$remoteVersion; ignoring manifest")
            return null
        }
        val files = json.optJSONObject("files") ?: return null
        val out = mutableListOf<ManifestEntry>()
        val names = files.keys()
        while (names.hasNext()) {
            val name = names.next()
            if (name in MANIFEST_EXCLUDED) continue
            val obj = files.optJSONObject(name) ?: return null
            val sha = obj.optString("sha256", "")
            if (sha.isBlank()) return null
            out.add(ManifestEntry(name, sha, obj.optLong("size", -1L)))
        }
        return out.takeIf { it.isNotEmpty() }
    }

    /** Same URL for every user of a release (CDN-friendly); `r=N` only after a hash mismatch. */
    private fun fileUrl(baseUrl: String, name: String, remoteVersion: Int, hashRetry: Int): String {
        val base = "$baseUrl/$name?v=$remoteVersion"
        return if (hashRetry == 0) base else "$base&r=$hashRetry"
    }

    private fun newRequest(url: String): Request = Request.Builder()
        .url(url)
        .header("User-Agent", USER_AGENT)
        .header("Cache-Control", "no-cache")
        .header("Pragma", "no-cache")
        .cacheControl(CacheControl.FORCE_NETWORK)
        .build()

    private suspend fun httpGet(url: String): String {
        var attempt = 0
        while (true) {
            try {
                client.newCall(newRequest(url)).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    return response.body?.string() ?: throw IOException("Empty response")
                }
            } catch (e: IOException) {
                if (isServerBusy(e) && attempt < MAX_SERVER_RETRIES) {
                    attempt++
                    delay(1_500L * attempt)
                    continue
                }
                throw e
            }
        }
    }

    private suspend fun downloadToFile(url: String, target: File, onBytes: suspend (Long) -> Unit) {
        client.newCall(newRequest(url)).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
            val body = response.body ?: throw IOException("Empty body for $url")
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        onBytes(read.toLong())
                    }
                }
            }
        }
    }

    /**
     * Atomic swap. The validated staging tree is first moved next to the live one (same parent
     * directory, so the final rename is atomic), then: active -> backup, incoming -> active.
     * On failure the previous tree is restored.
     */
    private fun activate(staging: File) {
        val active = EngineStorage.activeEnginesDir(context)
        val incoming = EngineStorage.incomingEnginesDir(context)
        val backup = EngineStorage.backupEnginesDir(context)

        if (incoming.exists()) incoming.deleteRecursively()
        if (backup.exists()) backup.deleteRecursively()

        if (!staging.renameTo(incoming)) {
            // cacheDir and filesDir should share a volume; copy as a safe fallback if they don't.
            staging.copyRecursively(incoming, overwrite = true)
            staging.deleteRecursively()
        }

        val hadActive = active.exists()
        if (hadActive && !active.renameTo(backup)) {
            incoming.deleteRecursively()
            throw IOException("تعذر الاحتفاظ بالنسخة السابقة")
        }
        if (!incoming.renameTo(active)) {
            if (hadActive && !backup.renameTo(active)) {
                Log.e(tag, "CRITICAL: rollback failed, engines may be inconsistent")
            }
            incoming.deleteRecursively()
            throw IOException("تعذر تفعيل الإصدار الجديد")
        }
        if (backup.exists()) backup.deleteRecursively()
        cleanupStaging()
    }

    fun cleanupStaging() {
        try {
            EngineStorage.stagingDir(context).deleteRecursively()
        } catch (_: Exception) {
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** 5xx or 429 from the CDN/origin: temporary by nature. */
    private fun isServerBusy(e: IOException): Boolean {
        val m = e.message ?: return false
        return m.startsWith("HTTP 5") || m.startsWith("HTTP 429")
    }

    /** Failures that are expected to heal on their own, so a background retry is worthwhile. */
    private fun isTransient(e: Exception): Boolean {
        val m = e.message.orEmpty()
        return m.startsWith(HASH_MISMATCH) || m.startsWith("HTTP 5") || m.startsWith("HTTP 429") ||
            e is java.net.SocketTimeoutException
    }

    /**
     * Queues one quiet retry later (bounded exponential back-off: 15 min, 30 min, ... max 6 h,
     * at most [MAX_BG_RETRIES] times until the next success). Never throws.
     */
    private fun scheduleBackgroundRetry() {
        try {
            val used = prefs.getInt(KEY_BG_RETRIES, 0)
            if (used >= MAX_BG_RETRIES) return
            prefs.edit().putInt(KEY_BG_RETRIES, used + 1).apply()
            val delayMinutes = minOf(15L shl used, 360L)
            val request = OneTimeWorkRequest.Builder(PeriodicSyncWorker::class.java)
                .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(RETRY_WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        } catch (t: Throwable) {
            Log.d(tag, "Could not schedule background retry: ${t.message}")
        }
    }

    private fun friendlyError(e: Exception): String = when {
        e.message?.startsWith(HASH_MISMATCH) == true ->
            "فشل التحقق من سلامة ${e.message?.removePrefix(HASH_MISMATCH)} " +
                "(نسخة قديمة في كاش CDN أو manifest.json غير محدّث)"
        e.message?.contains("HTTP 404") == true -> "المصدر غير متوفر (404)"
        e.message?.contains("HTTP 403") == true -> "الوصول مرفوض (403)"
        e.message?.contains("HTTP 429") == true -> "طلبات كثيرة (429)، حاول لاحقاً"
        e.message?.contains("HTTP 5") == true -> "خطأ في الخادم (5xx)"
        e is IOException && e.message?.startsWith("HTTP") != true &&
            (e is java.net.UnknownHostException || e is java.net.SocketException ||
                e is java.net.SocketTimeoutException || e is javax.net.ssl.SSLException) ->
            "تعذر الاتصال بالإنترنت"
        else -> e.message ?: "فشل غير معروف"
    }

    private data class ManifestEntry(val name: String, val sha256: String?, val size: Long)

    companion object {
        private val updateMutex = Mutex()
        private const val KEY_LAST_CHECK = "last_check_ms"
        private const val KEY_LAST_UPDATE = "last_update_ms"
        private const val HASH_MISMATCH = "HASH_MISMATCH:"

        const val USER_AGENT = "MediaDownloader-App/1.0"

        private const val KEY_BG_RETRIES = "bg_retry_count"
        private const val RETRY_WORK_NAME = "engine_update_retry"
        private const val MAX_SERVER_RETRIES = 2
        private const val MAX_HASH_RETRIES = 2
        private const val MAX_BG_RETRIES = 5

        /** Entries that are never downloaded from a manifest (version marker is written locally). */
        private val MANIFEST_EXCLUDED = setOf("version.txt", "manifest.json", "server.py", "app_version.json")

        /** Used only when no consistent manifest exists for the release. */
        private val FALLBACK_FILES = listOf(
            "runner.py",
            "common.py",
            "requirements.txt",
            "Snapchat/engine.py",
            "Instagram/engine.py",
            "Tiktok/engine.py",
            "Threads/engine.py",
            "Facebook/engine.py",
            "Pinterest/engine.py",
            "Reddit/engine.py",
            "Twitter/engine.py"
        )
    }
}
