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

class AnimeYTSource : AnimeSource {
    override val name: String = "AnimeYT (Español)"
    override val baseUrl: String = "https://animeyt.cc"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

    private fun fetchHtml(url: String, referer: String = baseUrl): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Referer", referer)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return ""
            return response.body?.string() ?: ""
        }
    }

    override suspend fun getTrending(): List<AnimeCard> = withContext(Dispatchers.IO) {
        val list = mutableListOf<AnimeCard>()
        try {
            val html = fetchHtml(baseUrl)
            if (html.isEmpty()) return@withContext list
            val doc = Jsoup.parse(html, baseUrl)

            val items = doc.select("article, .flw-item, .film-item, .item")
            for (it in items) {
                val a = it.selectFirst("a") ?: continue
                val href = a.absUrl("href")
                val img = it.selectFirst("img")
                val posterUrl = img?.attr("data-src")?.ifEmpty { img.attr("src") } ?: ""
                val titleEl = it.selectFirst(".title, h2, h3, .name")
                val title = titleEl?.text()?.trim() ?: (img?.attr("alt")?.trim() ?: "")

                val badgeEl = it.selectFirst(".badge, .ep-status, .episode, .quality")
                val badge = badgeEl?.text()?.trim() ?: "Anime"

                if (title.isNotEmpty() && href.isNotEmpty() && !href.contains("/temporada/")) {
                    list.add(
                        AnimeCard(
                            id = href,
                            title = title,
                            posterUrl = posterUrl,
                            detailUrl = href,
                            source = name,
                            episodeBadge = badge
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        list.distinctBy { it.detailUrl }
    }

    override suspend fun getRecentEpisodes(): List<AnimeCard> = getTrending()

    override suspend fun search(query: String): List<AnimeCard> = withContext(Dispatchers.IO) {
        val list = mutableListOf<AnimeCard>()
        try {
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            val searchUrl = "$baseUrl/?s=$encoded"
            val html = fetchHtml(searchUrl)
            if (html.isEmpty()) return@withContext list
            val doc = Jsoup.parse(html, baseUrl)

            val items = doc.select("article, .flw-item, .film-item, .item")
            for (it in items) {
                val a = it.selectFirst("a") ?: continue
                val href = a.absUrl("href")
                val img = it.selectFirst("img")
                val posterUrl = img?.attr("data-src")?.ifEmpty { img.attr("src") } ?: ""
                val titleEl = it.selectFirst(".title, h2, h3, .name")
                val title = titleEl?.text()?.trim() ?: (img?.attr("alt")?.trim() ?: "")

                if (title.isNotEmpty() && href.isNotEmpty() && !href.contains("/temporada/")) {
                    list.add(
                        AnimeCard(
                            id = href,
                            title = title,
                            posterUrl = posterUrl,
                            detailUrl = href,
                            source = name
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        list.distinctBy { it.detailUrl }
    }

    override suspend fun getAnimeDetail(detailUrl: String): AnimeDetail = withContext(Dispatchers.IO) {
        try {
            var pageUrl = detailUrl
            var html = fetchHtml(pageUrl)
            var doc = Jsoup.parse(html, pageUrl)

            // If detailUrl is an episode URL (e.g. /115068/anime/slug-capitulo-11/), navigate to the series page (/tv/slug/)
            if (pageUrl.contains("/anime/") || pageUrl.contains("-capitulo-")) {
                val seriesLink = doc.select("a[href*=/tv/]").firstOrNull { 
                    val h = it.attr("href")
                    h.contains("/tv/") && !h.endsWith("/tv/") && !h.endsWith("/tv")
                }?.absUrl("href")?.substringBefore("#")

                if (!seriesLink.isNullOrEmpty()) {
                    val seriesHtml = fetchHtml(seriesLink)
                    if (seriesHtml.isNotEmpty()) {
                        pageUrl = seriesLink
                        html = seriesHtml
                        doc = Jsoup.parse(seriesHtml, seriesLink)
                    }
                }
            }

            val title = doc.selectFirst("h1, .entry-title, .title")?.text()?.trim() ?: "Anime"
            val synopsis = doc.selectFirst(".sinopsis, .overview, .entry-content p, .description")?.text()?.trim() ?: ""
            val img = doc.selectFirst(".poster img, .cover img, img")
            val posterUrl = img?.attr("data-src")?.ifEmpty { img.attr("src") } ?: ""

            val genres = doc.select(".genres a, .genre a").map { it.text().trim() }

            val episodes = mutableListOf<AnimeEpisode>()
            val epLinks = doc.select("a[href*=/anime/], a[href*=/ver/], a[href*=-episodio-], a[href*=-capitulo-]")

            for (link in epLinks) {
                val href = link.absUrl("href")
                if (!href.contains("-capitulo-") && !href.contains("-episodio-") && !href.contains("/anime/")) continue
                val epNumMatch = Regex("""(?:episodio|capitulo)-?(\d+)""", RegexOption.IGNORE_CASE).find(href)
                val epNum = epNumMatch?.groupValues?.get(1)?.toIntOrNull() ?: (episodes.size + 1)

                episodes.add(
                    AnimeEpisode(
                        episodeNumber = epNum,
                        seasonNumber = 1,
                        title = "Episodio $epNum",
                        episodeUrl = href
                    )
                )
            }

            val uniqueEpisodes = episodes.distinctBy { it.episodeUrl }.sortedBy { it.episodeNumber }

            AnimeDetail(
                title = title,
                posterUrl = posterUrl,
                synopsis = synopsis,
                genres = genres,
                source = name,
                detailUrl = pageUrl,
                episodes = if (uniqueEpisodes.isNotEmpty()) uniqueEpisodes else listOf(AnimeEpisode(1, 1, "Episodio 1", detailUrl))
            )
        } catch (e: Exception) {
            e.printStackTrace()
            AnimeDetail(
                title = "Anime",
                posterUrl = "",
                synopsis = "Sin información disponible.",
                source = name,
                detailUrl = detailUrl,
                episodes = emptyList()
            )
        }
    }

    override suspend fun resolveStream(episodeUrl: String): StreamResult? = withContext(Dispatchers.IO) {
        try {
            val html = fetchHtml(episodeUrl)
            if (html.isEmpty()) return@withContext null

            // Direct m3u8
            val m3u8 = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(html)
            if (m3u8 != null) {
                return@withContext StreamResult(
                    videoUrl = m3u8.value,
                    isHls = true,
                    serverName = "AnimeYT Direct HLS"
                )
            }

            // Look for iframe
            val iframes = Regex("""<iframe[^>]*src=["']([^"']+)["']""").findAll(html)
            for (ifr in iframes) {
                val ifrUrl = ifr.groupValues[1]
                if (ifrUrl.contains("stream") || ifrUrl.contains("player") || ifrUrl.contains("embed")) {
                    return@withContext StreamResult(
                        videoUrl = ifrUrl,
                        isHls = false,
                        isEmbed = true,
                        serverName = "AnimeYT Player Embed",
                        headers = mapOf("Referer" to episodeUrl, "User-Agent" to userAgent)
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        null
    }
}
