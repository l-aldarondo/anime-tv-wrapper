package com.example.animetv.adblock

import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.WebResourceRequest
import java.util.concurrent.atomic.AtomicInteger

object AdBlocker {
    private const val TAG = "AdBlocker"
    private const val RULES_ASSET_PATH = "adblock/blocklist.txt"

    // Atomic counter for blocked requests to display in TV HUD
    val blockedCount = AtomicInteger(0)

    // Listener for UI updates
    var onBlockListener: ((Int) -> Unit)? = null

    // Recognized base domains for 9anime, GogoAnime, SoloLatino, AnimeFlix, AnimeYT, JKAnime, and trusted streaming / resource CDNs.
    // NOTE: pure telemetry/tracking domains (Google Tag Manager, Cloudflare Insights) are intentionally
    // NOT trusted here - the page renders fine without them, so they are blocked via blocklist.txt instead.
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
        "cdn.plyr.io"
    )

    // Allowed video servers and embed streaming domains (matched as exact host or subdomain).
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
        "ok.ru",
        "cdn-vk.ru",
        "vk.com",
        "vk.me",
        "mega.nz",
        "mega.co.nz",
        "mega.io",
        "uqload.io",
        "uqload.com",
        "vidmoly.me",
        "vidmoly.to",
        "voe.sx",
        "snapcdn.top",
        "bysesukior.com",
        "pelisserieshoy.com",
        "desu.sh",
        "f7hyg4q.org"
    )

    // Short brand-name fragments for video hosts that constantly rotate across TLDs/subdomains
    // (e.g. mixdrop.co / mixdrop.to / mixdrop2.ag). Matched per-DNS-label via
    // AdFilterEngine.hostContainsLabelToken, which is safer than a raw host.contains() check
    // because it can never match across a "." boundary into an unrelated domain.
    private val ALLOWED_VIDEO_HOST_FUZZY_TOKENS = listOf(
        "jkplayer",
        "jkplayers",
        "statlytic",
        "streamwish",
        "wishembed",
        "luluvdo",
        "morencius",
        "mega",
        "uqload",
        "vidmoly",
        "snapcdn",
        "mixdrop",
        "yourupload",
        "sesukior",
        "byse",
        "vidhide",
        "vidhidepre",
        "vidhidevip",
        "filelions",
        "streamvid",
        "desu",
        "dplayer",
        "f7hyg4q"
    )

    // Compiled-in safety-net of known ad networks, pop-up/pop-under services, and tracking domains.
    // This always applies even if the bundled blocklist.txt fails to load; the asset file
    // (assets/adblock/blocklist.txt) is the preferred place to add/remove rules going forward
    // since it does not require recompiling the app.
    private val BLOCKED_DOMAINS = setOf(
        "ideecoral.com",
        "relateova.com",
        "ocmufsnhvypis.com",
        "visariomedia.com",
        "adsco.re",
        "llvpn.com",
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
        "player-oxserver.js",
        "googletagmanager.com",
        "cloudflareinsights.com"
    )

    // Patterns for matching ad/tracking paths and query parameters
    private val AD_URL_PATTERNS = listOf(
        Regex(".*/(ads|adserver|popunder|clicktag|zoneid|vast|vpaid)/.*", RegexOption.IGNORE_CASE),
        Regex(".*\\b(banner_ad|ad_box|ad_banner|popunder_ad|sponsored_ad)\\b.*", RegexOption.IGNORE_CASE),
        Regex(".*[?&](zoneid|bannerid|campaignid|pop_id)=.*", RegexOption.IGNORE_CASE)
    )

    // Rules parsed from assets/adblock/blocklist.txt at startup (see loadRules). Empty until loaded,
    // in which case only the compiled-in lists above apply.
    @Volatile private var runtimeRules: AdFilterEngine.ParsedRules = AdFilterEngine.ParsedRules.EMPTY
    @Volatile private var rulesLoaded = false

    // Global toggle switch
    var isEnabled: Boolean = true

    /**
     * Parses assets/adblock/blocklist.txt and merges it into the active rule set. Safe to call
     * multiple times (e.g. to hot-reload after editing the bundled file during development);
     * failures are logged and simply leave the compiled-in safety-net lists in effect.
     */
    fun loadRules(context: Context) {
        try {
            val text = context.assets.open(RULES_ASSET_PATH).bufferedReader(Charsets.UTF_8).use { it.readText() }
            runtimeRules = AdFilterEngine.parse(text)
            rulesLoaded = true
            Log.d(
                TAG,
                "Loaded filter list: ${runtimeRules.blockedDomains.size} domains, " +
                    "${runtimeRules.allowedDomains.size} exceptions, " +
                    "${runtimeRules.blockedSubstrings.size} patterns, " +
                    "${runtimeRules.cosmeticSelectors.size} cosmetic selectors"
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not load $RULES_ASSET_PATH, falling back to compiled-in lists only", e)
        }
    }

    fun isTrustedDomain(host: String): Boolean = AdFilterEngine.hostMatchesAny(host, TRUSTED_DOMAINS)

    fun isAllowedVideoHost(host: String): Boolean {
        return AdFilterEngine.hostMatchesAny(host, ALLOWED_VIDEO_HOSTS) ||
            AdFilterEngine.hostContainsAnyLabelToken(host, ALLOWED_VIDEO_HOST_FUZZY_TOKENS)
    }

    /**
     * Determine whether the given URL is an advertisement, tracker, or pop-up redirect.
     */
    fun isAd(url: String?): Boolean = isAd(url, isForMainFrame = false)

    /** Overload that takes the intercepted request so main-frame document loads are never
     * subjected to the "unknown third-party script" heuristic. */
    fun isAd(request: WebResourceRequest?): Boolean {
        val url = request?.url?.toString() ?: return false
        return isAd(url, isForMainFrame = request.isForMainFrame)
    }

    private fun isAd(url: String?, isForMainFrame: Boolean): Boolean {
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

        // CRITICAL: trusted site domains, recognized video streaming hosts, and explicit filter-list
        // exceptions (@@||domain^) are never blocked, regardless of anything below.
        if (isTrustedDomain(host) || isAllowedVideoHost(host) || AdFilterEngine.hostMatchesAny(host, runtimeRules.allowedDomains)) {
            return false
        }

        // Explicit blocked domain (compiled-in safety net + runtime filter list)
        if (AdFilterEngine.hostMatchesAny(host, BLOCKED_DOMAINS)) {
            recordBlock(url, "Blocked domain match: $host")
            return true
        }
        if (AdFilterEngine.hostMatchesAny(host, runtimeRules.blockedDomains)) {
            recordBlock(url, "Blocked domain match (filter list): $host")
            return true
        }

        // Literal substring rules from the filter list (paths, query params, etc.)
        for (pattern in runtimeRules.blockedSubstrings) {
            if (lowerUrl.contains(pattern)) {
                recordBlock(url, "Matched filter list pattern: $pattern")
                return true
            }
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

        // Never apply the "unknown third-party script" heuristic to a main-frame document load -
        // it exists to catch ad-network <script>/<iframe> sub-resources, not page navigations.
        if (!isMedia && !isForMainFrame) {
            // If it's a JavaScript script from an unknown third party domain, it is very likely an
            // ad network script (e.g. Monetag, Adsterra rotator). Block it.
            if (lowerUrl.contains(".js") || lowerUrl.contains("/script") || lowerUrl.contains("/tag")) {
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
        val cosmeticFromRules = runtimeRules.cosmeticSelectors
        val cosmeticBlock = if (cosmeticFromRules.isNotEmpty()) {
            cosmeticFromRules.joinToString(", ") + " { display: none !important; visibility: hidden !important; }"
        } else {
            ""
        }

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
            .wb__-cover, .tutorial-overlay, div[class*="tutorial"],
            [class*="adblock" i], [id*="adblock" i], [class*="ad-block" i], [class*="antiadblock" i],
            [class*="disable-ad" i], [class*="social-bar" i], [class*="sociallocker" i],
            [class*="ads-modal" i], [class*="ad-modal" i], [class*="ad-warning" i] {
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

            $cosmeticBlock
        """.trimIndent().replace("\n", " ")
    }

    /**
     * Injected JavaScript code to:
     * 1. Suppress window.open completely
     * 2. Intercept click-jacking on fake overlays and external ad links
     * 3. Kill anti-adblock modals & fake voice-message overlays via a MutationObserver
     *    (reacts to DOM insertions immediately instead of polling on a fixed timer)
     * 4. Expose window.expandPlayerFullscreen() and auto-expand player on play
     */
    fun getAntiAdJs(): String {
        return """
            (function() {
                try {
                    // Neutralize SoloLatino ad payload. The actual data lives in a
                    // <script id="__sl_ads" type="application/json">{"h":"<ad script tags>","b":""}</script>
                    // element in the DOM - overriding the *global* window.__sl_ads (below) does
                    // nothing on its own, since the site's own code reads the element by id and
                    // JSON.parses its text content directly, never touching window.__sl_ads. Clear
                    // the actual element's content too, and keep re-clearing it since some pages
                    // re-render this element (e.g. on a server switch) with a fresh ad payload.
                    function neutralizeSlAdsElement() {
                        try {
                            var slAdsEl = document.getElementById('__sl_ads');
                            if (slAdsEl && slAdsEl.textContent !== '{"h":"","b":""}') {
                                slAdsEl.textContent = '{"h":"","b":""}';
                            }
                        } catch(e) {}
                    }
                    neutralizeSlAdsElement();
                    try {
                        window.__sl_ads = { h: "" };
                        Object.defineProperty(window, '__sl_ads', {
                            get: function() { return { h: "" }; },
                            set: function() {}
                        });
                    } catch(e) {}

                    // Any <img> whose source we blocked at the network level (a tracking pixel or
                    // ad creative from a blocked domain) still leaves behind the browser's "broken
                    // image" placeholder icon, since blocking the request doesn't remove the <img>
                    // element itself. img load errors don't bubble, but a capture-phase listener on
                    // the document still sees them - hide any image that fails to load, globally.
                    try {
                        document.addEventListener('error', function(e) {
                            if (e.target && e.target.tagName === 'IMG') {
                                try { e.target.style.setProperty('display', 'none', 'important'); } catch(err) {}
                            }
                        }, true);
                    } catch(e) {}

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
                        neutralizeSlAdsElement();

                        // Remove rogue elements attached directly to <html> (fake robot modals, skip ad overlays, push prompts)
                        var directBad = document.querySelectorAll('html > iframe, html > div, .D1BnW, [class*="D1BnW"]');
                        for (var db = 0; db < directBad.length; db++) {
                            try { directBad[db].remove(); } catch(e) {}
                        }

                        // Remove fake overlay divs & popups (NEVER touch player, embed, or video iframes)
                        var badElements = document.querySelectorAll('iframe[src*="furudloof"], iframe[src*="belchlipin"], iframe[src*="adsterra"], iframe[src*="monetag"], iframe[src*="popads"], iframe[src*="propeller"], iframe[src*="buildsstate"], iframe[src*="oxserver"], iframe[src*="excavatenearbywand"], div[data-area], .wrapper[data-area], div[class*="popup"], div[class*="popunder"], #modal.modal-vast, .modal-vast, .tutorial-overlay, div[class*="tutorial"], [class*="adblock" i], [id*="adblock" i], [class*="ad-block" i], [class*="antiadblock" i], [class*="disable-ad" i], [class*="social-bar" i], [class*="sociallocker" i], [class*="ads-modal" i], [class*="ad-modal" i], [class*="ad-warning" i]');
                        for (var i = 0; i < badElements.length; i++) {
                            try { badElements[i].remove(); } catch(e) {}
                        }

                        // Remove adblock-detection warning walls AND clickbait ad banners by keyword
                        // match, in case the site injects them without a recognizable class/id
                        // ("Este video tiene ventanas emergentes y anuncios", "disable your ad
                        // blocker", the classic green "Click this button" banner creative, etc.)
                        try {
                            var adWallTextRe = /anuncio|publicidad|adblock|ad.?block|ventana.?emergente|ventanas.?emergentes|pop.?up|bloqueador|desactiva.*(ad|anuncio)|click this button|click here to continue|you.?ve won|you have won|claim your (prize|reward)|verify you.?re human/i;
                            var textCandidates = document.querySelectorAll('div, section, aside, p, a, button');
                            for (var tc = 0; tc < textCandidates.length; tc++) {
                                var tEl = textCandidates[tc];
                                if (!tEl || tEl.tagName === 'VIDEO' || (tEl.querySelector && tEl.querySelector('video'))) continue;
                                var tTxt = (tEl.innerText || tEl.textContent || '').trim();
                                if (!tTxt || tTxt.length > 400 || !adWallTextRe.test(tTxt)) continue;
                                var tCs = window.getComputedStyle(tEl);
                                var tZ = parseInt(tCs.zIndex || '0', 10) || 0;
                                if (tCs.position === 'fixed' || tCs.position === 'absolute' || tZ > 100 || tEl.offsetWidth > window.innerWidth * 0.5) {
                                    try { tEl.remove(); } catch(e) {}
                                }
                            }
                        } catch(e) {}

                        // Generic ad interstitial/modal detector: native ad networks constantly
                        // rotate the exact wording ("Click this button" one day, "Explore the World
                        // Your Way... CONTINUE" the next), so chasing each new sentence is a losing
                        // game. Instead detect the STRUCTURE nearly all of them share: a large
                        // fixed/absolute overlay darkening most of the screen, containing a
                        // prominently-styled call-to-action button with generic
                        // continue/discover/claim-style wording. Legitimate full-screen modals on
                        // these sites are exactly two, both excluded by id/class below - anything
                        // else matching this shape mid-playback is not going to be real site UI.
                        try {
                            var adCtaRe = /^(continue|continuar|discover|explore|claim|unlock|get started|watch now|start now|download now|install now|join now|sign up|allow|next)$/i;
                            var modalCandidates = document.querySelectorAll('div');
                            for (var am = 0; am < modalCandidates.length; am++) {
                                var mEl = modalCandidates[am];
                                if (!mEl || (mEl.querySelector && mEl.querySelector('video'))) continue;
                                if (mEl.id === 'trailer-modal' || mEl.id === 'auth-modal' || (mEl.className && mEl.className.toString().indexOf('auth-modal') !== -1)) continue;
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
                        } catch(e) {}

                        // Smaller ad cards: not every rotating ad creative is a full-screen modal -
                        // some render as a card roughly the size of the player itself (e.g. a
                        // gradient "Explore Trending Styles Now... More / Close" card), with button
                        // labels too generic/common ("More", "Close") to safely text-match on their
                        // own. What's still a reliable signal regardless of wording: a positioned
                        // card with 2+ short generic buttons/links alongside a short marketing-style
                        // blurb (a heading plus a sentence or two), that isn't wrapping the actual
                        // player. Legitimate UI on these sites doesn't pair exactly that shape.
                        try {
                            var cardCandidates = document.querySelectorAll('div');
                            for (var ac = 0; ac < cardCandidates.length; ac++) {
                                var cEl = cardCandidates[ac];
                                if (!cEl || (cEl.querySelector && (cEl.querySelector('video') || cEl.querySelector('iframe')))) continue;
                                if (cEl.id === 'trailer-modal' || cEl.id === 'auth-modal' || (cEl.className && cEl.className.toString().indexOf('auth-modal') !== -1)) continue;
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
                        } catch(e) {}

                        // "Nuked z-index" bait overlays (fake "watch in HD now" style elements some
                        // embed CDNs float on top of the real player, either as a src-less iframe or a
                        // plain div - z-index right at the 32-bit signed int max in both observed
                        // cases). No legitimate site UI needs a z-index anywhere near 2^31-1. However a
                        // near-fullscreen instance of this pattern turned out, in testing, to sometimes
                        // BE the player's own legitimate click-to-play/gesture layer - deleting it left
                        // the player with no way to start playback at all. So only delete small,
                        // clearly-decorative matches; for anything covering most of the screen, just
                        // strip pointer-events so a real tap passes through to whatever is underneath
                        // instead of destroying a node the player might depend on.
                        try {
                            var extremeZEls = document.querySelectorAll('body *');
                            for (var ez = 0; ez < extremeZEls.length; ez++) {
                                var eEl = extremeZEls[ez];
                                if (!eEl || eEl.tagName === 'VIDEO' || (eEl.querySelector && eEl.querySelector('video'))) continue;
                                var eCs = window.getComputedStyle(eEl);
                                var eZ = parseInt(eCs.zIndex || '0', 10) || 0;
                                if ((eCs.position === 'fixed' || eCs.position === 'absolute') && eZ > 2000000000) {
                                    var eRect = eEl.getBoundingClientRect();
                                    var eCoversMost = eRect.width >= window.innerWidth * 0.75 && eRect.height >= window.innerHeight * 0.75;
                                    try {
                                        if (eCoversMost) {
                                            eEl.style.setProperty('pointer-events', 'none', 'important');
                                        } else {
                                            eEl.remove();
                                        }
                                    } catch(e) {}
                                }
                            }
                        } catch(e) {}

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

                        // SoloLatino: dismiss the auth modal and visually de-emphasize paid-tier
                        // server buttons (Premium/VIP) so they're less tempting to tap by mistake.
                        //
                        // This used to also auto-click the free server for the user, but every
                        // attempt at that caused a *different* problem: clicking a hidden language
                        // tab's button by accident, a reload loop when the "active" class never
                        // showed up in time, and finally interrupting an already-playing free server
                        // because the polling ran too eagerly. Each fix traded one failure for
                        // another without ever confirming the original was solved, so this is
                        // intentionally hands-off now - no reading "active" state, no clicking
                        // anything. The user picks their own server from the visible buttons, same as
                        // always; this only makes the paid ones look less like the right choice.
                        if (window.location.hostname.indexOf('sololatino.net') !== -1) {
                            var authM = document.getElementById('auth-modal') || document.querySelector('.auth-modal');
                            if (authM) { try { authM.remove(); } catch(e) {} }

                            var paidTierRe = /premium|\bvip\b/i;
                            var allServerBtns = document.querySelectorAll('button[data-server-btn], .server-btn');
                            for (var s = 0; s < allServerBtns.length; s++) {
                                var bTxt = (allServerBtns[s].innerText || '').trim();
                                if (paidTierRe.test(bTxt)) {
                                    try { allServerBtns[s].style.setProperty('opacity', '0.45', 'important'); } catch(e) {}
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
                        for (var b2 = 0; b2 < banners.length; b2++) {
                            try { banners[b2].remove(); } catch(e) {}
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

                    purgeOverlays();

                    // React to DOM insertions immediately instead of waiting on a fixed-interval poll,
                    // which shortens the "ad flash" window from up to 1s down to effectively 0.
                    try {
                        var purgeScheduled = false;
                        var scheduledPurge = function() {
                            if (purgeScheduled) return;
                            purgeScheduled = true;
                            var run = function() {
                                purgeScheduled = false;
                                purgeOverlays();
                            };
                            if (window.requestAnimationFrame) requestAnimationFrame(run);
                            else setTimeout(run, 16);
                        };
                        var observerTarget = document.documentElement || document.body;
                        if (observerTarget && window.MutationObserver) {
                            var mo = new MutationObserver(scheduledPurge);
                            mo.observe(observerTarget, {
                                childList: true,
                                subtree: true,
                                attributes: true,
                                attributeFilter: ['style', 'class', 'src']
                            });
                        }
                    } catch(e) {}

                    // Safety-net slow poll in case something evades the MutationObserver
                    // (much less frequent now that mutations are handled reactively above).
                    setInterval(purgeOverlays, 4000);

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
                            } else if (data && data.event === 'doubletap') {
                                // Bubbled up from a sanitized embed iframe's own double-tap detector -
                                // evaluateJavascript() can't reach into a cross-origin iframe directly,
                                // so that script posts up to us instead of calling AndroidBridge itself.
                                if (window.AndroidBridge && window.AndroidBridge.onPlayerDoubleTap) {
                                    window.AndroidBridge.onPlayerDoubleTap();
                                }
                            }
                        } catch(ignore) {}
                    }, false);

                    // Double-tap on the player toggles fullscreen. touch-action:manipulation plus
                    // our own preventDefault() on the second tap keep this from also triggering
                    // the WebView's native double-tap-to-zoom on the same gesture. Guarded so
                    // re-injecting this script (happens on every page load event) doesn't stack a
                    // second listener and fire the toggle twice per tap.
                    if (!window.__ab_dblTapInstalled) {
                        window.__ab_dblTapInstalled = true;
                        var lastPlayerTap = 0;
                        document.addEventListener('touchend', function(e) {
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
                            if (!wrap) return;
                            try { wrap.style.setProperty('touch-action', 'manipulation'); } catch(ignore) {}
                            var t = e.target;
                            var insideWrap = false;
                            while (t) { if (t === wrap) { insideWrap = true; break; } t = t.parentElement; }
                            if (!insideWrap) return;
                            var now = Date.now();
                            if (now - lastPlayerTap < 350) {
                                e.preventDefault();
                                lastPlayerTap = 0;
                                if (window.AndroidBridge && window.AndroidBridge.onPlayerDoubleTap) {
                                    window.AndroidBridge.onPlayerDoubleTap();
                                }
                            } else {
                                lastPlayerTap = now;
                            }
                        }, { passive: false, capture: true });
                    }

                } catch(e) {
                    console.error('AdBlocker script error: ', e);
                }
            })();
        """.trimIndent()
    }
}
