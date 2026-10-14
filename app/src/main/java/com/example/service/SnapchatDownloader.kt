package com.example.service

import android.util.Log
import com.example.model.CarouselItem
import com.example.model.MediaResult
import com.example.model.QualityOption
import com.example.model.RemoteConfigData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.regex.Pattern

/**
 * Snapchat Downloader:
 * - Engine 1: Deep Link & Short URL Redirect Resolver (HTTP 301/302/307 & HTML canonical meta/scripts for /t/ links)
 * - Engine 2: Direct High-Resolution HTML & Next.js Scraper (__NEXT_DATA__, sc-cdn.net direct streams, og:video)
 * - Engine 3: Failover to Cobalt API cluster (wuk.sh & cobalt.tools)
 * - Engine 4: DirectWebExtractor Fallback (Universal OpenGraph & HTML5 media scanner)
 */
class SnapchatDownloader(private val client: OkHttpClient) : BaseDownloaderEngine() {
    override val platformName: String = "Snapchat"
    private val tag = "SnapchatDownloader"

    override fun canHandle(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("snapchat.com") ||
                lower.contains("snap.com") ||
                lower.contains("sc-cdn.net")
    }

    override suspend fun extract(url: String, config: RemoteConfigData): MediaResult = withContext(Dispatchers.IO) {
        // Step 1: Follow HTTP and HTML redirects for /t/ short links & canonical URLs
        val canonicalUrl = resolveSnapchatUrl(url)
        Log.d(tag, "Resolved Snapchat URL: $canonicalUrl (from $url)")

        // Engine 1: Direct Snapchat Web & Next.js Scraper
        try {
            val directResult = tryDirectSnapchatScrape(canonicalUrl)
            if (directResult != null) {
                Log.d(tag, "Engine 1 (Direct Scraper) succeeded for Snapchat")
                return@withContext directResult
            }
        } catch (e: Exception) {
            Log.w(tag, "Engine 1 (Direct Scraper) error: ${e.message}")
        }

        // Engine 2: Cobalt API Cluster
        try {
            val primaryApi = config.engines["snapchat"]?.primaryApi?.ifBlank { "https://api.cobalt.tools/api/json" }
                ?: "https://api.cobalt.tools/api/json"
            return@withContext CobaltDownloader(client).extractDirect(canonicalUrl, primaryApi, "Snapchat")
        } catch (e: Exception) {
            Log.w(tag, "Engine 2 (Cobalt primary) failed: ${e.message}")
        }

        try {
            return@withContext CobaltDownloader(client).extractDirect(canonicalUrl, "https://co.wuk.sh/api/json", "Snapchat")
        } catch (e: Exception) {
            Log.w(tag, "Engine 2 (Cobalt secondary) failed: ${e.message}")
        }

        // Engine 3: Direct Web OpenGraph / Meta Scanner Fallback
        try {
            val webExtractor = DirectWebExtractor(client)
            return@withContext webExtractor.extract(canonicalUrl, config)
        } catch (e: Exception) {
            Log.w(tag, "Engine 3 (WebExtractor) failed: ${e.message}")
        }

        throw IOException("Unable to extract Snapchat media. Please ensure the Story or Spotlight link is public.")
    }

    /**
     * Resolves Snapchat short links (e.g. snapchat.com/t/xxxx, t.snapchat.com/xxxx)
     * through HTTP 301/302/307 redirects and HTML meta refresh/canonical tags.
     */
    private suspend fun resolveSnapchatUrl(rawUrl: String): String = withContext(Dispatchers.IO) {
        val initialUrl = UrlResolver.resolveRedirects(rawUrl)
        if (!initialUrl.contains("/t/", ignoreCase = true) && !initialUrl.contains("t.snapchat.com", ignoreCase = true)) {
            return@withContext initialUrl
        }

        try {
            val request = Request.Builder()
                .url(initialUrl)
                .header("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()

            client.newCall(request).execute().use { response ->
                val finalUrl = response.request.url.toString()
                if (finalUrl.isNotBlank() && !finalUrl.contains("/t/", ignoreCase = true)) {
                    return@withContext finalUrl
                }

                val html = response.body?.string().orEmpty()
                // Check <link rel="canonical" href="...">
                val canonicalMatcher = Pattern.compile("<link\\s+[^>]*rel=[\"']canonical[\"'][^>]*href=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(html)
                if (canonicalMatcher.find()) {
                    val found = canonicalMatcher.group(1).orEmpty()
                    if (found.startsWith("http")) return@withContext found
                }

                // Check <meta property="og:url" content="...">
                val ogUrlMatcher = Pattern.compile("<meta\\s+[^>]*property=[\"']og:url[\"'][^>]*content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(html)
                if (ogUrlMatcher.find()) {
                    val found = ogUrlMatcher.group(1).orEmpty()
                    if (found.startsWith("http")) return@withContext found
                }

                // Check meta refresh
                val refreshMatcher = Pattern.compile("<meta\\s+[^>]*http-equiv=[\"']refresh[\"'][^>]*content=[\"'][^\"']*url=([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(html)
                if (refreshMatcher.find()) {
                    val found = refreshMatcher.group(1).orEmpty()
                    if (found.startsWith("http")) return@withContext found
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "Failed to resolve Snapchat short URL via HTTP: ${e.message}")
        }

        initialUrl
    }

    /**
     * Direct HTML scraping of Snapchat Spotlight & Story pages.
     * Extracts media from Next.js payload (__NEXT_DATA__) and direct sc-cdn.net stream URLs.
     */
    private suspend fun tryDirectSnapchatScrape(targetUrl: String): MediaResult? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(targetUrl)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9,ar;q=0.8")
            .header("Referer", "https://www.snapchat.com/")
            .build()

        val html = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            response.body?.string().orEmpty()
        }

        if (html.isBlank()) return@withContext null

        var videoUrl: String? = null
        var thumbnailUrl: String? = null
        var title = "Snapchat Media"
        var author: String? = null

        // 1. Extract metadata from OpenGraph / Twitter meta tags
        val titleMatcher = Pattern.compile("<meta\\s+[^>]*property=[\"']og:title[\"'][^>]*content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(html)
        if (titleMatcher.find()) {
            title = cleanEscaped(titleMatcher.group(1).orEmpty())
        }

        val ogVideoMatcher = Pattern.compile("<meta\\s+[^>]*property=[\"']og:video(?:[:\\w]+)?[\"'][^>]*content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(html)
        if (ogVideoMatcher.find()) {
            videoUrl = cleanEscaped(ogVideoMatcher.group(1).orEmpty())
        }

        val ogImageMatcher = Pattern.compile("<meta\\s+[^>]*property=[\"']og:image[\"'][^>]*content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(html)
        if (ogImageMatcher.find()) {
            thumbnailUrl = cleanEscaped(ogImageMatcher.group(1).orEmpty())
        }

        val twitterStreamMatcher = Pattern.compile("<meta\\s+[^>]*name=[\"']twitter:player:stream[\"'][^>]*content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(html)
        if (twitterStreamMatcher.find() && videoUrl == null) {
            videoUrl = cleanEscaped(twitterStreamMatcher.group(1).orEmpty())
        }

        // 2. Extract from Next.js data payload (__NEXT_DATA__)
        val nextDataPattern = Pattern.compile("<script\\s+id=[\"']__NEXT_DATA__[\"']\\s+type=[\"']application/json[\"']>([\\s\\S]*?)</script>", Pattern.CASE_INSENSITIVE)
        val nextDataMatcher = nextDataPattern.matcher(html)
        if (nextDataMatcher.find()) {
            val jsonStr = nextDataMatcher.group(1).orEmpty()
            try {
                val nextObj = JSONObject(jsonStr)
                val props = nextObj.optJSONObject("props")?.optJSONObject("pageProps")
                if (props != null) {
                    // Extract creator / author
                    val creator = props.optJSONObject("creator")?.optString("username")
                        ?: props.optJSONObject("story")?.optString("creator")
                        ?: props.optJSONObject("spotlightStory")?.optString("creator")
                    if (!creator.isNullOrBlank()) {
                        author = "@$creator"
                    }

                    // Scan for mediaUrl / playbackUrl inside JSON
                    if (videoUrl == null) {
                        val videoPattern = Pattern.compile("\"(?:mediaUrl|videoUrl|downloadUrl|playbackUrl)\"\\s*:\\s*\"(https:[^\"]+?\\.mp4[^\"]*)\"", Pattern.CASE_INSENSITIVE)
                        val vm = videoPattern.matcher(jsonStr)
                        if (vm.find()) {
                            videoUrl = cleanEscaped(vm.group(1).orEmpty())
                        }
                    }

                    if (thumbnailUrl == null) {
                        val thumbPattern = Pattern.compile("\"(?:thumbnailUrl|posterUrl|snapThumbnail)\"\\s*:\\s*\"(https:[^\"]+?)\"", Pattern.CASE_INSENSITIVE)
                        val tm = thumbPattern.matcher(jsonStr)
                        if (tm.find()) {
                            thumbnailUrl = cleanEscaped(tm.group(1).orEmpty())
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(tag, "Failed to parse __NEXT_DATA__ JSON: ${e.message}")
            }
        }

        // 3. Fallback: Search direct sc-cdn.net stream URLs inside page
        if (videoUrl == null) {
            val scCdnPattern = Pattern.compile("https://[a-zA-Z0-9.-]+\\.sc-cdn\\.net/[^\"'\\s<>]+\\.mp4[^\"'\\s<>]*", Pattern.CASE_INSENSITIVE)
            val scMatcher = scCdnPattern.matcher(html)
            if (scMatcher.find()) {
                videoUrl = cleanEscaped(scMatcher.group(0).orEmpty())
            }
        }

        // 4. Fallback: Search cf-st.sc-cdn.net or bolt-gcdn.sc-cdn.net
        if (videoUrl == null) {
            val boltPattern = Pattern.compile("https://(?:bolt-gcdn|cf-st)\\.sc-cdn\\.net/[^\"'\\s<>]+\\.mp4[^\"'\\s<>]*", Pattern.CASE_INSENSITIVE)
            val boltMatcher = boltPattern.matcher(html)
            if (boltMatcher.find()) {
                videoUrl = cleanEscaped(boltMatcher.group(0).orEmpty())
            }
        }

        if (videoUrl.isNullOrBlank()) {
            return@withContext null
        }

        val cleanVideo = sanitizeMediaUrl(videoUrl)
        val cleanThumb = thumbnailUrl?.let { sanitizeMediaUrl(it) }

        MediaResult(
            title = if (title.isNotBlank()) title else "Snapchat Spotlight / Story",
            videoUrl = cleanVideo,
            thumbnailUrl = cleanThumb,
            platform = "Snapchat",
            author = author ?: "Snapchat User",
            sourceUrl = targetUrl,
            isDirectStream = true,
            qualityOptions = listOf(
                QualityOption(
                    label = "Snapchat HD Video (MP4)",
                    url = cleanVideo,
                    format = "mp4",
                    isHd = true
                )
            ),
            carouselItems = listOf(
                CarouselItem(
                    url = cleanVideo,
                    format = "mp4",
                    label = "Snapchat Video",
                    isHd = true
                )
            )
        )
    }

    private fun cleanEscaped(raw: String): String {
        var s = raw.trim().trim('"', '\'', '\\', ' ', '\n', '\r')
        while (s.contains("\\\\")) {
            s = s.replace("\\\\", "\\")
        }
        return s.replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("\\u0025", "%")
            .replace("\\u003d", "=")
            .replace("\\u003D", "=")
            .replace("\\u003f", "?")
            .replace("\\u003F", "?")
            .replace("&amp;", "&")
            .replace("\\\"", "\"")
            .trim('"', '\'', '\\', ' ')
    }

    private fun sanitizeMediaUrl(raw: String): String {
        val unescaped = cleanEscaped(raw)
        return unescaped.replace(" ", "%20")
    }
}
