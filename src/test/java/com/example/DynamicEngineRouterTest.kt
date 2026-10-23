package com.example

import com.example.service.DynamicEngineRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicEngineRouterTest {

    @Test
    fun routesKnownPlatformsToTheirEngineDirectory() {
        assertEquals("Snapchat", DynamicEngineRouter.route("https://www.snapchat.com/add/x").engineDir)
        assertEquals("Instagram", DynamicEngineRouter.route("https://www.instagram.com/reel/abc").engineDir)
        assertEquals("Tiktok", DynamicEngineRouter.route("https://www.tiktok.com/@u/video/1").engineDir)
        assertEquals("Twitter", DynamicEngineRouter.route("https://x.com/u/status/1").engineDir)
        assertEquals("Facebook", DynamicEngineRouter.route("https://fb.watch/abc/").engineDir)
        assertEquals("Pinterest", DynamicEngineRouter.route("https://pin.it/abc").engineDir)
        assertEquals("Threads", DynamicEngineRouter.route("https://www.threads.net/@u/post/1").engineDir)
        assertEquals("Reddit", DynamicEngineRouter.route("https://www.reddit.com/r/x/comments/1").engineDir)
    }

    @Test
    fun engineFileMatchesTheRoutedDirectory() {
        val route = DynamicEngineRouter.route("https://www.instagram.com/reel/abc")
        assertEquals("Instagram/engine.py", route.engineFile)
        assertTrue(route.source.contains("Instagram"))
    }

    @Test
    fun unknownHostsFallBackToUniversalEngine() {
        val route = DynamicEngineRouter.route("https://example.com/video/1")
        assertEquals("Web", route.platformName)
        assertNull(route.engineDir)
        assertEquals("", route.engineFile)
    }

    @Test
    fun doesNotMisrouteHostsThatMerelyContainAPlatformSubstring() {
        // "reddit.com" contains "t.co", "box.com" contains "x.com": must not match Twitter.
        assertEquals("Reddit", DynamicEngineRouter.route("https://www.reddit.com/r/x/comments/1").engineDir)
        assertNull(DynamicEngineRouter.route("https://box.com/file/1").engineDir)
        assertNull(DynamicEngineRouter.route("https://netflix.com/watch/1").engineDir)
    }

    @Test
    fun validateAcceptsHttpAndHttpsOnly() {
        assertTrue(DynamicEngineRouter.validate("https://instagram.com/reel/abc"))
        assertTrue(DynamicEngineRouter.validate("http://instagram.com/reel/abc"))
        assertFalse(DynamicEngineRouter.validate("ftp://instagram.com/x"))
        assertFalse(DynamicEngineRouter.validate("not a url"))
    }

    @Test
    fun detectsYouTubeRegardlessOfCase() {
        assertTrue(DynamicEngineRouter.isYouTubeUrl("https://YouTube.com/watch?v=1"))
        assertTrue(DynamicEngineRouter.isYouTubeUrl("HTTPS://YOUTU.BE/x"))
        assertFalse(DynamicEngineRouter.isYouTubeUrl("https://instagram.com/reel/abc"))
    }
}
