package com.example

import com.example.service.MasterDownloadEngine
import com.example.service.UrlResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.regex.Pattern

class ExampleUnitTest {
    private val engine = MasterDownloadEngine()

    @Test
    fun testYouTubeBlockedUrls() {
        assertTrue(engine.isYouTubeUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertTrue(engine.isYouTubeUrl("https://youtu.be/dQw4w9WgXcQ"))
        assertTrue(engine.isYouTubeUrl("https://youtube.com/shorts/abcdef12345"))
        assertTrue(engine.isYouTubeUrl("https://m.youtube.com/watch?v=123"))

        assertFalse(engine.isYouTubeUrl("https://www.tiktok.com/@user/video/123456789"))
        assertFalse(engine.isYouTubeUrl("https://www.instagram.com/reel/C3_abcde/"))
        assertFalse(engine.isYouTubeUrl("https://x.com/user/status/123456789"))
        assertFalse(engine.isYouTubeUrl("https://www.capcut.com/template/12345"))
    }

    @Test
    fun testRemoteEngineRegexMatching() {
        val tiktokPattern = Pattern.compile("(tiktok\\.com|douyin\\.com|tt\\.site)", Pattern.CASE_INSENSITIVE)
        assertTrue(tiktokPattern.matcher("https://www.tiktok.com/@creator/video/123").find())
        assertTrue(tiktokPattern.matcher("https://vm.tiktok.com/ZM8abcde/").find())
        assertTrue(tiktokPattern.matcher("https://www.tt.site/t/ZSabc123/").find())
        assertTrue(tiktokPattern.matcher("https://tt.site/t/ZSabc123").find())

        val instagramPattern = Pattern.compile("(instagram\\.com|instagr\\.am)", Pattern.CASE_INSENSITIVE)
        assertTrue(instagramPattern.matcher("https://www.instagram.com/reel/Cw_example").find())
        assertTrue(instagramPattern.matcher("https://instagr.am/p/Cw_example").find())

        val twitterPattern = Pattern.compile("(twitter\\.com|x\\.com)", Pattern.CASE_INSENSITIVE)
        assertTrue(twitterPattern.matcher("https://twitter.com/user/status/1234567890").find())
        assertTrue(twitterPattern.matcher("https://x.com/user/status/1234567890").find())

        val facebookPattern = Pattern.compile("(facebook\\.com|fb\\.watch)", Pattern.CASE_INSENSITIVE)
        assertTrue(facebookPattern.matcher("https://fb.watch/m123456/").find())
        assertTrue(facebookPattern.matcher("https://www.facebook.com/reel/123456789").find())
    }

    @Test
    fun testTikTokShortenedVariants() {
        assertTrue(UrlResolver.isTikTokShortenedUrl("https://www.tt.site/t/ZS12345/"))
        assertTrue(UrlResolver.isTikTokShortenedUrl("https://tt.site/t/ZS12345"))
        assertTrue(UrlResolver.isTikTokShortenedUrl("https://vt.tiktok.com/ZS12345/"))
        assertTrue(UrlResolver.isTikTokShortenedUrl("https://vm.tiktok.com/ZS12345/"))
        assertTrue(UrlResolver.isTikTokShortenedUrl("https://www.tiktok.com/t/ZS12345/"))

        assertFalse(UrlResolver.isTikTokShortenedUrl("https://www.instagram.com/reel/123"))
    }

    @Test
    fun testUrlExtractorCleaning() {
        val messy = "Watch this video: https://vt.tiktok.com/ZS12345/ now!"
        val extracted = UrlResolver.extractUrlFromString(messy)
        assertEquals("https://vt.tiktok.com/ZS12345", extracted)

        val ttSite = "Check out https://www.tt.site/t/ZSabc12/ on tt"
        assertEquals("https://www.tt.site/t/ZSabc12", UrlResolver.extractUrlFromString(ttSite))

        val tracked = "https://www.tiktok.com/@user/video/123456789?is_from_webapp=1&sender_device=pc"
        val cleaned = UrlResolver.cleanTrackingParams(tracked)
        assertEquals("https://www.tiktok.com/@user/video/123456789", cleaned)
    }

    @Test
    fun testPrefixAndSymbolsSanitization() {
        val equalsPrefix = "==https://www.instagram.com/reel/C3_abcde/=="
        assertEquals("https://www.instagram.com/reel/C3_abcde", UrlResolver.sanitizeAndExtractUrl(equalsPrefix))

        val markdownPrefix = "[Watch Reel](https://www.instagram.com/reel/C3_abcde/)"
        assertEquals("https://www.instagram.com/reel/C3_abcde", UrlResolver.sanitizeAndExtractUrl(markdownPrefix))

        val socialShare = "Amazing pin! Check it out: https://pin.it/7abcXYZ (via Pinterest)"
        assertEquals("https://pin.it/7abcXYZ", UrlResolver.sanitizeAndExtractUrl(socialShare))
    }

    @Test
    fun testInstagramShortcodeAndReelDetection() {
        val reelPattern = Pattern.compile("/(?:reel|reels|tv)/([A-Za-z0-9_-]+)", Pattern.CASE_INSENSITIVE)
        assertTrue(reelPattern.matcher("https://www.instagram.com/reel/C8qV186s83l/").find())
        assertTrue(reelPattern.matcher("https://www.instagram.com/reels/C8qV186s83l/").find())
        assertTrue(reelPattern.matcher("https://www.instagram.com/tv/C8qV186s83l/").find())
        assertFalse(reelPattern.matcher("https://www.instagram.com/p/C8qV186s83l/").find())

        val anyPostPattern = Pattern.compile("/(?:share/reel|share/p|p|reel|reels|share|tv)/([A-Za-z0-9_-]+)", Pattern.CASE_INSENSITIVE)
        val m = anyPostPattern.matcher("https://www.instagram.com/reel/C8qV186s83l/?igsh=xyz")
        assertTrue(m.find())
        assertEquals("C8qV186s83l", m.group(1))

        val shareMatcher = anyPostPattern.matcher("https://www.instagram.com/share/C8qV186s83l/?utm_source=ig_web_copy_link")
        assertTrue(shareMatcher.find())
        assertEquals("C8qV186s83l", shareMatcher.group(1))

        val shareReelMatcher = anyPostPattern.matcher("https://www.instagram.com/share/reel/C8qV186s83l/?igsh=xyz123")
        assertTrue(shareReelMatcher.find())
        assertEquals("C8qV186s83l", shareReelMatcher.group(1))

        val sharePMatcher = anyPostPattern.matcher("https://www.instagram.com/share/p/C8qV186s83l/?igsh=abc")
        assertTrue(sharePMatcher.find())
        assertEquals("C8qV186s83l", sharePMatcher.group(1))
    }
}
