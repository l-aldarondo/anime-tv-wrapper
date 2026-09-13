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
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class JKAnimeSource : AnimeSource {
    override val name: String = "JKAnime"
    override val baseUrl: String = "https://jkanime.net"

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

            // Select cards from home page
            val cards = doc.select(".card")
            for (c in cards) {
                val a = c.selectFirst("a") ?: continue
                val href = a.absUrl("href")
                val img = c.selectFirst("img")
                val imgUrl = img?.attr("data-animepic")?.ifEmpty { img.attr("src") } ?: ""
                val titleEl = c.selectFirst("h5, .title, .card-title")
                var rawTitle = img?.attr("alt")?.trim()?.ifEmpty { titleEl?.text()?.trim() ?: "" } ?: ""

                if (rawTitle.isEmpty()) continue

                val epBadgeEl = c.selectFirst(".badge-primary, .badge, .card-text.ep")
                var epBadge = epBadgeEl?.text()?.trim() ?: ""
                if (epBadge.isEmpty()) {
                    val epMatch = Regex("""Ep\s*(\d+)""", RegexOption.IGNORE_CASE).find(rawTitle)
                    if (epMatch != null) {
                        epBadge = "Ep ${epMatch.groupValues[1]}"
                    }
                }

                // Normalize detailUrl to series URL
                val detailUrl = if (href.matches(Regex(""".*/\d+/?$"""))) {
                    href.replace(Regex("""/\d+/?$"""), "/")
                } else {
                    href
                }

                if (rawTitle.isNotEmpty() && detailUrl.isNotEmpty()) {
                    list.add(
                        AnimeCard(
                            id = detailUrl,
                            title = rawTitle,
                            posterUrl = imgUrl,
                            detailUrl = detailUrl,
                            source = name,
                            episodeBadge = epBadge.ifEmpty { "Anime" }
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
            val searchUrl = "$baseUrl/buscar/${query.trim().replace(" ", "_")}/1/"
            val html = fetchHtml(searchUrl)
            if (html.isEmpty()) return@withContext list
            val doc = Jsoup.parse(html, baseUrl)

            val cards = doc.select(".anime__item, .card")
            for (c in cards) {
                val a = c.selectFirst("a") ?: continue
                val href = a.absUrl("href")
                val img = c.selectFirst("img") ?: c.selectFirst(".anime__item__pic")
                val imgUrl = img?.attr("src")?.ifEmpty { img.attr("data-setbg") } ?: ""
                val title = c.selectFirst("h5, .title, a")?.text()?.trim() ?: ""

                if (title.isNotEmpty() && href.isNotEmpty()) {
                    list.add(
                        AnimeCard(
                            id = href,
                            title = title,
                            posterUrl = imgUrl,
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
            // If the user clicked an episode URL like https://jkanime.net/slug/24/, normalize to series URL
            val seriesUrl = if (detailUrl.matches(Regex(""".*/\d+/?$"""))) {
                detailUrl.replace(Regex("""/\d+/?$"""), "/")
            } else {
                detailUrl
            }

            val html = fetchHtml(seriesUrl)
            val doc = Jsoup.parse(html, seriesUrl)

            val title = doc.selectFirst(".anime__details__title h3, h1, .title")?.text()?.trim() ?: "Anime"
            val rawSynopsis = doc.selectFirst(".anime__details__text p, .sinopsis, .description")?.text()?.trim()
                ?.ifEmpty { null }
                ?: doc.selectFirst("meta[property=og:description], meta[name=description]")?.attr("content")?.trim()
                ?: ""
            val synopsis = android.text.Html.fromHtml(rawSynopsis, android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim()
            val img = doc.selectFirst(".anime__details__pic, img.poster, .card-img img")
            val posterUrl = img?.attr("src")?.ifEmpty { img.attr("data-setbg") } ?: ""

            val genres = doc.select(".anime__details__widget ul li a, .genres a").map { it.text().trim() }

            val seasonNumMatch = Regex("""(?:temporada|season)\s*(\d+)""", RegexOption.IGNORE_CASE).find(title)
                ?: Regex("""(?:temporada|season)-?(\d+)""", RegexOption.IGNORE_CASE).find(seriesUrl)
            val seasonNum = seasonNumMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1

            // Extract episodes
            val episodes = mutableListOf<AnimeEpisode>()

            var totalEps = 0

            // 1. Check #uep (last published episode link, e.g. /one-piece/1177/)
            val uepEl = doc.selectFirst("#uep")
            val uepHref = uepEl?.attr("href") ?: ""
            if (uepHref.isNotEmpty()) {
                val uepMatch = Regex("""/(\d+)/?$""").find(uepHref)
                if (uepMatch != null) {
                    totalEps = uepMatch.groupValues[1].toIntOrNull() ?: 0
                }
            }

            // 2. If #uep not found, check script variables
            if (totalEps == 0) {
                val scriptContent = doc.select("script").map { it.data() }.firstOrNull { it.contains("anime_info") || it.contains("total_ep") }
                if (scriptContent != null) {
                    val totalMatch = Regex("""total_ep\s*=\s*['"]?(\d+)['"]?""").find(scriptContent)
                        ?: Regex("""total_episodios\s*=\s*['"]?(\d+)['"]?""").find(scriptContent)
                    if (totalMatch != null) {
                        totalEps = totalMatch.groupValues[1].toIntOrNull() ?: 0
                    }
                }
            }

            if (totalEps > 0) {
                val cleanBase = seriesUrl.trimEnd('/')
                for (i in 1..totalEps) {
                    episodes.add(
                        AnimeEpisode(
                            episodeNumber = i,
                            seasonNumber = seasonNum,
                            title = "Episodio $i",
                            episodeUrl = "$cleanBase/$i/"
                        )
                    )
                }
            } else {
                // Fallback: look for episode links in HTML
                val epLinks = doc.select("a[href*=$seriesUrl]").filter { 
                    it.attr("href").matches(Regex(""".*/\d+/?$""")) 
                }
                for (ep in epLinks) {
                    val epHref = ep.absUrl("href")
                    val numMatch = Regex("""/(\d+)/?$""").find(epHref)
                    val num = numMatch?.groupValues?.get(1)?.toIntOrNull() ?: episodes.size + 1
                    episodes.add(
                        AnimeEpisode(
                            episodeNumber = num,
                            title = "Episodio $num",
                            episodeUrl = epHref
                        )
                    )
                }
            }

            AnimeDetail(
                title = title,
                posterUrl = posterUrl,
                synopsis = synopsis,
                genres = genres,
                source = name,
                detailUrl = seriesUrl,
                episodes = episodes.sortedBy { it.episodeNumber }
            )
        } catch (e: Exception) {
            e.printStackTrace()
            AnimeDetail(
                title = "Anime",
                posterUrl = "",
                synopsis = "No se pudo cargar la información.",
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

            // Find all jkplayer iframes in the video array:
            // e.g. video[0] = '<iframe ... src="https://jkanime.net/jkplayer/um?e=...
            val matches = Regex("""src=["'](https://jkanime\.net/jkplayer/[^"']+)""").findAll(html)
            for (match in matches) {
                val playerUrl = match.groupValues[1]
                val playerHtml = fetchHtml(playerUrl, referer = episodeUrl)
                if (playerHtml.isEmpty()) continue

                // Check for direct .m3u8 links
                val m3u8Match = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(playerHtml)
                if (m3u8Match != null) {
                    return@withContext StreamResult(
                        videoUrl = m3u8Match.value,
                        isHls = true,
                        serverName = "JKPlayer Direct HLS",
                        headers = mapOf(
                            "User-Agent" to userAgent,
                            "Referer" to playerUrl
                        )
                    )
                }

                // Check for direct .mp4 links
                val mp4Match = Regex("""https?://[^\s"'<>]+\.mp4[^\s"'<>]*""").find(playerHtml)
                if (mp4Match != null) {
                    return@withContext StreamResult(
                        videoUrl = mp4Match.value,
                        isHls = false,
                        serverName = "JKPlayer MP4",
                        headers = mapOf(
                            "User-Agent" to userAgent,
                            "Referer" to playerUrl
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        null
    }
}
