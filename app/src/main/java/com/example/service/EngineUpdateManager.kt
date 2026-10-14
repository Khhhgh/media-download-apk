package com.example.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
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
 * Network rules (anti rate-limit):
 * - Everything is served through the jsDelivr CDN ([BASE_URL]). `api.github.com` and
 *   `raw.githubusercontent.com` are NEVER contacted.
 * - `version.txt` (a few bytes) is always requested as `version.txt?t=<now>` with
 *   `Cache-Control: no-cache` + FORCE_NETWORK, so a stale CDN/HTTP cache can't hide a release.
 * - Engine files are requested as `<file>?v=<remoteVersion>` so each release has its own URL.
 *
 * Safety rules:
 * - Download goes to `cacheDir/temp_engines`; nothing touches the live tree until every file
 *   is downloaded and (when a consistent manifest exists) SHA-256 verified.
 * - Activation is a same-directory rename swap with rollback to the previous known-good tree.
 * - The local version is "committed" implicitly by the new tree's own `version.txt`, which is
 *   written into staging only after all files are valid.
 * - Any failure keeps the last working tree untouched.
 */
class EngineUpdateManager(private val context: Context) {

    private val tag = "EngineUpdateManager"
    private val prefs = context.getSharedPreferences("engine_update_prefs", Context.MODE_PRIVATE)

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** CDN base (no trailing slash). Fixed on purpose: no GitHub API / raw endpoints. */
    val updateBaseUrl: String get() = BASE_URL

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
                val remoteVersion = fetchRemoteVersion()
                if (remoteVersion <= 0) {
                    return@withLock EngineUpdateResult(false, false, localVersion, "رقم إصدار غير صالح من المصدر")
                }

                val needsInstall = force || remoteVersion > localVersion || !EngineStorage.isInstalled(context)
                if (!needsInstall) {
                    Log.i(tag, "Engines up to date (local=$localVersion remote=$remoteVersion)")
                    recordCheck()
                    onProgress?.invoke(EngineUpdateProgress("لا توجد تحديثات جديدة", 100))
                    return@withLock EngineUpdateResult(true, false, localVersion, null)
                }

                val entries = resolveEntries(remoteVersion)
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

                    var attempt = 0
                    while (true) {
                        val before = downloaded
                        downloadToFile(fileUrl(entry.name, remoteVersion, attempt), target) { delta ->
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
                        if (!target.isFile || target.length() == 0L) {
                            throw IOException("ملف فارغ أو ناقص: ${entry.name}")
                        }
                        val expected = entry.sha256
                        if (expected == null || sha256(target).equals(expected, ignoreCase = true)) break
                        // Likely a stale CDN edge: retry once through a fresh cache key.
                        downloaded = before
                        if (++attempt >= 2) throw IOException(HASH_MISMATCH + entry.name)
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
                    .apply()
                Log.i(tag, "Engines updated: v$localVersion -> v$remoteVersion")
                onProgress?.invoke(EngineUpdateProgress("اكتمل التحديث", 100))
                EngineUpdateResult(true, true, remoteVersion, null)
            } catch (e: kotlinx.coroutines.CancellationException) {
                cleanupStaging()
                throw e
            } catch (e: Exception) {
                Log.w(tag, "Engine update failed, keeping current version: ${e.message}")
                cleanupStaging()
                recordCheck()
                EngineUpdateResult(false, false, EngineStorage.readActiveVersion(context), friendlyError(e))
            }
        }
    }

    /** Reads the live version number: a ~1-byte request, cache-busted with a timestamp. */
    suspend fun fetchRemoteVersion(): Int = withContext(Dispatchers.IO) {
        val body = httpGet("$BASE_URL/${EngineStorage.ENGINE_VERSION_FILE}?t=${System.currentTimeMillis()}")
        body.trim().lineSequence().firstOrNull()?.trim()?.toIntOrNull() ?: 0
    }

    /**
     * File list for a release. Prefers the manifest (gives SHA-256 verification) but only when it
     * describes exactly this release; a missing/stale/invalid manifest must never block updates,
     * so the built-in list is used (without hashes) instead.
     */
    private fun resolveEntries(remoteVersion: Int): List<ManifestEntry> {
        val fromManifest = try {
            fetchManifestEntries(remoteVersion)
        } catch (e: Exception) {
            Log.w(tag, "Manifest unavailable (${e.message}); using built-in file list")
            null
        }
        return fromManifest ?: FALLBACK_FILES.map { ManifestEntry(it, null, -1L) }
    }

    private fun fetchManifestEntries(remoteVersion: Int): List<ManifestEntry>? {
        val body = httpGet("$BASE_URL/${EngineStorage.ENGINE_MANIFEST_FILE}?t=${System.currentTimeMillis()}")
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

    private fun fileUrl(name: String, remoteVersion: Int, attempt: Int): String {
        val base = "$BASE_URL/$name?v=$remoteVersion"
        return if (attempt == 0) base else "$base&r=${System.currentTimeMillis()}"
    }

    private fun newRequest(url: String): Request = Request.Builder()
        .url(url)
        .header("User-Agent", USER_AGENT)
        .header("Cache-Control", "no-cache")
        .header("Pragma", "no-cache")
        .cacheControl(CacheControl.FORCE_NETWORK)
        .build()

    private fun httpGet(url: String): String {
        client.newCall(newRequest(url)).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            return response.body?.string() ?: throw IOException("Empty response")
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

        /** jsDelivr CDN proxy of the repo: no GitHub API, no raw.githubusercontent.com. */
        const val BASE_URL = "https://cdn.jsdelivr.net/gh/Khhhgh/media-download-apk@main/downapp"

        /** Entries that are never downloaded from a manifest (version marker is written locally). */
        private val MANIFEST_EXCLUDED = setOf("version.txt", "manifest.json", "server.py")

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
