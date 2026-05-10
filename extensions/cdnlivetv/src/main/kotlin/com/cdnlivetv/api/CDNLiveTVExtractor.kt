package com.cdnlivetv.api

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
 * WebView-based extractor for CDNLiveTV (cdnlivetv.tv).
 *
 * The player domain (cdnlivetv.tv) is Cloudflare-protected and returns 401 for direct HTTP.
 * The only reliable way to extract the HLS stream is by rendering the page in a WebView,
 * triggering OPlayer playback, and intercepting the resulting .m3u8 network request.
 *
 * Architecture:
 * 1. Player page loaded in headless WebView: cdnlivetv.tv/api/v1/channels/player/?name=...&code=...
 * 2. OPlayer (from cdn.jsdelivr.net/npm/@oplayer/hls) initializes and fetches the HLS manifest
 * 3. shouldInterceptRequest captures the first .m3u8 URL
 * 4. URL is passed to the player as an ExtractorLink
 */
open class CDNLiveTVExtractor(context: Context) : ExtractorApi() {
    override val name = "CDNLiveTV"
    override val mainUrl = "https://cdnlivetv.tv"
    override val requiresReferer = true
    private val appContext = context.applicationContext

    companion object {
        // Headers for player page requests (cdnlivetv.tv domain)
        val refererHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:141.0) Gecko/20100101 Firefox/141.0",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.5",
            "Referer" to "https://cdnlivetv.tv/"
        )
        // Headers for API requests (should match player domain for consistency)
        val jsonHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:141.0) Gecko/20100101 Firefox/141.0",
            "Accept" to "application/json, text/html, */*; q=0.01",
            "Accept-Language" to "en-US,en;q=0.5",
            "Referer" to "https://cdnlivetv.tv/"
        )
    }

    /**
     * Primary extraction: WebView-first approach with retry logic.
     * The player page requires browser rendering (Cloudflare + OPlayer JS).
     * HTTP-only approaches return 401 due to Cloudflare challenge.
     *
     * Fire TV Compatibility: Includes retry mechanism for failed extractions
     * due to slower hardware or network issues.
     */
    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Primary: WebView extraction (handles Cloudflare + OPlayer)
        // Try up to 2 times for Fire TV compatibility
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
                // Wait before retry (exponential backoff)
                if (attempt < 1) {
                    kotlinx.coroutines.delay(1000L * (attempt + 1))
                }
            }
        }

        // If WebView failed after retries, try fallback
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
                    this.referer = "$mainUrl/"
                    this.headers = refererHeaders
                }
            )
        }
    }

    /**
     * Launches a headless WebView to render the OPlayer page and capture
     * the .m3u8 stream URL from network requests.
     *
     * Fire TV Compatibility: Uses longer timeout and more robust settings
     * for older WebView implementations on Amazon Fire TV devices.
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
                        settings.blockNetworkImage = false

                        // Fire TV compatibility: Enable more permissive settings
                        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        settings.setGeolocationEnabled(false)

                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)

                                // Wait for OPlayer to load, then attempt to trigger playback
                                // Fire TV needs longer delay for slower JS execution
                                Handler(Looper.getMainLooper()).postDelayed({
                                    view?.evaluateJavascript(
                                        """
                                        (function() {
                                            try {
                                                // Multiple attempts to find and click play button
                                                var playBtn = document.querySelector('[class*="o-player"] [class*="play" i]') ||
                                                              document.querySelector('.o-icon-play') ||
                                                              document.querySelector('[class*="oplayer"] [class*="btn" i]') ||
                                                              document.querySelector('.player-play-btn') ||
                                                              document.querySelector('[data-action="play"]') ||
                                                              document.querySelector('button[aria-label*="play" i]');
                                                if (playBtn) { playBtn.click(); }
                                                
                                                // Try standard player APIs
                                                if (window.player && typeof window.player.play === 'function') {
                                                    window.player.play();
                                                }
                                                
                                                // Try OPlayer global
                                                if (window.OPlayer && typeof window.OPlayer.play === 'function') {
                                                    window.OPlayer.play();
                                                }
                                                
                                                // Generic video element play
                                                var video = document.querySelector('video');
                                                if (video) { video.play(); }
                                                
                                                return 'play_attempted';
                                            } catch(e) {
                                                return 'error: ' + e.message;
                                            }
                                        })();
                                        """.trimIndent()
                                    ) { result ->
                                        // Log result for debugging (optional)
                                    }
                                }, 3500) // Increased to 3.5s for Fire TV compatibility
                            }

                            @Suppress("DEPRECATION")
                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): android.webkit.WebResourceResponse? {
                                val reqUrl = request?.url?.toString() ?: return null

                                // Capture HLS manifest URLs
                                if (!captured.get() && (
                                    reqUrl.endsWith(".m3u8", ignoreCase = true) ||
                                    reqUrl.endsWith(".ms3", ignoreCase = true) ||
                                    (reqUrl.contains("/secure/api/v1/") && reqUrl.contains("playlist"))
                                )) {
                                    if (captured.compareAndSet(false, true)) {
                                        cont.resume(reqUrl)
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
                                // Don't fail on sub-resource errors (common on Fire TV)
                            }
                        }
                    }

                    webView.loadUrl(url)

                    // Increased timeout to 30 seconds for Fire TV compatibility
                    // Fire TV hardware is slower and needs more time for Cloudflare + OPlayer
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (captured.compareAndSet(false, true)) {
                            cont.resume(null)
                            try { webView?.destroy() } catch (_: Exception) {}
                        }
                    }, 30000)

                } catch (e: Exception) {
                    if (captured.compareAndSet(false, true)) {
                        cont.resume(null)
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
     *
     * Streaming Optimization: Adds headers for better buffering and playback
     * on slower connections and devices like Fire TV.
     */
    private suspend fun processVideoUrl(videoUrl: String, callback: (ExtractorLink) -> Unit) {
        val qualityLabel = when {
            videoUrl.contains("720") -> "720p"
            videoUrl.contains("1080") -> "1080p"
            videoUrl.contains("480") -> "480p"
            videoUrl.contains("360") -> "360p"
            else -> name
        }

        // Enhanced headers for better streaming performance
        val streamingHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:141.0) Gecko/20100101 Firefox/141.0",
            "Accept" to "*/*",
            "Accept-Language" to "en-US,en;q=0.5",
            "Accept-Encoding" to "gzip, deflate, br",
            "Origin" to "https://cdnlivetv.tv",
            "Referer" to "https://cdnlivetv.tv/",
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
                this.referer = "$mainUrl/"
                this.headers = streamingHeaders
            }
        )
    }
}