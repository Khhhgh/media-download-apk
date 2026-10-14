package com.example.service

import android.content.Context
import android.util.Log
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.example.model.CarouselItem
import com.example.model.MediaResult
import com.example.model.QualityOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException

/**
 * Executes extraction on-device through the dynamically updatable Python engine tree.
 *
 * The heavy lifting lives in `engine_runner.py` (bundled via Chaquopy) which dispatches to
 * `active_engines/runner.py`. This class is a thin, fault-contained bridge:
 * - starts the Python runtime once,
 * - forwards active engine + site-package paths to Python (priority for OTA engines),
 * - converts the standardized JSON response into a [MediaResult],
 * - converts any failure into a Kotlin exception carrying the real reason.
 */
class PythonEngineRunner(private val context: Context) {

    private val tag = "PythonEngineRunner"

    private fun ensureStarted() {
        if (!Python.isStarted()) {
            synchronized(Python::class.java) {
                if (!Python.isStarted()) {
                    Python.start(AndroidPlatform(context.applicationContext))
                }
            }
        }
    }

    /** Imports the runner and all platform engines; used by the first-run setup. */
    suspend fun verifyEngines(): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            ensureStarted()
            val active = EngineStorage.activeEnginesDir(context).absolutePath
            val site = EngineStorage.sitePackagesDir(context).absolutePath
            val raw = Python.getInstance()
                .getModule("engine_runner")
                .callAttr("verify", active, site)
                .toString()
            val json = JSONObject(raw)
            val ok = json.optBoolean("success", false)
            val imported = json.optJSONArray("imported")?.let { arr ->
                (0 until arr.length()).joinToString(", ") { arr.optString(it) }
            } ?: ""
            val message = if (ok) "تم التحقق من المحركات: $imported" else json.optString("error", "فشل التحقق")
            ok to message
        } catch (e: Exception) {
            Log.e(tag, "Engine verification failed", e)
            false to (e.message ?: "فشل التحقق من المحركات")
        }
    }

    /**
     * Runs extraction for [url] using the routed platform engine.
     *
     * @throws IOException when the Python runtime or the engine reports a failure.
     */
    suspend fun execute(url: String): MediaResult = withContext(Dispatchers.IO) {
        val route = DynamicEngineRouter.route(url)
        val active = EngineStorage.activeEnginesDir(context).absolutePath
        val site = EngineStorage.sitePackagesDir(context).absolutePath
        val version = EngineStorage.readActiveVersion(context)

        Log.i(
            tag,
            "Engine: Python | Platform: ${route.platformName} | Version: $version | Source: ${route.source}"
        )

        val raw: String
        try {
            ensureStarted()
            raw = Python.getInstance()
                .getModule("engine_runner")
                .callAttr("execute", active, site, route.platformName, url)
                .toString()
        } catch (e: Exception) {
            Log.e(tag, "Python runtime failure for ${route.platformName}", e)
            throw IOException("محرك Python غير متاح: ${e.message}")
        }

        val json = try {
            JSONObject(raw)
        } catch (e: Exception) {
            throw IOException("استجابة محرك غير صالحة")
        }

        if (!json.optBoolean("success", false)) {
            val reason = json.optString("error", "فشل الاستخراج")
            throw IOException(reason)
        }

        val result = parseResponse(json, url, route.platformName)
        if (result.videoUrl.isBlank() && result.carouselItems.isEmpty() && result.photoUrls.isEmpty()) {
            throw IOException("لم يتم العثور على وسائط في الرابط")
        }
        result
    }

    private fun parseResponse(json: JSONObject, sourceUrl: String, platform: String): MediaResult {
        val title = json.optString("title", "Media Download")
        val videoUrl = json.optString("videoUrl", json.optString("url", ""))
        val audioUrl = json.optString("audioUrl", "").ifBlank { null }
        val thumbnailUrl = json.optString("thumbnailUrl", "").ifBlank { null }
        val author = json.optString("author", "").ifBlank { null }
        val resolvedPlatform = json.optString("platform", platform).ifBlank { platform }

        val carousel = mutableListOf<CarouselItem>()
        json.optJSONArray("carouselItems")?.let { arr ->
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                carousel.add(
                    CarouselItem(
                        url = item.optString("url", ""),
                        format = item.optString("format", "jpg"),
                        label = item.optString("label", "Slide ${i + 1}"),
                        isHd = item.optBoolean("isHd", true)
                    )
                )
            }
        }

        val qualities = mutableListOf<QualityOption>()
        json.optJSONArray("qualityOptions")?.let { arr ->
            for (i in 0 until arr.length()) {
                val opt = arr.optJSONObject(i) ?: continue
                qualities.add(
                    QualityOption(
                        label = opt.optString("label", "Download"),
                        url = opt.optString("url", videoUrl),
                        format = opt.optString("format", "mp4"),
                        isHd = opt.optBoolean("isHd", true)
                    )
                )
            }
        }
        if (qualities.isEmpty() && videoUrl.isNotBlank()) {
            qualities.add(QualityOption("High Definition (HD)", videoUrl, format = "mp4", isHd = true))
        }

        val photos = mutableListOf<String>()
        json.optJSONArray("photoUrls")?.let { arr ->
            for (i in 0 until arr.length()) {
                val p = arr.optString(i, "")
                if (p.isNotBlank()) photos.add(p)
            }
        }

        return MediaResult(
            title = title,
            videoUrl = videoUrl,
            audioUrl = audioUrl,
            thumbnailUrl = thumbnailUrl,
            platform = resolvedPlatform,
            author = author,
            sourceUrl = json.optString("sourceUrl", sourceUrl).ifBlank { sourceUrl },
            qualityOptions = qualities,
            photoUrls = photos,
            carouselItems = carousel
        )
    }
}
