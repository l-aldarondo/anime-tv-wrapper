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

        // Sanitize video embeds (1anime, rapid-cloud, embed69, xupalace, vidhide, streamwish) to strip in-frame popunders
        val isEmbedPlayer = (host.contains("1anime.site") || host.contains("rapid-cloud") || host.contains("embed69") || host.contains("xupalace") || host.contains("vidhide") || host.contains("streamwish")) &&
                (url.contains("/play/") || url.contains("/embed") || url.contains("/e-") || url.contains("/e/") || url.contains("/v/"))
        val isMediaFile = url.contains(".m3u8") || url.contains(".mp4") || url.contains(".ts") || url.contains(".m4s") || url.contains(".js") || url.contains(".css")
        if (isEmbedPlayer && !isMediaFile) {
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
            val referer = request?.requestHeaders?.get("Referer")
                ?: when {
                    url.contains("sololatino") || url.contains("embed69") || url.contains("xupalace") -> "https://sololatino.net/"
                    url.contains("animeflix") -> "https://animeflix.team/"
                    url.contains("animeyt") || url.contains("mytsumi") -> "https://animeyt.cc/"
                    url.contains("gogoanime") || url.contains("megaplay") -> "https://gogoanime.by/"
                    else -> "https://9anime.or.at/"
                }
            connection.setRequestProperty("Referer", referer)

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                return null
            }

            var html = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }

            // Strip ad networks (furudloof, belchlipin, monetize, adsterra, oxserver, etc.)
            html = html.replace(
                Regex(
                    "<script[^>]+src=[\"'][^\"']*(?:furudloof|belchlipin|adsterra|monetag|subduepaler|clickadu|popads|propeller|buildsstate|oxserver)[^\"']*[\"'][^>]*>\\s*</script>",
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

            // Auto-start videos on JWPlayer / Megaplay embeds
            html = html.replace("autostart: false", "autostart: true")
            html = html.replace("autoPlay: false", "autoPlay: true")
            html = html.replace("autoPlay: !1", "autoPlay: !0")

            // Inject anti-overlay style and bidirectional TV remote control bridge
            val injection = """
                <style>
                    iframe[src*="furudloof"], iframe[src*="ad"], iframe[src*="pop"], iframe[src*="doubleclick"], iframe[src*="oxserver"],
                    div[data-area], .wrapper[data-area], div[class*="popup"], div[class*="popunder"],
                    #modal.modal-vast, .modal-vast, .tutorial-overlay { display: none !important; }
                </style>
                <script>
                    (function() {
                        var lastToggleTime = 0;

                        function getMedia() {
                            return document.querySelector('video') || document.querySelector('audio');
                        }
                        function getPlayBtn() {
                            return document.getElementById('azakuPlayButton') ||
                                   document.querySelector('.azaku-player-button') ||
                                   document.querySelector('.play-button-overlay') ||
                                   document.querySelector('.plyr__control--overlaid') ||
                                   document.querySelector('button[data-plyr="play"]') ||
                                   document.querySelector('.play-btn') ||
                                   document.querySelector('.play-button') ||
                                   document.querySelector('.jw-display-icon-display') ||
                                   document.querySelector('.jw-icon-playback') ||
                                   document.querySelector('.art-state') ||
                                   document.querySelector('.art-control-playAndPause');
                        }
                        function getJw() {
                            try {
                                if (window.jwplayer && typeof window.jwplayer === 'function') {
                                    return window.jwplayer();
                                }
                            } catch(e) {}
                            return null;
                        }
                        function getArt() {
                            try {
                                if (window.art && typeof window.art.play === 'function') {
                                    return window.art;
                                }
                            } catch(e) {}
                            return null;
                        }

                        function doPlay() {
                            var jw = getJw();
                            if (jw && typeof jw.play === 'function') {
                                try { jw.play(); } catch(e) {}
                            }
                            var art = getArt();
                            if (art) {
                                try { art.play(); } catch(e) {}
                            }
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
                            var jw = getJw();
                            if (jw && typeof jw.pause === 'function') {
                                try { jw.pause(); } catch(e) {}
                            }
                            var art = getArt();
                            if (art) {
                                try { art.pause(); } catch(e) {}
                            }
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

                            var jw = getJw();
                            if (jw && typeof jw.getState === 'function') {
                                try {
                                    var state = jw.getState();
                                    if (state === 'playing') {
                                        doPause();
                                    } else {
                                        doPlay();
                                    }
                                    return;
                                } catch(e) {}
                            }

                            var art = getArt();
                            if (art) {
                                try {
                                    if (art.playing) {
                                        art.pause();
                                    } else {
                                        art.play();
                                    }
                                    return;
                                } catch(e) {}
                            }

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
                            var jw = getJw();
                            if (jw && typeof jw.getPosition === 'function' && typeof jw.seek === 'function') {
                                try {
                                    var pos = jw.getPosition();
                                    var dur = typeof jw.getDuration === 'function' ? jw.getDuration() : 999999;
                                    jw.seek(Math.max(0, Math.min(dur, pos + seconds)));
                                    return;
                                } catch(e) {}
                            }
                            var art = getArt();
                            if (art && (typeof art.seek === 'function' || typeof art.currentTime === 'number')) {
                                try {
                                    if (typeof art.seek === 'function') {
                                        art.seek(Math.max(0, (art.currentTime || 0) + seconds));
                                    } else {
                                        art.currentTime = Math.max(0, art.currentTime + seconds);
                                    }
                                    return;
                                } catch(e) {}
                            }
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

                        // Listen for remote control commands from MainActivity and forward to subframes
                        window.addEventListener('message', function(e) {
                            try {
                                var subframes = document.querySelectorAll('iframe');
                                for (var k = 0; k < subframes.length; k++) {
                                    try { subframes[k].contentWindow.postMessage(e.data, '*'); } catch(err) {}
                                }

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

                        function attemptAutoStart() {
                            // Auto click AnimeYT intermediate button
                            var azBtn = document.getElementById('azakuPlayButton') || document.querySelector('.azaku-player-button');
                            if (azBtn) {
                                try {
                                    console.log('Auto clicking AnimeYT azakuPlayButton');
                                    azBtn.click();
                                } catch(e) {}
                            }

                            // Auto-show player interface on embed69 / SoloLatino
                            if (typeof showPlayerInterface === 'function') {
                                try {
                                    var fakeP = document.getElementById('fakePlayer');
                                    if (fakeP && fakeP.style.display !== 'none') {
                                        showPlayerInterface();
                                    }
                                } catch(e) {}
                            }
                            var playOverlay = document.querySelector('.play-button-overlay');
                            if (playOverlay) {
                                try { playOverlay.click(); } catch(e) {}
                            }
                            var vastModal = document.getElementById('modal');
                            if (vastModal && (vastModal.classList.contains('modal-vast') || vastModal.className.indexOf('vast') !== -1)) {
                                try { vastModal.remove(); } catch(e) {}
                            }

                            var art = getArt();
                            if (art) {
                                try { art.play(); } catch(e) {}
                            }
                            var jw = getJw();
                            if (jw) {
                                try {
                                    if (typeof jw.on === 'function') {
                                        jw.on('ready', function() {
                                            try { jw.play(); } catch(e) {}
                                        });
                                        jw.on('play', notifyPlay);
                                    }
                                    if (typeof jw.play === 'function') {
                                        jw.play();
                                    }
                                } catch(e) {}
                            }
                            var v = getMedia();
                            if (v) {
                                try { v.play().catch(function(){}); } catch(e) {}
                            }
                            var playBtn = getPlayBtn();
                            if (playBtn && (!v || v.paused)) {
                                try { playBtn.click(); } catch(e) {}
                            }
                        }

                        window.addEventListener('load', function() {
                            var v = getMedia();
                            if (v) {
                                v.addEventListener('play', notifyPlay);
                                v.addEventListener('playing', notifyPlay);
                            }
                            attemptAutoStart();
                        });

                        setTimeout(attemptAutoStart, 600);
                        setTimeout(attemptAutoStart, 1500);
                        setTimeout(attemptAutoStart, 3000);
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
        // The main viewport MUST remain on recognized anime providers (9anime, gogoanime, sololatino, animeflix, animeyt, etc.).
        // Any main-frame navigation to unknown external domains is an ad redirect or popup and is blocked.
        val allowedMainHosts = setOf(
            "9anime.or.at",
            "gogoanime.by",
            "anitaku.to",
            "gogoanime3.co",
            "sololatino.net",
            "animeflix.team",
            "9animes.me.uk",
            "animeyt.cc",
            "jkanime.net"
        )
        val isAllowedMain = allowedMainHosts.any { host == it || host.endsWith(".$it") }

        if (request.isForMainFrame) {
            if (isAllowedMain) {
                return false // Allow internal navigation on valid sources
            }
            Log.w(TAG, "BLOCKED external redirect/popup in main frame: $url")
            return true // Drop and block the popup redirect
        }

        // For subframes/iframes: allow recognized anime video servers & trusted domains
        val isAllowedVideoHost = AdBlocker.ALLOWED_VIDEO_HOSTS.any { host.contains(it) }
        val isTrustedDomain = AdBlocker.TRUSTED_DOMAINS.any { host == it || host.endsWith(".$it") }
        if (isAllowedVideoHost || isTrustedDomain || isAllowedMain) {
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
        val allowedMainHosts = setOf(
            "9anime.or.at",
            "gogoanime.by",
            "anitaku.to",
            "gogoanime3.co",
            "sololatino.net",
            "animeflix.team",
            "9animes.me.uk",
            "animeyt.cc",
            "jkanime.net"
        )
        if (allowedMainHosts.any { host == it || host.endsWith(".$it") }) {
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
