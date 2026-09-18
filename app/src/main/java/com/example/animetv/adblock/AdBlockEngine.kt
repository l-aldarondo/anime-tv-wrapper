package com.example.animetv.adblock

import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.WebResourceResponse
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader

/**
 * Ultra-fast AdBlocker merged with Cinecalidad/EliteTorrent protections.
 * Designed to block intrusive ads while protecting media stream stability.
 */
object AdBlockEngine {

    private const val TAG = "AdBlockEngine"

    val EMPTY_RESPONSE: WebResourceResponse
        get() = WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))

    private val BLOCKED_DOMAINS: HashSet<String> = hashSetOf(
        "adsterra.com", "monetag.com", "propellerads.com", "popads.net", "popcash.net",
        "exoclick.com", "juicyads.com", "trafficjunky.com", "adcash.com", "hilltopads.com",
        "doubleclick.net", "googleadservices.com", "googlesyndication.com",
        "cinecalidad.run", "fastget.xyz", "super-links.xyz", "relink.to", "shorte.st",
        "torrenthire.com", "trackers.best", "clarium.global", "onclickpredictiv.com",
        "deloton.com", "osarcotypes.com", "etaargon.com", "subduepaler.cyou"
    )

    private val BLOCKED_PATH_PATTERNS = listOf(
        "/popads", "/popunder", "/adsterra", "/vast.xml", "/vast?", "antiadblock",
        "/ads.js", "googleads", "/invoke", "/mtn/", "bit.ly/cc-ads", "short.ly"
    )

    fun initialize(context: Context) {
        Thread {
            try {
                context.assets.open("adblock_domains.txt").use { inputStream ->
                    BufferedReader(InputStreamReader(inputStream)).useLines { lines ->
                        lines.forEach { line ->
                            val clean = line.trim().lowercase()
                            if (clean.isNotEmpty() && !clean.startsWith("#")) {
                                BLOCKED_DOMAINS.add(clean)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Using built-in blocklist.")
            }
        }.start()
    }

    fun shouldBlock(url: String?): Boolean {
        if (url.isNullOrEmpty()) return false
        
        // NEVER block media segments or magnets
        if (url.contains(".m3u8") || url.contains(".ts") || url.contains(".mp4") || url.startsWith("magnet:")) {
            return false
        }

        val uri = try { Uri.parse(url) } catch (e: Exception) { return false }
        val host = uri.host?.lowercase() ?: return false

        // Safety for essential services
        if (host.endsWith("google.com") || host.endsWith("cloudflare.com") || host.contains("youtube")) {
            return false
        }

        if (BLOCKED_DOMAINS.any { host == it || host.endsWith(".$it") }) {
            Log.d(TAG, "BLOCKED (Host): $host")
            return true
        }

        val lowerUrl = url.lowercase()
        return BLOCKED_PATH_PATTERNS.any { lowerUrl.contains(it) }
    }
}
