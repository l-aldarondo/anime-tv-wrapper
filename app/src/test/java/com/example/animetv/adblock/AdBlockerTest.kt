package com.example.animetv.adblock

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AdBlockerTest {

    @Before
    fun setUp() {
        AdBlocker.blockedCount.set(0)
    }

    @Test
    fun testBlocksKnownAdDomains() {
        // Direct ad networks found on 9anime
        assertTrue(AdBlocker.isAd("https://gr.belchlipin.com/rop9lLf8fYLRDS0p/143751"))
        assertTrue(AdBlocker.isAd("https://sd.furudloof.com/rMXOajWlZHS0fTjc/95278"))
        assertTrue(AdBlocker.isAd("https://subduepaler.cyou/s/7d/7b/7d7b4bd720c7c70ed7f7fd0608dddb8a.svg"))

        // Pop-up & pop-under networks
        assertTrue(AdBlocker.isAd("https://adsterra.com/script.js"))
        assertTrue(AdBlocker.isAd("https://subdomain.propellerads.com/zone?id=123"))
        assertTrue(AdBlocker.isAd("https://popads.net/serve"))
        assertTrue(AdBlocker.isAd("https://syndication.exoclick.com/tag.php"))
        assertTrue(AdBlocker.isAd("https://alwingulla.com/push"))
        assertTrue(AdBlocker.isAd("https://highperformancecpmgate.com/traffic"))
        assertTrue(AdBlocker.isAd("https://googleads.g.doubleclick.net/pagead/ads"))
        assertTrue(AdBlocker.isAd("https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js"))
    }

    @Test
    fun testBlocksUnknownThirdPartyScripts() {
        // Unknown third-party scripts should be blocked
        assertTrue(AdBlocker.isAd("https://random-ad-rotator.xyz/loader.js"))
        assertTrue(AdBlocker.isAd("https://unknown-cdn-tracker.net/tracker.js?v=2"))
    }

    @Test
    fun testAllowsLegitimateAnimeUrls() {
        // Main anime domain and paths
        assertFalse(AdBlocker.isAd("https://9anime.or.at/"))
        assertFalse(AdBlocker.isAd("https://9anime.or.at/anime/solo-leveling/"))
        assertFalse(AdBlocker.isAd("https://9anime.or.at/episode/episode-1/"))
        assertFalse(AdBlocker.isAd("https://9anime.or.at/wp-content/themes/9animetv/style.css"))

        // Allowed video servers
        assertFalse(AdBlocker.isAd("https://my.1anime.site/play/fc49088eb7bb742fada2f00e10c3862b"))
        assertFalse(AdBlocker.isAd("https://my.1anime.site/stream/fc49088eb7bb742fada2f00e10c3862b"))
        assertFalse(AdBlocker.isAd("https://megacloud.tv/embed-2/e-1/abc123xyz"))
        assertFalse(AdBlocker.isAd("https://rapid-cloud.co/embed-6/xyz987"))

        // Legitimate CDN assets
        assertFalse(AdBlocker.isAd("https://cdnjs.cloudflare.com/ajax/libs/Swiper/5.4.5/css/swiper.min.css"))
        assertFalse(AdBlocker.isAd("https://fonts.googleapis.com/css?family=Inter"))
        assertFalse(AdBlocker.isAd("https://stackpath.bootstrapcdn.com/bootstrap/4.4.1/css/bootstrap.min.css"))
        assertFalse(AdBlocker.isAd("https://cdn.plyr.io/3.7.8/plyr.polyfilled.js"))
    }

    @Test
    fun testBlocksAppSchemeRedirects() {
        assertTrue(AdBlocker.isAd("intent://open.app#Intent;scheme=something;package=com.ad.malware;end"))
        assertTrue(AdBlocker.isAd("market://details?id=com.spam.app"))
        assertTrue(AdBlocker.isAd("whatsapp://send?text=spam"))
        assertTrue(AdBlocker.isAd("tg://resolve?domain=spam"))
    }

    @Test
    fun testCounterIncrementsOnBlock() {
        val initialCount = AdBlocker.blockedCount.get()
        AdBlocker.isAd("https://popads.net/test")
        AdBlocker.isAd("https://adsterra.com/banner")
        assertTrue(AdBlocker.blockedCount.get() == initialCount + 2)
    }

    @Test
    fun testAntiAdScriptsNotEmpty() {
        val css = AdBlocker.getAntiAdCss()
        assertTrue(css.contains("display: none !important"))
        assertTrue(css.contains(".animetv-fullscreen-wrap"))

        val js = AdBlocker.getAntiAdJs()
        assertTrue(js.contains("expandPlayerFullscreen"))
        assertTrue(js.contains("Object.defineProperty(window, 'open'"))
    }
}
