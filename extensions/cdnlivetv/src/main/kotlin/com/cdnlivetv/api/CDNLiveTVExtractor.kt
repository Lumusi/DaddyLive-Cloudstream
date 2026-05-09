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
 * Extractor for CDNLiveTV (cdnlivetv.tv).
 * The player page uses OPlayer which loads HLS streams via obfuscated JS.
 * We use WebView to intercept the .m3u8 manifest URL during player initialization.
 */
open class CDNLiveTVExtractor(context: Context) : ExtractorApi() {
    override val name = "CDNLiveTV"
    override val mainUrl = "https://cdnlivetv.tv"
    override val requiresReferer = true
    private val appContext = context.applicationContext

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // Channel API URLs from api.cdnlivetv.ru return .ru domain,
            // but the player page is on cdnlivetv.tv
            val playerUrl = when {
                url.startsWith("http") && !url.contains("cdnlivetv.tv") -> {
                    // Convert API URL to player URL if needed
                    // Extract channel name from the original URL the extension provided
                    url
                }
                else -> url
            }

            // Normalize http to https
            val normalizedUrl = playerUrl.replaceFirst("http://", "https://")

            val videoUrl = withContext(Dispatchers.Main) {
                getVideoUrlWithWebView(appContext, normalizedUrl)
            }

            if (videoUrl != null) {
                processVideoUrl(videoUrl, callback)
            } else {
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
        // Handle direct m3u8 URLs from CDN edge or fallback sources
        val m3u8Url = when {
            url.endsWith(".m3u8", ignoreCase = true) -> url
            url.contains(".m3u8", ignoreCase = true) -> {
                // Extract the full m3u8 URL from query params or fragments
                url
            }
            else -> null
        }

        if (m3u8Url != null) {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "Direct HLS",
                    url = m3u8Url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.quality = Qualities.Unknown.value
                    this.referer = "$mainUrl/"
                    this.headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:141.0) Gecko/20100101 Firefox/141.0",
                        "Origin" to mainUrl,
                        "Referer" to "$mainUrl/"
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
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:141.0) Gecko/20100101 Firefox/141.0"
                        settings.setSupportZoom(false)
                        settings.loadWithOverviewMode = true
                        settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                // Wait for OPlayer to load and initialize
                                Handler(Looper.getMainLooper()).postDelayed({
                                    // Try to trigger playback
                                    view?.evaluateJavascript(
                                        """
                                        (function() {
                                            try {
                                                // OPlayer - find and click play button
                                                var playBtn = document.querySelector('.oplayer-btn-play, .oplayer-large-play, [class*="play"], [class*="Play"]');
                                                if (playBtn) { playBtn.click(); return 'clicked'; }
                                                // Try calling player API if available
                                                if (window.player && window.player.play) { window.player.play(); return 'player_api'; }
                                                if (window.oplayer && window.oplayer.toggle) { window.oplayer.toggle(); return 'oplayer_toggle'; }
                                                // Try common video element
                                                var video = document.querySelector('video');
                                                if (video && video.src) { return video.src; }
                                                return 'no_action';
                                            } catch(e) { return 'error: ' + e.message; }
                                        })();
                                        """.trimIndent()
                                    ) { _ -> }
                                }, 2000)
                            }

                            @Suppress("DEPRECATION")
                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): android.webkit.WebResourceResponse? {
                                val reqUrl = request?.url?.toString() ?: return null

                                if (!captured.get() && (reqUrl.endsWith(".m3u8") || reqUrl.endsWith(".mpd", ignoreCase = true))) {
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

                    // Timeout after 20 seconds
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (captured.compareAndSet(false, true)) {
                            cont.resume(null, onCancellation = null)
                            try { webView.destroy() } catch (_: Exception) {}
                        }
                    }, 20000)

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
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:141.0) Gecko/20100101 Firefox/141.0",
                    "Origin" to mainUrl,
                    "Connection" to "keep-alive",
                    "Referer" to "$mainUrl/"
                )
            }
        )
    }
}