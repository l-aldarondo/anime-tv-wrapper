package com.example.animetv

import com.example.animetv.core.source.YtsLuSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtsLuTest {

    @Test
    fun testGetTrending() = runBlocking {
        val source = YtsLuSource()
        val trending = source.getTrending()
        println("=== YTS TRENDING: ${trending.size} movies ===")
        trending.take(5).forEach {
            println("Movie: ${it.title} | Rating: ${it.rating} | URL: ${it.detailUrl}")
        }
        assertNotNull(trending)
        assertTrue("Trending should not be empty", trending.isNotEmpty())
    }

    @Test
    fun testSearch() = runBlocking {
        val source = YtsLuSource()
        val results = source.search("Deadpool")
        println("=== YTS SEARCH 'Deadpool': ${results.size} results ===")
        results.take(5).forEach {
            println("Result: ${it.title} | Source: ${it.source} | URL: ${it.detailUrl}")
        }
        assertNotNull(results)
        assertTrue("Search should return results", results.isNotEmpty())
    }

    @Test
    fun testGetMovieDetail() = runBlocking {
        val source = YtsLuSource()
        // TMDB ID 533535 = Deadpool & Wolverine
        val detail = source.getAnimeDetail("https://en.yts.lu/movie/533535")
        println("=== YTS MOVIE DETAIL ===")
        println("Title: ${detail.title}")
        println("Poster: ${detail.posterUrl}")
        println("Episodes: ${detail.episodes.size}")
        assertNotNull(detail)
        assertTrue("Title should contain Deadpool", detail.title.contains("Deadpool", ignoreCase = true))
    }

    @Test
    fun testAdventureTimeDoesNotMatchFionnaAndCake() = runBlocking {
        val source = YtsLuSource()
        // Adventure Time original series (TMDB ID 15260, year 2010)
        val stream = source.resolveStream("https://en.yts.lu/tv/15260/1/1?title=Adventure+Time&year=2010")
        println("=== ADVENTURE TIME S01E01 STREAM ===")
        println("Server: ${stream?.serverName}")
        println("URL: ${stream?.videoUrl}")
        assertNotNull(stream)
        val server = stream?.serverName ?: ""
        assertFalse("Server title or torrent must NOT match Fionna and Cake", server.contains("Fionna", ignoreCase = true))
    }

    @Test
    fun testSearchTorrentsYts() = runBlocking {
        // Test with Spanish query and English query fallback
        val results = com.example.animetv.core.torrent.TorrentSearchRepository.searchTorrentsDirectForTest(
            query = "Hora de Aventura",
            originalQuery = "",
            englishQuery = "Adventure Time",
            seasonNumber = 1,
            episodeNumber = 1,
            isMovie = false,
            year = "2010"
        )
        println("=== TORRENT SEARCH 'Hora de Aventura' -> 'Adventure Time': ${results.size} items ===")
        results.take(10).forEach {
            println("Torrent: [${it.provider}] ${it.title} | ${it.resolutionBadge} | ${it.languageBadge} | seeds=${it.seeders}")
        }
        assertTrue("Should return YTS torrents when englishQuery is provided", results.isNotEmpty())
    }
}
