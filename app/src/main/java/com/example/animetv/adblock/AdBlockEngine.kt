package com.example.animetv.adblock

import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.WebResourceResponse
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader

/**
 * Ultra-fast, lightweight in-memory Ad, Pop-up, and Tracker blocker for Android System WebView.
 * Intercepts requests inside main frames and cross-origin iframes before network dispatch.
 */
object AdBlockEngine {

    private const val TAG = "AdBlockEngine"

    // Empty WebResourceResponse returned to instantly terminate blocked requests
    val EMPTY_RESPONSE: WebResourceResponse
        get() = WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))

    // High-priority blocked domains targeting streaming ad networks, pop-unders, and trackers
    private val BLOCKED_DOMAINS: HashSet<String> = hashSetOf(
        // Streaming & Video Ad Injection Networks
        "adsterra.com",
        "monetag.com",
        "propellerads.com",
        "popads.net",
        "popcash.net",
        "exoclick.com",
        "juicyads.com",
        "trafficjunky.com",
        "cpmstar.com",
        "bidvertiser.com",
        "yllix.com",
        "revenuehits.com",
        "evadav.com",
        "richpush.com",
        "clickadu.com",
        "zeroredirect.com",
        "adcash.com",
        "hilltopads.com",
        "mgid.com",
        "taboola.com",
        "outbrain.com",
        "doubleclick.net",
        "googleadservices.com",
        "googlesyndication.com",
        "google-analytics.com",
        "scorecardresearch.com",
        "quantserve.com",
        "criteo.com",
        "pubmatic.com",
        "rubiconproject.com",
        "casalemedia.com",
        "openx.net",
        "appnexus.com",
        "adnxs.com",
        "advertising.com",
        "serving-sys.com",
        "smartadserver.com",
        "disqus.com",
        "histats.com",
        "yandex.ru",
        "mc.yandex.ru",

        // Known anime site ad-networks and redirect domains
        "furudloof.com",
        "belchlipin.com",
        "subduepaler.com",
        "buildsstate.com",
        "oxserver.com",
        "excavatenearbywand.com",
        "cloudwindow-route.com",
        "desu.sh",
        "snapcdn.top",
        "bysesukior.com",
        "bysedikamoum.com",
        "minochinos.com",
        "ghbrisk.com",
        "audinifer.com",
        "f7hyg4q.org",
        "morencius.com",
        "anura.io",
        "clarium.global",
        "ad-delivery.net",
        "trafficstars.com",
        "ero-advertising.com",
        "realsrv.com",
        "onclickpredictiv.com",
        "creativecdn.com",
        "adsupply.com",
        "deloton.com",
        "onclickperformance.com",
        "onclkds.com",
        "pushwho.com",
        "tsyndicate.com",
        "adtng.com",
        "directrev.com",
        "popmyads.com",
        "zergnet.com",
        "revcontent.com",
        "infolinks.com",
        "adthrive.com",
        "media.net",
        "mediavine.com",
        "buysellads.com",
        "chitika.com",
        "adblade.com",
        "exponential.com",
        "tribalfusion.com",
        "sovrn.com",
        "yieldmo.com",
        "lijit.com",
        "teads.tv",
        "connatix.com",
        "undertone.com",
        "sharethrough.com",
        "spotxchange.com",
        "contextweb.com",
        "smartclip.net",
        "tremorhub.com",
        "inmobi.com",
        "chartbeat.com",
        "hotjar.com",
        "crazyegg.com",
        "optimizely.com",
        "segment.io",
        "statcounter.com",
        "clicky.com",
        "inspectlet.com",
        "luckyorange.com",
        "mouseflow.com",
        "clarity.ms",

        // Cryptominers & Malware bait
        "coinhive.com",
        "coin-hive.com",
        "crypto-loot.com",
        "authedmine.com",
        "cryptoloot.pro",
        "jsecoin.com",
        "webminepool.com",
        "webminerpool.com",
        "minr.pw"
    )

    // Substrings in URLs that definitively indicate an ad script or popunder
    private val BLOCKED_PATH_PATTERNS = listOf(
        "/popads",
        "/popcash",
        "/popunder",
        "/adsterra",
        "/monetag",
        "/propeller",
        "/vast.xml",
        "/vast?",
        "/vpaid",
        "antiadblock",
        "ad-provider",
        "/ad_tag",
        "/ads.js",
        "/ad.js",
        "/advert.js",
        "/adserver",
        "/banner_ads",
        "googleads",
        "pagead/js",
        "disqus.com/embed.js",
        "furudloof",
        "belchlipin",
        "subduepaler",
        "excavatenearbywand"
    )

    fun initialize(context: Context) {
        // Asynchronously load additional domains from assets if available
        Thread {
            try {
                context.assets.open("adblock_domains.txt").use { inputStream ->
                    BufferedReader(InputStreamReader(inputStream)).useLines { lines ->
                        var count = 0
                        lines.forEach { rawLine ->
                            val line = rawLine.trim().lowercase()
                            if (line.isNotEmpty() && !line.startsWith("#")) {
                                BLOCKED_DOMAINS.add(line)
                                count++
                            }
                        }
                        Log.i(TAG, "AdBlockEngine initialized with $count extra domains. Total: ${BLOCKED_DOMAINS.size}")
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "No extra adblock_domains.txt found; using built-in high-performance blocklist (${BLOCKED_DOMAINS.size} domains).")
            }
        }.start()
    }

    /**
     * Checks whether an HTTP/HTTPS URL should be blocked.
     */
    fun shouldBlock(url: String?): Boolean {
        if (url.isNullOrEmpty()) return false
        val uri = try {
            Uri.parse(url)
        } catch (e: Exception) {
            return false
        }

        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") {
            // Allow about:, data:, blob:, file:, etc.
            return false
        }

        val host = uri.host?.lowercase() ?: return false

        // Fast host matching with subdomain stripping (e.g. s1.ads.monetag.com -> monetag.com)
        if (isHostBlocked(host)) {
            Log.d(TAG, "BLOCKED (Host): $host -> $url")
            return true
        }

        // Fast path pattern matching
        val lowerUrl = url.lowercase()
        for (pattern in BLOCKED_PATH_PATTERNS) {
            if (lowerUrl.contains(pattern)) {
                Log.d(TAG, "BLOCKED (Pattern: $pattern): $url")
                return true
            }
        }

        return false
    }

    private fun isHostBlocked(host: String): Boolean {
        if (BLOCKED_DOMAINS.contains(host)) return true

        // Check parent domains (e.g., cdn.banner.monetag.com -> banner.monetag.com -> monetag.com)
        var dotIndex = host.indexOf('.')
        while (dotIndex != -1) {
            val parent = host.substring(dotIndex + 1)
            if (parent.isEmpty() || !parent.contains('.')) break
            if (BLOCKED_DOMAINS.contains(parent)) return true
            dotIndex = host.indexOf('.', dotIndex + 1)
        }
        return false
    }
}
