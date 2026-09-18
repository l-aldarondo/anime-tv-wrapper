package com.example.animetv

import com.example.animetv.core.torrent.TorrentSearchRepository
import com.example.animetv.core.torrent.TorrentStreamItem
import org.junit.Assert.*
import org.junit.Test

class TorrentFilterTest {

    @Test
    fun testParseSizeToBytes() {
        val bytes15Gb = TorrentSearchRepository.parseSizeToBytes("1.5 GB")
        val bytes6Gb = TorrentSearchRepository.parseSizeToBytes("6.2 GB")
        val bytes800Mb = TorrentSearchRepository.parseSizeToBytes("800 MB")

        println("1.5 GB = $bytes15Gb bytes")
        println("6.2 GB = $bytes6Gb bytes")
        println("800 MB = $bytes800Mb bytes")

        assertTrue(bytes15Gb > 1_500_000_000L)
        assertTrue(bytes6Gb > 6_000_000_000L)
        assertTrue(bytes800Mb in 800_000_000L..900_000_000L)
    }

    @Test
    fun testSizeFilterLogic() {
        val maxFileSizeGb = 1.5f
        val maxBytes = (maxFileSizeGb * 1024L * 1024L * 1024L).toLong()

        val items = listOf(
            TorrentStreamItem("Show S01E01 720p", "magnet:?xt=urn:btih:111", 50, 0L, "850 MB", "720p", "Latino", 1, "Torrentio"),
            TorrentStreamItem("Show S01E01 1080p", "magnet:?xt=urn:btih:222", 30, 0L, "1.4 GB", "1080p", "Latino", 1, "Torrentio"),
            TorrentStreamItem("Show S01E01 1080p Remux", "magnet:?xt=urn:btih:333", 20, 0L, "3.5 GB", "1080p", "Latino", 1, "Torrentio"),
            TorrentStreamItem("Show S01E01 4K HDR", "magnet:?xt=urn:btih:444", 15, 0L, "6.2 GB", "4K", "Latino", 1, "Torrentio"),
            TorrentStreamItem("Show S01E01 4K Remux", "magnet:?xt=urn:btih:555", 10, 0L, "9.8 GB", "4K", "Latino", 1, "Torrentio")
        )

        val filtered = items.filter { item ->
            val itemBytes = if (item.sizeBytes > 0L) item.sizeBytes else TorrentSearchRepository.parseSizeToBytes(item.sizeFormatted)
            !(itemBytes > 0L && itemBytes > maxBytes)
        }

        println("Original items: ${items.size}, Filtered items (<= 1.5 GB): ${filtered.size}")
        filtered.forEach { println("KEPT: ${it.title} (${it.sizeFormatted})") }

        assertEquals(2, filtered.size)
        assertTrue(filtered.any { it.sizeFormatted == "850 MB" })
        assertTrue(filtered.any { it.sizeFormatted == "1.4 GB" })
        assertFalse(filtered.any { it.sizeFormatted == "3.5 GB" })
        assertFalse(filtered.any { it.sizeFormatted == "6.2 GB" })
        assertFalse(filtered.any { it.sizeFormatted == "9.8 GB" })
    }
}
