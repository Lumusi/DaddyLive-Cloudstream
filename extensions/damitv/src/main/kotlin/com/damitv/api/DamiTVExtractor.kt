package com.damitv.api

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WebView-based extractor for DamiTV embed pages (pooembed.eu).
 *
 * The embed domain (pooembed.eu) loads player JS dynamically — either JW Player
 * (default) or Clappr — with no static video content in the HTML. The only
 * reliable way to extract the HLS stream is by rendering the page in a WebView,
 * letting the player initialize, and intercepting the resulting .m3u8 network request.
 *
 * Architecture:
 * 1. Embed page loaded in headless WebView: pooembed.eu/embed/{matchId}
 * 2. Player JS (JW Player / Clappr) initializes and fetches the HLS manifest
 * 3. shouldInterceptRequest captures the first .m3u8 URL
 * 4. URL is passed to the player as an ExtractorLink
 *
 * Ad domains are blocked to reduce page load time and prevent popup injection.
 */
open class DamiTVExtractor(context: Context) : ExtractorApi() {
    override val name = "DamiTV"
    override val mainUrl = "https://pooembed.eu"
    override val requiresReferer = true
    private val appContext = context.applicationContext

    companion object {
        val refererHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:141.0) Gecko/20100101 Firefox/141.0",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.5",
            "Referer" to "https://dami-tv.pro/"
        )

        // Ad/tracker domains to block — these inject popups and slow down the page
        private val blockedDomains = listOf(
            "wpnxiswpuyrfn.icu",
            "vbcojhroxkoaf.online",
            "adexchangeclear.com",
            "velocecdn.com"
        )
    }

    /**
     * Primary extraction: WebView-based with retry.
     * The pooembed.eu page requires JS execution (player loads dynamically).
     */
    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        var lastError: Exception? = null
        repeat(2) { attempt ->
            try {
                val normalizedUrl = url.replaceFirst("http://", "https://")
                val videoUrl = withContext(Dispatchers.Main) {
                    getVideoUrlWithWebView(appContext, normalizedUrl)
                }
                if (videoUrl != null) {
                    processVideoUrl(videoUrl, callback)
                    return
                }
            } catch (e: Exception) {
                lastError = e
                if (attempt < 1) {
                    kotlinx.coroutines.delay(1000L * (attempt + 1))
                }
            }
        }

        // Fallback: if url is already an m3u8
        tryExtractDirectUrl(url, callback)
    }

    /**
     * If the URL itself is already an .m3u8 link, return it directly.
     */
    private suspend fun tryExtractDirectUrl(
        url: String,
        callback: (ExtractorLink) -> Unit
    ) {
        if (url.endsWith(".m3u8", ignoreCase = true) || url.endsWith(".ms3", ignoreCase = true)) {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "Direct HLS",
                    url = url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.quality = Qualities.Unknown.value
                    this.referer = "https://dami-tv.pro/"
                    this.headers = refererHeaders
                }
            )
        }
    }

    /**
     * Launches a headless WebView to render the pooembed.eu embed page and capture
     * the .m3u8 stream URL from network requests.
     *
     * The page loads player JS dynamically after ad/tracking scripts. We wait
     * for the player to initialize and request the stream, then capture the .m3u8
     * URL via shouldInterceptRequest.
     */
    private suspend fun getVideoUrlWithWebView(context: Context, url: String): String? {
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<String?> { cont ->
                val captured = AtomicBoolean(false)
                var webView: WebView? = null

                try {
                    webView = WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.userAgentString =
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:141.0) Gecko/20100101 Firefox/141.0"
                        settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                        settings.setSupportZoom(false)
                        settings.loadWithOverviewMode = true
                        settings.blockNetworkImage = true  // Block images (ads)
                        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        settings.setGeolocationEnabled(false)

                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)

                                // Wait for player to initialize (ads + player JS need time)
                                Handler(Looper.getMainLooper()).postDelayed({
                                    view?.evaluateJavascript(
                                        """
                                        (function() {
                                            try {
                                                // Try JW Player API
                                                if (typeof jwplayer === 'function') {
                                                    try { jwplayer().play(); } catch(e) {}
                                                }
                                                // Try standard video element
                                                var video = document.querySelector('video');
                                                if (video) { video.play(); }
                                                // Try Clappr player
                                                if (window.player && typeof window.player.play === 'function') {
                                                    window.player.play();
                                                }
                                                return 'play_attempted';
                                            } catch(e) {
                                                return 'error: ' + e.message;
                                            }
                                        })();
                                        """.trimIndent()
                                    ) { result -> }
                                }, 6000) // 6s for ads + player init
                            }

                            @Suppress("DEPRECATION")
                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): android.webkit.WebResourceResponse? {
                                val reqUrl = request?.url?.toString() ?: return null

                                // Block ad/tracker domains to reduce page clutter
                                if (blockedDomains.any { reqUrl.contains(it, ignoreCase = true) }) {
                                    return android.webkit.WebResourceResponse(null, null, null)
                                }

                                // Capture HLS manifest URLs
                                if (!captured.get() && (
                                    reqUrl.endsWith(".m3u8", ignoreCase = true) ||
                                    reqUrl.endsWith(".ms3", ignoreCase = true)
                                )) {
                                    if (captured.compareAndSet(false, true)) {
                                        cont.resume(reqUrl, onCancellation = null)
                                        Handler(Looper.getMainLooper()).postDelayed({
                                            try { destroy() } catch (_: Exception) {}
                                        }, 500)
                                    }
                                }
                                return super.shouldInterceptRequest(view, request)
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: android.webkit.WebResourceError?
                            ) {
                                super.onReceivedError(view, request, error)
                                // Don't fail on sub-resource errors (ads failing, etc.)
                            }
                        }
                    }

                    webView.loadUrl(url)

                    // Timeout: 30 seconds max for stream capture
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (captured.compareAndSet(false, true)) {
                            cont.resume(null, onCancellation = null)
                            try { webView?.destroy() } catch (_: Exception) {}
                        }
                    }, 30000)

                } catch (e: Exception) {
                    if (captured.compareAndSet(false, true)) {
                        cont.resume(null, onCancellation = null)
                    }
                }

                cont.invokeOnCancellation {
                    if (captured.compareAndSet(false, true)) {
                        Handler(Looper.getMainLooper()).post {
                            try { webView?.destroy() } catch (_: Exception) {}
                        }
                    }
                }
            }
        }
    }

    /**
     * Wrap the captured HLS URL into an ExtractorLink for the player.
     */
    private suspend fun processVideoUrl(videoUrl: String, callback: (ExtractorLink) -> Unit) {
        val qualityLabel = when {
            videoUrl.contains("720") -> "720p"
            videoUrl.contains("1080") -> "1080p"
            videoUrl.contains("480") -> "480p"
            videoUrl.contains("360") -> "360p"
            else -> name
        }

        val streamingHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:141.0) Gecko/20100101 Firefox/141.0",
            "Accept" to "*/*",
            "Accept-Language" to "en-US,en;q=0.5",
            "Accept-Encoding" to "gzip, deflate, br",
            "Origin" to "https://pooembed.eu",
            "Referer" to "https://dami-tv.pro/",
            "Connection" to "keep-alive",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "cross-site"
        )

        callback.invoke(
            newExtractorLink(
                source = name,
                name = qualityLabel,
                url = videoUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.quality = Qualities.Unknown.value
                this.referer = "https://dami-tv.pro/"
                this.headers = streamingHeaders
            }
        )
    }
}
