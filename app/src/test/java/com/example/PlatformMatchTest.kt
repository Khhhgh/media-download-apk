package com.example

import com.example.data.api.ApiClient
import com.example.model.MediaUrlHints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformMatchTest {
    private fun detect(url: String): String? =
        ApiClient.DEFAULT_PLATFORMS.firstOrNull { it.matchesUrl(url) }?.id

    @Test
    fun everyPlatformIsDetectedAsItself() {
        assertEquals("tiktok", detect("https://vm.tiktok.com/ZM8abc/"))
        assertEquals("instagram", detect("https://www.instagram.com/p/C8qV186s83l/"))
        assertEquals("facebook", detect("https://m.facebook.com/watch/?v=1"))
        assertEquals("pinterest", detect("https://www.pinterest.com/pin/123/"))
        assertEquals("pinterest", detect("https://pin.it/7abcXYZ"))
        assertEquals("twitter", detect("https://x.com/user/status/123"))
        assertEquals("twitter", detect("https://t.co/abc"))
        assertEquals("snapchat", detect("https://www.snapchat.com/spotlight/abc"))
        assertEquals("reddit", detect("https://www.reddit.com/r/videos/comments/xyz/"))
        assertEquals("threads", detect("https://www.threads.net/@a/post/1"))
        assertEquals("threads", detect("https://www.threads.com/@a/post/1"))
    }

    @Test
    fun unrelatedHostsAreNotMatched() {
        assertEquals(null, detect("https://box.com/file"))
        assertEquals(null, detect("https://example.org/instagram.com"))
    }

    @Test
    fun twitterImageSuffixAndStreams() {
        assertTrue(MediaUrlHints.looksLikeImage("https://pbs.twimg.com/media/abc.jpg:large"))
        assertTrue(MediaUrlHints.looksLikeStreamPlaylist("https://video.twimg.com/a/playlist.m3u8?x=1"))
    }
}
