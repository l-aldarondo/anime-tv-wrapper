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

        if (AdBlocker.isAd(request)) {
            // Return an empty resource response to cancel loading the ad/tracker/overlay
            return WebResourceResponse(
                "text/plain",
                "UTF-8",
                ByteArrayInputStream(ByteArray(0))
            )
        }

        // Sanitize video embed DOCUMENTS (not every allowed video host) so we can inject the
        // ad-wall/overlay purge script into their own origin (JS injected from the top frame can
        // never reach into a cross-origin iframe).
        //
        // IMPORTANT: this list must only contain hosts CONFIRMED to render correctly when re-fetched
        // out-of-band via our own HttpURLConnection. embed69/SoloLatino proxy their actual player
        // through a rotating set of throwaway CDN domains (morencius.com, player.pelisserieshoy.com,
        // ...). Adding morencius.com here to fix its ad-overlay/"5 taps" annoyance broke it
        // completely instead (window.jwplayer never got defined, reproduced on multiple different,
        // never-before-loaded titles - not a fluke or a rate-limit on one URL): something about that
        // provider's own anti-bot/anti-proxy detection distinguishes our synthetic re-fetch from the
        // real WebView navigation regardless of headers/cookies forwarded, and serves a broken page
        // instead. A working player with an annoying overlay beats a broken one, so leave newly
        // rotated CDN names OUT of this list unless a fix here is actually verified to still play the
        // video afterward - don't just add every new provider name that shows up.
        val knownEmbedProvider = host.contains("1anime.site") || host.contains("rapid-cloud") ||
            host.contains("embed69") || host.contains("xupalace") || host.contains("vidhide") ||
            host.contains("streamwish")

        // Match against the URL PATH only (not the full URL string) and require the marker to be
        // the leading path segment - a naive "url.contains(\"/v/\")" also matches things like
        // ".../cf-fonts/v/outfit/...woff2" buried deep in an unrelated static asset URL, which would
        // route binary fonts/icons through the text-based HTML rewrite below and corrupt them.
        val path = request.url.path?.lowercase() ?: ""
        val looksLikeEmbedPath = path.startsWith("/play/") || path.startsWith("/embed/") || path.startsWith("/e-") || path.startsWith("/e/") || path.startsWith("/v/") || path.startsWith("/f/")
        val isEmbedPlayer = looksLikeEmbedPath && knownEmbedProvider
        val isStaticAsset = Regex("\\.(m3u8|mp4|ts|m4s|js|css|woff2?|ttf|otf|ico|png|jpe?g|gif|svg|webp)(\\?|$)").containsMatchIn(url.lowercase())
        if (isEmbedPlayer && !isStaticAsset) {
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

            // Forward every header the real WebView request carried (Accept, Accept-Language,
            // Sec-Fetch-*, etc.), not just User-Agent/Referer - some embed CDNs render a broken or
            // incomplete page (e.g. never define window.jwplayer) when a request looks synthetic
            // compared to what a real browser navigation sends.
            request?.requestHeaders?.forEach { (name, value) ->
                try { connection.setRequestProperty(name, value) } catch (e: Exception) { /* ignore malformed header */ }
            }
            if (connection.getRequestProperty("User-Agent") == null) {
                connection.setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 17; Pixel 10 Pro XL) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
                )
            }
            if (connection.getRequestProperty("Referer") == null) {
                val referer = when {
                    url.contains("sololatino") || url.contains("embed69") || url.contains("xupalace") -> "https://sololatino.net/"
                    url.contains("animeflix") -> "https://animeflix.team/"
                    url.contains("animeyt") || url.contains("mytsumi") -> "https://animeyt.cc/"
                    url.contains("gogoanime") || url.contains("megaplay") -> "https://gogoanime.by/"
                    else -> "https://9anime.or.at/"
                }
                connection.setRequestProperty("Referer", referer)
            }

            // This fetch is otherwise a completely separate, cookie-less request from the real
            // WebView navigation. Many of these rotating embed CDNs gate their actual player/stream
            // behind a session cookie or token set earlier on the same host - without it the server
            // can serve a degraded response (e.g. the player library script never actually
            // initializes) instead of what a real browser session would get.
            val cookie = android.webkit.CookieManager.getInstance().getCookie(url)
            if (!cookie.isNullOrBlank()) {
                connection.setRequestProperty("Cookie", cookie)
            }

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                return null
            }

            var html = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }

            // Strip <script> tags that load known ad networks (furudloof, belchlipin, adsterra, etc.)
            // or that implement adblock-detection walls (FuckAdBlock/BlockAdBlock-style libraries and
            // similar custom "disable your adblocker" checks are almost always inline <script> blocks
            // that mention "adblock" somewhere in their source).
            val adSrcPattern = Regex(
                "src=[\"'][^\"']*(?:furudloof|belchlipin|adsterra|monetag|subduepaler|clickadu|popads|propeller|buildsstate|oxserver|fuckadblock|blockadblock|adblock)[^\"']*[\"']",
                RegexOption.IGNORE_CASE
            )
            val sdSrcPattern = Regex("src=[\"'](?:https?:)?//sd\\.[^\"']+[\"']", RegexOption.IGNORE_CASE)
            val inlineAdblockPattern = Regex("adblock|fuckadblock|blockadblock", RegexOption.IGNORE_CASE)
            html = Regex("<script\\b[^>]*>[\\s\\S]*?</script>", RegexOption.IGNORE_CASE).replace(html) { match ->
                val tag = match.value
                val hasSrc = tag.contains("src=", ignoreCase = true)
                val isKnownAdOrDetectorSrc = hasSrc && (adSrcPattern.containsMatchIn(tag) || sdSrcPattern.containsMatchIn(tag))
                val isInlineAdblockDetector = !hasSrc && inlineAdblockPattern.containsMatchIn(tag)
                if (isKnownAdOrDetectorSrc || isInlineAdblockDetector) "" else tag
            }

            // Auto-start videos on JWPlayer / Megaplay embeds
            html = html.replace("autostart: false", "autostart: true")
            html = html.replace("autoPlay: false", "autoPlay: true")
            html = html.replace("autoPlay: !1", "autoPlay: !0")

            // Inject anti-overlay style and bidirectional TV remote control bridge
            val injection = """
                <style>
                    iframe[src*="furudloof"], iframe[src*="ad"], iframe[src*="pop"], iframe[src*="doubleclick"], iframe[src*="oxserver"],
                    div[data-area], .wrapper[data-area], div[class*="popup"], div[class*="popunder"],
                    #modal.modal-vast, .modal-vast, .tutorial-overlay,
                    [class*="adblock" i], [id*="adblock" i], [class*="ad-block" i], [class*="antiadblock" i],
                    [class*="disable-ad" i], [class*="social-bar" i], [class*="sociallocker" i],
                    [class*="ads-modal" i], [class*="ad-modal" i], [class*="ad-warning" i] { display: none !important; }
                </style>
                <script>
                    (function() {
                        var lastToggleTime = 0;

                        // This iframe is a separate origin/JS realm from the top document, so
                        // AdBlocker's window.open neutralization and overlay purging never reach in
                        // here on their own - it needs its own copy.
                        try {
                            Object.defineProperty(window, 'open', {
                                value: function(u) {
                                    console.log('AdBlocker(embed): Suppressed window.open -> ' + u);
                                    return null;
                                },
                                writable: false,
                                configurable: false
                            });
                        } catch(e) {
                            window.open = function() { return null; };
                        }

                        // A near-fullscreen/full-player-sized element with no distinguishing content is
                        // ambiguous - some players legitimately use a transparent full-cover div as
                        // their own click-to-play/gesture-unlock layer, and deleting it outright has
                        // been observed to leave the player with no way to start playback at all (the
                        // button never comes back). Only DELETE small, clearly-decorative bait cards;
                        // for anything close to full player size, just strip pointer-events so a real
                        // tap passes through to whatever real control sits beneath it, without
                        // destroying a node the player might depend on.
                        function neutralizeOverlay(el) {
                            try {
                                var rect = el.getBoundingClientRect();
                                var coversMost = rect.width >= window.innerWidth * 0.75 && rect.height >= window.innerHeight * 0.75;
                                if (coversMost) {
                                    el.style.setProperty('pointer-events', 'none', 'important');
                                } else {
                                    el.remove();
                                }
                            } catch(e) {}
                        }

                        // Remove adblock-detection warning walls ("this video has popups/ads, disable
                        // your adblocker") and generic click-catcher overlays that stack on top of the
                        // real play button, forcing several wasted taps.
                        function purgeAdWalls() {
                            try {
                                var textRe = /anuncio|publicidad|adblock|ad.?block|ventana.?emergente|ventanas.?emergentes|pop.?up|bloqueador|desactiva.*(ad|anuncio)|click this button|click here to continue|you.?ve won|you have won|claim your (prize|reward)|verify you.?re human/i;
                                var candidates = document.querySelectorAll('div, section, aside, p, a, button');
                                for (var i = 0; i < candidates.length; i++) {
                                    var el = candidates[i];
                                    if (!el || el.tagName === 'VIDEO' || (el.querySelector && el.querySelector('video'))) continue;
                                    var txt = (el.innerText || el.textContent || '').trim();
                                    if (!txt || txt.length > 400 || !textRe.test(txt)) continue;
                                    var cs = window.getComputedStyle(el);
                                    var z = parseInt(cs.zIndex || '0', 10) || 0;
                                    var looksLikeOverlay = cs.position === 'fixed' || cs.position === 'absolute' || z > 100;
                                    if (looksLikeOverlay || el.offsetWidth > window.innerWidth * 0.5) {
                                        // This one carries explicit ad/adblock-warning wording, so it's a
                                        // high-confidence match - safe to remove outright regardless of size.
                                        try { el.remove(); } catch(e) {}
                                    }
                                }

                                // Generic ad interstitial/modal detector: rather than chase every new
                                // ad creative's exact wording, catch the STRUCTURE most of them share -
                                // a large fixed/absolute overlay darkening most of the screen with a
                                // prominent generic call-to-action button.
                                var adCtaRe = /^(continue|continuar|discover|explore|claim|unlock|get started|watch now|start now|download now|install now|join now|sign up|allow|next)$/i;
                                var modalCandidates = document.querySelectorAll('div');
                                for (var am = 0; am < modalCandidates.length; am++) {
                                    var mEl = modalCandidates[am];
                                    if (!mEl || (mEl.querySelector && mEl.querySelector('video'))) continue;
                                    var mCs = window.getComputedStyle(mEl);
                                    if (mCs.position !== 'fixed' && mCs.position !== 'absolute') continue;
                                    var mRect = mEl.getBoundingClientRect();
                                    if (mRect.width < window.innerWidth * 0.7 || mRect.height < window.innerHeight * 0.5) continue;
                                    var ctaEls = mEl.querySelectorAll('button, a, [role="button"]');
                                    var hasAdCta = false;
                                    for (var ci = 0; ci < ctaEls.length; ci++) {
                                        var ctaTxt = (ctaEls[ci].innerText || '').trim();
                                        if (adCtaRe.test(ctaTxt)) { hasAdCta = true; break; }
                                    }
                                    if (hasAdCta) {
                                        try { mEl.remove(); } catch(e) {}
                                    }
                                }

                                // Smaller ad cards: some rotating ad creatives aren't full-screen
                                // modals but a card roughly player-sized with generic button labels
                                // too common to safely text-match ("More", "Close"). Reliable signal
                                // regardless of wording: a positioned card with 2+ short generic
                                // buttons/links plus a short marketing-style blurb, not wrapping the
                                // actual player.
                                var cardCandidates = document.querySelectorAll('div');
                                for (var ac = 0; ac < cardCandidates.length; ac++) {
                                    var cEl = cardCandidates[ac];
                                    if (!cEl || (cEl.querySelector && (cEl.querySelector('video') || cEl.querySelector('iframe')))) continue;
                                    var cCs = window.getComputedStyle(cEl);
                                    if (cCs.position !== 'fixed' && cCs.position !== 'absolute') continue;
                                    var cRect = cEl.getBoundingClientRect();
                                    if (cRect.width < 150 || cRect.height < 80) continue;
                                    var cActions = cEl.querySelectorAll(':scope > button, :scope > a, :scope > div > button, :scope > div > a');
                                    if (cActions.length < 2) continue;
                                    var cTxt = (cEl.innerText || '').trim();
                                    if (cTxt.length < 15 || cTxt.length > 250) continue;
                                    try { cEl.remove(); } catch(e) {}
                                }

                                // "Nuked z-index" bait overlays: some embed CDNs float a position:fixed
                                // element (an iframe with no real src, or a plain empty div - both
                                // patterns have been observed across different rotating providers for the
                                // same episode) with a z-index right at the 32-bit signed int max directly
                                // on top of the real player. No legitimate site UI needs a z-index
                                // anywhere near 2^31-1, but size still decides delete vs. neutralize (see
                                // neutralizeOverlay) since one observed instance turned out to be the
                                // player's own full-cover click layer, not an ad.
                                var extremeZEls = document.querySelectorAll('body *');
                                for (var ez = 0; ez < extremeZEls.length; ez++) {
                                    var eEl = extremeZEls[ez];
                                    if (!eEl || eEl.tagName === 'VIDEO' || (eEl.querySelector && eEl.querySelector('video'))) continue;
                                    var eCs = window.getComputedStyle(eEl);
                                    var eZ = parseInt(eCs.zIndex || '0', 10) || 0;
                                    if ((eCs.position === 'fixed' || eCs.position === 'absolute') && eZ > 2000000000) {
                                        neutralizeOverlay(eEl);
                                    }
                                }
                            } catch(e) {}
                        }

                        try {
                            if (window.MutationObserver) {
                                var adWallObserver = new MutationObserver(function() { purgeAdWalls(); });
                                adWallObserver.observe(document.documentElement || document.body, { childList: true, subtree: true });
                            }
                        } catch(e) {}

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
                            purgeAdWalls();

                            // Some play buttons/overlays act as a play/pause TOGGLE rather than
                            // "play only". Clicking them unconditionally on every retry (this used to
                            // run up to 12 times) could play -> pause -> play -> pause a video that had
                            // already started, leaving it paused with its button already hidden by the
                            // site's own optimistic UI and nothing left to bring it back. Only ever
                            // attempt to start playback while media both exists and is still paused.
                            var v = getMedia();
                            var alreadyPlaying = v && !v.paused && !v.ended;
                            if (alreadyPlaying) return;

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

                        purgeAdWalls();

                        // Retry loop: ad-wall overlays and click-catcher layers are often re-inserted
                        // dynamically, which used to make the user tap the visible play button several
                        // times (each tap only cleared one stacked layer). Keep purging and retrying
                        // playback automatically instead, stopping as soon as media is actually playing.
                        var autoStartAttempts = 0;
                        function retryAutoStart() {
                            autoStartAttempts++;
                            attemptAutoStart();
                            var v = getMedia();
                            if (v && !v.paused && !v.ended) return;
                            if (autoStartAttempts < 12) {
                                setTimeout(retryAutoStart, 500);
                            }
                        }
                        setTimeout(retryAutoStart, 300);
                    })();
                </script>
            """.trimIndent()

            // Some providers (morencius.com) serve an upper-case "</HEAD>" - matching case-
            // insensitively but then replacing case-sensitively silently found nothing and left the
            // page completely un-sanitized, which is why the ad-wall overlay purge never actually
            // ran on those pages.
            val headCloseRegex = Regex("</head>", RegexOption.IGNORE_CASE)
            html = if (headCloseRegex.containsMatchIn(html)) {
                headCloseRegex.replace(html, Regex.escapeReplacement(injection) + "</head>")
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
        val isAllowedVideoHost = AdBlocker.isAllowedVideoHost(host)
        val isTrustedDomain = AdBlocker.isTrustedDomain(host)
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
        // Inject cosmetic CSS and the popup neutralizer as early as possible, before the page
        // finishes rendering, to shrink the window where an ad can flash on screen.
        injectCss(view, AdBlocker.getAntiAdCss())
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
