package com.example.service

import android.content.Context
import android.util.Log
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

data class PipInstallResult(
    val requirement: String,
    val installed: Boolean,
    val supported: Boolean,
    val message: String
)

/**
 * Runtime installer for dynamically installable Python packages.
 *
 * Constraint (documented, Chaquopy-compatible): only **pure-Python wheels**
 * (`*-py3-none-any.whl`, i.e. no compiled extensions) can be installed at runtime by extracting
 * them into `filesDir/site-packages/`. Packages with native code must be declared in the Chaquopy
 * `pip {}` block and installed on the build machine, or shipped as a compatible Android wheel.
 *
 * This class never shells out to a system `pip` and never executes arbitrary commands.
 */
class DynamicPipInstaller(private val context: Context) {

    private val tag = "DynamicPipInstaller"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Returns the import names that are not currently importable in the Python environment. */
    suspend fun missingModules(importNames: List<String>): List<String> = withContext(Dispatchers.IO) {
        try {
            ensureStarted()
            val raw = Python.getInstance()
                .getModule("engine_runner")
                .callAttr("missing_modules", org.json.JSONArray(importNames).toString())
                .toString()
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).map { arr.optString(it) }
        } catch (e: Exception) {
            Log.w(tag, "Unable to probe modules: ${e.message}")
            importNames
        }
    }

    /** Installs a single requirement specification (name or name==version) if pure-Python. */
    suspend fun install(requirement: String): PipInstallResult = withContext(Dispatchers.IO) {
        val name = requirement.substringBefore("==").substringBefore(">=").substringBefore("~=").trim()
        val version = requirement.substringAfter("==", "").trim()
        if (name.isBlank()) {
            return@withContext PipInstallResult(requirement, false, false, "متطلب غير صالح")
        }
        try {
            val wheelUrl = resolvePurePythonWheel(name, version)
                ?: return@withContext PipInstallResult(
                    requirement, false, false,
                    "لا يتوفر Wheel بايثون نقي (يتطلب تضميناً وقت البناء عبر Chaquopy)"
                )
            val wheelFile = File(context.cacheDir, "$name.whl")
            download(wheelUrl, wheelFile)
            val target = EngineStorage.sitePackagesDir(context).apply { mkdirs() }
            extractWheel(wheelFile, target)
            wheelFile.delete()
            Log.i(tag, "Installed pure-python package: $name")
            PipInstallResult(requirement, true, true, "تم التثبيت: $name")
        } catch (e: Exception) {
            Log.w(tag, "Failed to install $requirement: ${e.message}")
            PipInstallResult(requirement, false, false, e.message ?: "فشل التثبيت")
        }
    }

    private fun resolvePurePythonWheel(name: String, version: String): String? {
        val url = "https://pypi.org/pypi/$name/json"
        val request = Request.Builder().url(url).header("User-Agent", "MediaDownload-Engine/1.0").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $name")
            val body = response.body?.string() ?: throw IOException("Empty PyPI response")
            val root = JSONObject(body)
            val releases = root.optJSONObject("releases") ?: return null
            val candidateVersions = if (version.isNotBlank()) listOf(version) else {
                root.optJSONObject("info")?.optString("version")?.let { listOf(it) }
                    ?: releases.keys().asSequence().toList()
            }
            for (v in candidateVersions) {
                val files = releases.optJSONArray(v) ?: continue
                for (i in 0 until files.length()) {
                    val file = files.optJSONObject(i) ?: continue
                    val filename = file.optString("filename", "")
                    val pkgType = file.optString("packagetype", "")
                    if (pkgType == "bdist_wheel" && isPurePythonWheel(filename)) {
                        return file.optString("url", "").ifBlank { null }
                    }
                }
            }
        }
        return null
    }

    private fun isPurePythonWheel(filename: String): Boolean {
        val lower = filename.lowercase()
        // Pure wheels are tagged with "none-any"; native wheels carry a platform tag.
        return lower.endsWith(".whl") && lower.contains("-none-any.whl")
    }

    private fun download(url: String, target: File) {
        val request = Request.Builder().url(url).header("User-Agent", "MediaDownload-Engine/1.0").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty body")
            body.byteStream().use { input -> target.outputStream().use { input.copyTo(it) } }
        }
    }

    /** Extracts a wheel (zip) into site-packages, skipping metadata-only `.data` scripts. */
    private fun extractWheel(wheel: File, target: File) {
        ZipInputStream(wheel.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                val isMetadataOnly = name.endsWith(".dist-info/RECORD") ||
                    name.endsWith(".dist-info/WHEEL") ||
                    name.endsWith(".dist-info/METADATA") ||
                    name.endsWith(".dist-info/entry_points.txt")
                if (!entry.isDirectory && !name.startsWith("../") && !name.contains("..")) {
                    val outFile = File(target, name)
                    outFile.parentFile?.mkdirs()
                    if (isMetadataOnly || !name.contains(".data/")) {
                        outFile.outputStream().use { zip.copyTo(it) }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    private fun ensureStarted() {
        if (!Python.isStarted()) {
            synchronized(Python::class.java) {
                if (!Python.isStarted()) {
                    Python.start(AndroidPlatform(context.applicationContext))
                }
            }
        }
    }
}
