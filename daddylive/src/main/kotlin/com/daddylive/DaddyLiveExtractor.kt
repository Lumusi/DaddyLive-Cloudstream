package com.daddylive

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * WebView-based extractor for resolving HLS streams from daddylive.org embed pages.
 * The site loads streams via iframes from embedsports.top which serve .m3u8 HLS manifests.
 */
open class DaddyLiveExtractor(context: Context) : ExtractorApi() {
    override val name = "DaddyLive"
    override val mainUrl = "https://daddylive.org"
    override val requiresReferer = true
    private val appContext = context.applicationContext

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val videoUrl = withContext(Dispatchers.Main) {
                getVideoUrlWithWebView(appContext, url)
            }
            if (videoUrl != null) {
                processVideoUrl(videoUrl, callback)
            } else {
                // Fallback: if WebView didn't find m3u8, try the URL directly
                // The embed page iframe src itself may contain a direct stream URL
                tryExtractDirectUrl(url, subtitleCallback, callback)
            }
        } catch (e: Exception) {
            tryExtractDirectUrl(url, subtitleCallback, callback)
        }
    }

    private fun tryExtractDirectUrl(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // If the URL itself points to an m3u8 stream, use it directly
        if (url.endsWith(".m3u8", ignoreCase = true)) {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "Direct HLS",
                    url = url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.quality = Qualities.Unknown.value
                    this.referer = mainUrl + "/"
                    this.headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                        "Origin" to mainUrl
                    )
                }
            )
        }
    }

    private suspend fun getVideoUrlWithWebView(context: Context, url: String): String? {
        return withContext(Dispatchers.Main) {
            kotlin.coroutines.suspendCancellableCoroutine<String?> { cont ->
                val captured = java.util.concurrent.atomic.AtomicBoolean(false)

                try {
                    val webView = WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = true
                        settings.allowContentAccess = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.userAgentString =
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                // Auto-click play button if present (JWPlayer, Flowplayer, etc.)
                                Handler(Looper.getMainLooper()).postDelayed({
                                    view?.evaluateJavascript(
                                        """
                                        (function() {
                                            try {
                                                var btn = document.querySelector('.jw-icon-display, .jw-display-play-container, .vjs-big-play-button, .fp-playButton, .player-btn');
                                                if (btn) { btn.click(); return 'clicked'; }
                                                if (typeof jwplayer !== 'undefined' && jwplayer().play) { jwplayer().play(); return 'jwplayer'; }
                                                if (typeof flowplayer !== 'undefined' && flowplayer().play) { flowplayer().play(); return 'flowplayer'; }
                                                // Try clicking the first clickable element in the player area
                                                var playerArea = document.querySelector('.jwplayer, .flowplayer, .video-js, #player, .player-container');
                                                if (playerArea) {
                                                    var clickEvt = new MouseEvent('click', { bubbles: true });
                                                    playerArea.dispatchEvent(clickEvt);
                                                    return 'dispatched_click';
                                                }
                                                return 'no_player';
                                            } catch(e) { return 'error: ' + e.message; }
                                        })();
                                    """.trimIndent()
                                    ) { _ -> }
                                }, 1500)
                            }

                            @Suppress("DEPRECATION")
                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): android.webkit.WebResourceResponse? {
                                val reqUrl = request?.url?.toString() ?: return null

                                // Capture HLS manifest URLs
                                if (reqUrl.endsWith(".m3u8") && !captured.get()) {
                                    if (captured.compareAndSet(false, true)) {
                                        cont.resume(reqUrl)
                                        Handler(Looper.getMainLooper()).postDelayed({
                                            try { destroy() } catch (_: Exception) {}
                                        }, 500)
                                    }
                                }
                                return super.shouldInterceptRequest(view, request)
                            }
                        }
                    }

                    webView.loadUrl(url)

                    // Timeout after 15 seconds
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (captured.compareAndSet(false, true)) {
                            cont.resume(null)
                            try { webView.destroy() } catch (_: Exception) {}
                        }
                    }, 15000)

                } catch (e: Exception) {
                    if (captured.compareAndSet(false, true)) {
                        cont.resume(null)
                    }
                }

                cont.invokeOnCancellation {
                    if (captured.compareAndSet(false, true)) {
                        Handler(Looper.getMainLooper()).post {
                            try { /* cleanup handled */ } catch (_: Exception) {}
                        }
                    }
                }
            }
        }
    }

    private fun processVideoUrl(videoUrl: String, callback: (ExtractorLink) -> Unit) {
        val qualityLabel = when {
            videoUrl.contains("720") -> "720p"
            videoUrl.contains("1080") -> "1080p"
            videoUrl.contains("480") -> "480p"
            videoUrl.contains("360") -> "360p"
            videoUrl.contains("alpha") -> "Alpha-Trusted 720p 30fps"
            videoUrl.contains("bravo") -> "Bravo-High Fps Low Bitrate"
            videoUrl.contains("echo") -> "Echo-Good Quality"
            videoUrl.contains("golf") -> "Golf-High Quality Direct"
            videoUrl.contains("hotel") -> "Hotel-Very High Quality"
            videoUrl.contains("intel") -> "Intel-Wide Coverage"
            else -> name
        }

        callback.invoke(
            newExtractorLink(
                source = name,
                name = qualityLabel,
                url = videoUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.quality = Qualities.Unknown.value
                this.referer = mainUrl + "/"
                this.headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                    "Origin" to mainUrl,
                    "Connection" to "keep-alive"
                )
            }
        )
    }
}