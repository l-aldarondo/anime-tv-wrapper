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

class NineAnimeSource : AnimeSource {
    override val name: String = "9Anime (Global HD)"
    override val baseUrl: String = "https://9anime.or.at"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

    private fun fetchHtml(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Referer", baseUrl)
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

            val items = doc.select(".film-poster, .item, .flw-item, article")
            for (it in items) {
                val a = it.selectFirst("a") ?: continue
                val href = a.absUrl("href")
                val img = it.selectFirst("img")
                val posterUrl = img?.attr("src")?.ifEmpty { img.attr("data-src") } ?: ""
                val title = img?.attr("alt")?.trim()?.ifEmpty { a.attr("title").trim() } ?: ""

                if (title.isNotEmpty() && href.isNotEmpty() && !href.endsWith("/genre/") && !href.endsWith("/wp-admin/")) {
                    list.add(
                        AnimeCard(
                            id = href,
                            title = title,
                            posterUrl = posterUrl,
                            detailUrl = href,
                            source = name,
                            episodeBadge = "Sub/Dub"
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        list.distinctBy { it.detailUrl }
    }

    override suspend fun getRecentEpisodes(): List<AnimeCard> = withContext(Dispatchers.IO) {
        getTrending()
    }

    override suspend fun search(query: String): List<AnimeCard> = withContext(Dispatchers.IO) {
        val list = mutableListOf<AnimeCard>()
        try {
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            val searchUrl = "$baseUrl/?s=$encoded"
            val html = fetchHtml(searchUrl)
            if (html.isEmpty()) return@withContext list
            val doc = Jsoup.parse(html, baseUrl)

            val items = doc.select(".film-poster, .item, .flw-item, article")
            for (it in items) {
                val a = it.selectFirst("a") ?: continue
                val href = a.absUrl("href")
                val img = it.selectFirst("img")
                val posterUrl = img?.attr("src")?.ifEmpty { img.attr("data-src") } ?: ""
                val title = img?.attr("alt")?.trim()?.ifEmpty { a.attr("title").trim() } ?: ""

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

            val title = doc.selectFirst("h1, .entry-title")?.text()?.trim() ?: "Anime"
            val rawSynopsis = doc.selectFirst(".description, .entry-content p, .synopsis, .film-description")?.text()?.trim()
                ?.ifEmpty { null }
                ?: doc.selectFirst("meta[property=og:description], meta[name=description]")?.attr("content")?.trim()
                ?: ""
            val synopsis = android.text.Html.fromHtml(rawSynopsis, android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim()
            val img = doc.selectFirst(".poster img, .cover img, img")
            val posterUrl = img?.attr("src")?.ifEmpty { img.attr("data-src") } ?: ""

            val genres = doc.select(".genre a, .genres a").map { it.text().trim() }

            val episodes = mutableListOf<AnimeEpisode>()

            // 1. Check for data-series ID to fetch episodes dynamically from 9Anime API
            val seriesIdMatch = Regex("""data-series=["'](\d+)["']""").find(html)
            val seriesId = seriesIdMatch?.groupValues?.get(1)

            if (!seriesId.isNullOrEmpty()) {
                try {
                    val apiUrl = "$baseUrl/wp-json/9animetv/v1/episodes/$seriesId?active=0"
                    val apiJson = fetchHtml(apiUrl)
                    if (apiJson.isNotEmpty()) {
                        val jsonObj = org.json.JSONObject(apiJson)
                        val pagesHtml = jsonObj.optString("pages", "")
                        if (pagesHtml.isNotEmpty()) {
                            val epDoc = Jsoup.parse(pagesHtml, baseUrl)
                            val epAnchors = epDoc.select("a[href]")
                            for (anchor in epAnchors) {
                                val href = anchor.absUrl("href")
                                val dataNum = anchor.attr("data-number").toIntOrNull()
                                val numMatch = Regex("""episode-(\d+)""").find(href)
                                val epNum = dataNum ?: (numMatch?.groupValues?.get(1)?.toIntOrNull() ?: (episodes.size + 1))
                                val rawTitle = anchor.attr("title").trim()
                                val epTitle = if (rawTitle.isNotEmpty()) rawTitle else "Episode $epNum"

                                val seasonNumMatch = Regex("""(?:season|temporada)-?(\d+)""", RegexOption.IGNORE_CASE).find(href)
                                    ?: Regex("""(?:season|temporada)\s*(\d+)""", RegexOption.IGNORE_CASE).find(rawTitle)
                                val seasonNum = seasonNumMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1

                                episodes.add(
                                    AnimeEpisode(
                                        episodeNumber = epNum,
                                        seasonNumber = seasonNum,
                                        title = epTitle,
                                        episodeUrl = href
                                    )
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            // 2. Fallback: static episode links in page
            if (episodes.isEmpty()) {
                val epLinks = doc.select("a[href*=-episode-], a[href*=/episode/]")
                for (link in epLinks) {
                    val href = link.absUrl("href")
                    val text = link.text().trim()
                    val numMatch = Regex("""episode-(\d+)""").find(href)
                    val epNum = numMatch?.groupValues?.get(1)?.toIntOrNull() ?: (episodes.size + 1)
                    val epTitle = if (text.isNotEmpty() && text.length < 60) text else "Episode $epNum"

                    val seasonNumMatch = Regex("""(?:season|temporada)-?(\d+)""", RegexOption.IGNORE_CASE).find(href)
                        ?: Regex("""(?:season|temporada)\s*(\d+)""", RegexOption.IGNORE_CASE).find(text)
                    val seasonNum = seasonNumMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1

                    episodes.add(
                        AnimeEpisode(
                            episodeNumber = epNum,
                            seasonNumber = seasonNum,
                            title = epTitle,
                            episodeUrl = href
                        )
                    )
                }
            }

            if (episodes.isEmpty()) {
                // If the detailUrl is already an episode URL, provide itself
                episodes.add(
                    AnimeEpisode(
                        episodeNumber = 1,
                        seasonNumber = 1,
                        title = title,
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
                episodes = episodes.distinctBy { it.episodeUrl }.sortedBy { it.episodeNumber }
            )
        } catch (e: Exception) {
            e.printStackTrace()
            AnimeDetail(
                title = "Anime",
                posterUrl = "",
                synopsis = "No se pudo cargar la información.",
                source = name,
                detailUrl = detailUrl,
                episodes = listOf(AnimeEpisode(1, 1, "Reproducir Episodio", detailUrl))
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
                    serverName = "9Anime Direct HLS"
                )
            }

            // 2. Fetch servers via 9anime AJAX endpoint: /ajax/episode/servers?id=$epId
            val epIdMatch = Regex("""episodeId\s*:\s*(\d+)""").find(html)
                ?: Regex("""data-id=["'](\d+)["']""").find(html)
                ?: Regex("""data-ep-id=["'](\d+)["']""").find(html)

            val epId = epIdMatch?.groupValues?.get(1)
            if (!epId.isNullOrEmpty()) {
                try {
                    val ajaxUrl = "$baseUrl/ajax/episode/servers?id=$epId"
                    val request = Request.Builder()
                        .url(ajaxUrl)
                        .header("User-Agent", userAgent)
                        .header("Referer", episodeUrl)
                        .header("X-Requested-With", "XMLHttpRequest")
                        .build()

                    client.newCall(request).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val jsonStr = resp.body?.string() ?: ""
                            val json = org.json.JSONObject(jsonStr)
                            val serversHtml = json.optString("html", "")
                            val embedMatches = Regex("""data-embed=["']([^"']+)["']""").findAll(serversHtml)
                            for (em in embedMatches) {
                                val b64 = em.groupValues[1]
                                try {
                                    val decodedUrl = String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT)).trim()
                                    if (decodedUrl.startsWith("http")) {
                                        return@withContext StreamResult(
                                            videoUrl = decodedUrl,
                                            isHls = decodedUrl.contains(".m3u8"),
                                            isEmbed = true,
                                            serverName = "9Anime Player Embed",
                                            headers = mapOf("Referer" to baseUrl, "User-Agent" to userAgent)
                                        )
                                    }
                                } catch (e: Exception) {}
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            // 3. Look for iframe player in page
            val iframes = Regex("""<iframe[^>]*src=["']([^"']+)["']""").findAll(html)
            for (ifr in iframes) {
                val url = ifr.groupValues[1]
                if (url.isNotEmpty() && !url.contains("google") && !url.contains("facebook")) {
                    return@withContext StreamResult(
                        videoUrl = url,
                        isHls = url.contains(".m3u8"),
                        isEmbed = true,
                        serverName = "9Anime Embed",
                        headers = mapOf("Referer" to episodeUrl, "User-Agent" to userAgent)
                    )
                }
            }

            // Fallback: Return episode URL for clean embed rendering in PlayerActivity
            return@withContext StreamResult(
                videoUrl = episodeUrl,
                isHls = false,
                isEmbed = true,
                serverName = "9Anime Web Player",
                headers = mapOf("Referer" to baseUrl)
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
        null
    }
}
