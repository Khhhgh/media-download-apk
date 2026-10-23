package com.example

import com.example.model.CarouselItem
import com.example.model.MediaKind
import com.example.model.MediaResult
import com.example.model.authorLabel
import com.example.model.mediaItems
import com.example.model.mediaKind
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaKindTest {

    private fun media(
        videoUrl: String = "",
        photos: List<String> = emptyList(),
        carousel: List<CarouselItem> = emptyList(),
        author: String? = null,
        platform: String = "TikTok"
    ) = MediaResult(
        title = "t",
        videoUrl = videoUrl,
        platform = platform,
        author = author,
        sourceUrl = "https://example.com",
        photoUrls = photos,
        carouselItems = carousel
    )

    @Test
    fun singleVideoIsVideo() {
        val m = media(videoUrl = "https://cdn.example.com/v/clip.mp4?token=1")
        assertEquals(MediaKind.VIDEO, m.mediaKind())
        assertEquals(1, m.mediaItems().size)
    }

    @Test
    fun tiktokPhotoPostIsAlbumEvenWhenVideoUrlIsPresent() {
        val m = media(
            videoUrl = "https://cdn.example.com/music/song.mp4",
            photos = listOf("https://p16.tiktokcdn.com/a~tplv-photomode-image.jpeg", "https://p16.tiktokcdn.com/b~tplv-photomode-image.jpeg")
        )
        assertEquals(MediaKind.CAROUSEL_IMAGES, m.mediaKind())
        assertEquals(2, m.mediaItems().size)
    }

    @Test
    fun singlePhotoIsImage() {
        val m = media(videoUrl = "https://cdn.example.com/photo.jpg")
        assertEquals(MediaKind.IMAGE, m.mediaKind())
    }

    @Test
    fun mixedCarouselIsMixed() {
        val m = media(
            carousel = listOf(
                CarouselItem("https://cdn.example.com/1.jpg", "jpg"),
                CarouselItem("https://cdn.example.com/2.mp4", "mp4")
            )
        )
        assertEquals(MediaKind.CAROUSEL_MIXED, m.mediaKind())
    }

    @Test
    fun duplicateUrlsAreCountedOnce() {
        val url = "https://cdn.example.com/1.jpg"
        val m = media(photos = listOf(url, url), carousel = listOf(CarouselItem(url, "jpg")))
        assertEquals(1, m.mediaItems().size)
        assertEquals(MediaKind.IMAGE, m.mediaKind())
    }

    @Test
    fun missingAuthorFallsBackToPlatformNeverNull() {
        assertEquals("TikTok", media(author = null).authorLabel())
        assertEquals("TikTok", media(author = "null").authorLabel())
        assertEquals("TikTok", media(author = "  ").authorLabel())
        assertEquals("@user", media(author = "@user").authorLabel())
        assertEquals("@user", media(author = "user").authorLabel())
    }
}
