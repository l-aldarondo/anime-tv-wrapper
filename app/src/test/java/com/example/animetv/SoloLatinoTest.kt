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
}
