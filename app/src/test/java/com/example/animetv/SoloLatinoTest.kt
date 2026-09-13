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
    fun testSearchHiddenMurder() = kotlinx.coroutines.runBlocking {
        println("=== TESTING SEARCH HIDDEN MURDER ===")
        val results = com.example.animetv.core.CatalogRepository.searchAll("Hidden Murder")
        println("=== SEARCH ALL (Hidden Murder): size=${results.size} ===")
        results.forEach { println("RESULT: ${it.title} (${it.source}) -> ${it.detailUrl}") }
    }
}

