package com.example.data.api

import android.util.Log
import com.example.model.CarouselItem
import com.example.model.MediaUrlHints
import com.example.model.cleanAccountName
import com.example.model.MediaResult
import com.example.model.PlatformInfo
import com.example.model.QualityOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiClient(
    private val baseUrl: String = "https://thaker.duckdns.org"
) {
    companion object {
        private const val TAG = "ApiClient"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

        val DEFAULT_PLATFORMS = listOf(
            PlatformInfo(
                id = "tiktok",
                name = "TikTok",
                nameAr = "تيك توك",
                enabled = true,
                patterns = listOf("tiktok.com", "douyin.com", "tt.site", "vt.tiktok.com", "vm.tiktok.com"),
                sampleUrl = "tiktok.com/@user/video/123",
                icon = "tiktok",
                badge = "No Watermark"
            ),
            PlatformInfo(
                id = "instagram",
                name = "Instagram",
                nameAr = "إنستغرام",
                enabled = true,
                patterns = listOf("instagram.com", "instagr.am", "cdninstagram.com"),
                sampleUrl = "instagram.com/reel/xyz",
                icon = "instagram",
                badge = "Reels & Stories"
            ),
            PlatformInfo(
                id = "facebook",
                name = "Facebook",
                nameAr = "فيسبوك",
                enabled = true,
                patterns = listOf("facebook.com", "fb.watch", "fb.com", "fb.me"),
                sampleUrl = "facebook.com/watch/?v=123",
                icon = "facebook",
                badge = "HD 1080p"
            ),
            PlatformInfo(
                id = "pinterest",
                name = "Pinterest",
                nameAr = "بينتيريست",
                enabled = true,
                patterns = listOf("pinterest.com", "pin.it", "pinimg.com"),
                sampleUrl = "pin.it/xyz",
                icon = "pinterest",
                badge = "Video & Pins"
            ),
            PlatformInfo(
                id = "twitter",
                name = "X / Twitter",
                nameAr = "تويتر / X",
                enabled = true,
                patterns = listOf("twitter.com", "x.com", "t.co"),
                sampleUrl = "x.com/user/status/123",
                icon = "twitter",
                badge = "Direct MP4"
            ),
            PlatformInfo(
                id = "snapchat",
                name = "Snapchat",
                nameAr = "سناب شات",
                enabled = true,
                patterns = listOf("snapchat.com", "snap.com", "sc-cdn.net"),
                sampleUrl = "story.snapchat.com/s/xyz",
                icon = "snapchat",
                badge = "Spotlight"
            ),
            PlatformInfo(
                id = "reddit",
                name = "Reddit",
                nameAr = "ريديت",
                enabled = true,
                patterns = listOf("reddit.com", "redd.it"),
                sampleUrl = "reddit.com/r/videos/comments/xyz",
                icon = "reddit",
                badge = "Merged Audio/Video"
            ),
            PlatformInfo(
                id = "threads",
                name = "Threads",
                nameAr = "ثريدز",
                enabled = true,
                patterns = listOf("threads.net", "threads.com"),
                sampleUrl = "threads.net/@user/post/xyz",
                icon = "threads",
                badge = "HD"
            )
        )
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /**
     * Fetches dynamic platforms list from the Ubuntu VPS: GET /api/platforms
     * If the server is offline or unreachable, falls back to DEFAULT_PLATFORMS.
     */
    suspend fun fetchPlatforms(): List<PlatformInfo> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/api/platforms"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .get()
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "fetchPlatforms returned HTTP ${response.code}, using defaults")
                    return@withContext DEFAULT_PLATFORMS
                }

                val bodyStr = response.body?.string().orEmpty()
                val json = JSONObject(bodyStr)
                val platformsArray = json.optJSONArray("platforms") ?: JSONArray()
                val list = mutableListOf<PlatformInfo>()

                for (i in 0 until platformsArray.length()) {
                    val obj = platformsArray.getJSONObject(i)
                    val patternsJson = obj.optJSONArray("patterns") ?: JSONArray()
                    val patterns = mutableListOf<String>()
                    for (p in 0 until patternsJson.length()) {
                        patterns.add(patternsJson.getString(p))
                    }

                    list.add(
                        PlatformInfo(
                            id = obj.optString("id", "platform_$i"),
                            name = obj.optString("name", "Unknown"),
                            nameAr = obj.optString("name_ar", ""),
                            enabled = obj.optBoolean("enabled", true),
                            maintenanceMessage = obj.optString("maintenance_message").takeIf { it.isNotBlank() },
                            patterns = patterns,
                            sampleUrl = obj.optString("sample_url", ""),
                            icon = obj.optString("icon", "web"),
                            badge = obj.optString("badge", "")
                        )
                    )
                }

                if (list.isNotEmpty()) list else DEFAULT_PLATFORMS
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch platforms from VPS: ${e.message}, using defaults")
            DEFAULT_PLATFORMS
        }
    }

    /**
     * Sends extraction request to Ubuntu VPS: POST /api/extract
     * Request body: {"url": "..."}
     */
    suspend fun extractMedia(targetUrl: String): MediaResult = withContext(Dispatchers.IO) {
        val endpoint = "$baseUrl/api/extract"
        val payload = JSONObject().apply {
            put("url", targetUrl)
        }.toString()

        val request = Request.Builder()
            .url(endpoint)
            .header("User-Agent", USER_AGENT)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val response = httpClient.newCall(request).execute()
        val code = response.code
        val bodyStr = response.body?.string().orEmpty()

        if (code == 503) {
            // Platform under maintenance on server
            val errJson = try { JSONObject(bodyStr) } catch (_: Exception) { null }
            val maintenanceMsg = errJson?.optString("error")?.takeIf { it.isNotBlank() }
                ?: "المنصة قيد الصيانة المؤقتة على السيرفر / Platform is under maintenance"
            throw IOException(maintenanceMsg)
        }

        if (!response.isSuccessful) {
            val errJson = try { JSONObject(bodyStr) } catch (_: Exception) { null }
            val errorMsg = errJson?.optString("error")?.takeIf { it.isNotBlank() }
                ?: "Server returned HTTP $code"
            throw IOException(errorMsg)
        }

        val json = JSONObject(bodyStr)
        if (json.has("success") && !json.optBoolean("success", true)) {
            val errMsg = json.optString("error").takeIf { it.isNotBlank() }
                ?: "Extraction failed on server"
            throw IOException(errMsg)
        }

        // Support both direct fields and wrapped "data": { ... }
        val data = if (json.has("data") && json.optJSONObject("data") != null) {
            json.getJSONObject("data")
        } else {
            json
        }

        parseMediaResult(data, targetUrl)
    }

    /**
     * org.json returns the literal text "null" from optString() when the value is JSON null.
     * That produced "null@" in the UI. This reads the first usable value among [keys] and
     * ignores JSON null, "null" and "undefined".
     */
    private fun JSONObject.cleanString(vararg keys: String): String {
        for (key in keys) {
            if (!has(key) || isNull(key)) continue
            val text = when (val value = opt(key)) {
                is String -> value
                is Number, is Boolean -> value.toString()
                else -> null
            }?.trim() ?: continue
            if (text.isNotEmpty() && !text.equals("null", ignoreCase = true) && !text.equals("undefined", ignoreCase = true)) {
                return text
            }
        }
        return ""
    }

    private fun extractAuthor(json: JSONObject): String? {
        val keys = listOf(
            "author", "username", "user_name", "uploader", "owner", "creator",
            "nickname", "unique_id", "user", "account", "author_name", "channel"
        )
        for (key in keys) {
            if (!json.has(key) || json.isNull(key)) continue
            when (val value = json.opt(key)) {
                is String -> cleanAccountName(value)?.let { return it }
                is JSONObject -> {
                    for (nameKey in listOf("unique_id", "username", "user_name", "nickname", "name", "display_name")) {
                        cleanAccountName(value.cleanString(nameKey))?.let { return it }
                    }
                }
                else -> Unit
            }
        }
        return null
    }

    private fun parseMediaResult(json: JSONObject, originalUrl: String): MediaResult {
        val title = json.cleanString("title", "desc", "description").ifBlank { "Media Download" }

        val rawVideoUrl = json.cleanString("videoUrl", "video_url", "url", "download_url")

        val audioUrl = json.cleanString("audioUrl", "audio_url", "music", "music_url")
            .takeIf { it.isNotBlank() }

        val thumbnailUrl = json.cleanString("thumbnailUrl", "thumbnail_url", "thumbnail", "thumb", "cover")
            .takeIf { it.isNotBlank() }

        val platform = json.cleanString("platform").ifBlank { "Media" }
        val author = extractAuthor(json)
        val durationText = json.cleanString("durationText", "duration").takeIf { it.isNotBlank() }

        // Declared post type (some servers send "image", "photo", "slideshow", "carousel", "video")
        val declaredType = json.cleanString("type", "media_type", "post_type", "kind").lowercase()
        val declaresImages = listOf("image", "photo", "slide", "carousel", "album", "gallery").any { declaredType.contains(it) }
        val declaresVideo = declaredType.contains("video") && !declaresImages

        // A "video" URL that is really an audio track (TikTok photo posts) must never be used as video
        val videoUrl = when {
            rawVideoUrl.isBlank() -> ""
            rawVideoUrl == audioUrl -> ""
            MediaUrlHints.looksLikeAudio(rawVideoUrl) -> ""
            else -> rawVideoUrl
        }

        // Quality options
        val qualityList = mutableListOf<QualityOption>()
        val qualitiesArr = json.optJSONArray("qualityOptions")
            ?: json.optJSONArray("qualities")
            ?: json.optJSONArray("formats")

        if (qualitiesArr != null) {
            for (i in 0 until qualitiesArr.length()) {
                val qObj = qualitiesArr.optJSONObject(i) ?: continue
                val qUrl = qObj.cleanString("url", "download_url")
                if (qUrl.isNotBlank()) {
                    qualityList.add(
                        QualityOption(
                            label = qObj.cleanString("label").ifBlank { "MP4" },
                            url = qUrl,
                            format = qObj.cleanString("format").ifBlank { "mp4" },
                            isHd = qObj.optBoolean("isHd", false) || qObj.optBoolean("hd", false)
                        )
                    )
                }
            }
        }

        // Extract all photo URLs from any possible array key
        val photoList = extractPhotoUrls(json)

        // Extract all carousel items
        val carouselList = extractCarouselItems(json).toMutableList()

        // A video post whose "images" array only holds its cover must stay a video post.
        val hasRealVideo = videoUrl.isNotBlank() && !MediaUrlHints.looksLikeImage(videoUrl)
        val onlyCover = photoList.size <= 1 && (photoList.isEmpty() || photoList.first() == thumbnailUrl)
        val carouselHasVideo = carouselList.any { it.format.equals("mp4", ignoreCase = true) }
        if (hasRealVideo && !declaresImages && !carouselHasVideo && (declaresVideo || onlyCover) && carouselList.size <= 1) {
            photoList.clear()
            carouselList.clear()
        }

        // The "video" URL is really a photo (single photo post)
        if (photoList.isEmpty() && carouselList.isEmpty() && videoUrl.isNotBlank() && MediaUrlHints.looksLikeImage(videoUrl)) {
            photoList.add(videoUrl)
        }

        // 1. Photos but no carousel entries -> build the carousel from the photos
        if (carouselList.isEmpty() && photoList.isNotEmpty()) {
            photoList.forEachIndexed { idx, url ->
                carouselList.add(CarouselItem(url = url, format = "jpg", label = "Photo ${idx + 1}", isHd = true))
            }
        }
        // 2. Carousel entries but no photo list -> collect the image slides
        if (photoList.isEmpty() && carouselList.isNotEmpty()) {
            carouselList.forEach { item ->
                if (!item.format.equals("mp4", ignoreCase = true)) photoList.add(item.url)
            }
        }

        // Quality options only make sense for a real video
        if (qualityList.isEmpty() && videoUrl.isNotBlank() && photoList.isEmpty()) {
            qualityList.add(QualityOption(label = "فيديو عالي الدقة (HD MP4)", url = videoUrl, format = "mp4", isHd = true))
            if (!audioUrl.isNullOrBlank()) {
                qualityList.add(QualityOption(label = "تحميل الصوت (MP3)", url = audioUrl, format = "mp3", isHd = false))
            }
        }

        val resolvedVideoUrl = when {
            videoUrl.isNotBlank() -> videoUrl
            carouselList.isNotEmpty() -> carouselList.first().url
            photoList.isNotEmpty() -> photoList.first()
            else -> ""
        }

        return MediaResult(
            title = title,
            videoUrl = resolvedVideoUrl,
            audioUrl = audioUrl,
            thumbnailUrl = thumbnailUrl ?: photoList.firstOrNull() ?: carouselList.firstOrNull()?.url,
            platform = platform,
            author = author,
            durationText = durationText,
            sourceUrl = originalUrl,
            qualityOptions = qualityList,
            carouselItems = carouselList,
            photoUrls = photoList,
            isDirectStream = true
        )
    }

    private fun extractPhotoUrls(json: JSONObject): MutableList<String> {
        val results = mutableListOf<String>()
        val seen = mutableSetOf<String>()

        val photoKeys = listOf(
            "photoUrls", "photo_urls", "photos", "images", "gallery",
            "slides", "items", "entries", "media", "medias", "album"
        )

        for (key in photoKeys) {
            val arr = json.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) {
                val element = arr.opt(i) ?: continue
                val candidateUrl: String? = when (element) {
                    is String -> element
                    is JSONObject -> {
                        // Skip video entries; they belong to the carousel, not to the photo list
                        val type = element.cleanString("type", "media_type").lowercase()
                        if (type.contains("video")) null
                        else element.cleanString(
                            "url", "display_url", "image", "src", "download_url", "photo_url", "original"
                        ).takeIf { it.isNotBlank() }
                    }
                    else -> null
                }

                val clean = candidateUrl?.trim().orEmpty()
                if ((clean.startsWith("http://") || clean.startsWith("https://")) &&
                    !MediaUrlHints.looksLikeVideo(clean) &&
                    !MediaUrlHints.looksLikeAudio(clean)
                ) {
                    if (seen.add(clean)) results.add(clean)
                }
            }
        }
        return results
    }

    private fun extractCarouselItems(json: JSONObject): List<CarouselItem> {
        val results = mutableListOf<CarouselItem>()
        val seen = mutableSetOf<String>()

        val carouselKeys = listOf(
            "carouselItems", "carousel_items", "carousel", "slides",
            "entries", "items", "media", "medias", "gallery"
        )

        for (key in carouselKeys) {
            val arr = json.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) {
                val element = arr.opt(i) as? JSONObject ?: continue
                val cUrl = element.cleanString("url", "download_url", "display_url", "src", "image")
                if (cUrl.isBlank() || MediaUrlHints.looksLikeAudio(cUrl) || !seen.add(cUrl)) continue

                val isVideo = element.cleanString("type", "media_type").equals("video", ignoreCase = true) ||
                    element.cleanString("format").equals("mp4", ignoreCase = true) ||
                    (MediaUrlHints.looksLikeVideo(cUrl) && !MediaUrlHints.looksLikeImage(cUrl))

                results.add(
                    CarouselItem(
                        url = cUrl,
                        format = if (isVideo) "mp4" else "jpg",
                        label = element.cleanString("label").ifBlank {
                            if (isVideo) "Video ${results.size + 1}" else "Photo ${results.size + 1}"
                        },
                        isHd = element.optBoolean("isHd", true)
                    )
                )
            }
        }
        return results
    }
}
