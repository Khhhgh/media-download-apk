package com.example.model

import androidx.annotation.Keep

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

