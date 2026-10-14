package com.example.service

import android.content.Context
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

data class FirstRunResult(
    val success: Boolean,
    val usedBundled: Boolean,
    val version: Int,
    val error: String? = null
)

/**
 * Orchestrates the very first launch.
 *
 * Steps:
 * 1. Install the engines bundled inside the APK so the app works with no connectivity.
 * 2. Contact the HTTPS update source and install the newest available engine version.
 * 3. Install the pure-Python requirements that can actually be supported on-device.
 * 4. Verify that the engine modules import correctly.
 * 5. Persist the completed state + version so this never runs twice after success.
 *
 * The UI is never blocked (all work happens on Dispatchers.IO); the dialog observes
 * [FirstRunProgress].
 */
class FirstRunSetupManager(private val context: Context) {

    private val tag = "FirstRunSetupManager"
    private val prefs = context.getSharedPreferences("first_run_prefs", Context.MODE_PRIVATE)
    private val updateManager = EngineUpdateManager(context)
    private val pipInstaller = DynamicPipInstaller(context)
    private val pythonRunner = PythonEngineRunner(context)

    var isFirstRunCompleted: Boolean
        get() = prefs.getBoolean(KEY_DONE, false)
        private set(value) = prefs.edit().putBoolean(KEY_DONE, value).apply()

    fun shouldRunSetup(): Boolean = !isFirstRunCompleted

    /** Packages that must not be installed on-device (server-only runtime). */
    private val serverOnlyPackages = setOf("flask", "gunicorn")

    suspend fun runSetup(
        onProgress: suspend (FirstRunProgress) -> Unit
    ): FirstRunResult = withContext(Dispatchers.IO) {
        try {
            onProgress(FirstRunProgress("تهيئة المحركات المضمنة", 3))
            val bundledInstalled = EngineStorage.installBundledIfNeeded(context)
            val bundledVersion = EngineStorage.readActiveVersion(context)
            if (bundledVersion <= 0) {
                return@withContext FirstRunResult(false, false, 0, "المحركات المضمنة غير صالحة")
            }
            Log.i(tag, "Bundled engines ready (installed=$bundledInstalled version=$bundledVersion)")

            onProgress(FirstRunProgress("الاتصال بمصدر التحديث", 8))
            var usedBundled = true
            val update = updateManager.checkAndUpdate(onProgress = { p ->
                val mapped = 10 + (p.percent * 70) / 100
                onProgress(
                    FirstRunProgress(
                        stage = p.stage,
                        percent = mapped.coerceIn(10, 80),
                        downloadedBytes = p.downloadedBytes,
                        totalBytes = p.totalBytes,
                        bytesPerSecond = p.bytesPerSecond
                    )
                )
            })
            if (update.updated) {
                usedBundled = false
                Log.i(tag, "First-run installed OTA engine version ${update.activeVersion}")
            } else if (!update.success) {
                // Offline or remote failure: the valid bundled copy is used instead.
                Log.w(tag, "OTA unavailable on first run (${update.error}); using bundled engines")
            }

            onProgress(FirstRunProgress("تثبيت المكتبات المدعومة", 82))
            installRequirements()

            onProgress(FirstRunProgress("التحقق من المحركات", 94))
            val (verified, message) = pythonRunner.verifyEngines()
            if (!verified) {
                // A failed import on top of a failed OTA means we cannot guarantee a working setup.
                if (!update.success && !update.updated && !EngineStorage.isInstalled(context)) {
                    return@withContext FirstRunResult(false, usedBundled, bundledVersion, message)
                }
                Log.w(tag, "Engine verification warning: $message")
            }

            onProgress(FirstRunProgress("اكتمل التثبيت", 100))
            isFirstRunCompleted = true
            prefs.edit().putInt(KEY_VERSION, update.activeVersion).apply()
            FirstRunResult(true, usedBundled, update.activeVersion, null)
        } catch (e: Exception) {
            Log.e(tag, "First-run setup failed", e)
            FirstRunResult(false, true, EngineStorage.readActiveVersion(context), e.message ?: "فشل غير معروف")
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
        private const val KEY_DONE = "isFirstRunCompleted"
        private const val KEY_VERSION = "installed_engine_version"
    }
}
