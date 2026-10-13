package com.example

import com.example.service.MasterDownloadEngine
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
        val tiktokPattern = Pattern.compile("(tiktok\\.com|douyin\\.com)", Pattern.CASE_INSENSITIVE)
        assertTrue(tiktokPattern.matcher("https://www.tiktok.com/@creator/video/123").find())
        assertTrue(tiktokPattern.matcher("https://vm.tiktok.com/ZM8abcde/").find())

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
}
