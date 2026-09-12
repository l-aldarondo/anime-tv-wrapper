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

class GogoAnimeSource : AnimeSource {
    override val name: String = "GogoAnime (Global Sub)"
    override val baseUrl: String = "https://gogoanime.by"

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

            val items = doc.select(".last_episodes li, .film_list-wrap .flw-item, .item, article")
            for (it in items) {
                val a = it.selectFirst("a") ?: continue
                val href = a.absUrl("href")
                val img = it.selectFirst("img")
                val posterUrl = img?.attr("src")?.ifEmpty { img.attr("data-src") } ?: ""
                val titleEl = it.selectFirst(".name, .title, a")
                var rawTitle = titleEl?.text()?.trim() ?: ""

                var epBadge = ""
                val epMatch = Regex("""Ep\s*(\d+)""", RegexOption.IGNORE_CASE).find(it.text())
                if (epMatch != null) {
                    epBadge = "Ep ${epMatch.groupValues[1]}"
                    rawTitle = rawTitle.replace(Regex("""Episode\s*\d+\s*(English Subbed)?""", RegexOption.IGNORE_CASE), "").trim()
                }

                if (rawTitle.isNotEmpty() && href.isNotEmpty()) {
                    list.add(
                        AnimeCard(
                            id = href,
                            title = rawTitle,
                            posterUrl = posterUrl,
                            detailUrl = href,
                            source = name,
                            episodeBadge = epBadge
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

            val items = doc.select(".last_episodes li, .film_list-wrap .flw-item, .item, article")
            for (it in items) {
                val a = it.selectFirst("a") ?: continue
                val href = a.absUrl("href")
                val img = it.selectFirst("img")
                val posterUrl = img?.attr("src")?.ifEmpty { img.attr("data-src") } ?: ""
                val titleEl = it.selectFirst(".name, .title, a")
                val title = titleEl?.text()?.trim() ?: ""

                if (title.isNotEmpty() && href.isNotEmpty()) {
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
            val html = fetchHtml(detailUrl)
            val doc = Jsoup.parse(html, detailUrl)

            val title = doc.selectFirst("h1, .entry-title, .title")?.text()?.trim() ?: "Anime"
            val synopsis = doc.selectFirst(".content-txt, .description, p")?.text()?.trim() ?: ""
            val img = doc.selectFirst(".anime_info_body_bg img, img")
            val posterUrl = img?.attr("src")?.ifEmpty { img.attr("data-src") } ?: ""

            val genres = doc.select(".type a").map { it.text().trim() }

            val episodes = mutableListOf<AnimeEpisode>()
            val epLinks = doc.select("#episode_related a, .anime_video_body ul li a, a[href*=-episode-]")

            for (link in epLinks) {
                val href = link.absUrl("href")
                val epText = link.text().trim()
                val epNumMatch = Regex("""episode-(\d+)""").find(href)
                val epNum = epNumMatch?.groupValues?.get(1)?.toIntOrNull() ?: episodes.size + 1

                episodes.add(
                    AnimeEpisode(
                        episodeNumber = epNum,
                        seasonNumber = 1,
                        title = if (epText.isNotEmpty() && epText.length < 50) epText else "Episodio $epNum",
                        episodeUrl = href
                    )
                )
            }

            // If it was a single episode page and no episode list found, add this episode
            if (episodes.isEmpty()) {
                val epNumMatch = Regex("""episode-(\d+)""").find(detailUrl)
                val epNum = epNumMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                episodes.add(
                    AnimeEpisode(
                        episodeNumber = epNum,
                        seasonNumber = 1,
                        title = "Episodio $epNum",
                        episodeUrl = detailUrl
                    )
                )
            }

            AnimeDetail(
                title = title,
                posterUrl = posterUrl,
                synopsis = synopsis,
                genres = genres,
                source = name,
                detailUrl = detailUrl,
                episodes = episodes.sortedBy { it.episodeNumber }
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

            // 1. Direct m3u8 in page
            val m3u8 = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(html)
            if (m3u8 != null) {
                return@withContext StreamResult(
                    videoUrl = m3u8.value,
                    isHls = true,
                    serverName = "GogoAnime Direct HLS"
                )
            }

            // 2. Look for iframe embed
            val iframes = Regex("""<iframe[^>]*src=["']([^"']+)["']""").findAll(html)
            for (ifr in iframes) {
                val ifrUrl = ifr.groupValues[1]
                if (ifrUrl.contains("stream") || ifrUrl.contains("player") || ifrUrl.contains("embed")) {
                    val ifrHtml = fetchHtml(ifrUrl, referer = episodeUrl)
                    val ifrM3u8 = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(ifrHtml)
                    if (ifrM3u8 != null) {
                        return@withContext StreamResult(
                            videoUrl = ifrM3u8.value,
                            isHls = true,
                            serverName = "Gogo Stream",
                            headers = mapOf("Referer" to ifrUrl, "User-Agent" to userAgent)
                        )
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        null
    }
}
