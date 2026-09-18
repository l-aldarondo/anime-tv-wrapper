package com.example.animetv.core.source

import com.example.animetv.core.model.AnimeCard
import com.example.animetv.core.model.AnimeDetail
import com.example.animetv.core.model.AnimeEpisode
import com.example.animetv.core.model.StreamResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class CinecalidadSource : AnimeSource {
    override val name: String = "Cinecalidad (Backup Latino)"
    override var baseUrl: String = "https://www.cinecalidad.ro"
    override val mirrors: List<String> = listOf(
        "https://www.cinecalidad.ro",
        "https://cinecalidad.ro",
        "https://v2.cinecalidad.vip"
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

    private fun fetchHtml(url: String): String {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Referer", baseUrl)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) "" else response.body?.string() ?: ""
            }
        } catch (e: Exception) { "" }
    }

    override suspend fun getTrending(): List<AnimeCard> = withContext(Dispatchers.IO) {
        val list = mutableListOf<AnimeCard>()
        val html = fetchHtml(baseUrl)
        if (html.isEmpty()) return@withContext list
        
        val doc = Jsoup.parse(html, baseUrl)
        val items = doc.select(".home_post_cont, .post_box, .contenedor-list, .home-post, .item")
        for (item in items) {
            val a = item.selectFirst("a") ?: continue
            val href = a.absUrl("href")
            val img = item.selectFirst("img")
            val posterUrl = img?.attr("data-src")?.ifEmpty { img.attr("src") } ?: ""
            val title = img?.attr("alt")?.trim()?.ifEmpty {
                item.selectFirst(".titulo, .in_title")?.text()?.trim()
            } ?: a.text().trim()
            
            if (title.isNotEmpty() && href.isNotEmpty()) {
                list.add(AnimeCard(
                    id = href,
                    title = title,
                    posterUrl = posterUrl,
                    detailUrl = href,
                    source = name,
                    episodeBadge = "Latino"
                ))
            }
        }
        list.distinctBy { it.detailUrl }
    }

    override suspend fun getRecentEpisodes(): List<AnimeCard> = getTrending()

    override suspend fun search(query: String): List<AnimeCard> = withContext(Dispatchers.IO) {
        val list = mutableListOf<AnimeCard>()
        val encoded = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$baseUrl/?s=$encoded"
        val html = fetchHtml(searchUrl)
        if (html.isEmpty()) return@withContext list
        
        val doc = Jsoup.parse(html, baseUrl)
        val items = doc.select(".home_post_cont, .post_box, .contenedor-list, .home-post, .item")
        for (item in items) {
            val a = item.selectFirst("a") ?: continue
            val href = a.absUrl("href")
            val img = item.selectFirst("img")
            val posterUrl = img?.attr("data-src")?.ifEmpty { img.attr("src") } ?: ""
            val title = img?.attr("alt")?.trim()?.ifEmpty {
                item.selectFirst(".titulo, .in_title")?.text()?.trim()
            } ?: a.text().trim()
            
            if (title.isNotEmpty() && href.isNotEmpty()) {
                list.add(AnimeCard(
                    id = href,
                    title = title,
                    posterUrl = posterUrl,
                    detailUrl = href,
                    source = name,
                    episodeBadge = "Latino"
                ))
            }
        }
        list.distinctBy { it.detailUrl }
    }

    override suspend fun getAnimeDetail(detailUrl: String): AnimeDetail = withContext(Dispatchers.IO) {
        val html = fetchHtml(detailUrl)
        val doc = Jsoup.parse(html, detailUrl)
        
        val title = doc.selectFirst("h1")?.text()?.trim() ?: "Cinecalidad Title"
        val synopsis = doc.selectFirst(".description, .entry-content p")?.text()?.trim() ?: ""
        val posterUrl = doc.selectFirst(".poster img")?.attr("src") ?: ""
        
        val episodes = listOf(AnimeEpisode(1, 1, "Película Completa", detailUrl))
        
        AnimeDetail(
            title = title,
            posterUrl = posterUrl,
            synopsis = synopsis,
            source = name,
            detailUrl = detailUrl,
            episodes = episodes
        )
    }

    override suspend fun resolveStream(episodeUrl: String): StreamResult? = withContext(Dispatchers.IO) {
        val html = fetchHtml(episodeUrl)
        if (html.isEmpty()) return@withContext null
        
        // Cinecalidad uses multiple servers (Vidoza, Upstream, etc.)
        // We look for iframes or script patterns
        val iframes = Regex("""<iframe[^>]*src=["']([^"']+)["']""").findAll(html)
        for (ifr in iframes) {
            val url = ifr.groupValues[1]
            if (url.contains("vidoza") || url.contains("upstream") || url.contains("filemoon")) {
                return@withContext StreamResult(
                    videoUrl = url,
                    isHls = false,
                    isEmbed = true,
                    serverName = "Cinecalidad Embed",
                    headers = mapOf("Referer" to baseUrl)
                )
            }
        }
        null
    }
}
