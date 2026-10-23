package com.example.model

import androidx.annotation.Keep
import java.util.Locale

@Keep
data class QualityOption(
    val label: String,
    val url: String,
    val format: String = "mp4",
    val isHd: Boolean = false
)

/**
 * A single downloadable slide inside a multi-item post (e.g. Instagram Carousel).
 * [format] is either "mp4" for video slides or an image extension ("jpg", "png") for photos.
 */
@Keep
data class CarouselItem(
    val url: String,
    val format: String = "jpg",
    val label: String = "",
    val isHd: Boolean = true
)

@Keep
data class MediaResult(
    val title: String,
    val videoUrl: String,
    val audioUrl: String? = null,
    val thumbnailUrl: String? = null,
    val platform: String,
    val author: String? = null,
    val durationText: String? = null,
    val sourceUrl: String,
    val qualityOptions: List<QualityOption> = emptyList(),
    val isDirectStream: Boolean = true,
    val photoUrls: List<String> = emptyList(),
    val carouselItems: List<CarouselItem> = emptyList()
)

/** Kind of post, detected from the API response and the link extensions. */
enum class MediaKind {
    /** One video -> Movies/MediaDownloader (MP4). */
    VIDEO,

    /** One photo -> Pictures/MediaDownloader (JPG). */
    IMAGE,

    /** Photo album / slideshow (TikTok photo mode, Instagram photo carousel) -> Pictures. */
    CAROUSEL_IMAGES,

    /** Carousel that contains videos (or videos and photos together). */
    CAROUSEL_MIXED
}

/** One resolved, de-duplicated file of a post. */
data class MediaItem(
    val url: String,
    val isVideo: Boolean
)

object MediaUrlHints {
    private val IMAGE_EXT = listOf("jpg", "jpeg", "png", "webp", "awebp", "heic", "heif", "gif", "avif", "bmp")
    private val VIDEO_EXT = listOf("mp4", "mov", "webm", "mkv", "m4v", "3gp")
    private val AUDIO_EXT = listOf("mp3", "m4a", "aac", "ogg", "opus", "wav")

    private fun path(url: String): String {
        val full = url.lowercase(Locale.ROOT).substringBefore('#').substringBefore('?')
        // old Twitter links look like ".../abc.jpg:large"
        val suffix = full.substringAfterLast(':', "")
        return if (suffix in setOf("large", "orig", "small", "medium", "thumb")) full.substringBeforeLast(':') else full
    }

    private fun hasExt(url: String, list: List<String>): Boolean {
        val p = path(url)
        return list.any { p.endsWith(".$it") }
    }

    fun looksLikeImage(url: String): Boolean {
        if (url.isBlank()) return false
        if (hasExt(url, IMAGE_EXT)) return true
        val lower = url.lowercase(Locale.ROOT)
        // TikTok photo-mode CDN links and explicit mime hints in the query string
        return lower.contains("photomode") ||
            lower.contains("mime_type=image") ||
            lower.contains("mime_type=jpeg") ||
            lower.contains("format=jpg") ||
            lower.contains("format=jpeg") ||
            lower.contains("format=webp")
    }

    /** HLS / DASH playlists are text manifests, not a file that can be saved directly. */
    fun looksLikeStreamPlaylist(url: String): Boolean {
        val p = path(url)
        return p.endsWith(".m3u8") || p.endsWith(".mpd")
    }

    fun looksLikeVideo(url: String): Boolean {
        if (url.isBlank()) return false
        if (hasExt(url, VIDEO_EXT)) return true
        val lower = url.lowercase(Locale.ROOT)
        return lower.contains("mime_type=video")
    }

    fun looksLikeAudio(url: String): Boolean {
        if (url.isBlank()) return false
        if (hasExt(url, AUDIO_EXT)) return true
        return url.lowercase(Locale.ROOT).contains("mime_type=audio")
    }
}

/**
 * Every downloadable file of the post, in order, without duplicates.
 *
 * Priority: carousel slides, then photo URLs, and only when both are empty the main video URL.
 * (A TikTok photo post may carry a music/slideshow URL in videoUrl; the real photos always win.)
 */
fun MediaResult.mediaItems(): List<MediaItem> {
    val seen = LinkedHashMap<String, MediaItem>()

    carouselItems.forEach { slide ->
        val url = slide.url.trim()
        if (url.isNotBlank() && !seen.containsKey(url)) {
            val isVideo = slide.format.equals("mp4", ignoreCase = true) ||
                (MediaUrlHints.looksLikeVideo(url) && !MediaUrlHints.looksLikeImage(url))
            seen[url] = MediaItem(url, isVideo)
        }
    }

    photoUrls.forEach { raw ->
        val url = raw.trim()
        if (url.isNotBlank() && !seen.containsKey(url)) {
            seen[url] = MediaItem(url, isVideo = false)
        }
    }

    if (seen.isEmpty()) {
        val url = videoUrl.trim()
        if (url.isNotBlank()) {
            seen[url] = MediaItem(url, isVideo = !MediaUrlHints.looksLikeImage(url))
        }
    }
    return seen.values.toList()
}

/** Detects the post type: single video, single photo, photo album, or mixed carousel. */
fun MediaResult.mediaKind(): MediaKind {
    val items = mediaItems()
    if (items.isEmpty()) return MediaKind.VIDEO
    val videos = items.count { it.isVideo }
    return when {
        items.size == 1 -> if (items[0].isVideo) MediaKind.VIDEO else MediaKind.IMAGE
        videos == 0 -> MediaKind.CAROUSEL_IMAGES
        else -> MediaKind.CAROUSEL_MIXED
    }
}

/**
 * Null-safe account name. Returns null when the name is missing or is a placeholder
 * such as "null" / "undefined" so the UI can fall back to the platform name.
 */
fun MediaResult.authorOrNull(): String? = cleanAccountName(author)

fun cleanAccountName(raw: String?): String? {
    val cleaned = raw?.trim()?.trimStart('@')?.trim().orEmpty()
    if (cleaned.isBlank()) return null
    if (cleaned.equals("null", ignoreCase = true) || cleaned.equals("undefined", ignoreCase = true)) return null
    return cleaned
}

/** Text shown under the title: "@account" or the platform name when no account is available. */
fun MediaResult.authorLabel(): String {
    val name = authorOrNull()
    if (name != null) return "@$name"
    return platform.trim().ifBlank { "Media" }
}
