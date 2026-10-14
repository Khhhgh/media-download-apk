package com.example.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class FirstRunProgress(
    val stage: String,
    val percent: Int,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val bytesPerSecond: Long = 0L
)

/**
 * @param success   the app can run (engines are on disk)
 * @param provisioned true when the engines were downloaded from the CDN; false when the app is
 *                  running on the temporary offline fallback and provisioning must be retried.
 */
data class FirstRunResult(
    val success: Boolean,
    val provisioned: Boolean,
    val version: Int,
    val error: String? = null
)

/**
 * First-run / clean-install provisioning.
 *
 * Rules:
 * - `is_engine_provisioned == false` (fresh install, reinstall, cleared data, or a build that
 *   predates this flag) -> the engines are downloaded from the CDN. The APK `assets/engines`
 *   copy is NOT touched while the network works.
 * - Only if the download fails (offline / CDN down) are the bundled engines copied, as a
 *   temporary fallback. The flag stays false so provisioning is retried when a connection is
 *   available (next launch, or silently while the app is open).
 * - On success: requirements are prepared in `filesDir/site-packages`, the engines are
 *   import-verified, the remote version is stored and `is_engine_provisioned = true`.
 */
class FirstRunSetupManager(private val context: Context) {

    private val tag = "FirstRunSetupManager"
    private val prefs = context.getSharedPreferences("first_run_prefs", Context.MODE_PRIVATE)
    private val updateManager = EngineUpdateManager(context)
    private val pipInstaller = DynamicPipInstaller(context)
    private val pythonRunner = PythonEngineRunner(context)

    var isProvisioned: Boolean
        get() = prefs.getBoolean(KEY_PROVISIONED, false)
        private set(value) = prefs.edit().putBoolean(KEY_PROVISIONED, value).apply()

    /**
     * True when the blocking setup window must be shown: not provisioned yet AND either a
     * connection exists (fetch the latest engines now) or nothing usable is on disk.
     * An offline relaunch that already runs on the fallback engines is not blocked again.
     */
    fun shouldRunSetup(): Boolean {
        EngineStorage.recoverInterruptedSwap(context)
        if (isProvisioned && EngineStorage.isInstalled(context)) return false
        return isOnline() || !EngineStorage.isInstalled(context)
    }

    fun isOnline(): Boolean = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.getNetworkCapabilities(cm.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } catch (e: Exception) {
        true // cannot tell: let the real request decide
    }

    /** Packages that must not be installed on-device (server-only runtime). */
    private val serverOnlyPackages = setOf("flask", "gunicorn")

    suspend fun runSetup(
        onProgress: suspend (FirstRunProgress) -> Unit
    ): FirstRunResult = withContext(Dispatchers.IO) {
        try {
            onProgress(FirstRunProgress("الاتصال بخادم التحديث", 3))
            val update = updateManager.checkAndUpdate(
                force = true,
                onProgress = { p ->
                    val mapped = 5 + (p.percent * 75) / 100
                    onProgress(
                        FirstRunProgress(
                            stage = p.stage,
                            percent = mapped.coerceIn(5, 80),
                            downloadedBytes = p.downloadedBytes,
                            totalBytes = p.totalBytes,
                            bytesPerSecond = p.bytesPerSecond
                        )
                    )
                }
            )

            if (!update.success) {
                Log.w(tag, "CDN provisioning failed (${update.error}); offline fallback")
                return@withContext offlineFallback(update.error, onProgress)
            }
            Log.i(tag, "Engines provisioned from CDN, version ${update.activeVersion}")

            onProgress(FirstRunProgress("تثبيت المكتبات المدعومة", 82))
            installRequirements()

            onProgress(FirstRunProgress("التحقق من المحركات", 94))
            val (verified, message) = pythonRunner.verifyEngines()
            if (!verified) Log.w(tag, "Engine verification warning: $message")

            onProgress(FirstRunProgress("اكتمل التثبيت", 100))
            prefs.edit()
                .putInt(KEY_VERSION, update.activeVersion)
                .putBoolean(KEY_PROVISIONED, true)
                .apply()
            FirstRunResult(true, true, update.activeVersion, null)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(tag, "First-run setup failed", e)
            offlineFallback(e.message ?: "فشل غير معروف", onProgress)
        }
    }

    /** Last resort: make the app usable with the APK-bundled engines until a connection exists. */
    private suspend fun offlineFallback(
        cause: String?,
        onProgress: suspend (FirstRunProgress) -> Unit
    ): FirstRunResult {
        if (!EngineStorage.isInstalled(context)) {
            onProgress(FirstRunProgress("تهيئة المحركات المدمجة (وضع عدم الاتصال)", 85))
            EngineStorage.installBundledIfNeeded(context)
        }
        val version = EngineStorage.readActiveVersion(context)
        return if (EngineStorage.isInstalled(context)) {
            FirstRunResult(true, false, version, cause)
        } else {
            FirstRunResult(false, false, 0, cause ?: "تعذر تنزيل المحركات ولا توجد نسخة مدمجة")
        }
    }

    /** Best-effort install of the pure-Python requirements; native packages are reported as unsupported. */
    private suspend fun installRequirements() {
        val requirementsFile = java.io.File(
            EngineStorage.activeEnginesDir(context),
            "requirements.txt"
        )
        if (!requirementsFile.isFile) return

        val requirements = requirementsFile.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }

        val importNames = requirements.map { spec ->
            spec.substringBefore("==").substringBefore(">=").substringBefore("~=").trim()
        }
        // Map distribution names to import names where they differ.
        val importMap = mapOf("beautifulsoup4" to "bs4", "pyyaml" to "yaml", "pillow" to "PIL")

        val missing = pipInstaller.missingModules(importNames.map { importMap[it.lowercase()] ?: it })
        if (missing.isEmpty()) {
            Log.i(tag, "All required modules already importable")
            return
        }

        for (spec in requirements) {
            val name = spec.substringBefore("==").substringBefore(">=").substringBefore("~=").trim()
            if (name.lowercase() in serverOnlyPackages) {
                Log.i(tag, "Skipping server-only package: $name")
                continue
            }
            val importName = importMap[name.lowercase()] ?: name
            if (importName !in missing) continue
            val result = pipInstaller.install(spec)
            Log.i(tag, "pip install $spec -> installed=${result.installed} supported=${result.supported} (${result.message})")
        }
    }

    fun reset() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_PROVISIONED = "is_engine_provisioned"
        private const val KEY_VERSION = "installed_engine_version"
    }
}
