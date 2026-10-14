package com.example.service

/**
 * Dynamic Engine Router.
 *
 * Single authority for:
 * - validating a source URL,
 * - detecting the target platform,
 * - resolving which Python engine directory (under `active_engines/`) handles the request,
 * - describing the execution source for structured logging.
 *
 * The Kotlin layer never scrapes media itself; it only routes to the on-device Python engines
 * via [PythonEngineRunner], so extraction logic stays fully OTA-updatable.
 */
object DynamicEngineRouter {

    data class Route(
        /** Human readable platform name used for reporting/logging. */
        val platformName: String,
        /** Directory name under `active_engines/` (matches `downapp/<Dir>/engine.py`). */
        val engineDir: String?,
        /** Relative engine file path (blank for the universal fallback). */
        val engineFile: String,
        val source: String
    )

    private data class Rule(
        val platformName: String,
        val engineDir: String,
        /** Domains to match against the request host. A trailing dot means "any ccTLD/prefix". */
        val hostFragments: List<String>
    )

    // Order matters: more specific hosts first.
    private val rules = listOf(
        Rule("Snapchat", "Snapchat", listOf("snapchat.com", "snap.com", "sc-cdn.net")),
        Rule("Instagram", "Instagram", listOf("instagram.com", "instagr.am")),
        Rule("TikTok", "Tiktok", listOf("tiktok.com", "douyin.com", "tt.site")),
        Rule("Twitter", "Twitter", listOf("twitter.com", "x.com", "t.co")),
        Rule("Facebook", "Facebook", listOf("facebook.com", "fb.watch", "fb.com")),
        Rule("Pinterest", "Pinterest", listOf("pinterest.", "pin.it")),
        Rule("Threads", "Threads", listOf("threads.net", "threads.com")),
        Rule("Reddit", "Reddit", listOf("reddit.com", "redd.it"))
    )

    val supportedEngineDirs: List<String> = rules.map { it.engineDir }

    fun validate(url: String): Boolean {
        val trimmed = url.trim()
        return (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)) && trimmed.length in 8..2048
    }

    fun isYouTubeUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("youtube.com") || lower.contains("youtu.be") || lower.contains("yt.be")
    }

    fun route(url: String): Route {
        val host = hostOf(url)
        rules.firstOrNull { rule -> rule.hostMatches(host) }?.let { rule ->
            return Route(
                platformName = rule.platformName,
                engineDir = rule.engineDir,
                engineFile = "${rule.engineDir}/engine.py",
                source = "Python(${rule.engineDir}/engine.py)"
            )
        }
        return Route(
            platformName = "Web",
            engineDir = null,
            engineFile = "",
            source = "Python(universal)"
        )
    }

    private fun Rule.hostMatches(host: String): Boolean = hostFragments.any { fragment ->
        if (fragment.endsWith(".")) {
            // Prefix fragment (e.g. "pinterest.") to cover ccTLDs like pinterest.co.uk.
            host.startsWith(fragment) || host.contains(".$fragment")
        } else {
            // Exact host or any subdomain of it (never a mid-string match).
            host == fragment || host.endsWith(".$fragment")
        }
    }

    /** Extracts the lower-cased host from a URL, stripping scheme, userinfo and port. */
    private fun hostOf(url: String): String {
        val lower = url.lowercase().trim()
        val afterScheme = lower.substringAfter("://", lower)
        val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        val hostPort = authority.substringAfterLast('@')
        return hostPort.substringBefore(':')
    }
}
