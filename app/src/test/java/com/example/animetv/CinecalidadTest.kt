package com.example.animetv

import com.example.animetv.core.source.CinecalidadSource
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*

class CinecalidadTest {

    @Test
    fun testTrending() = runBlocking {
        val source = CinecalidadSource()
        val trending = source.getTrending()
        println("=== CINECALIDAD TRENDING (${trending.size} items) ===")
        trending.take(5).forEach {
            println("ITEM: ${it.title} -> ${it.detailUrl}")
        }
        assertTrue("Trending should not be empty", trending.isNotEmpty())
    }

    @Test
    fun testSearchAndResolve() = runBlocking {
        val source = CinecalidadSource()
        val searchResults = source.search("Deadpool")
        println("=== CINECALIDAD SEARCH 'Deadpool' (${searchResults.size} items) ===")
        searchResults.take(5).forEach {
            println("SEARCH RESULT: ${it.title} -> ${it.detailUrl}")
        }
        assertTrue("Search should return results for Deadpool", searchResults.isNotEmpty())

        val first = searchResults.first()
        val stream = source.resolveStream(first.detailUrl)
        println("RESOLVE STREAM RESULT: $stream")
        assertNotNull("StreamResult should not be null", stream)
        assertTrue("Stream videoUrl should be valid", stream!!.videoUrl.startsWith("http"))
    }
}
