package com.example.animetv.adblock

import android.net.Uri
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger

object AdBlocker {
    private const val TAG = "AdBlocker"

    // Atomic counter for blocked requests to display in TV HUD
    val blockedCount = AtomicInteger(0)

    // Listener for UI updates
    var onBlockListener: ((Int) -> Unit)? = null

    // Recognized base domains for 9anime, GogoAnime, SoloLatino, AnimeFlix, AnimeYT, JKAnime, and trusted streaming / resource CDNs
    val TRUSTED_DOMAINS = setOf(
        "9anime.or.at",
        "1anime.site",
        "my.1anime.site",
        "gogoanime.by",
        "anitaku.to",
        "gogoanime3.co",
        "anihdplay.com",
        "embtaku.pro",
        "megaplay.su",
        "jwplayer.com",
        "cdn.jwplayer.com",
        "jwpcdn.com",
        "ssl.p.jwpcdn.com",
        "googlevideo.com",
        "wp.com",
        "i0.wp.com",
        "i1.wp.com",
        "i2.wp.com",
        "sololatino.net",
        "embed69.org",
        "xupalace.org",
        "image.tmdb.org",
        "tmdb.org",
        "animeflix.team",
        "9animes.me.uk",
        "megaplay.buzz",
        "megaplay-1.buzz",
        "animeyt.cc",
        "mytsumi.com",
        "jkanime.net",
        "jkdesa.com",
        "cdn.jkdesa.com",
        "jkplayers.com",
        "challenges.cloudflare.com",
        "turnstile.cloudflare.com",
        "statlytic.net",
        "streamwish.to",
        "streamwish.com",
        "filemoon.sx",
        "filemoon.to",
        "voe.sx",
        "uqload.io",
        "uqload.com",
        "vidmoly.me",
        "vidmoly.to",
        "cdnjs.cloudflare.com",
        "fonts.googleapis.com",
        "fonts.gstatic.com",
        "stackpath.bootstrapcdn.com",
        "maxcdn.bootstrapcdn.com",
        "ajax.googleapis.com",
        "unpkg.com",
        "cdn.jsdelivr.net",
        "disqus.com",
        "disquscdn.com",
        "code.jquery.com",
        "plyr.io",
        "cdn.plyr.io",
        "cloudflareinsights.com",
        "static.cloudflareinsights.com",
        "googletagmanager.com",
        "www.googletagmanager.com"
    )

    // Allowed video servers and embed streaming domains
    val ALLOWED_VIDEO_HOSTS = listOf(
        "1anime.site",
        "my.1anime.site",
        "megacloud.tv",
        "megacloud.club",
        "rapid-cloud.co",
        "rapid-cloud.ru",
        "rabbitstream.net",
        "streamtape.com",
        "streamtape.net",
        "mp4upload.com",
        "filemoon.sx",
        "filemoon.to",
        "filemoon.in",
        "doodstream.com",
        "dood.to",
        "dood.so",
        "vidstream.pro",
        "vidstream.to",
        "vizcloud.online",
        "vizcloud.co",
        "vidsrc.me",
        "vidsrc.to",
        "megaplay.su",
        "megaplay.buzz",
        "megaplay-1.buzz",
        "megaplay",
        "googlevideo.com",
        "gogoanime.by",
        "jwplayer.com",
        "jwpcdn.com",
        "sololatino.net",
        "embed69.org",
        "xupalace.org",
        "animeflix.team",
        "9animes.me.uk",
        "animeyt.cc",
        "mytsumi.com",
        "jkanime.net",
        "jkdesa.com",
        "jkplayer",
        "jkplayers",
        "statlytic",
        "streamwish",
        "wishembed",
        "luluvdo",
        "ok.ru",
        "cdn-vk.ru",
        "vk.com",
        "vk.me",
        "mega.nz",
        "mega.co.nz",
        "mega.io",
        "mega",
        "uqload",
        "vidmoly",
        "voe.sx",
        "snapcdn.top",
        "snapcdn",
        "mixdrop",
        "yourupload",
        "bysesukior.com",
        "sesukior",
        "byse",
        "pelisserieshoy.com",
        "pelisserieshoy",
        "vidhide",
        "vidhidepre",
        "vidhidevip",
        "filelions",
        "streamvid",
        "desu.sh",
        "desu",
        "dplayer",
        "f7hyg4q.org",
        "f7hyg4q"
    )

    // Known ad networks, pop-up/pop-under services, and tracking domains
    private val BLOCKED_DOMAINS = setOf(
        "excavatenearbywand.com",
        "belchlipin.com",
        "furudloof.com",
        "subduepaler.cyou",
        "adsterra.com",
        "propellerads.com",
        "popads.net",
        "popcash.net",
        "exoclick.com",
        "mgid.com",
        "taboola.com",
        "outbrain.com",
        "adnxs.com",
        "doubleclick.net",
        "googlesyndication.com",
        "google-analytics.com",
        "adservice.google.com",
        "clarium.io",
        "coinhive.com",
        "trafficfactory.biz",
        "juicyads.com",
        "onclickalgo.com",
        "adkeeper.co.uk",
        "adtrue.com",
        "alwingulla.com",
        "highperformancecpmgate.com",
        "deloton.com",
        "a-ads.com",
        "monetag.com",
        "yandex.ru",
        "aniview.com",
        "serving-sys.com",
        "rubiconproject.com",
        "criteo.com",
        "pubmatic.com",
        "openx.net",
        "casalemedia.com",
        "exponential.com",
        "spotxchange.com",
        "yieldmo.com",
        "smartadserver.com",
        "sovrn.com",
        "revcontent.com",
        "zergnet.com",
        "clickadu.com",
        "hilltopads.com",
        "admaven.com",
        "evadav.com",
        "richpush.co",
        "rollerads.com",
        "trafee.com",
        "adcombo.com",
        "galaksion.com",
        "mondiad.com",
        "pushground.com",
        "infolinks.com",
        "bidvertiser.com",
        "propellerclick.com",
        "syndication.exoclick.com",
        "tsyndicate.com",
        "creative.xlivrdr.com",
        "bidgear.com",
        "yllix.com",
        "adcash.com",
        "popmyads.com",
        "advertiser.com",
        "popunder.net",
        "adbit.biz",
        "realsrv.com",
        "wunderloop.net",
        "optmnstr.com",
        "scorecardresearch.com",
        "quantserve.com",
        "oxserver",
        "player-oxserver.js"
    )

    // Patterns for matching ad/tracking paths and query parameters
    private val AD_URL_PATTERNS = listOf(
        Regex(".*/(ads|adserver|popunder|clicktag|zoneid|vast|vpaid)/.*", RegexOption.IGNORE_CASE),
        Regex(".*\\b(banner_ad|ad_box|ad_banner|popunder_ad|sponsored_ad)\\b.*", RegexOption.IGNORE_CASE),
        Regex(".*[?&](zoneid|bannerid|campaignid|pop_id)=.*", RegexOption.IGNORE_CASE)
    )

    // Global toggle switch
    var isEnabled: Boolean = true

    /**
     * Determine whether the given URL is an advertisement, tracker, or pop-up redirect.
     */
    fun isAd(url: String?): Boolean {
        if (!isEnabled) return false
        if (url.isNullOrBlank()) return false

        val lowerUrl = url.lowercase().trim()

        // Block suspicious non-web intent/market schemes directly
        if (lowerUrl.startsWith("intent:") ||
            lowerUrl.startsWith("market:") ||
            lowerUrl.startsWith("whatsapp:") ||
            lowerUrl.startsWith("tg:") ||
            lowerUrl.startsWith("line:") ||
            lowerUrl.startsWith("fb:")
        ) {
            recordBlock(url, "Blocked app scheme redirect")
            return true
        }

        val host = try {
            java.net.URI(url).host?.lowercase()
        } catch (e: Exception) {
            try {
                Uri.parse(url).host?.lowercase()
            } catch (ex: Exception) {
                null
            }
        } ?: return false

        // Check if host matches any explicitly blocked domain or subdomain
        for (blocked in BLOCKED_DOMAINS) {
            if (host == blocked || host.endsWith(".$blocked")) {
                recordBlock(url, "Blocked domain match: $host -> $blocked")
                return true
            }
        }

        val isTrusted = TRUSTED_DOMAINS.any { host == it || host.endsWith(".$it") }
        val isVideoHost = ALLOWED_VIDEO_HOSTS.any { host == it || host.endsWith(".$it") || host.contains(it) }

        // CRITICAL: Never block trusted domains or recognized video streaming hosts
        if (isTrusted || isVideoHost) {
            return false
        }

        // Check ad regex patterns
        for (pattern in AD_URL_PATTERNS) {
            if (pattern.matches(lowerUrl)) {
                recordBlock(url, "Matched ad regex pattern")
                return true
            }
        }

        // Third-party domains containing explicit ad markers
        if (host.contains("adserver") || host.contains("popads") || host.contains("syndication") || host.contains("adsterra")) {
            recordBlock(url, "Host keyword match: $host")
            return true
        }

        // Allow video streaming media segments and files (HLS m3u8, ts chunks, mp4, disguised seg-.js, video player bundles, etc.)
        val isMedia = lowerUrl.contains(".m3u8") ||
                lowerUrl.contains(".ts") ||
                lowerUrl.contains(".mp4") ||
                lowerUrl.contains(".m4s") ||
                lowerUrl.contains("/stream/") ||
                lowerUrl.contains("/play/") ||
                lowerUrl.contains("seg-") ||
                lowerUrl.contains("snapcdn") ||
                lowerUrl.contains("/assets/") ||
                lowerUrl.contains("/player/") ||
                lowerUrl.contains("/embed/") ||
                lowerUrl.contains("/video/")

        if (!isMedia) {
            // If it's a JavaScript script or iframe from an unknown third party domain,
            // it is an ad network script (e.g. Monetag, Adsterra rotator). Block it!
            if (lowerUrl.contains(".js") || lowerUrl.contains("/script") || lowerUrl.contains("/tag") || lowerUrl.endsWith("/")) {
                recordBlock(url, "Blocked unknown third-party script/domain: $host")
                return true
            }
        }

        return false
    }

    private fun recordBlock(url: String, reason: String) {
        val total = blockedCount.incrementAndGet()
        try {
            Log.d(TAG, "[$total] Blocked: $url ($reason)")
        } catch (t: Throwable) {
            println("[$TAG][$total] Blocked: $url ($reason)")
        }
        onBlockListener?.invoke(total)
    }

    /**
     * Injected CSS string to hide ad banners, fake overlays, and anti-adblock elements.
     * Also defines full-screen player expansion rules.
     */
    fun getAntiAdCss(): String {
        return """
            /* Hide all scrollbars and scroll indicators */
            ::-webkit-scrollbar {
                display: none !important;
                width: 0 !important;
                height: 0 !important;
                background: transparent !important;
            }
            * {
                scrollbar-width: none !important;
                -ms-overflow-style: none !important;
            }
            html, body {
                scrollbar-width: none !important;
                -ms-overflow-style: none !important;
            }

            /* Hide fake ad overlays, fake player banners, and anti-adblock modals */
            html > iframe, html > div, .D1BnW, [class*="D1BnW"], div:has(> .notranslate), .notranslate,
            div[style*="z-index: 214748364"], div[style*="z-index: 999999"],
            iframe[src*="/ads/"], iframe[src*="doubleclick"], iframe[src*="googlesyndication"],
            iframe[src*="popads"], iframe[src*="adsterra"], iframe[src*="propeller"],
            iframe[src*="furudloof"], iframe[src*="belchlipin"], iframe[src*="buildsstate"],
            iframe[src*="monetag"], iframe[src*="excavatenearbywand"],
            div[class*="ad-"], div[class*="banner"], div[id*="ad-"], div[id*="banner"],
            div[class*="popup"], div[class*="popunder"],
            div[data-area], .wrapper[data-area],
            .ad-container, .adsbygoogle, .a-box, .notice-ad,
            #overlay, .modal-backdrop, .sweet-alert,
            a[href*="bet"], a[href*="affiliate"], a[href*="gamble"],
            .ts-ad-banner, .widget_banner, [data-ad-slot],
            .wb__-cover, .tutorial-overlay, div[class*="tutorial"] {
                display: none !important;
                visibility: hidden !important;
                width: 0 !important;
                height: 0 !important;
                pointer-events: none !important;
                opacity: 0 !important;
            }

            /* Hide 'Watch Now' featured carousel / slider on main page (both 9anime and gogoanime) */
            .deslide-wrap, #slider, .swiper-container#slider, .deslide-item, .top-slider,
            div[class*="deslide"], .slidtop, .slidtop * {
                display: none !important;
                visibility: hidden !important;
                height: 0 !important;
                max-height: 0 !important;
                margin: 0 !important;
                padding: 0 !important;
                overflow: hidden !important;
            }

            /* Hide '9anime is back' domain notice banner and announcements */
            .ts-announcement, .ts-announcement-general, div[class*="ts-announcement"],
            div[class*="announcement"], .notice-bar, .domain-alert, #notice {
                display: none !important;
                visibility: hidden !important;
                height: 0 !important;
                max-height: 0 !important;
                margin: 0 !important;
                padding: 0 !important;
                overflow: hidden !important;
            }

            /* Ensure player containers and embed iframes are ALWAYS visible, sized, and interactive */
            .wb_-playerarea, .player-embed, #player, #player-container, .player-wrap, .video-content,
            #main-player-wrap, #player-frame, #player-section, .mg-3mb3d, .mg3-player, #megaplay-player,
            .azaku-player-container {
                position: relative !important;
                width: 100% !important;
                aspect-ratio: 16 / 9 !important;
                min-height: 240px !important;
                background: #000000 !important;
                display: block !important;
                visibility: visible !important;
                opacity: 1 !important;
            }

            #player-embed, .player-embed, .player-wrap, #player-container, #main-player-wrap, #player-frame,
            .mg-3mb3d, .mg3-player, #megaplay-player, .azaku-player-container {
                display: block !important;
                visibility: visible !important;
                position: relative !important;
                width: 100% !important;
                height: 100% !important;
                min-height: 240px !important;
                opacity: 1 !important;
            }

            #modal.modal-vast, .modal-vast, .download-panel, .tutorial-overlay, #decryptionLoader, .server-toast,
            #auth-modal, .auth-modal, .modal-auth, .auth-modal__backdrop {
                display: none !important;
                visibility: hidden !important;
                height: 0 !important;
                pointer-events: none !important;
            }

            iframe#iframe-embed, iframe.player-iframe, .player-embed iframe, iframe[src*="player"],
            iframe[src*="embed"], iframe[src*="megaplay"], iframe[src*="mytsumi"], iframe[src*="xupalace"],
            iframe[src*="options.php"], iframe[src*="contenedor.php"], iframe#iframePlayer,
            iframe.player_conte, iframe[src*="jkplayer"] {
                display: block !important;
                visibility: visible !important;
                width: 100% !important;
                height: 100% !important;
                min-height: 240px !important;
                border: 0 !important;
                opacity: 1 !important;
                pointer-events: auto !important;
            }

            /* Dedicated Fullscreen Player Expansion CSS */
            .animetv-fullscreen-wrap {
                position: fixed !important;
                top: 0 !important;
                left: 0 !important;
                width: 100vw !important;
                height: 100vh !important;
                z-index: 2147483647 !important;
                background: #000000 !important;
                margin: 0 !important;
                padding: 0 !important;
            }
            .animetv-fullscreen-wrap .wb_-playerarea,
            .animetv-fullscreen-wrap .player-embed,
            .animetv-fullscreen-wrap #player-embed,
            .animetv-fullscreen-wrap #player,
            .animetv-fullscreen-wrap #player-container,
            .animetv-fullscreen-wrap .player-wrap,
            .animetv-fullscreen-wrap #main-player-wrap,
            .animetv-fullscreen-wrap #player-frame,
            .animetv-fullscreen-wrap #megaplay-player,
            .animetv-fullscreen-wrap .mg3-player,
            .animetv-fullscreen-wrap .azaku-player-container,
            .animetv-fullscreen-wrap .player_conte,
            .animetv-fullscreen-wrap iframe,
            .animetv-fullscreen-wrap video {
                width: 100vw !important;
                height: 100vh !important;
                max-width: 100vw !important;
                max-height: 100vh !important;
                border: 0 !important;
                margin: 0 !important;
                padding: 0 !important;
            }
        """.trimIndent().replace("\n", " ")
    }

    /**
     * Injected JavaScript code to:
     * 1. Suppress window.open completely
     * 2. Intercept click-jacking on fake overlays and external ad links
     * 3. Kill anti-adblock modals & fake voice-message overlays
     * 4. Expose window.expandPlayerFullscreen() and auto-expand player on play
     */
    fun getAntiAdJs(): String {
        return """
            (function() {
                try {
                    // 1. Completely neutralize window.open
                    try {
                        Object.defineProperty(window, 'open', {
                            value: function(url) {
                                console.log('AdBlocker: Suppressed window.open -> ' + url);
                                return null;
                            },
                            writable: false,
                            configurable: false
                        });
                    } catch(e) {
                        window.open = function(url) {
                            console.log('AdBlocker: Suppressed window.open fallback -> ' + url);
                            return null;
                        };
                    }

                    function isInternalLink(u) {
                        if (!u || u === '#' || u.indexOf('/') === 0 || u.indexOf('#') === 0 || u.indexOf('javascript:') === 0) return true;
                        var curHost = window.location.hostname;
                        return (curHost && u.indexOf(curHost) !== -1) || 
                               u.indexOf('9anime.or.at') !== -1 || 
                               u.indexOf('gogoanime.by') !== -1 ||
                               u.indexOf('sololatino.net') !== -1 ||
                               u.indexOf('animeflix.team') !== -1 ||
                               u.indexOf('9animes.me.uk') !== -1 ||
                               u.indexOf('animeyt.cc') !== -1 ||
                               u.indexOf('jkanime.net') !== -1 ||
                               u.indexOf('mytsumi.com') !== -1 ||
                               u.indexOf('bysesukior.com') !== -1 ||
                               u.indexOf('embed69.org') !== -1 ||
                               u.indexOf('pelisserieshoy.com') !== -1;
                    }

                    // 2. Neutralize anchor programmatic click-jacking
                    try {
                        var origClick = HTMLAnchorElement.prototype.click;
                        HTMLAnchorElement.prototype.click = function() {
                            var href = this.getAttribute('href') || '';
                            var target = this.getAttribute('target') || '';
                            if (target === '_blank' || !isInternalLink(href)) {
                                console.log('AdBlocker: Blocked anchor click -> ' + href);
                                return;
                            }
                            return origClick.apply(this, arguments);
                        };
                    } catch(e) {}

                    // 3. Capture-phase click interceptor (blocks external popup tabs & redirects)
                    document.addEventListener('click', function(e) {
                        var el = e.target;

                        // Check if click was on an external popup or target="_blank" link
                        while (el && el !== document.body) {
                            if (el.tagName === 'A') {
                                var href = el.getAttribute('href') || '';
                                var target = el.getAttribute('target') || '';
                                if (target === '_blank' || !isInternalLink(href)) {
                                    e.preventDefault();
                                    e.stopPropagation();
                                    console.log('AdBlocker: Suppressed external popup link click -> ' + href);
                                    return false;
                                }
                            }
                            el = el.parentElement;
                        }
                    }, true);

                    // 4. Purge overlay divs and fake player overlays
                    function purgeOverlays() {
                        // Remove rogue elements attached directly to <html> (fake robot modals, skip ad overlays, push prompts)
                        var directBad = document.querySelectorAll('html > iframe, html > div, .D1BnW, [class*="D1BnW"]');
                        for (var db = 0; db < directBad.length; db++) {
                            try { directBad[db].remove(); } catch(e) {}
                        }

                        // Remove fake overlay divs & popups (NEVER touch player, embed, or video iframes)
                        var badElements = document.querySelectorAll('iframe[src*="furudloof"], iframe[src*="belchlipin"], iframe[src*="adsterra"], iframe[src*="monetag"], iframe[src*="popads"], iframe[src*="propeller"], iframe[src*="buildsstate"], iframe[src*="oxserver"], iframe[src*="excavatenearbywand"], div[data-area], .wrapper[data-area], div[class*="popup"], div[class*="popunder"], #modal.modal-vast, .modal-vast, .tutorial-overlay, div[class*="tutorial"]');
                        for (var i = 0; i < badElements.length; i++) {
                            try { badElements[i].remove(); } catch(e) {}
                        }

                        // Auto-dismiss tutorial steps (e.g. embed69 "Cambiar Idioma")
                        var skipBtns = document.querySelectorAll('button, a, div');
                        for (var sk = 0; sk < skipBtns.length; sk++) {
                            var otxt = (skipBtns[sk].innerText || '').trim().toLowerCase();
                            if (otxt === 'omitir') {
                                try { skipBtns[sk].click(); } catch(e) {}
                            }
                        }

                        // Auto-dismiss AnimeYT notices, dialogs, and cookies
                        var dismissBtns = document.querySelectorAll('button[data-aniyt-cookie-dismiss], [data-abismo-login-dialog] .close, [aria-label="Cerrar"]');
                        for (var d = 0; d < dismissBtns.length; d++) {
                            try { dismissBtns[d].click(); } catch(e) {}
                        }
                        var allBtns = document.querySelectorAll('button');
                        for (var b = 0; b < allBtns.length; b++) {
                            if (allBtns[b].innerText && allBtns[b].innerText.trim().toLowerCase() === 'entendido') {
                                try { allBtns[b].click(); } catch(e) {}
                            }
                        }

                        // SoloLatino: Auto-select free server (Servidor 1) and dismiss auth modal
                        if (window.location.hostname.indexOf('sololatino.net') !== -1) {
                            var authM = document.getElementById('auth-modal') || document.querySelector('.auth-modal');
                            if (authM) { try { authM.remove(); } catch(e) {} }

                            var sBtns = document.querySelectorAll('button[data-server-btn], .server-btn');
                            for (var s = 0; s < sBtns.length; s++) {
                                var bTxt = (sBtns[s].innerText || '').toLowerCase();
                                if (bTxt.indexOf('servidor 1') !== -1 || bTxt.indexOf('server 1') !== -1) {
                                    var hasPlayerIfr = document.querySelector('iframe#iframePlayer, iframe[src*="embed69"], iframe[src*="xupalace"]');
                                    if (!hasPlayerIfr && !sBtns[s].classList.contains('active')) {
                                        try { sBtns[s].click(); } catch(e) {}
                                    }
                                    break;
                                }
                            }
                        }

                        // Auto-trigger SoloLatino play overlay if present
                        var playOverlay = document.querySelector('.play-button-overlay');
                        if (playOverlay && document.getElementById('fakePlayer') && document.getElementById('fakePlayer').style.display !== 'none') {
                            try { playOverlay.click(); } catch(e) {}
                        }

                        // Hide main page Watch Now carousel / slider (both 9anime and gogoanime)
                        var carousels = document.querySelectorAll('.owl-carousel, .carousel-wrap, #carousel, .slider-movies, .top-slider, .slidtop');
                        for (var c = 0; c < carousels.length; c++) {
                            carousels[c].style.setProperty('display', 'none', 'important');
                            carousels[c].style.setProperty('height', '0px', 'important');
                        }

                        // Remove '9anime is back' announcement banner
                        var banners = document.querySelectorAll('.ts-announcement, .ts-announcement-general, div[class*="ts-announcement"], div[class*="announcement"], .notice-bar, .domain-alert, #notice');
                        for (var b = 0; b < banners.length; b++) {
                            try { banners[b].remove(); } catch(e) {}
                        }

                        // Ensure iframe player has allowfullscreen
                        var ifr = document.getElementById('iframe-embed') || 
                                  document.querySelector('.player-embed iframe') || 
                                  document.querySelector('iframe.player-iframe') || 
                                  document.querySelector('iframe[src*="player"]') || 
                                  document.querySelector('iframe[src*="embed"]') || 
                                  document.querySelector('iframe[src*="megaplay"]') || 
                                  document.getElementById('player-frame') || 
                                  document.querySelector('iframe[src*="mytsumi"]') || 
                                  document.querySelector('iframe[src*="embed69"]') || 
                                  document.querySelector('iframe[src*="xupalace"]') ||
                                  document.querySelector('iframe#iframePlayer') ||
                                  document.querySelector('iframe.player_conte') ||
                                  document.querySelector('iframe[src*="jkplayer"]');
                        if (ifr) {
                            if (!ifr.hasAttribute('allowfullscreen')) {
                                ifr.setAttribute('allowfullscreen', 'true');
                            }
                            var allow = ifr.getAttribute('allow') || '';
                            if (allow.indexOf('fullscreen') === -1) {
                                ifr.setAttribute('allow', 'autoplay; fullscreen *; encrypted-media *');
                            }
                        }

                        // Neutralize target="_blank" empty anchors
                        var blankAnchors = document.querySelectorAll('a[target="_blank"]');
                        for (var a = 0; a < blankAnchors.length; a++) {
                            var anc = blankAnchors[a];
                            var h = anc.getAttribute('href') || '';
                            if (!isInternalLink(h)) {
                                anc.removeAttribute('target');
                                anc.setAttribute('href', 'javascript:void(0)');
                            }
                        }
                    }

                    setInterval(purgeOverlays, 1000);
                    purgeOverlays();

                    // 5. Expose Fullscreen Player Expand/Collapse to Android and Web
                    window.expandPlayerFullscreen = function(enable) {
                        var wrap = document.querySelector('.player-wrap') || 
                                   document.querySelector('.wb_-playerarea') || 
                                   document.getElementById('player-embed') || 
                                   document.querySelector('.player-embed') || 
                                   document.querySelector('#player') || 
                                   document.querySelector('#player-container') || 
                                   document.querySelector('.video-content') || 
                                   document.querySelector('#main-player-wrap') || 
                                   document.querySelector('#player-section') || 
                                   document.querySelector('.mg-3mb3d') || 
                                   document.querySelector('.mg3-player') || 
                                   document.querySelector('.azaku-player-container') ||
                                   document.querySelector('.player_conte');
                        var ifr = document.getElementById('iframe-embed') || 
                                  document.querySelector('.player-embed iframe') || 
                                  document.querySelector('iframe.player-iframe') || 
                                  document.querySelector('iframe[src*="player"]') || 
                                  document.querySelector('iframe[src*="embed"]') || 
                                  document.querySelector('iframe[src*="megaplay"]') || 
                                  document.getElementById('player-frame') || 
                                  document.querySelector('iframe[src*="mytsumi"]') || 
                                  document.querySelector('iframe[src*="embed69"]') || 
                                  document.querySelector('iframe[src*="xupalace"]') ||
                                  document.querySelector('iframe#iframePlayer') ||
                                  document.querySelector('iframe.player_conte') ||
                                  document.querySelector('iframe[src*="jkplayer"]');
                        
                        if (enable) {
                            if (wrap) {
                                wrap.classList.add('animetv-fullscreen-wrap');
                            }
                            if (ifr) {
                                try {
                                    if (ifr.requestFullscreen) ifr.requestFullscreen().catch(function(){});
                                    else if (ifr.webkitRequestFullscreen) ifr.webkitRequestFullscreen();
                                } catch(e) {}
                            }
                            console.log('Player expanded to fullscreen');
                        } else {
                            if (wrap) {
                                wrap.classList.remove('animetv-fullscreen-wrap');
                            }
                            try {
                                if (document.exitFullscreen) document.exitFullscreen().catch(function(){});
                                else if (document.webkitExitFullscreen) document.webkitExitFullscreen();
                            } catch(e) {}
                            console.log('Player collapsed from fullscreen');
                        }
                    };

                    // Global play / playing event listeners
                    function handlePlayDetected() {
                        console.log('Video play detected -> expanding player to fullscreen');
                        if (window.expandPlayerFullscreen) {
                            window.expandPlayerFullscreen(true);
                        }
                        if (window.AndroidBridge && window.AndroidBridge.onVideoPlayDetected) {
                            window.AndroidBridge.onVideoPlayDetected();
                        }
                    }
                    document.addEventListener('play', handlePlayDetected, true);
                    document.addEventListener('playing', handlePlayDetected, true);

                    // Listen for player postMessages (e.g. plyr, megacloud)
                    window.addEventListener('message', function(event) {
                        try {
                            var data = typeof event.data === 'string' ? JSON.parse(event.data) : event.data;
                            if (data && (data.event === 'play' || data.event === 'playing' || data.type === 'play' || data.status === 'playing')) {
                                console.log('Iframe player postMessage play detected');
                                if (window.expandPlayerFullscreen) {
                                    window.expandPlayerFullscreen(true);
                                }
                                if (window.AndroidBridge && window.AndroidBridge.onVideoPlayDetected) {
                                    window.AndroidBridge.onVideoPlayDetected();
                                }
                            }
                        } catch(ignore) {}
                    }, false);

                } catch(e) {
                    console.error('AdBlocker script error: ', e);
                }
            })();
        """.trimIndent()
    }
}
