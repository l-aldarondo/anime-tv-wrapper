package com.example.animetv

import com.example.animetv.core.source.LaMovieSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LaMovieTest {

    @Test
    fun testSearch() = runBlocking {
        val source = LaMovieSource()
        val results = source.search("Deadpool")
        println("=== LAMOVIE SEARCH 'Deadpool' (${results.size} items) ===")
        results.take(5).forEach {
            println("ITEM: ${it.title} [${it.episodeBadge}] -> ${it.detailUrl}")
        }
        assertTrue("Search results for Deadpool should not be empty", results.isNotEmpty())
    }

    @Test
    fun testGetDetailAndResolve() = runBlocking {
        val source = LaMovieSource()
        val results = source.search("Deadpool")
        assertTrue("Search should return results", results.isNotEmpty())

        val movie = results.first()
        val detail = source.getAnimeDetail(movie.detailUrl)
        println("=== LAMOVIE DETAIL: ${detail.title} ===")
        println("Episodes count: ${detail.episodes.size}")
        assertTrue("Episodes should not be empty", detail.episodes.isNotEmpty())

        val firstEp = detail.episodes.first()
        val stream = source.resolveStream(firstEp.episodeUrl)
        println("=== RESOLVED STREAM ===")
        println("videoUrl: ${stream?.videoUrl}")
        println("isHls: ${stream?.isHls}")
        println("isEmbed: ${stream?.isEmbed}")
        println("serverName: ${stream?.serverName}")
        assertNotNull("Stream result should not be null", stream)
        assertTrue("Video URL should be valid http URL", stream!!.videoUrl.startsWith("http"))
    }

    @Test
    fun testResolveBackupStreamForSoloLatino() = runBlocking {
        val source = LaMovieSource()
        // SoloLatino TV episode URL format
        val soloLatinoEpUrl = "https://sololatino.net/serie/breaking-bad/temporada-1-episodio-1"
        val backupStream = source.resolveBackupStream(soloLatinoEpUrl)
        println("=== RESOLVED BACKUP STREAM FOR SOLOLATINO ===")
        println("URL: $soloLatinoEpUrl")
        println("videoUrl: ${backupStream?.videoUrl}")
        println("isHls: ${backupStream?.isHls}")
        println("isEmbed: ${backupStream?.isEmbed}")
        println("serverName: ${backupStream?.serverName}")
        assertNotNull("Backup stream should resolve for Breaking Bad S1E1", backupStream)
        assertTrue("Backup video URL should be valid", backupStream!!.videoUrl.startsWith("http"))
    }
}
