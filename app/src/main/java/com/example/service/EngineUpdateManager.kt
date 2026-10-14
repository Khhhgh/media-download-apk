package com.example.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
 * Dynamic OTA update service for the Python engine tree.
 *
 * Guarantees:
 * - The remote version is read first; when it is equal or lower, nothing is downloaded.
 * - All files (and their SHA-256 hashes) are validated in a staging directory before activation.
 * - The new tree is activated by an atomic-ish directory swap with rollback to the previous
 *   known-good copy if activation fails.
 * - The local version is only committed after every stage succeeds.
 * - Transient network/HTTP failures keep the last valid version intact.
 */
class EngineUpdateManager(private val context: Context) {

    private val tag = "EngineUpdateManager"
    private val prefs = context.getSharedPreferences("engine_update_prefs", Context.MODE_PRIVATE)

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Base URL hosting version.txt, manifest.json and the engine tree (no trailing slash). */
    var updateBaseUrl: String
        get() = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        set(value) {
            val normalized = value.trimEnd('/')
            // All update traffic must be encrypted; reject any cleartext source.
            if (!normalized.startsWith("https://")) {
                Log.w(tag, "Ignoring non-HTTPS update base URL: $normalized")
                return
            }
            prefs.edit().putString(KEY_BASE_URL, normalized).apply()
        }

    val lastCheckTime: Long get() = prefs.getLong(KEY_LAST_CHECK, 0L)
    val lastUpdateTime: Long get() = prefs.getLong(KEY_LAST_UPDATE, 0L)

    fun recordCheck() = prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()

    /**
     * Checks the remote source and installs a newer engine version if available.
     *
     * @param force when true, installs the remote version even if it is not strictly newer
     *              (used by the first-run setup so the very first install is authoritative).
     */
    suspend fun checkAndUpdate(
        onProgress: (suspend (EngineUpdateProgress) -> Unit)? = null,
        force: Boolean = false
    ): EngineUpdateResult = withContext(Dispatchers.IO) {
        updateMutex.withLock {
            // Ensure we always have a base (bundled) copy before touching the network.
            EngineStorage.installBundledIfNeeded(context)
            val localVersion = EngineStorage.readActiveVersion(context)

            try {
                onProgress?.invoke(EngineUpdateProgress("التحقق من مصدر التحديث", 2))
                val remoteVersion = fetchRemoteVersion()

                if (remoteVersion <= 0) {
                    return@withLock EngineUpdateResult(false, false, localVersion, "رقم إصدار غير صالح من المصدر")
                }

                if (!force && remoteVersion <= localVersion) {
                    Log.i(tag, "Engine already up to date (local=$localVersion remote=$remoteVersion)")
                    recordCheck()
                    onProgress?.invoke(EngineUpdateProgress("لا توجد تحديثات جديدة", 100))
                    return@withLock EngineUpdateResult(true, false, localVersion, null)
                }

                val manifest = fetchManifest(remoteVersion)
                val files = manifest.optJSONObject("files")
                    ?: return@withLock EngineUpdateResult(false, false, localVersion, "ملف manifest غير صالح")

                val entries = mutableListOf<ManifestEntry>()
                val names = files.keys()
                var totalBytes = 0L
                while (names.hasNext()) {
                    val name = names.next()
                    val obj = files.optJSONObject(name) ?: continue
                    val size = obj.optLong("size", -1L)
                    val sha = obj.optString("sha256", "")
                    if (sha.isBlank() || size < 0) {
                        return@withLock EngineUpdateResult(false, false, localVersion, "بيانات تحقق ناقصة للملف $name")
                    }
                    entries.add(ManifestEntry(name, sha, size))
                    totalBytes += size
                }
                if (entries.isEmpty()) {
                    return@withLock EngineUpdateResult(false, false, localVersion, "لا توجد ملفات محركات في المصدر")
                }

                // Disk space guard (require roughly 2x the payload for staging + activation).
                val required = totalBytes * 2
                val usable = EngineStorage.stagingDir(context).usableSpace
                if (usable in 1L until required) {
                    return@withLock EngineUpdateResult(false, false, localVersion, "مساحة التخزين غير كافية")
                }

                val staging = EngineStorage.stagingDir(context)
                if (staging.exists()) staging.deleteRecursively()
                if (!staging.mkdirs() && !staging.isDirectory) {
                    return@withLock EngineUpdateResult(false, false, localVersion, "تعذر إنشاء مجلد مؤقت")
                }

                var downloaded = 0L
                val startTime = System.currentTimeMillis()
                for ((index, entry) in entries.withIndex()) {
                    val target = File(staging, entry.name)
                    target.parentFile?.mkdirs()
                    downloadToFile("${updateBaseUrl}/${entry.name}", target) { delta ->
                        downloaded += delta
                    }
                    val elapsed = (System.currentTimeMillis() - startTime).coerceAtLeast(1L)
                    val speed = (downloaded * 1000L) / elapsed
                    val basePercent = if (totalBytes > 0) ((downloaded * 70L) / totalBytes).toInt() else 0
                    onProgress?.invoke(
                        EngineUpdateProgress(
                            stage = "تنزيل المحركات (${index + 1}/${entries.size})",
                            percent = 10 + basePercent,
                            downloadedBytes = downloaded,
                            totalBytes = totalBytes,
                            bytesPerSecond = speed
                        )
                    )
                }

                onProgress?.invoke(EngineUpdateProgress("التحقق من سلامة الملفات", 82))
                entries.forEach { entry ->
                    val target = File(staging, entry.name)
                    if (!target.isFile) {
                        staging.deleteRecursively()
                        return@withLock EngineUpdateResult(false, false, localVersion, "ملف ناقص: ${entry.name}")
                    }
                    val actual = sha256(target)
                    if (!actual.equals(entry.sha256, ignoreCase = true)) {
                        staging.deleteRecursively()
                        return@withLock EngineUpdateResult(false, false, localVersion, "فشل التحقق من سلامة: ${entry.name}")
                    }
                }

                onProgress?.invoke(EngineUpdateProgress("تفعيل الإصدار الجديد", 90))
                activate(staging)

                prefs.edit()
                    .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
                    .putLong(KEY_LAST_UPDATE, System.currentTimeMillis())
                    .apply()
                Log.i(tag, "Engine updated to v$remoteVersion")
                onProgress?.invoke(EngineUpdateProgress("اكتمل التحديث", 100))
                EngineUpdateResult(true, true, remoteVersion, null)
            } catch (e: Exception) {
                Log.w(tag, "Engine update failed, keeping current version: ${e.message}")
                cleanupStaging()
                recordCheck()
                EngineUpdateResult(false, false, EngineStorage.readActiveVersion(context), friendlyError(e))
            }
        }
    }

    suspend fun fetchRemoteVersion(): Int = withContext(Dispatchers.IO) {
        val body = httpGet("${updateBaseUrl}/${EngineStorage.ENGINE_VERSION_FILE}")
        body.trim().toIntOrNull() ?: 0
    }

    private fun fetchManifest(remoteVersion: Int): JSONObject {
        val body = httpGet("${updateBaseUrl}/${EngineStorage.ENGINE_MANIFEST_FILE}")
        val json = JSONObject(body)
        val manifestVersion = json.optInt("version", remoteVersion)
        if (manifestVersion != remoteVersion) {
            throw IOException("manifest version ($manifestVersion) != version.txt ($remoteVersion)")
        }
        return json
    }

    private fun httpGet(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "MediaDownload-Engine/1.0")
            .header("Cache-Control", "no-cache")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}")
            }
            return response.body?.string() ?: throw IOException("Empty response")
        }
    }

    private fun downloadToFile(url: String, target: File, onBytes: (Long) -> Unit) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "MediaDownload-Engine/1.0")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
            val body = response.body ?: throw IOException("Empty body for $url")
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        onBytes(read.toLong())
                    }
                }
            }
        }
    }

    /** Swaps the staged tree in, keeping the previous version until activation succeeds. */
    private fun activate(staging: File) {
        val active = EngineStorage.activeEnginesDir(context)
        val backup = EngineStorage.backupEnginesDir(context)
        if (backup.exists()) backup.deleteRecursively()

        val hadActive = active.exists()
        if (hadActive && !active.renameTo(backup)) {
            throw IOException("تعذر الاحتفاظ بالنسخة السابقة")
        }
        if (!staging.renameTo(active)) {
            // Roll back to the previous known-good copy.
            if (hadActive && !backup.renameTo(active)) {
                Log.e(tag, "CRITICAL: rollback failed, engines may be inconsistent")
            }
            throw IOException("تعذر تفعيل الإصدار الجديد")
        }
        // Activation succeeded; drop the previous copy.
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
        e.message?.contains("HTTP 404") == true -> "المصدر غير متوفر (404)"
        e.message?.contains("HTTP 403") == true -> "الوصول مرفوض (403)"
        e.message?.contains("HTTP 429") == true -> "طلبات كثيرة (429)، حاول لاحقاً"
        e.message?.contains("HTTP 5") == true -> "خطأ في الخادم (5xx)"
        e is IOException -> "تعذر الاتصال بالإنترنت"
        else -> e.message ?: "فشل غير معروف"
    }

    private data class ManifestEntry(val name: String, val sha256: String, val size: Long)

    companion object {
        private val updateMutex = Mutex()
        private const val KEY_BASE_URL = "engine_update_base_url"
        private const val KEY_LAST_CHECK = "last_check_ms"
        private const val KEY_LAST_UPDATE = "last_update_ms"

        const val DEFAULT_BASE_URL = "https://raw.githubusercontent.com/Khhhgh/media-download-apk/main/downapp"
    }
}
