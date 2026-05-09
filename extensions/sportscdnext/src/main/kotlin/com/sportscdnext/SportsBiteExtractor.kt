package com.sportscdnext

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
 * Extractor for SportsBite (livetv.moviebite.cc).
 * Handles iframe-based streams from wikisport.club / dlhd.link via WebView.
 * Direct proxy URLs (store.sportsbite.online) are handled in SportsBite.loadLinks() directly.
 */
open class SportsBiteExtractor(context: Context) : ExtractorApi() {
    override val name = "SportsBite"
    override val mainUrl = "https://livetv.moviebite.cc"
    override val requiresReferer = true
    private val appContext = context.applicationContext

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // Use WebView for iframe-based streams (wikisport.club, dlhd.link, etc.)
            val videoUrl = withContext(Dispatchers.Main) {
                getVideoUrlWithWebView(appContext, url)
            }
            if (videoUrl != null) {
                processVideoUrl(videoUrl, callback)
            } else {
                // Fallback: if URL itself is an m3u8, use it directly
                tryExtractDirectUrl(url, subtitleCallback, callback)
            }
        } catch (e: Exception) {
            tryExtractDirectUrl(url, subtitleCallback, callback)
        }
    }

    private suspend fun tryExtractDirectUrl(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (url.endsWith(".m3u8", ignoreCase = true)) {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "Direct HLS",
                    url = url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.quality = Qualities.Unknown.value
                    this.referer = "$mainUrl/"
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
            suspendCancellableCoroutine<String?> { cont ->
                val captured = AtomicBoolean(false)

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

                                // Auto-click play button after page loads
                                Handler(Looper.getMainLooper()).postDelayed({
                                    view?.evaluateJavascript(
                                        """
                                        (function() {
                                            try {
                                                var btn = document.querySelector('.jw-icon-display, .jw-display-play-container, .vjs-big-play-button, .fp-playButton, .player-btn, .play-button, [class*="play" i]');
                                                if (btn) { btn.click(); return 'clicked'; }
                                                if (typeof jwplayer !== 'undefined' && jwplayer().play) { jwplayer().play(); return 'jwplayer'; }
                                                if (typeof flowplayer !== 'undefined' && flowplayer().play) { flowplayer().play(); return 'flowplayer'; }
                                                if (typeof player !== 'undefined' && player.play) { player.play(); return 'player_api'; }
                                                var playerArea = document.querySelector('.jwplayer, .flowplayer, .video-js, #player, .player-container, iframe');
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

                                if (!captured.get() && (reqUrl.endsWith(".m3u8") || reqUrl.endsWith(".mp4"))) {
                                    if (captured.compareAndSet(false, true)) {
                                        cont.resume(reqUrl, onCancellation = null)
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
                            cont.resume(null, onCancellation = null)
                            try { webView.destroy() } catch (_: Exception) {}
                        }
                    }, 15000)

                } catch (e: Exception) {
                    if (captured.compareAndSet(false, true)) {
                        cont.resume(null, onCancellation = null)
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

    private suspend fun processVideoUrl(videoUrl: String, callback: (ExtractorLink) -> Unit) {
        val qualityLabel = when {
            videoUrl.contains("720") -> "720p"
            videoUrl.contains("1080") -> "1080p"
            videoUrl.contains("480") -> "480p"
            videoUrl.contains("360") -> "360p"
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
                this.referer = "$mainUrl/"
                this.headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                    "Origin" to mainUrl,
                    "Connection" to "keep-alive"
                )
            }
        )
    }
}