package com.example.service

import android.util.Log
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
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class YouTubeBlockedException(
    message: String = "YouTube is strictly blocked. In compliance with developer policies, YouTube downloading is not supported."
) : Exception(message)

abstract class BaseDownloaderEngine {
    abstract val platformName: String
    abstract fun canHandle(url: String): Boolean
    abstract suspend fun extract(url: String, config: RemoteConfigData): MediaResult
}

class TikWMDownloader(private val client: OkHttpClient) : BaseDownloaderEngine() {
    override val platformName: String = "TikTok"

    override fun canHandle(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("tiktok.com") || lower.contains("douyin.com")
    }

    override suspend fun extract(url: String, config: RemoteConfigData): MediaResult = withContext(Dispatchers.IO) {
        val engineEndpoint = config.engines["tiktok"]?.primaryApi?.ifBlank { "https://api.tikwm.com/api/" }
            ?: "https://api.tikwm.com/api/"
        extractFromEndpoint(url, engineEndpoint) ?: run {
            val fallback = config.engines["tiktok"]?.fallbackApi?.ifBlank { "https://co.wuk.sh/api/json" }
                ?: "https://co.wuk.sh/api/json"
            CobaltDownloader(client).extractDirect(url, fallback, "TikTok")
        }
    }

    suspend fun extractFromEndpoint(url: String, endpoint: String): MediaResult? = withContext(Dispatchers.IO) {
        try {
            val requestUrl = if (endpoint.contains("tikwm.com")) {
                "$endpoint?url=${java.net.URLEncoder.encode(url, "UTF-8")}&count=12&cursor=0&web=1&hd=1"
            } else {
                endpoint
            }

            val request = Request.Builder()
                .url(requestUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                .header("Accept", "application/json")
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: ""
                val json = JSONObject(body)
                val code = json.optInt("code", -1)
                if (code == 0 && json.has("data")) {
                    val data = json.getJSONObject("data")
                    val title = data.optString("title", "TikTok Video").ifBlank { "TikTok Video" }
                    val cover = data.optString("cover", "")
                    val hdPlay = data.optString("hdplay", "")
                    val play = data.optString("play", "")
                    val music = data.optString("music", "")
                    val author = data.optJSONObject("author")?.optString("nickname", "Creator") ?: "Creator"

                    val primaryVideo = if (hdPlay.isNotBlank()) hdPlay else play
                    val qualities = mutableListOf<QualityOption>()
                    if (hdPlay.isNotBlank()) {
                        qualities.add(QualityOption(label = "1080p HD (No Watermark)", url = hdPlay, isHd = true))
                    }
                    if (play.isNotBlank()) {
                        qualities.add(QualityOption(label = "720p Clean (Fast)", url = play, isHd = false))
                    }
                    if (music.isNotBlank()) {
                        qualities.add(QualityOption(label = "Original Audio (MP3)", url = music, format = "mp3"))
                    }

                    if (primaryVideo.isNotBlank()) {
                        return@withContext MediaResult(
                            title = title,
                            videoUrl = primaryVideo,
                            audioUrl = music.ifBlank { null },
                            thumbnailUrl = cover.ifBlank { null },
                            platform = "TikTok",
                            author = author,
                            sourceUrl = url,
                            qualityOptions = qualities
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("TikWMDownloader", "Extraction failed: ${e.message}")
        }
        null
    }
}

class CobaltDownloader(private val client: OkHttpClient) : BaseDownloaderEngine() {
    override val platformName: String = "Cobalt Engine"

    override fun canHandle(url: String): Boolean = true

    override suspend fun extract(url: String, config: RemoteConfigData): MediaResult = withContext(Dispatchers.IO) {
        val platform = detectPlatformName(url)
        val engineConfig = config.engines[platform.lowercase()]
        val primaryEndpoint = engineConfig?.primaryApi?.ifBlank { "https://api.cobalt.tools/api/json" }
            ?: "https://api.cobalt.tools/api/json"
        val fallbackEndpoint = engineConfig?.fallbackApi?.ifBlank { "https://co.wuk.sh/api/json" }
            ?: "https://co.wuk.sh/api/json"

        try {
            extractDirect(url, primaryEndpoint, platform)
        } catch (e: Exception) {
            Log.w("CobaltDownloader", "Primary API ($primaryEndpoint) failed, switching to fallback ($fallbackEndpoint)")
            extractDirect(url, fallbackEndpoint, platform)
        }
    }

    suspend fun extractDirect(url: String, endpoint: String, platform: String): MediaResult = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("url", url)
            put("videoQuality", "max")
            put("filenamePattern", "basic")
            put("audioFormat", "mp3")
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = payload.toString().toRequestBody(mediaType)

        val request = Request.Builder()
            .url(endpoint)
            .post(requestBody)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("User-Agent", "MediaDownloadApp/1.0")
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw IOException("HTTP error ${response.code} from $endpoint")
        }

        val bodyString = response.body?.string() ?: ""
        val json = JSONObject(bodyString)
        val status = json.optString("status", "")

        if (status == "tunnel" || status == "redirect" || status == "success") {
            val videoUrl = json.optString("url", "")
            val audioUrl = json.optString("audio", "")
            val filename = json.optString("filename", "$platform Video")

            if (videoUrl.isNotBlank()) {
                return@withContext MediaResult(
                    title = filename,
                    videoUrl = videoUrl,
                    audioUrl = audioUrl.ifBlank { null },
                    platform = platform,
                    sourceUrl = url,
                    qualityOptions = listOf(
                        QualityOption(label = "Original Quality (HD)", url = videoUrl, isHd = true)
                    )
                )
            }
        } else if (status == "picker" && json.has("picker")) {
            val picker = json.getJSONArray("picker")
            if (picker.length() > 0) {
                val first = picker.getJSONObject(0)
                val videoUrl = first.optString("url", "")
                return@withContext MediaResult(
                    title = "$platform Carousel",
                    videoUrl = videoUrl,
                    platform = platform,
                    sourceUrl = url,
                    qualityOptions = listOf(
                        QualityOption(label = "Selected Item", url = videoUrl)
                    )
                )
            }
        }

        throw IOException("Unrecognized extraction response from $endpoint")
    }

    private fun detectPlatformName(url: String): String {
        val lower = url.lowercase()
        return when {
            lower.contains("instagram.com") || lower.contains("instagr.am") -> "Instagram"
            lower.contains("twitter.com") || lower.contains("x.com") -> "Twitter"
            lower.contains("facebook.com") || lower.contains("fb.watch") -> "Facebook"
            lower.contains("capcut.") -> "CapCut"
            lower.contains("snapchat.com") -> "Snapchat"
            lower.contains("pinterest.") || lower.contains("pin.it") -> "Pinterest"
            lower.contains("threads.net") -> "Threads"
            lower.contains("reddit.com") -> "Reddit"
            lower.contains("likee.") -> "Likee"
            lower.contains("kwai.") -> "Kwai"
            lower.contains("linkedin.com") -> "LinkedIn"
            lower.contains("soundcloud.com") -> "SoundCloud"
            lower.contains("dailymotion.com") || lower.contains("dai.ly") -> "Dailymotion"
            lower.contains("tumblr.com") -> "Tumblr"
            else -> "Media"
        }
    }
}

class DirectWebExtractor(private val client: OkHttpClient) : BaseDownloaderEngine() {
    override val platformName: String = "Web Extractor"

    override fun canHandle(url: String): Boolean = true

    override suspend fun extract(url: String, config: RemoteConfigData): MediaResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val html = response.body?.string() ?: ""

                val ogVideoPattern = Pattern.compile("<meta\\s+(?:property|name)=[\"']og:video(?::secure_url)?[\"']\\s+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
                val ogVideoMatcher = ogVideoPattern.matcher(html)
                var videoUrl = if (ogVideoMatcher.find()) ogVideoMatcher.group(1) else null

                val ogTitlePattern = Pattern.compile("<meta\\s+(?:property|name)=[\"']og:title[\"']\\s+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
                val ogTitleMatcher = ogTitlePattern.matcher(html)
                val title = if (ogTitleMatcher.find()) ogTitleMatcher.group(1) else "Extracted Video"

                val ogImagePattern = Pattern.compile("<meta\\s+(?:property|name)=[\"']og:image[\"']\\s+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
                val ogImageMatcher = ogImagePattern.matcher(html)
                val thumb = if (ogImageMatcher.find()) ogImageMatcher.group(1) else null

                if (videoUrl.isNullOrBlank()) {
                    val srcPattern = Pattern.compile("<source\\s+[^>]*src=[\"']([^\"']+\\.mp4[^\"']*)[\"']", Pattern.CASE_INSENSITIVE)
                    val srcMatcher = srcPattern.matcher(html)
                    if (srcMatcher.find()) {
                        videoUrl = srcMatcher.group(1)
                    }
                }

                if (!videoUrl.isNullOrBlank()) {
                    val cleanUrl = videoUrl.replace("&amp;", "&")
                    return@withContext MediaResult(
                        title = title ?: "Video Download",
                        videoUrl = cleanUrl,
                        thumbnailUrl = thumb,
                        platform = "Media",
                        sourceUrl = url,
                        qualityOptions = listOf(QualityOption("High Definition (HD)", cleanUrl, isHd = true))
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("DirectWebExtractor", "Web extractor error: ${e.message}")
        }

        throw IOException("Extraction service reached maximum failover attempts. Ensure the link is public.")
    }
}

class MasterDownloadEngine(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) {
    private val tikwm = TikWMDownloader(client)
    private val cobalt = CobaltDownloader(client)
    private val webExtractor = DirectWebExtractor(client)

    fun isYouTubeUrl(url: String): Boolean {
        val lower = url.lowercase().trim()
        return lower.contains("youtube.com") ||
                lower.contains("youtu.be") ||
                lower.contains("yt.be")
    }

    suspend fun resolveMedia(url: String, config: RemoteConfigData): MediaResult = withContext(Dispatchers.IO) {
        val cleanUrl = url.trim()
        if (isYouTubeUrl(cleanUrl)) {
            throw YouTubeBlockedException()
        }

        // Dynamically iterate over platform keys in engines map
        for ((platformKey, engineConfig) in config.engines) {
            if (engineConfig.regex.isNotBlank()) {
                try {
                    val pattern = Pattern.compile(engineConfig.regex, Pattern.CASE_INSENSITIVE)
                    if (pattern.matcher(cleanUrl).find()) {
                        // Match found! Route to primary_api and auto-switch to fallback_api on failure
                        val primaryApi = engineConfig.primaryApi
                        val fallbackApi = engineConfig.fallbackApi

                        try {
                            if (primaryApi.contains("tikwm.com")) {
                                val res = tikwm.extractFromEndpoint(cleanUrl, primaryApi)
                                if (res != null) return@withContext res
                            } else {
                                return@withContext cobalt.extractDirect(cleanUrl, primaryApi, platformKey.replaceFirstChar { it.uppercase() })
                            }
                        } catch (e: Exception) {
                            Log.w("MasterDownloadEngine", "Primary API ($primaryApi) failed for $platformKey: ${e.message}. Auto-switching to fallback ($fallbackApi)")
                        }

                        // Try Fallback API if primary failed or returned null
                        if (fallbackApi.isNotBlank()) {
                            try {
                                if (fallbackApi.contains("tikwm.com")) {
                                    val res = tikwm.extractFromEndpoint(cleanUrl, fallbackApi)
                                    if (res != null) return@withContext res
                                } else {
                                    return@withContext cobalt.extractDirect(cleanUrl, fallbackApi, platformKey.replaceFirstChar { it.uppercase() })
                                }
                            } catch (e: Exception) {
                                Log.w("MasterDownloadEngine", "Fallback API ($fallbackApi) failed for $platformKey: ${e.message}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w("MasterDownloadEngine", "Regex parse error for $platformKey: ${e.message}")
                }
            }
        }

        // Secondary / Tertiary fallback
        if (tikwm.canHandle(cleanUrl)) {
            return@withContext tikwm.extract(cleanUrl, config)
        }

        try {
            return@withContext cobalt.extract(cleanUrl, config)
        } catch (e: Exception) {
            return@withContext webExtractor.extract(cleanUrl, config)
        }
    }
}
