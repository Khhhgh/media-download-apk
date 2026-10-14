package com.example.service

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.model.CarouselItem
import com.example.model.MediaResult
import com.example.model.QualityOption
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Layer 2: Headless Local Extraction Engine (Zero-Cookie / Zero-Session).
 *
 * Runs an ephemeral background WebView using the user device's Residential IP
 * to bypass Instagram's server-side cloud IP 403 / login_required blocks.
 * Intercepts direct .mp4 CDN streams from cdninstagram.com and fbcdn.net,
 * injects lightweight DOM JS probes as secondary fallback, and strictly
 * terminates within an 8-second window with immediate resource cleanup.
 */
class HeadlessInstagramExtractor(private val context: Context) {
    private val TAG = "HeadlessInstagram"

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun extract(
        canonicalUrl: String,
        shortcode: String,
        isReel: Boolean
    ): MediaResult? = withTimeoutOrNull(8000L) {
        withContext(Dispatchers.Main) {
            val completion = CompletableDeferred<MediaResult?>()
            val capturedVideos = LinkedHashSet<String>()
            val capturedImages = LinkedHashSet<String>()
            var webView: WebView? = null

            try {
                webView = WebView(context.applicationContext).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.loadsImagesAutomatically = true
                    settings.userAgentString =
                        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val reqUrl = request?.url?.toString() ?: return null

                            // Check for direct CDN video streams
                            val isCdnDomain = reqUrl.contains("cdninstagram.com") || reqUrl.contains("fbcdn.net")
                            val isMp4Stream = reqUrl.contains(".mp4") ||
                                    reqUrl.contains("mime_type=video_mp4") ||
                                    reqUrl.contains("/v/t50.") ||
                                    reqUrl.contains("/o1/v/")

                            if (isCdnDomain && isMp4Stream) {
                                synchronized(capturedVideos) {
                                    if (!capturedVideos.contains(reqUrl)) {
                                        capturedVideos.add(reqUrl)
                                        Log.d(TAG, "Headless intercepted direct CDN MP4 stream: $reqUrl")
                                        val items = listOf(
                                            CarouselItem(
                                                url = reqUrl,
                                                format = "mp4",
                                                label = if (isReel) "Instagram Reel (MP4)" else "Instagram Video (MP4)",
                                                isHd = true
                                            )
                                        )
                                        val mediaResult = MediaResult(
                                            title = if (isReel) "Instagram Reel" else "Instagram Video",
                                            videoUrl = reqUrl,
                                            platform = "Instagram",
                                            sourceUrl = canonicalUrl,
                                            qualityOptions = listOf(
                                                QualityOption(
                                                    label = "Original Quality Video (MP4)",
                                                    url = reqUrl,
                                                    format = "mp4",
                                                    isHd = true
                                                )
                                            ),
                                            carouselItems = items
                                        )
                                        completion.complete(mediaResult)
                                    }
                                }
                            } else if (isCdnDomain && (reqUrl.contains(".jpg") || reqUrl.contains(".webp") || reqUrl.contains("/p/"))) {
                                synchronized(capturedImages) {
                                    if (capturedImages.size < 10) {
                                        capturedImages.add(reqUrl)
                                    }
                                }
                            }
                            return null
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            if (completion.isCompleted) return

                            // Secondary fallback: DOM JS probe for <video> or <meta property="og:video">
                            val jsProbe = """
                                (function() {
                                    try {
                                        var v = document.querySelector('video source') || document.querySelector('video');
                                        if (v && v.src && v.src.indexOf('http') === 0) return v.src;
                                        var og = document.querySelector('meta[property="og:video"]');
                                        if (og && og.content && og.content.indexOf('http') === 0) return og.content;
                                        var scripts = document.querySelectorAll('script');
                                        for (var i = 0; i < scripts.length; i++) {
                                            var text = scripts[i].textContent || '';
                                            var m = text.match(/"video_url"\s*:\s*"(https:[^"]+)"/);
                                            if (m && m[1]) return m[1].replace(/\\u0026/g, '&').replace(/\\\//g, '/');
                                        }
                                    } catch(e) {}
                                    return '';
                                })();
                            """.trimIndent()

                            view?.evaluateJavascript(jsProbe) { evalResult ->
                                if (completion.isCompleted) return@evaluateJavascript
                                val cleanUrl = evalResult
                                    ?.removeSurrounding("\"")
                                    ?.replace("\\/", "/")
                                    ?.replace("\\u0026", "&")
                                    ?.trim() ?: ""

                                if (cleanUrl.startsWith("http")) {
                                    Log.d(TAG, "Headless DOM JS extracted MP4: $cleanUrl")
                                    val items = listOf(
                                        CarouselItem(
                                            url = cleanUrl,
                                            format = "mp4",
                                            label = if (isReel) "Instagram Reel (MP4)" else "Instagram Video (MP4)",
                                            isHd = true
                                        )
                                    )
                                    val mediaResult = MediaResult(
                                        title = if (isReel) "Instagram Reel" else "Instagram Video",
                                        videoUrl = cleanUrl,
                                        platform = "Instagram",
                                        sourceUrl = canonicalUrl,
                                        qualityOptions = listOf(
                                            QualityOption(
                                                label = "Original Quality Video (MP4)",
                                                url = cleanUrl,
                                                format = "mp4",
                                                isHd = true
                                            )
                                        ),
                                        carouselItems = items
                                    )
                                    completion.complete(mediaResult)
                                }
                            }
                        }
                    }

                    // Load captioned embed endpoint which renders video DOM cleanly without login dialogs
                    val embedUrl = "https://www.instagram.com/p/$shortcode/embed/captioned/"
                    loadUrl(embedUrl)
                }

                val result = completion.await()
                if (result != null) {
                    return@withContext result
                }

                // If not a Reel and we caught images
                if (!isReel && capturedImages.isNotEmpty()) {
                    val imgList = capturedImages.toList()
                    val carousel = imgList.mapIndexed { idx, u ->
                        CarouselItem(url = u, format = "jpg", label = "Photo ${idx + 1}", isHd = true)
                    }
                    return@withContext MediaResult(
                        title = "Instagram Photos",
                        videoUrl = imgList.first(),
                        platform = "Instagram",
                        sourceUrl = canonicalUrl,
                        qualityOptions = listOf(
                            QualityOption(
                                label = if (imgList.size > 1) "Download All Photos (${imgList.size} JPG)" else "Download Photo (JPG)",
                                url = imgList.first(),
                                format = "jpg",
                                isHd = true
                            )
                        ),
                        photoUrls = imgList,
                        carouselItems = carousel
                    )
                }

                null
            } catch (e: Exception) {
                Log.w(TAG, "Headless execution error: ${e.message}")
                null
            } finally {
                // Strict cleanup: stop loading and destroy immediately to prevent memory leak
                try {
                    webView?.stopLoading()
                    webView?.loadUrl("about:blank")
                    webView?.destroy()
                } catch (e: Exception) {
                    Log.w(TAG, "WebView cleanup error: ${e.message}")
                }
            }
        }
    }
}
