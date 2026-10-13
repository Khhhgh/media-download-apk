package com.example.service

import android.net.Uri
import android.util.Log
import com.example.model.MediaResult
import com.example.model.QualityOption
import com.example.model.RemoteConfigData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
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

/**
 * Resolves shortened URLs (tt.site, vt.tiktok.com, vm.tiktok.com, fb.watch, t.co, etc.)
 * by following HTTP redirects to obtain the full canonical destination URL.
 */
object UrlResolver {
    private const val TAG = "UrlResolver"
    // Standard User-Agent for redirect resolution
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    private val redirectClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun isTikTokShortenedUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("tt.site") ||
                lower.contains("vt.tiktok.com") ||
                lower.contains("vm.tiktok.com") ||
                lower.contains("tiktok.com/t/")
    }

    fun cleanTrackingParams(url: String): String {
        return try {
            val uri = Uri.parse(url)
            val host = uri.host?.lowercase() ?: ""
            if (host.contains("tiktok.com") && url.contains("/video/")) {
                val path = uri.path ?: ""
                "https://$host$path"
            } else {
                url
            }
        } catch (_: Exception) {
            url
        }
    }

    suspend fun resolveRedirects(rawUrl: String): String = withContext(Dispatchers.IO) {
        val cleanUrl = extractUrlFromString(rawUrl)
        try {
            val request = Request.Builder()
                .url(cleanUrl)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .build()

            redirectClient.newCall(request).execute().use { response ->
                val finalUrl = response.request.url.toString()
                Log.d(TAG, "Resolved [$cleanUrl] -> [$finalUrl]")
                if (finalUrl.isNotBlank() && finalUrl != cleanUrl) {
                    return@withContext cleanTrackingParams(finalUrl)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Redirect resolution failed for $cleanUrl: ${e.message}")
        }
        cleanTrackingParams(cleanUrl)
    }

    fun extractUrlFromString(input: String): String {
        val trimmed = input.trim()
        val matcher = Pattern.compile("https?://[^\\s]+", Pattern.CASE_INSENSITIVE).matcher(trimmed)
        return if (matcher.find()) {
            matcher.group(0).trim().trimEnd('/', '?', '#')
        } else {
            trimmed
        }
    }
}

/**
 * High-performance TikTok Extractor supporting:
 * - tt.site & *.tt.site (e.g. tt.site/t/...)
 * - Shortened links (vt.tiktok.com, vm.tiktok.com, tiktok.com/t/...)
 * - Public TikWM API (HD 1080p without watermark, SD clean, MP3 audio, cover)
 * - Fallback via Cobalt cluster & HTML JSON scraper
 */
class TikTokDownloader(private val client: OkHttpClient) {
    private val tag = "TikTokDownloader"
    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    suspend fun extract(url: String, config: RemoteConfigData): MediaResult = withContext(Dispatchers.IO) {
        // Step 1: Expand shortened URLs (tt.site, vt.tiktok.com, vm.tiktok.com, tiktok.com/t/...)
        val isShortened = UrlResolver.isTikTokShortenedUrl(url)
        val expandedUrl = if (isShortened) {
            UrlResolver.resolveRedirects(url)
        } else {
            UrlResolver.cleanTrackingParams(url)
        }
        Log.d(tag, "Extracting TikTok media from: $expandedUrl (original: $url)")

        val primaryEndpoint = config.engines["tiktok"]?.primaryApi?.ifBlank { "https://www.tikwm.com/api/" }
            ?: "https://www.tikwm.com/api/"

        // Step 2: Query TikWM API with expanded URL, fallback to original if different
        val tikWmResult = tryTikWmGet(expandedUrl, primaryEndpoint)
            ?: tryTikWmPost(expandedUrl, primaryEndpoint)
            ?: (if (expandedUrl != url) tryTikWmGet(url, primaryEndpoint) else null)
            ?: (if (expandedUrl != url) tryTikWmPost(url, primaryEndpoint) else null)
            ?: tryTikWmGet(expandedUrl, "https://www.tikwm.com/api/")

        if (tikWmResult != null) {
            return@withContext tikWmResult
        }

        // Step 3: Fallback to Cobalt Instances
        Log.w(tag, "TikWM API unsuccessful, trying Cobalt cluster fallback...")
        try {
            return@withContext CobaltDownloader(client).extractDirect(expandedUrl, "https://api.cobalt.tools/api/json", "TikTok")
        } catch (e: Exception) {
            Log.w(tag, "Cobalt fallback failed: ${e.message}")
        }

        try {
            return@withContext CobaltDownloader(client).extractDirect(expandedUrl, "https://co.wuk.sh/api/json", "TikTok")
        } catch (e: Exception) {
            Log.w(tag, "Cobalt secondary fallback failed: ${e.message}")
        }

        throw IOException("Unable to extract TikTok video. Make sure the video is public and accessible.")
    }

    private fun tryTikWmGet(targetUrl: String, endpoint: String): MediaResult? {
        try {
            val encodedUrl = URLEncoder.encode(targetUrl, "UTF-8")
            val base = if (endpoint.endsWith("/")) endpoint else "$endpoint/"
            val requestUrl = "${base}?url=$encodedUrl&hd=1"

            val request = Request.Builder()
                .url(requestUrl)
                .header("User-Agent", userAgent)
                .header("Accept", "application/json")
                .header("Referer", "https://www.tikwm.com/")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    return parseTikWmResponse(body, targetUrl)
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "TikWM GET request failed: ${e.message}")
        }
        return null
    }

    private fun tryTikWmPost(targetUrl: String, endpoint: String): MediaResult? {
        try {
            val formBody = FormBody.Builder()
                .add("url", targetUrl)
                .add("hd", "1")
                .build()

            val request = Request.Builder()
                .url(endpoint)
                .post(formBody)
                .header("User-Agent", userAgent)
                .header("Accept", "application/json")
                .header("Referer", "https://www.tikwm.com/")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    return parseTikWmResponse(body, targetUrl)
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "TikWM POST request failed: ${e.message}")
        }
        return null
    }

    private fun parseTikWmResponse(jsonBody: String, sourceUrl: String): MediaResult? {
        try {
            val json = JSONObject(jsonBody)
            val code = json.optInt("code", -1)
            if (code == 0 && json.has("data")) {
                val data = json.getJSONObject("data")
                val title = data.optString("title", "TikTok Video").ifBlank { "TikTok Video" }
                var cover = data.optString("cover", "")
                var hdPlay = data.optString("hdplay", "")
                var play = data.optString("play", "")
                var music = data.optString("music", "")
                val author = data.optJSONObject("author")?.optString("nickname", "Creator") ?: "Creator"

                // Normalize relative URLs
                if (hdPlay.startsWith("/")) hdPlay = "https://www.tikwm.com$hdPlay"
                if (play.startsWith("/")) play = "https://www.tikwm.com$play"
                if (music.startsWith("/")) music = "https://www.tikwm.com$music"
                if (cover.startsWith("/")) cover = "https://www.tikwm.com$cover"

                var wmPlay = data.optString("wmplay", "")
                if (wmPlay.startsWith("/")) wmPlay = "https://www.tikwm.com$wmPlay"

                val primaryVideo = if (hdPlay.isNotBlank()) hdPlay else play
                val qualities = mutableListOf<QualityOption>()
                if (hdPlay.isNotBlank()) {
                    qualities.add(QualityOption(label = "1080p HD (No Watermark)", url = hdPlay, isHd = true))
                }
                if (play.isNotBlank()) {
                    qualities.add(QualityOption(label = "720p Clean (Fast)", url = play, isHd = false))
                }
                if (wmPlay.isNotBlank()) {
                    qualities.add(QualityOption(label = "Original (With Watermark)", url = wmPlay, isHd = false))
                }
                if (music.isNotBlank()) {
                    qualities.add(QualityOption(label = "Original Audio (MP3)", url = music, format = "mp3"))
                }

                if (primaryVideo.isNotBlank()) {
                    return MediaResult(
                        title = title,
                        videoUrl = primaryVideo,
                        audioUrl = music.ifBlank { null },
                        thumbnailUrl = cover.ifBlank { null },
                        platform = "TikTok",
                        author = author,
                        sourceUrl = sourceUrl,
                        qualityOptions = qualities
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "Failed to parse TikWM JSON: ${e.message}")
        }
        return null
    }
}

/**
 * Twitter / X Extractor:
 * - Resolves status ID and username
 * - Tier 1: Keyless public FxTwitter / VxTwitter API
 * - Tier 2: Cobalt cluster
 * - Tier 3: Twitsave parser
 */
class TwitterDownloader(private val client: OkHttpClient) {
    private val tag = "TwitterDownloader"

    suspend fun extract(url: String, config: RemoteConfigData): MediaResult = withContext(Dispatchers.IO) {
        val expandedUrl = UrlResolver.resolveRedirects(url)

        // Tier 1: FxTwitter API
        val fxResult = tryFxTwitter(expandedUrl)
        if (fxResult != null) return@withContext fxResult

        // Tier 2: Cobalt API
        try {
            val endpoint = config.engines["twitter"]?.primaryApi?.ifBlank { "https://api.cobalt.tools/api/json" }
                ?: "https://api.cobalt.tools/api/json"
            return@withContext CobaltDownloader(client).extractDirect(expandedUrl, endpoint, "Twitter/X")
        } catch (e: Exception) {
            Log.w(tag, "Cobalt twitter extraction failed: ${e.message}")
        }

        try {
            return@withContext CobaltDownloader(client).extractDirect(expandedUrl, "https://co.wuk.sh/api/json", "Twitter/X")
        } catch (e: Exception) {
            Log.w(tag, "Cobalt secondary twitter extraction failed: ${e.message}")
        }

        throw IOException("Unable to extract Twitter/X video. Please verify the tweet contains media and is public.")
    }

    private fun tryFxTwitter(url: String): MediaResult? {
        try {
            // Convert x.com / twitter.com to api.fxtwitter.com
            val uri = Uri.parse(url)
            val path = uri.path ?: return null
            val apiUrl = "https://api.fxtwitter.com$path"

            val request = Request.Builder()
                .url(apiUrl)
                .header("User-Agent", "Mozilla/5.0")
                .header("Accept", "application/json")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val json = JSONObject(body)
                    if (json.optInt("code", 0) == 200 && json.has("tweet")) {
                        val tweet = json.getJSONObject("tweet")
                        val text = tweet.optString("text", "Twitter Video")
                        val author = tweet.optJSONObject("author")?.optString("name", "X User") ?: "X User"

                        var videoUrl: String? = null
                        var thumbUrl: String? = null
                        val qualities = mutableListOf<QualityOption>()

                        if (tweet.has("media")) {
                            val mediaObj = tweet.getJSONObject("media")
                            if (mediaObj.has("videos")) {
                                val videosArray = mediaObj.getJSONArray("videos")
                                if (videosArray.length() > 0) {
                                    val vid = videosArray.getJSONObject(0)
                                    videoUrl = vid.optString("url")
                                    thumbUrl = vid.optString("thumbnail_url")
                                    qualities.add(QualityOption(label = "Original Quality (HD)", url = videoUrl, isHd = true))
                                }
                            }
                        }

                        if (!videoUrl.isNullOrBlank()) {
                            return MediaResult(
                                title = text.take(60),
                                videoUrl = videoUrl,
                                thumbnailUrl = thumbUrl,
                                platform = "Twitter/X",
                                author = author,
                                sourceUrl = url,
                                qualityOptions = qualities
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "FxTwitter extraction failed: ${e.message}")
        }
        return null
    }
}

/**
 * Facebook Extractor:
 * - Unshortens fb.watch
 * - Tier 1: Direct HTML parser extracting playable_url_quality_hd & playable_url
 * - Tier 2: Cobalt cluster
 */
class FacebookDownloader(private val client: OkHttpClient) {
    private val tag = "FacebookDownloader"
    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    suspend fun extract(url: String, config: RemoteConfigData): MediaResult = withContext(Dispatchers.IO) {
        val expandedUrl = UrlResolver.resolveRedirects(url)

        // Tier 1: Direct HTML scraping of public Facebook video stream
        val directResult = tryDirectFacebookHtml(expandedUrl)
        if (directResult != null) return@withContext directResult

        // Tier 2: Cobalt cluster
        try {
            val endpoint = config.engines["facebook"]?.primaryApi?.ifBlank { "https://api.cobalt.tools/api/json" }
                ?: "https://api.cobalt.tools/api/json"
            return@withContext CobaltDownloader(client).extractDirect(expandedUrl, endpoint, "Facebook")
        } catch (e: Exception) {
            Log.w(tag, "Cobalt Facebook extraction failed: ${e.message}")
        }

        try {
            return@withContext CobaltDownloader(client).extractDirect(expandedUrl, "https://co.wuk.sh/api/json", "Facebook")
        } catch (e: Exception) {
            Log.w(tag, "Cobalt secondary Facebook failed: ${e.message}")
        }

        throw IOException("Unable to extract Facebook video. Make sure the video is public.")
    }

    private fun tryDirectFacebookHtml(url: String): MediaResult? {
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val html = response.body?.string() ?: ""

                    var hdUrl: String? = null
                    var sdUrl: String? = null

                    // Regex match for HD stream
                    val hdPattern = Pattern.compile("\"(?:playable_url_quality_hd|browser_native_hd_url)\":\"([^\"]+)\"")
                    val hdMatcher = hdPattern.matcher(html)
                    if (hdMatcher.find()) {
                        hdUrl = cleanFacebookUrl(hdMatcher.group(1))
                    }

                    // Regex match for SD stream
                    val sdPattern = Pattern.compile("\"(?:playable_url|browser_native_sd_url)\":\"([^\"]+)\"")
                    val sdMatcher = sdPattern.matcher(html)
                    if (sdMatcher.find()) {
                        sdUrl = cleanFacebookUrl(sdMatcher.group(1))
                    }

                    // Fallback to og:video
                    if (hdUrl.isNullOrBlank() && sdUrl.isNullOrBlank()) {
                        val ogPattern = Pattern.compile("<meta\\s+(?:property|name)=[\"']og:video(?::secure_url)?[\"']\\s+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
                        val ogMatcher = ogPattern.matcher(html)
                        if (ogMatcher.find()) {
                            sdUrl = cleanFacebookUrl(ogMatcher.group(1))
                        }
                    }

                    val chosenUrl = hdUrl ?: sdUrl
                    if (!chosenUrl.isNullOrBlank()) {
                        val titlePattern = Pattern.compile("<meta\\s+(?:property|name)=[\"']og:title[\"']\\s+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
                        val titleMatcher = titlePattern.matcher(html)
                        val title = if (titleMatcher.find()) titleMatcher.group(1) else "Facebook Video"

                        val qualities = mutableListOf<QualityOption>()
                        if (!hdUrl.isNullOrBlank()) {
                            qualities.add(QualityOption(label = "1080p HD Quality", url = hdUrl, isHd = true))
                        }
                        if (!sdUrl.isNullOrBlank()) {
                            qualities.add(QualityOption(label = "Standard Quality (Fast)", url = sdUrl, isHd = false))
                        }

                        return MediaResult(
                            title = title,
                            videoUrl = chosenUrl,
                            platform = "Facebook",
                            sourceUrl = url,
                            qualityOptions = qualities
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "Direct Facebook HTML scraping failed: ${e.message}")
        }
        return null
    }

    private fun cleanFacebookUrl(raw: String): String {
        return raw.replace("\\/", "/")
            .replace("\\u002526", "&")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
    }
}

/**
 * Instagram Extractor:
 * - Cobalt cluster with custom headers
 * - OpenGraph & Embedded JSON Scraper
 */
class InstagramDownloader(private val client: OkHttpClient) {
    private val tag = "InstagramDownloader"

    suspend fun extract(url: String, config: RemoteConfigData): MediaResult = withContext(Dispatchers.IO) {
        val expandedUrl = UrlResolver.resolveRedirects(url)

        // Tier 1: Cobalt Primary
        try {
            val endpoint = config.engines["instagram"]?.primaryApi?.ifBlank { "https://api.cobalt.tools/api/json" }
                ?: "https://api.cobalt.tools/api/json"
            return@withContext CobaltDownloader(client).extractDirect(expandedUrl, endpoint, "Instagram")
        } catch (e: Exception) {
            Log.w(tag, "Instagram Cobalt primary failed: ${e.message}")
        }

        // Tier 2: Cobalt Secondary
        try {
            return@withContext CobaltDownloader(client).extractDirect(expandedUrl, "https://co.wuk.sh/api/json", "Instagram")
        } catch (e: Exception) {
            Log.w(tag, "Instagram Cobalt secondary failed: ${e.message}")
        }

        // Tier 3: Direct Web Meta / OpenGraph
        try {
            return@withContext DirectWebExtractor(client).extract(expandedUrl, config)
        } catch (e: Exception) {
            Log.w(tag, "Instagram web extractor failed: ${e.message}")
        }

        throw IOException("Unable to extract Instagram video. Ensure the post/reel is public.")
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
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
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
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
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

/**
 * Master Download Engine orchestrating all platform scrapers,
 * unshortening redirects, and multi-tier failovers.
 */
class MasterDownloadEngine(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
) {
    private val tiktok = TikTokDownloader(client)
    private val twitter = TwitterDownloader(client)
    private val facebook = FacebookDownloader(client)
    private val instagram = InstagramDownloader(client)
    private val cobalt = CobaltDownloader(client)
    private val webExtractor = DirectWebExtractor(client)

    fun isYouTubeUrl(url: String): Boolean {
        val lower = url.lowercase().trim()
        return lower.contains("youtube.com") ||
                lower.contains("youtu.be") ||
                lower.contains("yt.be")
    }

    suspend fun resolveMedia(rawUrl: String, config: RemoteConfigData): MediaResult = withContext(Dispatchers.IO) {
        val cleanUrl = UrlResolver.extractUrlFromString(rawUrl)
        if (cleanUrl.isBlank()) {
            throw IllegalArgumentException("Empty or invalid link")
        }

        if (isYouTubeUrl(cleanUrl)) {
            throw YouTubeBlockedException()
        }

        val lower = cleanUrl.lowercase()

        // 1. TikTok & Douyin (including tt.site, vt.tiktok.com & vm.tiktok.com)
        if (lower.contains("tiktok.com") || lower.contains("douyin.com") || lower.contains("tt.site")) {
            return@withContext tiktok.extract(cleanUrl, config)
        }

        // 2. Twitter / X
        if (lower.contains("twitter.com") || lower.contains("x.com")) {
            return@withContext twitter.extract(cleanUrl, config)
        }

        // 3. Facebook & fb.watch
        if (lower.contains("facebook.com") || lower.contains("fb.watch")) {
            return@withContext facebook.extract(cleanUrl, config)
        }

        // 4. Instagram
        if (lower.contains("instagram.com") || lower.contains("instagr.am")) {
            return@withContext instagram.extract(cleanUrl, config)
        }

        // 5. Dynamic Remote Engines Regex Matching
        for ((platformKey, engineConfig) in config.engines) {
            if (engineConfig.regex.isNotBlank()) {
                try {
                    val pattern = Pattern.compile(engineConfig.regex, Pattern.CASE_INSENSITIVE)
                    if (pattern.matcher(cleanUrl).find()) {
                        val primaryApi = engineConfig.primaryApi
                        val fallbackApi = engineConfig.fallbackApi

                        try {
                            if (primaryApi.contains("tikwm.com")) {
                                return@withContext tiktok.extract(cleanUrl, config)
                            } else {
                                return@withContext cobalt.extractDirect(cleanUrl, primaryApi, platformKey.replaceFirstChar { it.uppercase() })
                            }
                        } catch (e: Exception) {
                            Log.w("MasterDownloadEngine", "Primary API failed for $platformKey: ${e.message}")
                        }

                        if (fallbackApi.isNotBlank()) {
                            try {
                                return@withContext cobalt.extractDirect(cleanUrl, fallbackApi, platformKey.replaceFirstChar { it.uppercase() })
                            } catch (e: Exception) {
                                Log.w("MasterDownloadEngine", "Fallback API failed for $platformKey: ${e.message}")
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        // 6. Generic Fallback
        try {
            return@withContext cobalt.extract(cleanUrl, config)
        } catch (_: Exception) {
            return@withContext webExtractor.extract(cleanUrl, config)
        }
    }
}
