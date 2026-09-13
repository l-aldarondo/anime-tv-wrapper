package com.example.animetv.core.source

import com.example.animetv.core.model.AnimeCard
import com.example.animetv.core.model.AnimeDetail
import com.example.animetv.core.model.AnimeEpisode
import com.example.animetv.core.model.StreamResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class SoloLatinoSource : AnimeSource {
    override val name: String = "SoloLatino (Audio Latino)"
    override val baseUrl: String = "https://sololatino.net"

    private val cookieStore = HashMap<String, MutableList<Cookie>>()
    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val list = cookieStore.getOrPut(url.host) { mutableListOf() }
            for (c in cookies) {
                list.removeAll { it.name == c.name }
                list.add(c)
            }
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            return cookieStore[url.host] ?: emptyList()
        }
    }

    private val client = OkHttpClient.Builder()
        .cookieJar(cookieJar)
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
            val html = fetchHtml("$baseUrl/animes")
            if (html.isEmpty()) return@withContext list
            val doc = Jsoup.parse(html, baseUrl)

            val items = doc.select(".item, .poster, article, .item-pelicula, .card")
            for (it in items) {
                val a = it.selectFirst("a") ?: continue
                val href = a.absUrl("href")
                if (!href.contains("/serie/") && !href.contains("/pelicula/")) continue

                val img = it.selectFirst("img")
                val posterUrl = img?.attr("src")?.ifEmpty { img.attr("data-src") } ?: ""
                val title = img?.attr("alt")?.trim() ?: it.selectFirst(".title, h2, h3")?.text()?.trim() ?: ""

                val ratingEl = it.selectFirst(".rating, .vote, .stars")
                val rating = ratingEl?.text()?.trim() ?: ""

                if (title.isNotEmpty() && href.isNotEmpty()) {
                    list.add(
                        AnimeCard(
                            id = href,
                            title = title,
                            posterUrl = posterUrl,
                            detailUrl = href,
                            source = name,
                            rating = rating
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
            val searchUrl = "$baseUrl/buscar?q=$encoded"
            val html = fetchHtml(searchUrl)
            if (html.isEmpty()) return@withContext list
            val doc = Jsoup.parse(html, baseUrl)

            val items = doc.select(".item, .poster, article, .item-pelicula, .card")
            for (it in items) {
                val a = it.selectFirst("a") ?: continue
                val href = a.absUrl("href")
                val img = it.selectFirst("img")
                val posterUrl = img?.attr("src")?.ifEmpty { img.attr("data-src") } ?: ""
                val title = img?.attr("alt")?.trim() ?: it.selectFirst(".title, h2, h3")?.text()?.trim() ?: ""

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
            val rawSynopsis = doc.selectFirst(".overview, .sinopsis, .entry-content p, .film-description, .description")?.text()?.trim()
                ?.ifEmpty { null }
                ?: doc.selectFirst("meta[property=og:description], meta[name=description]")?.attr("content")?.trim()
                ?: ""
            val synopsis = android.text.Html.fromHtml(rawSynopsis, android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim()
            val img = doc.selectFirst(".poster img, .cover img, img")
            val posterUrl = img?.attr("src")?.ifEmpty { img.attr("data-src") } ?: ""

            val genres = doc.select(".genres a, .genre a").map { it.text().trim() }

            val episodes = mutableListOf<AnimeEpisode>()
            val epLinks = doc.select("a[href*=/episodio-]")

            for (link in epLinks) {
                val href = link.absUrl("href")
                val epText = link.text().trim()
                
                // Match episode number from URL e.g. /episodio-1
                val epNumMatch = Regex("""episodio-(\d+)""").find(href)
                val epNum = epNumMatch?.groupValues?.get(1)?.toIntOrNull() ?: episodes.size + 1

                val seasonNumMatch = Regex("""temporada-(\d+)""").find(href)
                val seasonNum = seasonNumMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1

                val epTitle = if (epText.isNotEmpty() && epText.length < 80) epText else "Episodio $epNum"

                episodes.add(
                    AnimeEpisode(
                        episodeNumber = epNum,
                        seasonNumber = seasonNum,
                        title = epTitle,
                        episodeUrl = href
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
                episodes = episodes.sortedWith(compareBy({ it.seasonNumber }, { it.episodeNumber }))
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
            // High performance direct stream resolution (Servidor 1 HLS & Embed)
            val result = SoloLatinoStreamResolver.resolve(episodeUrl)
            if (result != null) return@withContext result

            // Fallback: direct m3u8 in page
            val html = fetchHtml(episodeUrl, referer = baseUrl)
            val m3u8 = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(html)
            if (m3u8 != null) {
                return@withContext StreamResult(
                    videoUrl = m3u8.value,
                    isHls = true,
                    serverName = "SoloLatino Direct HLS"
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        null
    }
}
