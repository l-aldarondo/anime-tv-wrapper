package com.example.animetv.core.extractor

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.animetv.core.model.StreamResult
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object HeadlessStreamExtractor {

    private const val TIMEOUT_MS = 8000L

    /**
     * Resolves an obfuscated video page or embed iframe into a direct .m3u8 or .mp4 stream URL,
     * or a clean embed URL for the native player.
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun extractStreamUrl(context: Context, pageUrl: String, referer: String = ""): StreamResult? {
        return suspendCancellableCoroutine { continuation ->
            val mainHandler = Handler(Looper.getMainLooper())

            mainHandler.post {
                var webView: WebView? = null
                var hasResumed = false
                var candidateEmbedUrl: String? = null

                fun cleanup() {
                    mainHandler.removeCallbacksAndMessages(null)
                    try {
                        webView?.stopLoading()
                        webView?.destroy()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    webView = null
                }

                val timeoutRunnable = Runnable {
                    if (!hasResumed) {
                        hasResumed = true
                        val embed = candidateEmbedUrl
                        if (embed != null && continuation.isActive) {
                            cleanup()
                            continuation.resume(
                                StreamResult(
                                    videoUrl = embed,
                                    isHls = false,
                                    isEmbed = true,
                                    serverName = "Servidor Embebido",
                                    headers = mapOf("Referer" to pageUrl)
                                )
                            )
                        } else {
                            cleanup()
                            if (continuation.isActive) continuation.resume(null)
                        }
                    }
                }
                mainHandler.postDelayed(timeoutRunnable, TIMEOUT_MS)

                continuation.invokeOnCancellation {
                    mainHandler.post { cleanup() }
                }

                try {
                    webView = WebView(context.applicationContext).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

                        webChromeClient = object : WebChromeClient() {
                            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                                return true
                            }
                        }

                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                // Automated clicker: trigger server button and fake player overlays
                                val clickScript = """
                                    (function() {
                                        try {
                                            // 1. Click 'Servidor 1' (avoid PREMIUM VIP paywall)
                                            var btns = document.querySelectorAll('[data-server-btn]');
                                            var targetBtn = null;
                                            for (var i = 0; i < btns.length; i++) {
                                                var t = (btns[i].textContent || '').toUpperCase();
                                                if (t.includes('SERVIDOR 1') || (!t.includes('PREMIUM') && btns.length > 1)) {
                                                    targetBtn = btns[i];
                                                    break;
                                                }
                                            }
                                            if (!targetBtn && btns.length > 1) targetBtn = btns[1];
                                            if (targetBtn) targetBtn.click();

                                            // 2. Click fake player overlays or play buttons
                                            var playOverlay = document.querySelector('.play-button-overlay, #play-button, .fake-player-container, .play-button-circle');
                                            if (playOverlay) playOverlay.click();

                                            // 3. Inspect iframes
                                            var iframes = document.querySelectorAll('iframe');
                                            for (var i = 0; i < iframes.length; i++) {
                                                var src = iframes[i].src;
                                                if (src && !src.includes('turnstile') && !src.includes('google') && !src.includes('trailer')) {
                                                    console.log('PLAYER_IFRAME:' + src);
                                                }
                                            }
                                        } catch(e) {}
                                    })();
                                """.trimIndent()
                                view?.evaluateJavascript(clickScript, null)

                                // Check again after 1.2 seconds in case elements rendered dynamically
                                mainHandler.postDelayed({
                                    view?.evaluateJavascript(clickScript, null)
                                }, 1200)
                            }

                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): WebResourceResponse? {
                                val url = request?.url?.toString() ?: ""

                                // 1. Direct HLS or MP4 stream intercepted!
                                if (url.contains(".m3u8") || (url.contains(".mp4") && !url.contains("favicon")) || url.contains("/hls/") || url.contains("/manifest")) {
                                    if (!hasResumed) {
                                        hasResumed = true
                                        val isHls = !url.contains(".mp4")
                                        val reqHeaders = request?.requestHeaders ?: emptyMap()
                                        mainHandler.post {
                                            cleanup()
                                            if (continuation.isActive) {
                                                continuation.resume(
                                                    StreamResult(
                                                        videoUrl = url,
                                                        isHls = isHls,
                                                        isEmbed = false,
                                                        serverName = "Stream Directo",
                                                        headers = reqHeaders
                                                    )
                                                )
                                            }
                                        }
                                    }
                                }

                                // 2. Detect player embed URLs (rewrite pelisserieshoy to embed69)
                                if (url.contains("embed69") || url.contains("streamwish") ||
                                    url.contains("vidhide") || url.contains("filemoon") || url.contains("streamtape") ||
                                    url.contains("/player/") || url.contains("pelisserieshoy.com/f/")) {
                                    if (!url.contains(".js") && !url.contains(".css") && !url.contains(".png")) {
                                        val cleanUrl = if (url.contains("player.pelisserieshoy.com/f/")) {
                                            url.replace("player.pelisserieshoy.com", "embed69.org")
                                        } else {
                                            url
                                        }
                                        if (candidateEmbedUrl == null || candidateEmbedUrl?.contains("pelisserieshoy") == true || cleanUrl.contains("embed69")) {
                                            candidateEmbedUrl = cleanUrl
                                        }
                                    }
                                }

                                return super.shouldInterceptRequest(view, request)
                            }
                        }

                        val headers = if (referer.isNotEmpty()) mapOf("Referer" to referer) else emptyMap()
                        loadUrl(pageUrl, headers)
                    }
                } catch (e: Exception) {
                    if (!hasResumed) {
                        hasResumed = true
                        cleanup()
                        if (continuation.isActive) continuation.resume(null)
                    }
                }
            }
        }
    }
}
