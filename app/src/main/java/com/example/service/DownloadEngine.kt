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

// Standard realistic User-Agent required for all HTTP requests across the app
const val UNIVERSAL_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

class YouTubeBlockedException(
    message: String = "YouTube is strictly blocked. In compliance with developer policies, YouTube downloading is not supported."
) : Exception(message)

abstract class BaseDownloaderEngine {
    abstract val platformName: String
    abstract fun canHandle(url: String): Boolean
    abstract suspend fun extract(url: String, config: RemoteConfigData): MediaResult
}

/**
 * Robust URL Sanitizer and Redirect Resolver:
 * - Sanitizes input: trims whitespace, control chars, accidental quotes/brackets/slashes.
 * - Extracts valid URL via regex (https?://[^\s]+) even if surrounded by captions/text.
 * - Resolves HTTP 301/302 redirects with universal User-Agent.
 */
object UrlResolver {
    private const val TAG = "UrlResolver"

    private val redirectClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /**
     * Strictly extracts valid URL from user input, removing extra captions, quotes, and punctuation.
     */
    fun sanitizeAndExtractUrl(input: String): String {
        if (input.isBlank()) return ""

        // Extract first valid HTTP/HTTPS link using regex: https?://[^\s"'>]+
        val pattern = Pattern.compile("https?://[^\\s\"'>]+", Pattern.CASE_INSENSITIVE)
        val matcher = pattern.matcher(input)

        return if (matcher.find()) {
            var url = matcher.group(0).trim()
            // Strip trailing punctuation and accidental trailing slashes often copied alongside social links
            url = url.trimEnd('.', ',', ';', ':', ')', ']', '}', '\'', '"', '\\', '>', '<')
            while (url.endsWith("/") && !url.endsWith("://")) {
                url = url.substring(0, url.length - 1)
            }
            url.trimEnd('.', ',', ';', ':', ')', ']', '}', '\'', '"', '\\', '>', '<')
        } else {
            // Strip accidental prefixes (such as leading '/', whitespace, markdown, or social share captions)
            val trimmed = input.trim().trimStart('/', '\\', ' ', '\t', '\n', '\r', '"', '\'', '<', '>', '#', '*', '`')
            if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
                val candidate = trimmed.split("\\s+".toRegex())[0]
                var cand = candidate.trimEnd('.', ',', ';', ':', ')', ']', '}', '\'', '"', '\\', '>', '<')
                while (cand.endsWith("/") && !cand.endsWith("://")) {
                    cand = cand.substring(0, cand.length - 1)
                }
                cand.trimEnd('.', ',', ';', ':', ')', ']', '}', '\'', '"', '\\', '>', '<')
            } else {
                trimmed
            }
        }
    }

    /**
     * Backward-compatible alias for existing callers
     */
    fun extractUrlFromString(input: String): String = sanitizeAndExtractUrl(input)

    fun isTikTokShortenedUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("tt.site") ||
                lower.contains("vt.tiktok.com") ||
                lower.contains("vm.tiktok.com") ||
                lower.contains("tiktok.com/t/")
    }

    fun cleanTrackingParams(url: String): String {
        return try {
            val qIndex = url.indexOf('?')
            val base = if (qIndex != -1) url.substring(0, qIndex) else url
            val hashIndex = base.indexOf('#')
            val cleanBase = if (hashIndex != -1) base.substring(0, hashIndex) else base

            if (url.contains("tiktok.com", ignoreCase = true) && url.contains("/video/")) {
                cleanBase
            } else if (url.contains("pinterest.", ignoreCase = true) && url.contains("/pin/")) {
                cleanBase
            } else if (qIndex != -1 && (url.contains("utm_", ignoreCase = true) ||
                        url.contains("is_from_webapp", ignoreCase = true) ||
                        url.contains("fbclid", ignoreCase = true) ||
                        url.contains("igshid", ignoreCase = true))) {
                cleanBase
            } else {
                url
            }
        } catch (_: Exception) {
            url
        }
    }

    private val noRedirectClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    suspend fun resolveRedirects(rawUrl: String): String = withContext(Dispatchers.IO) {
        val cleanUrl = sanitizeAndExtractUrl(rawUrl)
        if (cleanUrl.isBlank()) return@withContext rawUrl

        var current = cleanUrl
        var hops = 0
        val maxHops = 10

        // Trace up to 10 redirect hops explicitly for pin.it, bit.ly, t.co, vm.tiktok.com, fb.watch, etc.
        while (hops < maxHops) {
            try {
                val request = Request.Builder()
                    .url(current)
                    .header("User-Agent", UNIVERSAL_USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .build()

                val response = noRedirectClient.newCall(request).execute()
                val code = response.code
                val location = response.header("Location")
                response.close()

                if ((code in 300..399) && !location.isNullOrBlank()) {
                    val nextUrl = if (location.startsWith("http://") || location.startsWith("https://")) {
                        location
                    } else {
                        val scheme = if (current.startsWith("http://", ignoreCase = true)) "http" else "https"
                        val host = current.substringAfter("://").substringBefore('/')
                        if (location.startsWith("//")) {
                            "$scheme:$location"
                        } else if (location.startsWith("/")) {
                            "$scheme://$host$location"
                        } else {
                            "$scheme://$host/$location"
                        }
                    }
                    Log.d(TAG, "Redirect hop $hops: [$current] -> [$nextUrl] (code $code)")
                    current = nextUrl
                    hops++
                } else {
                    break
                }
            } catch (e: Exception) {
                Log.w(TAG, "Redirect loop interrupted at hop $hops for $current: ${e.message}")
                break
            }
        }

        // Secondary check with OkHttpClient followRedirects for any Javascript or intermediate redirects
        try {
            val req = Request.Builder()
                .url(current)
                .header("User-Agent", UNIVERSAL_USER_AGENT)
                .build()
            redirectClient.newCall(req).execute().use { res ->
                val finalUrl = res.request.url.toString()
                if (finalUrl.isNotBlank() && finalUrl != current) {
                    current = finalUrl
                }
            }
        } catch (_: Exception) {}

        cleanTrackingParams(current)
    }
}

/**
 * TikTok Downloader:
 * - Engine 1: TikWM API (GET & POST) -> HD 1080p, SD, MP3, Cover
 * - Engine 2: Cobalt cluster (Primary & Secondary)
 * - Engine 3: Alternative TikWM mirror & oEmbed/HTML Scraper
 */
class TikTokDownloader(private val client: OkHttpClient) {
    private val tag = "TikTokDownloader"

    suspend fun extract(url: String, config: RemoteConfigData): MediaResult = withContext(Dispatchers.IO) {
        // Expand shortened URLs (tt.site, vt.tiktok.com, vm.tiktok.com, tiktok.com/t/...)
        val expandedUrl = UrlResolver.resolveRedirects(url)
        Log.d(tag, "Extracting TikTok from: $expandedUrl (original: $url)")

        // --- Engine 1: TikWM API ---
        try {
            val primaryEndpoint = config.engines["tiktok"]?.primaryApi?.ifBlank { "https://www.tikwm.com/api/" }
                ?: "https://www.tikwm.com/api/"

            val tikWmResult = tryTikWmGet(expandedUrl, primaryEndpoint)
                ?: tryTikWmPost(expandedUrl, primaryEndpoint)
                ?: (if (expandedUrl != url) tryTikWmGet(url, primaryEndpoint) else null)
                ?: (if (expandedUrl != url) tryTikWmPost(url, primaryEndpoint) else null)
                ?: tryTikWmGet(expandedUrl, "https://www.tikwm.com/api/")

            if (tikWmResult != null) {
                return@withContext tikWmResult
            }
        } catch (e: Exception) {
            Log.w(tag, "Engine 1 (TikWM) failed: ${e.message}")
        }

        // --- Engine 2: Cobalt API ---
        try {
            val primaryCobalt = config.engines["tiktok"]?.fallbackApi?.ifBlank { "https://api.cobalt.tools/api/json" }
                ?: "https://api.cobalt.tools/api/json"
            return@withContext CobaltDownloader(client).extractDirect(expandedUrl, primaryCobalt, "TikTok")
        } catch (e: Exception) {
            Log.w(tag, "Engine 2 (Cobalt primary) failed: ${e.message}")
        }

        try {
            return@withContext CobaltDownloader(client).extractDirect(expandedUrl, "https://co.wuk.sh/api/json", "TikTok")
        } catch (e: Exception) {
            Log.w(tag, "Engine 2 (Cobalt secondary) failed: ${e.message}")
        }

        // --- Engine 3: Direct oEmbed / HTML Scraper & Mirror ---
        try {
            val htmlResult = tryTikTokHtmlRegex(expandedUrl)
            if (htmlResult != null) return@withContext htmlResult
        } catch (e: Exception) {
            Log.w(tag, "Engine 3 (HTML Regex) failed: ${e.message}")
        }

        try {
            val mirrorResult = tryTikWmGet(expandedUrl, "https://tikwm.com/api/")
            if (mirrorResult != null) return@withContext mirrorResult
        } catch (e: Exception) {
            Log.w(tag, "Engine 3 (TikWM mirror) failed: ${e.message}")
        }

        try {
            val oembedResult = tryTikTokOembed(expandedUrl)
            if (oembedResult != null) return@withContext oembedResult
        } catch (e: Exception) {
            Log.w(tag, "Engine 3 (oEmbed) failed: ${e.message}")
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
                .header("User-Agent", UNIVERSAL_USER_AGENT)
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
                .header("User-Agent", UNIVERSAL_USER_AGENT)
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

                if (hdPlay.startsWith("/")) hdPlay = "https://www.tikwm.com$hdPlay"
                if (play.startsWith("/")) play = "https://www.tikwm.com$play"
                if (music.startsWith("/")) music = "https://www.tikwm.com$music"
                if (cover.startsWith("/")) cover = "https://www.tikwm.com$cover"

                var wmPlay = data.optString("wmplay", "")
                if (wmPlay.startsWith("/")) wmPlay = "https://www.tikwm.com$wmPlay"

                // Check for TikTok Photo Album / Slideshow (`images` JSON array)
                val photoUrls = mutableListOf<String>()
                if (data.has("images")) {
                    val imagesArray = data.optJSONArray("images")
                    if (imagesArray != null && imagesArray.length() > 0) {
                        for (i in 0 until imagesArray.length()) {
                            var imgUrl = imagesArray.optString(i, "")
                            if (imgUrl.isNotBlank()) {
                                if (imgUrl.startsWith("/")) imgUrl = "https://www.tikwm.com$imgUrl"
                                photoUrls.add(imgUrl)
                            }
                        }
                    }
                }

                // If this is a photo album post
                if (photoUrls.isNotEmpty()) {
                    val albumTitle = title.ifBlank { "TikTok Photo Album (${photoUrls.size} Photos)" }
                    val qualities = mutableListOf<QualityOption>()
                    qualities.add(
                        QualityOption(
                            label = "Download All Photos (${photoUrls.size} JPG)",
                            url = photoUrls.first(),
                            format = "jpg",
                            isHd = true
                        )
                    )
                    if (music.isNotBlank()) {
                        qualities.add(
                            QualityOption(
                                label = "Original Audio (MP3)",
                                url = music,
                                format = "mp3"
                            )
                        )
                    }

                    return MediaResult(
                        title = albumTitle,
                        videoUrl = photoUrls.first(),
                        audioUrl = music.ifBlank { null },
                        thumbnailUrl = cover.ifBlank { photoUrls.firstOrNull() },
                        platform = "TikTok",
                        author = author,
                        sourceUrl = sourceUrl,
                        qualityOptions = qualities,
                        photoUrls = photoUrls
                    )
                }

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

    private fun tryTikTokOembed(url: String): MediaResult? {
        try {
            val encoded = URLEncoder.encode(url, "UTF-8")
            val request = Request.Builder()
                .url("https://www.tiktok.com/oembed?url=$encoded")
                .header("User-Agent", UNIVERSAL_USER_AGENT)
                .header("Accept", "application/json")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val json = JSONObject(body)
                    val title = json.optString("title", "TikTok Video")
                    val author = json.optString("author_name", "TikTok Creator")
                    val thumb = json.optString("thumbnail_url", "")
                    // If oEmbed returns, use direct web extractor with the extracted title/author
                    return DirectWebExtractor(client).extractDirectWithMeta(url, title, author, thumb, "TikTok")
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "TikTok oEmbed failed: ${e.message}")
        }
        return null
    }

    private fun tryTikTokHtmlRegex(url: String): MediaResult? {
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", UNIVERSAL_USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val html = response.body?.string() ?: ""
                    val playAddrPattern = Pattern.compile("\"(?:playAddr|downloadAddr)\":\"([^\"]+)\"")
                    val matcher = playAddrPattern.matcher(html)
                    if (matcher.find()) {
                        val videoUrl = matcher.group(1).replace("\\u0026", "&").replace("\\/", "/")
                        return MediaResult(
                            title = "TikTok Video",
                            videoUrl = videoUrl,
                            platform = "TikTok",
                            sourceUrl = url,
                            qualityOptions = listOf(QualityOption("HD Video", videoUrl, isHd = true))
                        )
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }
}

/**
 * Instagram Downloader:
 * - Engine 1: FastDL / SnapInsta / SaveIG API
 * - Engine 2: Cobalt API (Primary & Secondary)
 * - Engine 3: Public GraphQL endpoint with headers & Direct OpenGraph Scraper
 */
class InstagramDownloader(private val client: OkHttpClient) {
    private val tag = "InstagramDownloader"

    suspend fun extract(url: String, config: RemoteConfigData): MediaResult = withContext(Dispatchers.IO) {
        val expandedUrl = UrlResolver.resolveRedirects(url)

        // --- Engine 1: FastDL / SnapInsta API Endpoint ---
        try {
            val snapResult = tryFastDlOrSnapInsta(expandedUrl)
            if (snapResult != null) return@withContext snapResult
        } catch (e: Exception) {
            Log.w(tag, "Engine 1 (FastDL/SnapInsta) failed: ${e.message}")
        }

        // --- Engine 2: Cobalt API ---
        try {
            val endpoint = config.engines["instagram"]?.primaryApi?.ifBlank { "https://api.cobalt.tools/api/json" }
                ?: "https://api.cobalt.tools/api/json"
            return@withContext CobaltDownloader(client).extractDirect(expandedUrl, endpoint, "Instagram")
        } catch (e: Exception) {
            Log.w(tag, "Engine 2 (Cobalt primary) failed: ${e.message}")
        }

        try {
            return@withContext CobaltDownloader(client).extractDirect(expandedUrl, "https://co.wuk.sh/api/json", "Instagram")
        } catch (e: Exception) {
            Log.w(tag, "Engine 2 (Cobalt secondary) failed: ${e.message}")
        }

        // --- Engine 3: Public GraphQL endpoint / OpenGraph Scraper ---
        try {
            val graphResult = tryInstagramPublicGraph(expandedUrl)
            if (graphResult != null) return@withContext graphResult
        } catch (e: Exception) {
            Log.w(tag, "Engine 3 (Public Graph) failed: ${e.message}")
        }

        try {
            return@withContext DirectWebExtractor(client).extract(expandedUrl, config)
        } catch (e: Exception) {
            Log.w(tag, "Engine 3 (Direct Web) failed: ${e.message}")
        }

        throw IOException("Unable to extract Instagram video. Ensure the post/reel is public.")
    }

    private fun tryFastDlOrSnapInsta(url: String): MediaResult? {
        try {
            // FastDL / SaveIG search endpoint
            val formBody = FormBody.Builder()
                .add("q", url)
                .add("t", "media")
                .add("lang", "en")
                .build()

            val request = Request.Builder()
                .url("https://v3.fastdl.app/api/convert")
                .post(formBody)
                .header("User-Agent", UNIVERSAL_USER_AGENT)
                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                .header("Origin", "https://fastdl.app")
                .header("Referer", "https://fastdl.app/")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val json = JSONObject(body)
                    if (json.has("url")) {
                        val videoArray = json.optJSONArray("url")
                        if (videoArray != null && videoArray.length() > 0) {
                            val first = videoArray.getJSONObject(0)
                            val videoUrl = first.optString("url")
                            if (videoUrl.isNotBlank()) {
                                return MediaResult(
                                    title = "Instagram Reel",
                                    videoUrl = videoUrl,
                                    platform = "Instagram",
                                    sourceUrl = url,
                                    qualityOptions = listOf(
                                        QualityOption("1080p HD Quality", videoUrl, isHd = true)
                                    )
                                )
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "FastDL conversion failed: ${e.message}")
        }
        return null
    }

    private fun tryInstagramPublicGraph(url: String): MediaResult? {
        try {
            val clean = url.split("?").first().trimEnd('/')
            val jsonUrl = "$clean/?__a=1&__d=dis"

            val request = Request.Builder()
                .url(jsonUrl)
                .header("User-Agent", UNIVERSAL_USER_AGENT)
                .header("Accept", "application/json")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val json = JSONObject(body)
                    val items = json.optJSONArray("items")
                    if (items != null && items.length() > 0) {
                        val item = items.getJSONObject(0)
                        val videoVersions = item.optJSONArray("video_versions")
                        if (videoVersions != null && videoVersions.length() > 0) {
                            val best = videoVersions.getJSONObject(0)
                            val videoUrl = best.optString("url")
                            val caption = item.optJSONObject("caption")?.optString("text", "Instagram Video") ?: "Instagram Video"
                            val author = item.optJSONObject("user")?.optString("username", "Instagram User") ?: "Instagram User"

                            if (videoUrl.isNotBlank()) {
                                return MediaResult(
                                    title = caption.take(60),
                                    videoUrl = videoUrl,
                                    platform = "Instagram",
                                    author = author,
                                    sourceUrl = url,
                                    qualityOptions = listOf(
                                        QualityOption("1080p HD Quality", videoUrl, isHd = true)
                                    )
                                )
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "Instagram GraphQL parse failed: ${e.message}")
        }
        return null
    }
}

/**
 * Pinterest Downloader (pin.it & pinterest.com):
 * - Engine 1: Direct HTML Scraper (__PWS_DATA__, application/ld+json, Video/Original Image Regex)
 * - Engine 2: Cobalt API (POST payload with URL)
 * - Engine 3: Universal OpenGraph Fallback (og:video & og:image)
 */
class PinterestDownloader(private val client: OkHttpClient) {
    private val tag = "PinterestDownloader"

    suspend fun extract(url: String, config: RemoteConfigData): MediaResult = withContext(Dispatchers.IO) {
        val resolvedUrl = UrlResolver.resolveRedirects(url)
        Log.d(tag, "Extracting Pinterest from canonical URL: $resolvedUrl (original: $url)")

        // --- Engine 1: Direct HTML Scraper (__PWS_DATA__, ld+json, og tags, and direct stream regex) ---
        try {
            val directResult = tryDirectPinterestScrape(resolvedUrl)
            if (directResult != null) return@withContext directResult
        } catch (e: Exception) {
            Log.w(tag, "Engine 1 (Direct Pinterest Scrape) failed: ${e.message}")
        }

        // --- Engine 2: Cobalt API ---
        try {
            val endpoint = config.engines["pinterest"]?.primaryApi?.ifBlank { "https://api.cobalt.tools/api/json" }
                ?: "https://api.cobalt.tools/api/json"
            return@withContext CobaltDownloader(client).extractDirect(resolvedUrl, endpoint, "Pinterest")
        } catch (e: Exception) {
            Log.w(tag, "Engine 2 (Cobalt primary) failed: ${e.message}")
        }

        try {
            return@withContext CobaltDownloader(client).extractDirect(resolvedUrl, "https://co.wuk.sh/api/json", "Pinterest")
        } catch (e: Exception) {
            Log.w(tag, "Engine 2 (Cobalt secondary) failed: ${e.message}")
        }

        // --- Engine 3: Universal OpenGraph Fallback ---
        try {
            val ogResult = tryPinterestOpenGraph(resolvedUrl)
            if (ogResult != null) return@withContext ogResult
        } catch (e: Exception) {
            Log.w(tag, "Engine 3 (OpenGraph) failed: ${e.message}")
        }

        throw IOException("Unable to extract Pinterest media. Ensure the link is public.")
    }

    private fun tryDirectPinterestScrape(url: String): MediaResult? {
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", UNIVERSAL_USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Sec-Fetch-Mode", "navigate")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val html = response.body?.string() ?: return null

                // Extract page title and fallback thumbnail
                val titlePattern = Pattern.compile("<meta\\s+(?:property|name)=[\"']og:title[\"']\\s+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
                val titleMatcher = titlePattern.matcher(html)
                val pageTitle = if (titleMatcher.find()) titleMatcher.group(1).replace("&amp;", "&").trim() else "Pinterest Media"

                val ogImgPattern = Pattern.compile("<meta\\s+(?:property|name)=[\"']og:image[\"']\\s+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
                val ogImgMatcher = ogImgPattern.matcher(html)
                val ogImage = if (ogImgMatcher.find()) ogImgMatcher.group(1).replace("&amp;", "&").trim() else null

                // ==============================================================
                // STEP 1: CHECK FOR VIDEO STREAMS FIRST (Strict Priority)
                // ==============================================================
                var detectedVideoUrl: String? = null

                // 1A. Inspect __PWS_DATA__ for video_list streams (V_720P, V_EXP7, V_HLSV4, or direct .mp4)
                val pwsPattern = Pattern.compile("<script[^>]+id=[\"']__PWS_DATA__[\"'][^>]*>(.*?)</script>", Pattern.DOTALL)
                val pwsMatcher = pwsPattern.matcher(html)
                var pwsJsonStr = ""
                if (pwsMatcher.find()) {
                    pwsJsonStr = pwsMatcher.group(1).trim()

                    // Look for V_720P video
                    val v720Pattern = Pattern.compile("\"V_720P\"\\s*:\\s*\\{[^}]*\"url\"\\s*:\\s*\"(https:[^\"\\\\]+?)\"")
                    val v720Matcher = v720Pattern.matcher(pwsJsonStr)
                    if (v720Matcher.find()) {
                        detectedVideoUrl = v720Matcher.group(1).replace("\\/", "/")
                    }

                    // Look for V_EXP7 video
                    if (detectedVideoUrl == null) {
                        val vExpPattern = Pattern.compile("\"V_EXP7\"\\s*:\\s*\\{[^}]*\"url\"\\s*:\\s*\"(https:[^\"\\\\]+?)\"")
                        val vExpMatcher = vExpPattern.matcher(pwsJsonStr)
                        if (vExpMatcher.find()) {
                            detectedVideoUrl = vExpMatcher.group(1).replace("\\/", "/")
                        }
                    }

                    // Look for any MP4 inside video_list
                    if (detectedVideoUrl == null) {
                        val videoListPattern = Pattern.compile("\"video_list\"\\s*:\\s*\\{[^}]*\"url\"\\s*:\\s*\"(https:[^\"\\\\]+?\\.mp4[^\"\\\\]*?)\"")
                        val vlMatcher = videoListPattern.matcher(pwsJsonStr)
                        if (vlMatcher.find()) {
                            detectedVideoUrl = vlMatcher.group(1).replace("\\/", "/")
                        }
                    }

                    // Look for any general Pinterest video URL in __PWS_DATA__
                    if (detectedVideoUrl == null) {
                        val videoRegex = Pattern.compile("\"url\":\"(https:[^\"\\\\]+?(?:v[0-9]?\\.pinimg\\.com|/videos/)[^\"\\\\]*?\\.mp4[^\"\\\\]*?)\"")
                        val vMatch = videoRegex.matcher(pwsJsonStr)
                        if (vMatch.find()) {
                            detectedVideoUrl = vMatch.group(1).replace("\\/", "/")
                        }
                    }
                }

                // 1B. Inspect application/ld+json for contentUrl (for VideoObject)
                if (detectedVideoUrl == null) {
                    val ldJsonPattern = Pattern.compile("<script[^>]+type=[\"']application/ld\\+json[\"'][^>]*>(.*?)</script>", Pattern.DOTALL)
                    val ldMatcher = ldJsonPattern.matcher(html)
                    while (ldMatcher.find()) {
                        try {
                            val json = JSONObject(ldMatcher.group(1).trim())
                            val contentUrl = json.optString("contentUrl", "")
                            if (contentUrl.isNotBlank() && (contentUrl.contains(".mp4") || contentUrl.contains("v.pinimg.com"))) {
                                detectedVideoUrl = contentUrl
                                break
                            }
                        } catch (_: Exception) {}
                    }
                }

                // 1C. Inspect OpenGraph video tags
                if (detectedVideoUrl == null) {
                    val ogVideoPattern = Pattern.compile("<meta\\s+(?:property|name)=[\"'](?:og:video|og:video:url|og:video:secure_url)[\"']\\s+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
                    val ogMatcher = ogVideoPattern.matcher(html)
                    if (ogMatcher.find()) {
                        detectedVideoUrl = ogMatcher.group(1).replace("&amp;", "&").replace("\\/", "/").trim()
                    }
                }

                // 1D. Fallback HTML regex for Pinterest video streams (.mp4)
                if (detectedVideoUrl == null) {
                    val mp4Pattern = Pattern.compile("\"(https://v[0-9]?\\.pinimg\\.com/videos/[^\"]+\\.mp4[^\"]*)\"")
                    val mp4Matcher = mp4Pattern.matcher(html)
                    if (mp4Matcher.find()) {
                        detectedVideoUrl = mp4Matcher.group(1).replace("\\/", "/")
                    }
                }

                if (detectedVideoUrl == null) {
                    val anyMp4Pattern = Pattern.compile("(https://[^\"'<>\\s]+\\.(?:mp4|m3u8)[^\"'<>\\s]*)")
                    val anyMp4Matcher = anyMp4Pattern.matcher(html)
                    while (anyMp4Matcher.find()) {
                        val candidate = anyMp4Matcher.group(1).replace("\\/", "/").replace("&amp;", "&")
                        if (candidate.contains("pinimg.com") || candidate.contains("pinterest.com")) {
                            detectedVideoUrl = candidate
                            break
                        }
                    }
                }

                // IF A VIDEO STREAM WAS FOUND: Strictly return as VIDEO (.mp4)
                if (detectedVideoUrl != null && detectedVideoUrl.isNotBlank()) {
                    val cleanVideo = detectedVideoUrl.replace("&amp;", "&").trim()
                    return MediaResult(
                        title = if (pageTitle != "Pinterest Media") pageTitle else "Pinterest Video",
                        videoUrl = cleanVideo,
                        thumbnailUrl = ogImage,
                        platform = "Pinterest",
                        sourceUrl = url,
                        qualityOptions = listOf(
                            QualityOption(
                                label = "High Definition (HD) MP4",
                                url = cleanVideo,
                                format = "mp4",
                                isHd = true
                            )
                        )
                    )
                }

                // ==============================================================
                // STEP 2: NO VIDEO STREAM FOUND -> HANDLE AS STATIC IMAGE (.jpg)
                // ==============================================================
                var detectedImageUrl: String? = null

                // 2A. In __PWS_DATA__, look for highest resolution images (orig / originals)
                if (pwsJsonStr.isNotBlank()) {
                    val origUrlPattern = Pattern.compile("\"(?:orig|original)\"\\s*:\\s*\\{[^}]*\"url\"\\s*:\\s*\"(https:[^\"\\\\]+?)\"")
                    val origMatcher = origUrlPattern.matcher(pwsJsonStr)
                    if (origMatcher.find()) {
                        detectedImageUrl = origMatcher.group(1).replace("\\/", "/")
                    }

                    if (detectedImageUrl == null) {
                        val origRegex = Pattern.compile("\"(https://i\\.pinimg\\.com/originals/[^\"]+)\"")
                        val origMatch = origRegex.matcher(pwsJsonStr)
                        if (origMatch.find()) {
                            detectedImageUrl = origMatch.group(1).replace("\\/", "/")
                        }
                    }

                    if (detectedImageUrl == null) {
                        val p736Regex = Pattern.compile("\"(https://i\\.pinimg\\.com/736x/[^\"]+)\"")
                        val p736Match = p736Regex.matcher(pwsJsonStr)
                        if (p736Match.find()) {
                            detectedImageUrl = p736Match.group(1).replace("\\/", "/")
                        }
                    }
                }

                // 2B. Inspect application/ld+json for image
                if (detectedImageUrl == null) {
                    val ldJsonPattern = Pattern.compile("<script[^>]+type=[\"']application/ld\\+json[\"'][^>]*>(.*?)</script>", Pattern.DOTALL)
                    val ldMatcher = ldJsonPattern.matcher(html)
                    while (ldMatcher.find()) {
                        try {
                            val json = JSONObject(ldMatcher.group(1).trim())
                            val imgUrl = json.optString("image", "")
                            if (imgUrl.isNotBlank() && imgUrl.startsWith("http")) {
                                detectedImageUrl = imgUrl
                                break
                            }
                        } catch (_: Exception) {}
                    }
                }

                // 2C. OpenGraph image tag
                if (detectedImageUrl == null && !ogImage.isNullOrBlank()) {
                    detectedImageUrl = ogImage
                }

                // 2D. Regex in HTML for i.pinimg.com/originals
                if (detectedImageUrl == null) {
                    val originalsPattern = Pattern.compile("(https://i\\.pinimg\\.com/originals/[a-zA-Z0-9/_.-]+)")
                    val originalsMatcher = originalsPattern.matcher(html)
                    if (originalsMatcher.find()) {
                        detectedImageUrl = originalsMatcher.group(1)
                    }
                }

                // IF AN IMAGE WAS FOUND: Strictly return as IMAGE (.jpg)
                if (detectedImageUrl != null && detectedImageUrl.isNotBlank()) {
                    var cleanImage = detectedImageUrl.replace("&amp;", "&").trim()
                    if (cleanImage.contains("i.pinimg.com/736x/")) {
                        cleanImage = cleanImage.replace("i.pinimg.com/736x/", "i.pinimg.com/originals/")
                    }

                    val format = if (cleanImage.lowercase().contains(".png")) "png" else "jpg"
                    return MediaResult(
                        title = if (pageTitle != "Pinterest Media") pageTitle else "Pinterest Image",
                        videoUrl = cleanImage,
                        thumbnailUrl = cleanImage,
                        platform = "Pinterest",
                        sourceUrl = url,
                        qualityOptions = listOf(
                            QualityOption(
                                label = "Original Quality Image",
                                url = cleanImage,
                                format = format,
                                isHd = true
                            )
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "Pinterest HTML scrape error: ${e.message}")
        }
        return null
    }

    private fun tryPinterestOpenGraph(url: String): MediaResult? {
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", UNIVERSAL_USER_AGENT)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val html = response.body?.string() ?: return null

                // Check video first!
                val ogVideoPattern = Pattern.compile("<meta\\s+(?:property|name)=[\"'](?:og:video|og:video:url|og:video:secure_url)[\"']\\s+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
                val ogMatcher = ogVideoPattern.matcher(html)
                if (ogMatcher.find()) {
                    val vUrl = ogMatcher.group(1).replace("&amp;", "&").replace("\\/", "/").trim()
                    return MediaResult(
                        title = "Pinterest Video",
                        videoUrl = vUrl,
                        platform = "Pinterest",
                        sourceUrl = url,
                        qualityOptions = listOf(QualityOption("High Definition (HD) MP4", vUrl, format = "mp4", isHd = true))
                    )
                }

                // If no video, check image
                val ogImgPattern = Pattern.compile("<meta\\s+(?:property|name)=[\"']og:image[\"']\\s+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
                val ogImgMatcher = ogImgPattern.matcher(html)
                if (ogImgMatcher.find()) {
                    var imgUrl = ogImgMatcher.group(1).replace("&amp;", "&").replace("\\/", "/").trim()
                    if (imgUrl.contains("i.pinimg.com/736x/")) {
                        imgUrl = imgUrl.replace("i.pinimg.com/736x/", "i.pinimg.com/originals/")
                    }
                    val format = if (imgUrl.lowercase().contains(".png")) "png" else "jpg"
                    return MediaResult(
                        title = "Pinterest Image",
                        videoUrl = imgUrl,
                        thumbnailUrl = imgUrl,
                        platform = "Pinterest",
                        sourceUrl = url,
                        qualityOptions = listOf(QualityOption("Original Quality Image", imgUrl, format = format, isHd = true))
                    )
                }
            }
        } catch (_: Exception) {}
        return null
    }
}

/**
 * Twitter / X Downloader:
 * - Engine 1: Cobalt API (Primary & Secondary)
 * - Engine 2: Twitsave API & FxTwitter / VxTwitter
 * - Engine 3: Direct Web Extractor / oEmbed
 */
class TwitterDownloader(private val client: OkHttpClient) {
    private val tag = "TwitterDownloader"

    suspend fun extract(url: String, config: RemoteConfigData): MediaResult = withContext(Dispatchers.IO) {
        val expandedUrl = UrlResolver.resolveRedirects(url)

        // --- Engine 1: Cobalt API ---
        try {
            val endpoint = config.engines["twitter"]?.primaryApi?.ifBlank { "https://api.cobalt.tools/api/json" }
                ?: "https://api.cobalt.tools/api/json"
            return@withContext CobaltDownloader(client).extractDirect(expandedUrl, endpoint, "Twitter/X")
        } catch (e: Exception) {
            Log.w(tag, "Engine 1 (Cobalt primary) failed: ${e.message}")
        }

        try {
            return@withContext CobaltDownloader(client).extractDirect(expandedUrl, "https://co.wuk.sh/api/json", "Twitter/X")
        } catch (e: Exception) {
            Log.w(tag, "Engine 1 (Cobalt secondary) failed: ${e.message}")
        }

        // --- Engine 2: Twitsave API & FxTwitter / VxTwitter ---
        try {
            val fxResult = tryFxTwitter(expandedUrl)
            if (fxResult != null) return@withContext fxResult
        } catch (e: Exception) {
            Log.w(tag, "Engine 2 (FxTwitter) failed: ${e.message}")
        }

        try {
            val twitsaveResult = tryTwitsave(expandedUrl)
            if (twitsaveResult != null) return@withContext twitsaveResult
        } catch (e: Exception) {
            Log.w(tag, "Engine 2 (Twitsave) failed: ${e.message}")
        }

        // --- Engine 3: Direct Web Extractor ---
        try {
            return@withContext DirectWebExtractor(client).extract(expandedUrl, config)
        } catch (e: Exception) {
            Log.w(tag, "Engine 3 (Web Extractor) failed: ${e.message}")
        }

        throw IOException("Unable to extract Twitter/X video. Please verify the tweet contains media and is public.")
    }

    private fun tryFxTwitter(url: String): MediaResult? {
        try {
            val uri = Uri.parse(url)
            val path = uri.path ?: return null
            val apiUrl = "https://api.fxtwitter.com$path"

            val request = Request.Builder()
                .url(apiUrl)
                .header("User-Agent", UNIVERSAL_USER_AGENT)
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

    private fun tryTwitsave(url: String): MediaResult? {
        try {
            val encoded = URLEncoder.encode(url, "UTF-8")
            val request = Request.Builder()
                .url("https://twitsave.com/info?url=$encoded")
                .header("User-Agent", UNIVERSAL_USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val html = response.body?.string() ?: ""
                    val pattern = Pattern.compile("<a\\s+[^>]*href=[\"'](https?://[^\"']+twitsave[^\"']+\\.mp4[^\"']*)[\"']", Pattern.CASE_INSENSITIVE)
                    val matcher = pattern.matcher(html)
                    if (matcher.find()) {
                        val videoUrl = matcher.group(1)
                        return MediaResult(
                            title = "Twitter Video",
                            videoUrl = videoUrl,
                            platform = "Twitter/X",
                            sourceUrl = url,
                            qualityOptions = listOf(QualityOption("High Definition (HD)", videoUrl, isHd = true))
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "Twitsave parsing failed: ${e.message}")
        }
        return null
    }
}

/**
 * Facebook Downloader:
 * - Engine 1: Direct HTML Scraper (playable_url_quality_hd, browser_native_hd_url, hd_src_no_ratelimit)
 * - Engine 2: Cobalt API (Primary & Secondary)
 * - Engine 3: FDown Scraper
 * - Engine 4: Direct Web Extractor / OpenGraph
 */
class FacebookDownloader(private val client: OkHttpClient) {
    private val tag = "FacebookDownloader"

    suspend fun extract(url: String, config: RemoteConfigData): MediaResult = withContext(Dispatchers.IO) {
        val expandedUrl = UrlResolver.resolveRedirects(url)

        // --- Engine 1: Direct Facebook HTML Stream (playable_url_quality_hd, browser_native_hd_url, hd_src_no_ratelimit) ---
        try {
            val directResult = tryDirectFacebookHtml(expandedUrl)
            if (directResult != null) return@withContext directResult
        } catch (e: Exception) {
            Log.w(tag, "Engine 1 (Direct Facebook HTML) failed: ${e.message}")
        }

        // --- Engine 2: Cobalt API ---
        try {
            val endpoint = config.engines["facebook"]?.primaryApi?.ifBlank { "https://api.cobalt.tools/api/json" }
                ?: "https://api.cobalt.tools/api/json"
            return@withContext CobaltDownloader(client).extractDirect(expandedUrl, endpoint, "Facebook")
        } catch (e: Exception) {
            Log.w(tag, "Engine 2 (Cobalt primary) failed: ${e.message}")
        }

        try {
            return@withContext CobaltDownloader(client).extractDirect(expandedUrl, "https://co.wuk.sh/api/json", "Facebook")
        } catch (e: Exception) {
            Log.w(tag, "Engine 2 (Cobalt secondary) failed: ${e.message}")
        }

        // --- Engine 3 Fallback: FDown Scraper ---
        try {
            val fdownResult = tryFdown(expandedUrl)
            if (fdownResult != null) return@withContext fdownResult
        } catch (e: Exception) {
            Log.w(tag, "Engine 3 (FDown) failed: ${e.message}")
        }

        // --- Engine 4: Direct Web Extractor / OpenGraph ---
        try {
            return@withContext DirectWebExtractor(client).extract(expandedUrl, config)
        } catch (e: Exception) {
            Log.w(tag, "Engine 4 (Direct Web) failed: ${e.message}")
        }

        throw IOException("Unable to extract Facebook video. Make sure the video is public.")
    }

    private fun tryDirectFacebookHtml(url: String): MediaResult? {
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", UNIVERSAL_USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Sec-Fetch-Mode", "navigate")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val html = response.body?.string() ?: ""

                    var hdUrl: String? = null
                    var sdUrl: String? = null

                    // Regex match for HD stream
                    val hdPattern = Pattern.compile("\"(?:playable_url_quality_hd|browser_native_hd_url|hd_src_no_ratelimit)\":\"([^\"]+)\"")
                    val hdMatcher = hdPattern.matcher(html)
                    if (hdMatcher.find()) {
                        hdUrl = cleanFacebookUrl(hdMatcher.group(1))
                    }

                    // Regex match for SD stream
                    val sdPattern = Pattern.compile("\"(?:playable_url|browser_native_sd_url|sd_src_no_ratelimit)\":\"([^\"]+)\"")
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

    private fun tryFdown(url: String): MediaResult? {
        try {
            val formBody = FormBody.Builder()
                .add("URLz", url)
                .build()

            val request = Request.Builder()
                .url("https://fdown.net/download.php")
                .post(formBody)
                .header("User-Agent", UNIVERSAL_USER_AGENT)
                .header("Referer", "https://fdown.net/")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val html = response.body?.string() ?: ""
                    val hdPattern = Pattern.compile("id=[\"']hdlink[\"'][^>]*href=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
                    val sdPattern = Pattern.compile("id=[\"']sdlink[\"'][^>]*href=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)

                    val hdMatcher = hdPattern.matcher(html)
                    val sdMatcher = sdPattern.matcher(html)

                    val hd = if (hdMatcher.find()) hdMatcher.group(1).replace("&amp;", "&") else null
                    val sd = if (sdMatcher.find()) sdMatcher.group(1).replace("&amp;", "&") else null

                    val chosen = hd ?: sd
                    if (!chosen.isNullOrBlank()) {
                        val qualities = mutableListOf<QualityOption>()
                        if (!hd.isNullOrBlank()) qualities.add(QualityOption("1080p HD Quality", hd, isHd = true))
                        if (!sd.isNullOrBlank()) qualities.add(QualityOption("SD Standard Quality", sd, isHd = false))

                        return MediaResult(
                            title = "Facebook Video",
                            videoUrl = chosen,
                            platform = "Facebook",
                            sourceUrl = url,
                            qualityOptions = qualities
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "FDown parser failed: ${e.message}")
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
            .header("User-Agent", UNIVERSAL_USER_AGENT)
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
            val filename = json.optString("filename", "$platform Media")

            if (videoUrl.isNotBlank()) {
                val isImage = videoUrl.contains(".jpg") || videoUrl.contains(".jpeg") ||
                        videoUrl.contains(".png") || videoUrl.contains(".webp") ||
                        filename.endsWith(".jpg") || filename.endsWith(".jpeg") ||
                        filename.endsWith(".png") || filename.endsWith(".webp")
                val format = if (isImage) {
                    if (videoUrl.contains(".png") || filename.endsWith(".png")) "png" else "jpg"
                } else "mp4"

                return@withContext MediaResult(
                    title = filename,
                    videoUrl = videoUrl,
                    audioUrl = audioUrl.ifBlank { null },
                    platform = platform,
                    sourceUrl = url,
                    qualityOptions = listOf(
                        QualityOption(
                            label = if (isImage) "Original Quality Image" else "Original Quality (HD)",
                            url = videoUrl,
                            format = format,
                            isHd = true
                        )
                    )
                )
            }
        } else if (status == "picker" && json.has("picker")) {
            val picker = json.getJSONArray("picker")
            if (picker.length() > 0) {
                val first = picker.getJSONObject(0)
                val videoUrl = first.optString("url", "")
                val isImage = videoUrl.contains(".jpg") || videoUrl.contains(".jpeg") ||
                        videoUrl.contains(".png") || videoUrl.contains(".webp")
                val format = if (isImage) "jpg" else "mp4"
                return@withContext MediaResult(
                    title = "$platform Media",
                    videoUrl = videoUrl,
                    platform = platform,
                    sourceUrl = url,
                    qualityOptions = listOf(
                        QualityOption(
                            label = if (isImage) "Original Quality Image" else "Selected Item",
                            url = videoUrl,
                            format = format
                        )
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

    fun extractDirectWithMeta(
        url: String,
        title: String,
        author: String,
        thumb: String,
        platform: String
    ): MediaResult? {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", UNIVERSAL_USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val html = response.body?.string() ?: ""
                    val ogVideoPattern = Pattern.compile("<meta\\s+(?:property|name)=[\"'](?:og:video|twitter:player:stream)(?::secure_url)?[\"']\\s+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
                    val ogMatcher = ogVideoPattern.matcher(html)
                    val videoUrl = if (ogMatcher.find()) ogMatcher.group(1).replace("&amp;", "&") else null

                    if (!videoUrl.isNullOrBlank()) {
                        return MediaResult(
                            title = title,
                            videoUrl = videoUrl,
                            thumbnailUrl = thumb.ifBlank { null },
                            platform = platform,
                            author = author,
                            sourceUrl = url,
                            qualityOptions = listOf(QualityOption("High Definition (HD)", videoUrl, isHd = true))
                        )
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun extract(url: String, config: RemoteConfigData): MediaResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", UNIVERSAL_USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val html = response.body?.string() ?: ""

                // 1. Parse Title (og:title, twitter:title, or <title>)
                val ogTitlePattern = Pattern.compile("<meta\\s+(?:property|name)=[\"'](?:og:title|twitter:title)[\"']\\s+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
                val ogTitleMatcher = ogTitlePattern.matcher(html)
                var title = if (ogTitleMatcher.find()) ogTitleMatcher.group(1).trim() else null

                if (title.isNullOrBlank()) {
                    val titleTagPattern = Pattern.compile("<title>([^<]+)</title>", Pattern.CASE_INSENSITIVE)
                    val titleTagMatcher = titleTagPattern.matcher(html)
                    if (titleTagMatcher.find()) {
                        title = titleTagMatcher.group(1).trim()
                    }
                }
                val resolvedTitle = title?.ifBlank { "Media Download" } ?: "Media Download"

                // 2. Parse Image (og:image or twitter:image)
                val ogImagePattern = Pattern.compile("<meta\\s+(?:property|name)=[\"'](?:og:image|twitter:image)(?::secure_url)?[\"']\\s+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
                val ogImageMatcher = ogImagePattern.matcher(html)
                val thumb = if (ogImageMatcher.find()) ogImageMatcher.group(1).replace("&amp;", "&").trim() else null

                // 3. Parse Video (og:video, twitter:player:stream, etc.)
                val ogVideoPattern = Pattern.compile("<meta\\s+(?:property|name)=[\"'](?:og:video|twitter:player:stream)(?::secure_url)?[\"']\\s+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
                val ogVideoMatcher = ogVideoPattern.matcher(html)
                var videoUrl = if (ogVideoMatcher.find()) ogVideoMatcher.group(1).replace("&amp;", "&").trim() else null

                // Fallback to <source src="...mp4"> or <video src="...">
                if (videoUrl.isNullOrBlank()) {
                    val srcPattern = Pattern.compile("<(?:source|video)\\s+[^>]*src=[\"']([^\"']+\\.mp4[^\"']*)[\"']", Pattern.CASE_INSENSITIVE)
                    val srcMatcher = srcPattern.matcher(html)
                    if (srcMatcher.find()) {
                        videoUrl = srcMatcher.group(1).replace("&amp;", "&").trim()
                    }
                }

                // If video found -> Return Video MediaResult
                if (!videoUrl.isNullOrBlank()) {
                    val cleanVideoUrl = videoUrl.replace("&amp;", "&")
                    return@withContext MediaResult(
                        title = resolvedTitle,
                        videoUrl = cleanVideoUrl,
                        thumbnailUrl = thumb,
                        platform = "Web",
                        sourceUrl = url,
                        qualityOptions = listOf(QualityOption("High Definition (HD)", cleanVideoUrl, isHd = true))
                    )
                }

                // Universal Final Defense Line: If no video is found, but og:image or twitter:image is present -> Return high-res Image MediaResult!
                if (!thumb.isNullOrBlank() && thumb.startsWith("http")) {
                    return@withContext MediaResult(
                        title = resolvedTitle,
                        videoUrl = thumb,
                        thumbnailUrl = thumb,
                        platform = "Web",
                        sourceUrl = url,
                        qualityOptions = listOf(QualityOption("High Resolution Image", thumb, format = "jpg"))
                    )
                }

                // Last Regex Attempt: Search for any MP4 URL in HTML
                val mp4Regex = Pattern.compile("(https?://[^\"'<>\\s]+\\.mp4(?:\\?[^\"'<>\\s]*)?)", Pattern.CASE_INSENSITIVE)
                val mp4Matcher = mp4Regex.matcher(html)
                if (mp4Matcher.find()) {
                    val foundUrl = mp4Matcher.group(1).replace("&amp;", "&")
                    return@withContext MediaResult(
                        title = resolvedTitle,
                        videoUrl = foundUrl,
                        thumbnailUrl = thumb,
                        platform = "Web",
                        sourceUrl = url,
                        qualityOptions = listOf(QualityOption("High Definition (HD)", foundUrl, isHd = true))
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
    private val pinterest = PinterestDownloader(client)
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
        val cleanUrl = UrlResolver.sanitizeAndExtractUrl(rawUrl)
        if (cleanUrl.isBlank()) {
            throw IllegalArgumentException("Empty or invalid link")
        }

        if (isYouTubeUrl(cleanUrl)) {
            throw YouTubeBlockedException()
        }

        // Automatic redirect resolution (HTTP 301/302/307/308) before calling extraction engines
        val resolvedUrl = UrlResolver.resolveRedirects(cleanUrl)
        val lower = resolvedUrl.lowercase()

        // 1. TikTok & Douyin (including tt.site, vt.tiktok.com & vm.tiktok.com)
        if (lower.contains("tiktok.com") || lower.contains("douyin.com") || lower.contains("tt.site")) {
            return@withContext tiktok.extract(resolvedUrl, config)
        }

        // 2. Pinterest (pin.it & pinterest.com)
        if (lower.contains("pinterest.") || lower.contains("pin.it")) {
            return@withContext pinterest.extract(resolvedUrl, config)
        }

        // 3. Twitter / X
        if (lower.contains("twitter.com") || lower.contains("x.com") || lower.contains("t.co")) {
            return@withContext twitter.extract(resolvedUrl, config)
        }

        // 4. Facebook & fb.watch
        if (lower.contains("facebook.com") || lower.contains("fb.watch")) {
            return@withContext facebook.extract(resolvedUrl, config)
        }

        // 5. Instagram
        if (lower.contains("instagram.com") || lower.contains("instagr.am")) {
            return@withContext instagram.extract(resolvedUrl, config)
        }

        // 6. Dynamic Remote Engines Regex Matching
        for ((platformKey, engineConfig) in config.engines) {
            if (engineConfig.regex.isNotBlank()) {
                try {
                    val pattern = Pattern.compile(engineConfig.regex, Pattern.CASE_INSENSITIVE)
                    if (pattern.matcher(resolvedUrl).find()) {
                        val primaryApi = engineConfig.primaryApi
                        val fallbackApi = engineConfig.fallbackApi

                        try {
                            if (primaryApi.contains("tikwm.com")) {
                                return@withContext tiktok.extract(resolvedUrl, config)
                            } else {
                                return@withContext cobalt.extractDirect(resolvedUrl, primaryApi, platformKey.replaceFirstChar { it.uppercase() })
                            }
                        } catch (e: Exception) {
                            Log.w("MasterDownloadEngine", "Primary API failed for $platformKey: ${e.message}")
                        }

                        if (fallbackApi.isNotBlank()) {
                            try {
                                return@withContext cobalt.extractDirect(resolvedUrl, fallbackApi, platformKey.replaceFirstChar { it.uppercase() })
                            } catch (e: Exception) {
                                Log.w("MasterDownloadEngine", "Fallback API failed for $platformKey: ${e.message}")
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        // 7. Generic Fallback: Cobalt -> DirectWebExtractor (OpenGraph video & image scraper)
        try {
            return@withContext cobalt.extract(resolvedUrl, config)
        } catch (_: Exception) {
            return@withContext webExtractor.extract(resolvedUrl, config)
        }
    }
}
