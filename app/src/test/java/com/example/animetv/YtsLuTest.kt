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
        assertTrue("Episodes should have 1 item", detail.episodes.isNotEmpty())
    }
}
