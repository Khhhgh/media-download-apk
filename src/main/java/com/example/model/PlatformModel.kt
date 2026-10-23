package com.example.model

import androidx.annotation.Keep

@Keep
data class PlatformInfo(
    val id: String,
    val name: String,
    val nameAr: String = "",
    val enabled: Boolean = true,
    val maintenanceMessage: String? = null,
    val patterns: List<String> = emptyList(),
    val sampleUrl: String = "",
    val icon: String = "web",
    val badge: String = ""
) {
    fun getDisplayName(isArabic: Boolean): String {
        return if (isArabic && nameAr.isNotBlank()) nameAr else name
    }

    /**
     * Matches by host, not by plain substring. A substring test made "t.co" (Twitter) match
     * pinterest.com, snapchat.com and reddit.com, so those links were detected as Twitter and
     * were blocked whenever Twitter was in maintenance.
     */
    fun matchesUrl(url: String): Boolean {
        val lower = url.trim().lowercase()
        val host = hostOf(lower)
        return patterns.any { raw ->
            val pat = raw.trim().lowercase().removePrefix("www.")
            when {
                pat.isEmpty() -> false
                host == null || pat.contains('/') -> lower.contains(pat)
                else -> host == pat || host.endsWith(".$pat")
            }
        }
    }

    private fun hostOf(lowerUrl: String): String? {
        val withScheme = if (lowerUrl.startsWith("http://") || lowerUrl.startsWith("https://")) lowerUrl else "https://$lowerUrl"
        val host = try {
            java.net.URI(withScheme.substringBefore(' ')).host
        } catch (_: Exception) {
            null
        }
        return host?.removePrefix("www.")?.takeIf { it.isNotBlank() }
    }
}

@Keep
data class PlatformsResponse(
    val success: Boolean = true,
    val version: Int = 1,
    val platforms: List<PlatformInfo> = emptyList()
)
