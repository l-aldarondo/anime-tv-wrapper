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
        val streams = source.resolveAllStreams(first.detailUrl)
        println("RESOLVE ALL STREAMS (${streams.size}):")
        streams.forEach { println("STREAM: ${it.serverName} -> ${it.videoUrl}") }
        assertTrue("Should resolve streams for Cinecalidad movie", streams.isNotEmpty())
    }
}
