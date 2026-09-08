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

    // Recognized base domains for 9anime and trusted streaming / resource CDNs
    val TRUSTED_DOMAINS = setOf(
        "9anime.or.at",
        "1anime.site",
        "my.1anime.site",
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
        "doodstream.com",
        "dood.to",
        "dood.so",
        "vidstream.pro",
        "vidstream.to",
        "vizcloud.online",
        "vizcloud.co",
        "vidsrc.me",
        "vidsrc.to"
    )

    // Known ad networks, pop-up/pop-under services, and tracking domains
    private val BLOCKED_DOMAINS = setOf(
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
        "quantserve.com"
    )

    // Patterns for matching ad/tracking paths and query parameters
    private val AD_URL_PATTERNS = listOf(
        Regex(".*/(ads|adserver|popunder|clicktag|zoneid|vast|vpaid)/.*", RegexOption.IGNORE_CASE),
        Regex(".*\\b(banner_ad|ad_box|ad_banner|popunder_ad|sponsored_ad)\\b.*", RegexOption.IGNORE_CASE),
        Regex(".*[?&](zoneid|bannerid|campaignid|pop_id)=.*", RegexOption.IGNORE_CASE)
    )

    /**
     * Determine whether the given URL is an advertisement, tracker, or pop-up redirect.
     */
    fun isAd(url: String?): Boolean {
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
        val isVideoHost = ALLOWED_VIDEO_HOSTS.any { host == it || host.endsWith(".$it") }

        if (!isTrusted && !isVideoHost) {
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

            // Allow video streaming media segments and files (HLS m3u8, ts chunks, mp4, etc.)
            val isMedia = lowerUrl.contains(".m3u8") ||
                    lowerUrl.contains(".ts") ||
                    lowerUrl.contains(".mp4") ||
                    lowerUrl.contains(".m4s") ||
                    lowerUrl.contains("/stream/") ||
                    lowerUrl.contains("/play/")

            if (!isMedia) {
                // If it's a JavaScript script or iframe from an unknown third party domain,
                // it is an ad network script (e.g. Monetag, Adsterra rotator). Block it!
                if (lowerUrl.contains(".js") || lowerUrl.contains("/script") || lowerUrl.contains("/tag") || lowerUrl.endsWith("/")) {
                    recordBlock(url, "Blocked unknown third-party script/domain: $host")
                    return true
                }
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
            iframe[src*="/ads/"], iframe[src*="doubleclick"], iframe[src*="googlesyndication"],
            iframe[src*="popads"], iframe[src*="adsterra"], iframe[src*="propeller"],
            iframe[src*="furudloof"], iframe[src*="belchlipin"],
            iframe:not(#iframe-embed):not([src*="1anime"]):not([src*="megacloud"]):not([src*="rapid"]):not([src*="streamtape"]):not([src*="disqus"]):not([id^="dsq"]),
            div[class*="ad-"], div[class*="banner"], div[id*="ad-"], div[id*="banner"],
            div[class*="popup"], div[class*="popunder"],
            div[data-area], .wrapper[data-area],
            .ad-container, .adsbygoogle, .a-box, .notice-ad,
            #overlay, .modal-backdrop, .sweet-alert,
            div[style*="z-index: 2147483647"], div[style*="z-index: 999999"],
            a[href*="bet"], a[href*="affiliate"], a[href*="gamble"],
            .ts-ad-banner, .widget_banner, [data-ad-slot],
            .wb__-cover {
                display: none !important;
                visibility: hidden !important;
                width: 0 !important;
                height: 0 !important;
                pointer-events: none !important;
                opacity: 0 !important;
            }

            /* Hide 'Watch Now' featured carousel / slider on main page */
            .deslide-wrap, #slider, .swiper-container#slider, .deslide-item, .top-slider,
            div[class*="deslide"] {
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

            /* Ensure 9anime player container and embed iframe are ALWAYS visible, sized, and interactive */
            .wb_-playerarea {
                position: relative !important;
                width: 100% !important;
                aspect-ratio: 16 / 9 !important;
                min-height: 220px !important;
                background: #000000 !important;
            }

            #player-embed {
                display: block !important;
                visibility: visible !important;
                position: relative !important;
                width: 100% !important;
                height: 100% !important;
                min-height: 220px !important;
            }

            iframe#iframe-embed {
                display: block !important;
                visibility: visible !important;
                width: 100% !important;
                height: 100% !important;
                min-height: 220px !important;
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
            .animetv-fullscreen-wrap #player-embed,
            .animetv-fullscreen-wrap iframe#iframe-embed {
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

                    // 2. Neutralize anchor programmatic click-jacking
                    try {
                        var origClick = HTMLAnchorElement.prototype.click;
                        HTMLAnchorElement.prototype.click = function() {
                            var href = this.getAttribute('href') || '';
                            var target = this.getAttribute('target') || '';
                            if (target === '_blank' || (href && href !== '#' && href.indexOf('9anime.or.at') === -1 && href.indexOf('/') !== 0)) {
                                console.log('AdBlocker: Blocked anchor click -> ' + href);
                                return;
                            }
                            return origClick.apply(this, arguments);
                        };
                    } catch(e) {}

                    // 3. Capture-phase click interceptor
                    document.addEventListener('click', function(e) {
                        var el = e.target;
                        
                        // Check if user clicked the player area -> trigger auto-fullscreen
                        var playerArea = el.closest ? el.closest('.player-wrap, .wb_-playerarea, #player-embed') : null;
                        if (playerArea) {
                            console.log('Player area clicked -> triggering fullscreen expansion');
                            if (window.expandPlayerFullscreen) {
                                window.expandPlayerFullscreen(true);
                            }
                            if (window.AndroidBridge && window.AndroidBridge.onVideoPlayDetected) {
                                window.AndroidBridge.onVideoPlayDetected();
                            }
                        }

                        // Check if click was on an external popup or target="_blank" link
                        while (el && el !== document.body) {
                            if (el.tagName === 'A') {
                                var href = el.getAttribute('href') || '';
                                var target = el.getAttribute('target') || '';
                                if (target === '_blank' || (href && href !== '#' && href.indexOf('9anime.or.at') === -1 && href.indexOf('/') !== 0 && href.indexOf('#') !== 0)) {
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
                        // Remove fake overlay divs & popups
                        var badElements = document.querySelectorAll('iframe:not(#iframe-embed):not([id^="dsq"]), div[data-area], .wrapper[data-area], div[class*="popup"], div[class*="popunder"]');
                        for (var i = 0; i < badElements.length; i++) {
                            try { badElements[i].remove(); } catch(e) {}
                        }

                        // Hide main page Watch Now carousel
                        var sliderWrap = document.querySelector('.deslide-wrap') || document.getElementById('slider');
                        if (sliderWrap && sliderWrap.style.display !== 'none') {
                            sliderWrap.style.display = 'none';
                        }

                        // Remove '9anime is back' announcement banner
                        var announcements = document.querySelectorAll('.ts-announcement, div[class*="ts-announcement"], div[class*="announcement"]');
                        for (var i = 0; i < announcements.length; i++) {
                            try { announcements[i].remove(); } catch(e) {}
                        }

                        // Purge fixed high z-index screen-covering click traps
                        var allDivs = document.querySelectorAll('div, a');
                        for (var d = 0; d < allDivs.length; d++) {
                            var item = allDivs[d];
                            if (item.classList && item.classList.contains('animetv-fullscreen-wrap')) continue;
                            var style = window.getComputedStyle(item);
                            if (style.position === 'fixed') {
                                var z = parseInt(style.zIndex, 10);
                                if (z >= 9999 && !item.querySelector('video') && !item.querySelector('iframe')) {
                                    if (item.offsetWidth >= window.innerWidth * 0.85 && item.offsetHeight >= window.innerHeight * 0.85) {
                                        try { item.remove(); } catch(e) {}
                                    }
                                }
                            }
                        }

                        // Ensure player iframe has fullscreen and autoplay enabled
                        var ifr = document.getElementById('iframe-embed');
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
                            if (h.indexOf('9anime.or.at') === -1 && h.indexOf('/') !== 0) {
                                anc.removeAttribute('target');
                                anc.setAttribute('href', 'javascript:void(0)');
                            }
                        }
                    }

                    setInterval(purgeOverlays, 1000);
                    purgeOverlays();

                    // 5. Expose Fullscreen Player Expand/Collapse to Android and Web
                    window.expandPlayerFullscreen = function(enable) {
                        var wrap = document.querySelector('.player-wrap') || document.querySelector('.wb_-playerarea') || document.getElementById('player-embed');
                        var ifr = document.getElementById('iframe-embed');
                        
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
                            console.log('Player restored to normal layout');
                        }
                    };

                    // Global play event listener
                    document.addEventListener('play', function(e) {
                        console.log('Video play detected -> expanding player to fullscreen');
                        if (window.expandPlayerFullscreen) {
                            window.expandPlayerFullscreen(true);
                        }
                        if (window.AndroidBridge && window.AndroidBridge.onVideoPlayDetected) {
                            window.AndroidBridge.onVideoPlayDetected();
                        }
                    }, true);

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
