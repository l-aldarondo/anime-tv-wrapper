package com.example.animetv.webview

import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.animetv.adblock.AdBlocker
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL

class AnimeWebViewClient : WebViewClient() {

    companion object {
        private const val TAG = "AnimeWebViewClient"
    }

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?
    ): WebResourceResponse? {
        val url = request?.url?.toString() ?: return super.shouldInterceptRequest(view, request)
        val host = request.url.host?.lowercase() ?: ""

        if (AdBlocker.isAd(url)) {
            // Return an empty resource response to cancel loading the ad/tracker/overlay
            return WebResourceResponse(
                "text/plain",
                "UTF-8",
                ByteArrayInputStream(ByteArray(0))
            )
        }

        // Sanitize player iframe embeds to purge in-frame ad scripts and inject remote playback controls
        val isPlayerUrl = url.contains("/play/") || url.contains("/embed") || url.contains("/e-") || url.contains("/v/") || url.contains("stream")
        val isPlayerHost = AdBlocker.ALLOWED_VIDEO_HOSTS.any { host.contains(it) }
        val isMediaFile = url.contains(".m3u8") || url.contains(".mp4") || url.contains(".ts") || url.contains(".m4s") || url.contains(".js") || url.contains(".css")
        if (isPlayerUrl && isPlayerHost && !isMediaFile) {
            val sanitized = sanitizePlayerResponse(url, request)
            if (sanitized != null) return sanitized
        }

        return super.shouldInterceptRequest(view, request)
    }

    private fun sanitizePlayerResponse(url: String, request: WebResourceRequest?): WebResourceResponse? {
        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 6000
            connection.readTimeout = 6000
            connection.setRequestProperty(
                "User-Agent",
                request?.requestHeaders?.get("User-Agent")
                    ?: "Mozilla/5.0 (Linux; Android 17; Pixel 10 Pro XL) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
            )
            connection.setRequestProperty("Referer", "https://9anime.or.at/")

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                return null
            }

            var html = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }

            // Strip ad networks (furudloof, belchlipin, monetize, adsterra, etc.)
            html = html.replace(
                Regex(
                    "<script[^>]+src=[\"'][^\"']*(?:furudloof|belchlipin|adsterra|monetag|subduepaler|clickadu|popads|propeller)[^\"']*[\"'][^>]*>\\s*</script>",
                    RegexOption.IGNORE_CASE
                ),
                ""
            )
            html = html.replace(
                Regex(
                    "<script[^>]+src=[\"'](?:https?:)?//sd\\.[^\"']+[\"'][^>]*>\\s*</script>",
                    RegexOption.IGNORE_CASE
                ),
                ""
            )

            // Inject anti-overlay style and bidirectional TV remote control bridge
            val injection = """
                <style>
                    iframe[src*="furudloof"], iframe[src*="ad"], iframe[src*="pop"], iframe[src*="doubleclick"],
                    div[data-area], .wrapper[data-area], div[class*="popup"], div[class*="popunder"] { display: none !important; }
                </style>
                <script>
                    (function() {
                        var lastToggleTime = 0;

                        function getMedia() {
                            return document.querySelector('video') || document.querySelector('audio');
                        }
                        function getPlayBtn() {
                            return document.querySelector('.plyr__control--overlaid') ||
                                   document.querySelector('button[data-plyr="play"]') ||
                                   document.querySelector('.play-btn') ||
                                   document.querySelector('.play-button');
                        }

                        function doPlay() {
                            var v = getMedia();
                            var btn = getPlayBtn();
                            if (v) {
                                try {
                                    if (v.plyr && typeof v.plyr.play === 'function') v.plyr.play();
                                    else v.play().catch(function(){});
                                } catch(e) {
                                    try { v.play(); } catch(e2) {}
                                }
                            }
                            if (btn && (!v || v.paused)) {
                                try { btn.click(); } catch(e) {}
                            }
                        }

                        function doPause() {
                            var v = getMedia();
                            if (v) {
                                try {
                                    if (v.plyr && typeof v.plyr.pause === 'function') v.plyr.pause();
                                } catch(e) {}
                                try {
                                    v.pause();
                                } catch(e) {}
                                if (!v.paused) {
                                    var btn = document.querySelector('button[data-plyr="play"]');
                                    if (btn) try { btn.click(); } catch(e) {}
                                }
                            }
                        }

                        function doToggle() {
                            var now = Date.now();
                            if (now - lastToggleTime < 300) return;
                            lastToggleTime = now;

                            var v = getMedia();
                            if (v) {
                                if (v.paused) {
                                    doPlay();
                                } else {
                                    doPause();
                                }
                            } else {
                                var btn = getPlayBtn();
                                if (btn) btn.click();
                            }
                        }

                        function doSeek(seconds) {
                            var v = getMedia();
                            if (!v) return;
                            try {
                                if (v.plyr && typeof v.plyr.currentTime !== 'undefined') {
                                    v.plyr.currentTime = Math.max(0, Math.min(v.plyr.duration || 999999, v.plyr.currentTime + seconds));
                                } else {
                                    v.currentTime = Math.max(0, Math.min(v.duration || 999999, v.currentTime + seconds));
                                }
                            } catch(e) {
                                try { v.currentTime += seconds; } catch(err) {}
                            }
                        }

                        // Listen for remote control commands from MainActivity
                        window.addEventListener('message', function(e) {
                            try {
                                var data = typeof e.data === 'string' ? JSON.parse(e.data) : e.data;
                                if (!data) return;
                                var action = data.action || data.type || data.command;
                                if (action === 'toggle' || action === 'play-pause') {
                                    doToggle();
                                } else if (action === 'play') {
                                    doPlay();
                                } else if (action === 'pause') {
                                    doPause();
                                } else if (action === 'seek') {
                                    var secs = Number(data.seconds != null ? data.seconds : data.value);
                                    if (!isNaN(secs)) {
                                        doSeek(secs);
                                    }
                                }
                            } catch(err) {}
                        }, false);

                        function notifyPlay() {
                            try { window.top.postMessage({event: 'play'}, '*'); } catch(e) {}
                        }
                        document.addEventListener('click', notifyPlay, true);
                        document.addEventListener('touchstart', notifyPlay, true);
                        window.addEventListener('load', function() {
                            var v = getMedia();
                            if (v) {
                                v.addEventListener('play', notifyPlay);
                                v.addEventListener('playing', notifyPlay);
                            }
                        });
                    })();
                </script>
            """.trimIndent()

            html = if (html.contains("</head>", ignoreCase = true)) {
                html.replace("</head>", "$injection</head>")
            } else {
                injection + html
            }

            WebResourceResponse(
                "text/html",
                "UTF-8",
                ByteArrayInputStream(html.toByteArray(Charsets.UTF_8))
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error sanitizing player HTML: $url", e)
            null
        }
    }

    override fun shouldOverrideUrlLoading(
        view: WebView?,
        request: WebResourceRequest?
    ): Boolean {
        val url = request?.url?.toString() ?: return false
        val uri = request.url

        val scheme = uri.scheme?.lowercase() ?: ""
        if (scheme !in listOf("http", "https")) {
            // Reject non-web schemes (intent:, market:, etc.)
            Log.w(TAG, "Blocked non-web scheme: $url")
            return true
        }

        val host = uri.host?.lowercase() ?: ""

        // Check if URL is an ad
        if (AdBlocker.isAd(url)) {
            Log.w(TAG, "Blocked ad navigation to: $url")
            return true
        }

        // STRICT MAIN-FRAME LOCK:
        // The main viewport MUST remain on 9anime.or.at.
        // Any main-frame navigation to any external domain is an ad redirect or popup and is blocked.
        if (request.isForMainFrame) {
            if (host == "9anime.or.at" || host.endsWith(".9anime.or.at")) {
                return false // Allow internal navigation on 9anime
            }
            Log.w(TAG, "BLOCKED external redirect/popup in main frame: $url")
            return true // Drop and block the popup redirect
        }

        // For subframes/iframes: allow recognized anime video servers & trusted domains
        val isAllowedVideoHost = AdBlocker.ALLOWED_VIDEO_HOSTS.any { host.contains(it) }
        val isTrustedDomain = AdBlocker.TRUSTED_DOMAINS.any { host == it || host.endsWith(".$it") }
        if (isAllowedVideoHost || isTrustedDomain) {
            return false
        }

        // Block unexpected external tab hops / redirects
        Log.w(TAG, "Blocked third party navigation hop: $url")
        return true
    }

    @Deprecated("Deprecated in Java")
    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
        if (url == null) return false
        val uri = try { Uri.parse(url) } catch (e: Exception) { return false }
        val host = uri.host?.lowercase() ?: ""

        if (AdBlocker.isAd(url)) return true
        if (host == "9anime.or.at" || host.endsWith(".9anime.or.at")) {
            return false
        }

        Log.w(TAG, "BLOCKED deprecated shouldOverrideUrlLoading: $url")
        return true
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        // Inject popup neutralizer immediately
        view?.evaluateJavascript(AdBlocker.getAntiAdJs(), null)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)

        // Inject cosmetic CSS filter
        injectCss(view, AdBlocker.getAntiAdCss())

        // Inject dynamic popup and overlay cleaner script
        view?.evaluateJavascript(AdBlocker.getAntiAdJs(), null)
    }

    private fun injectCss(webView: WebView?, css: String) {
        try {
            val encodedCss = Base64.encodeToString(css.toByteArray(), Base64.NO_WRAP)
            val js = """
                (function() {
                    var styleId = 'animetv_adblock_css';
                    var existing = document.getElementById(styleId);
                    if (!existing) {
                        var style = document.createElement('style');
                        style.id = styleId;
                        style.type = 'text/css';
                        style.innerHTML = window.atob('$encodedCss');
                        document.head.appendChild(style);
                    }
                })();
            """.trimIndent()
            webView?.evaluateJavascript(js, null)
        } catch (e: Exception) {
            Log.e(TAG, "Error injecting anti-ad CSS", e)
        }
    }
}
