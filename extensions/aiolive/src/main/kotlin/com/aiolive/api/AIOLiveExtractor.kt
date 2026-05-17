package com.aiolive.api

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

open class AIOLiveExtractor(context: Context) : ExtractorApi() {
    override val name = "AIOLive"
    override val mainUrl = "https://cdnlivetv.tv"
    override val requiresReferer = true
    private val appContext = context.applicationContext

    companion object {
        val refererHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:141.0) Gecko/20100101 Firefox/141.0",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.5",
            "Referer" to "https://cdnlivetv.tv/"
        )
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (url.endsWith(".m3u8", ignoreCase = true) || url.endsWith(".ms3", ignoreCase = true)) {
            tryExtractDirectUrl(url, callback)
            return
        }

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

        tryExtractDirectUrl(url, callback)
    }

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
                        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        settings.setGeolocationEnabled(false)

                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)

                                Handler(Looper.getMainLooper()).postDelayed({
                                    view?.evaluateJavascript(
                                        """
                                        (function() {
                                            try {
                                                var playBtn = document.querySelector('[class*="o-player"] [class*="play" i]') ||
                                                              document.querySelector('.o-icon-play') ||
                                                              document.querySelector('[class*="oplayer"] [class*="btn" i]') ||
                                                              document.querySelector('.player-play-btn') ||
                                                              document.querySelector('[data-action="play"]') ||
                                                              document.querySelector('button[aria-label*="play" i]');
                                                if (playBtn) { playBtn.click(); }
                                                if (typeof jwplayer === 'function') {
                                                    try { jwplayer().play(); } catch(e) {}
                                                }
                                                if (window.player && typeof window.player.play === 'function') {
                                                    window.player.play();
                                                }
                                                if (window.OPlayer && typeof window.OPlayer.play === 'function') {
                                                    window.OPlayer.play();
                                                }
                                                var video = document.querySelector('video');
                                                if (video) { video.play(); }
                                                return 'play_attempted';
                                            } catch(e) {
                                                return 'error: ' + e.message;
                                            }
                                        })();
                                        """.trimIndent()
                                    ) { _ -> }
                                }, 3500)
                            }

                            @Suppress("DEPRECATION")
                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): android.webkit.WebResourceResponse? {
                                val reqUrl = request?.url?.toString() ?: return null

                                if (!captured.get() && (
                                    reqUrl.endsWith(".m3u8", ignoreCase = true) ||
                                    reqUrl.endsWith(".ms3", ignoreCase = true) ||
                                    reqUrl.contains("/m3u8/", ignoreCase = true) ||
                                    (reqUrl.contains("m3u8", ignoreCase = true) && reqUrl.contains("http")) ||
                                    (reqUrl.contains("/secure/api/v1/") && reqUrl.contains("playlist"))
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
                            }
                        }
                    }

                    webView.loadUrl(url)

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

    private suspend fun processVideoUrl(videoUrl: String, callback: (ExtractorLink) -> Unit) {
        val qualityLabel = when {
            videoUrl.contains("1080") -> "1080p"
            videoUrl.contains("720") -> "720p"
            videoUrl.contains("480") -> "480p"
            videoUrl.contains("360") -> "360p"
            else -> name
        }

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
