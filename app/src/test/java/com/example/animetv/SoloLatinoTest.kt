package com.example.animetv

import com.example.animetv.core.source.SoloLatinoStreamResolver
import org.junit.Test

class SoloLatinoTest {
    @Test
    fun testEpisode8() {
        val url = "https://sololatino.net/serie/hora-de-aventura/temporada-1/episodio-8"
        println("=== TESTING RESOLVE FOR: $url ===")
        val result = SoloLatinoStreamResolver.resolve(url)
        println("=== RESULT: $result ===")
        org.junit.Assert.assertNotNull("StreamResult should not be null", result)
        org.junit.Assert.assertTrue("videoUrl should be .m3u8", result!!.videoUrl.contains(".m3u8"))
    }

    @Test
    fun testAdventureTimeDetail() = kotlinx.coroutines.runBlocking {
        val s = com.example.animetv.core.source.SoloLatinoSource()
        val d = s.getAnimeDetail("https://sololatino.net/serie/hora-de-aventura")
        println("=== ADVENTURE TIME DETAIL ===")
        println("Title: ${d.title}, tmdbId: ${d.tmdbId}, tmdbMediaType: ${d.tmdbMediaType}")
        println("Episodes count: ${d.episodes.size}")
        val seasonGroups = d.episodes.groupBy { it.seasonNumber }
        println("Seasons found in scraper: ${seasonGroups.keys.sorted()}")
        seasonGroups.forEach { (sNum, eps) ->
            println("Season $sNum has ${eps.size} eps. First: ${eps.firstOrNull()?.title} (${eps.firstOrNull()?.episodeUrl}), Last: ${eps.lastOrNull()?.title}")
        }
    }

    @Test
    fun testSearch() = kotlinx.coroutines.runBlocking {
        val s = com.example.animetv.core.source.SoloLatinoSource()
        val results = s.search("Parecido a un asesinato")
        println("=== SEARCH RESULTS: size=${results.size} ===")
        results.forEach { println("RESULT: ${it.title} -> ${it.detailUrl}") }
    }

    @Test
    fun testMovieResolve() {
        val url = "https://sololatino.net/pelicula/parecido-a-un-asesinato"
        println("=== TESTING MOVIE RESOLVE: $url ===")

        val result = SoloLatinoStreamResolver.resolve(url)
        println("=== MOVIE RESULT: $result ===")
        org.junit.Assert.assertNotNull("StreamResult for movie should not be null", result)
    }

    @Test
    fun testGetHomeSections() = kotlinx.coroutines.runBlocking {
        val s = com.example.animetv.core.source.SoloLatinoSource()
        val sections = s.getHomeSections()
        println("=== RETRIEVED ${sections.size} HOME SECTIONS ===")
        for (sec in sections) {
            println("SECTION: '${sec.title}' -> ${sec.cards.size} items (First: ${sec.cards.firstOrNull()?.title})")
        }
        org.junit.Assert.assertTrue("Should find multiple rows", sections.isNotEmpty())
        org.junit.Assert.assertFalse("Should not contain animes", sections.any { it.title.contains("anime", ignoreCase = true) })
    }

    @Test
    fun testTrailerExtract() = kotlinx.coroutines.runBlocking {
        val client = okhttp3.OkHttpClient()
        val html = client.newCall(
            okhttp3.Request.Builder()
                .url("https://sololatino.net/pelicula/parecido-a-un-asesinato")
                .header("User-Agent", "Mozilla/5.0")
                .build()
        ).execute().body?.string() ?: ""
        val doc = org.jsoup.Jsoup.parse(html)
        val trailerId = doc.selectFirst("[data-trailer]")?.attr("data-trailer")?.trim() ?: ""
        println("=== EXTRACTED TRAILER ID: $trailerId ===")
        org.junit.Assert.assertTrue("Should have trailer ID", trailerId.isNotEmpty())
    }
}


