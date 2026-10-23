package com.example.service

import android.content.Context
import com.example.data.api.ApiClient
import com.example.model.MediaResult
import com.example.model.PlatformInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.regex.Pattern

class YouTubeBlockedException(
    message: String = "YouTube is strictly blocked. In compliance with developer policies, YouTube downloading is not supported."
) : Exception(message)

object UrlResolver {
    /**
     * Strictly extracts valid URL from user input, removing extra captions, prefixes (e.g. '=='), quotes, and punctuation.
     */
    fun sanitizeAndExtractUrl(input: String): String {
        if (input.isBlank()) return ""

        // Extract first valid HTTP/HTTPS link using regex: https?://[^\s"'<>]+
        val pattern = Pattern.compile("https?://[^\\s\"'<>]+", Pattern.CASE_INSENSITIVE)
        val matcher = pattern.matcher(input)

        return if (matcher.find()) {
            var url = matcher.group(0).trim()
            url = url.trimEnd('.', ',', ';', ':', ')', ']', '}', '\'', '"', '\\', '>', '<', '?', '!', '=', '`', '*')
            while (url.endsWith("/") && !url.endsWith("://")) {
                url = url.substring(0, url.length - 1)
            }
            url.trimEnd('.', ',', ';', ':', ')', ']', '}', '\'', '"', '\\', '>', '<', '?', '!', '=', '`', '*')
        } else {
            val trimmed = input.trim().trimStart('=', '/', '\\', ' ', '\t', '\n', '\r', '"', '\'', '<', '>', '#', '*', '`', '-', '+', ':')
            if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
                val candidate = trimmed.split("\\s+".toRegex())[0]
                var cand = candidate.trimEnd('.', ',', ';', ':', ')', ']', '}', '\'', '"', '\\', '>', '<', '?', '!', '=', '`', '*')
                while (cand.endsWith("/") && !cand.endsWith("://")) {
                    cand = cand.substring(0, cand.length - 1)
                }
                cand.trimEnd('.', ',', ';', ':', ')', ']', '}', '\'', '"', '\\', '>', '<', '?', '!', '=', '`', '*')
            } else {
                trimmed
            }
        }
    }

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
            if (qIndex != -1) url.substring(0, qIndex) else url
        } catch (_: Exception) {
            url
        }
    }
}

class MasterDownloadEngine(
    private val context: Context? = null,
    private val apiClient: ApiClient = ApiClient()
) {
    fun isYouTubeUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("youtube.com") ||
                lower.contains("youtu.be") ||
                lower.contains("youtube-nocookie.com")
    }

    /**
     * Resolves media via Ubuntu VPS API endpoint https://thaker.duckdns.org/api/extract
     */
    suspend fun resolveMedia(
        rawUrl: String,
        activePlatforms: List<PlatformInfo> = emptyList()
    ): MediaResult = withContext(Dispatchers.IO) {
        val cleanUrl = UrlResolver.sanitizeAndExtractUrl(rawUrl)
        if (cleanUrl.isBlank()) {
            throw IOException("Please enter a valid link")
        }

        if (isYouTubeUrl(cleanUrl)) {
            throw YouTubeBlockedException()
        }

        // Check if platform is marked in maintenance in activePlatforms list
        val matchedPlatform = activePlatforms.firstOrNull { it.matchesUrl(cleanUrl) }
        if (matchedPlatform != null && !matchedPlatform.enabled) {
            val msg = matchedPlatform.maintenanceMessage
                ?: "المنصة قيد الصيانة المؤقتة على السيرفر / Platform under maintenance"
            throw IOException(msg)
        }

        // Send extraction request to Ubuntu VPS
        apiClient.extractMedia(cleanUrl)
    }
}
