package com.example.service

import android.util.Log
import com.example.model.CarouselItem
import com.example.model.MediaResult
import com.example.model.QualityOption
import com.example.model.RemoteConfigData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Dynamic Modular Runner Architecture (DynamicEngineRunner)
 *
 * Implements cloud-backed dynamic modular extraction aligned with the `downapp/` architecture.
 *
 * Capabilities:
 * - Queries dynamic cloud runner microservice / OTA endpoints configured in RemoteConfig
 * - Allows instantaneous OTA engine upgrades without requiring APK updates
 * - Auto-detects platform and dispatches standardized schema requests
 * - Parses standardized JSON responses matching downapp/runner.py
 * - Transparent failover to native on-device engines when dynamic runner is unreachable
 */
class DynamicEngineRunner(
    private val client: OkHttpClient
) {
    private val tag = "DynamicEngineRunner"

    // Engine architecture version synced with downapp/version.txt
    val engineVersion: Int = 1

    private val dynamicClient = client.newBuilder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * Attempts dynamic cloud execution for the given URL using the dynamic modular runner.
     * Returns null if dynamic cloud runner is not configured or fails, allowing native fallbacks.
     */
    suspend fun tryDynamicExecution(
        url: String,
        platformName: String,
        config: RemoteConfigData
    ): MediaResult? = withContext(Dispatchers.IO) {
        val engineConfig = config.engines[platformName.lowercase()] ?: config.engines["custom"]
        val runnerEndpoint = engineConfig?.primaryApi?.takeIf { it.isNotBlank() && it.startsWith("http") }

        if (runnerEndpoint == null) {
            Log.d(tag, "No custom dynamic runner endpoint specified for $platformName, using native engine pipeline.")
            return@withContext null
        }

        try {
            Log.d(tag, "Invoking Dynamic Modular Runner at [$runnerEndpoint] for $platformName...")
            val payload = JSONObject().apply {
                put("url", url)
                put("platform", platformName)
                put("engine_version", engineVersion)
            }

            val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url(runnerEndpoint)
                .post(body)
                .header("User-Agent", UNIVERSAL_USER_AGENT)
                .header("Accept", "application/json")
                .header("X-App-Engine-Version", engineVersion.toString())
                .build()

            dynamicClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(tag, "Dynamic runner returned HTTP ${response.code}")
                    return@withContext null
                }

                val bodyStr = response.body?.string() ?: return@withContext null
                val json = JSONObject(bodyStr)
                if (json.optBoolean("success", true) && (json.has("videoUrl") || json.has("url"))) {
                    return@withContext parseDynamicResponse(json, url, platformName)
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "Dynamic cloud runner execution failed: ${e.message}. Falling back to native engine.")
        }
        null
    }

    private fun parseDynamicResponse(json: JSONObject, sourceUrl: String, platform: String): MediaResult {
        val title = json.optString("title", "Media Download")
        val videoUrl = json.optString("videoUrl", json.optString("url", ""))
        val thumbnailUrl = json.optString("thumbnailUrl", "").ifBlank { null }
        val author = json.optString("author", "").ifBlank { null }

        val carouselArray = json.optJSONArray("carouselItems")
        val carouselList = mutableListOf<CarouselItem>()
        if (carouselArray != null) {
            for (i in 0 until carouselArray.length()) {
                val item = carouselArray.optJSONObject(i) ?: continue
                carouselList.add(
                    CarouselItem(
                        url = item.optString("url", ""),
                        format = item.optString("format", "mp4"),
                        label = item.optString("label", "Slide ${i + 1}"),
                        isHd = item.optBoolean("isHd", true)
                    )
                )
            }
        }

        val qualityArray = json.optJSONArray("qualityOptions")
        val qualityList = mutableListOf<QualityOption>()
        if (qualityArray != null) {
            for (i in 0 until qualityArray.length()) {
                val opt = qualityArray.optJSONObject(i) ?: continue
                qualityList.add(
                    QualityOption(
                        label = opt.optString("label", "Download"),
                        url = opt.optString("url", videoUrl),
                        format = opt.optString("format", "mp4"),
                        isHd = opt.optBoolean("isHd", true)
                    )
                )
            }
        }

        val photoArray = json.optJSONArray("photoUrls")
        val photoList = mutableListOf<String>()
        if (photoArray != null) {
            for (i in 0 until photoArray.length()) {
                val p = photoArray.optString(i, "")
                if (p.isNotBlank()) photoList.add(p)
            }
        }

        return MediaResult(
            title = title,
            videoUrl = videoUrl,
            thumbnailUrl = thumbnailUrl,
            platform = platform,
            author = author,
            sourceUrl = sourceUrl,
            qualityOptions = qualityList,
            photoUrls = photoList,
            carouselItems = carouselList
        )
    }
}
