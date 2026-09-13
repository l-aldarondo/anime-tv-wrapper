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
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class SoloStreamSource : AnimeSource {
    override val name: String = "SoloStream (Películas y Series)"
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
            val html = fetchHtml("$baseUrl/")
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
                val rating = it.selectFirst(".rating, .vote, .stars")?.text()?.trim() ?: ""

                if (title.isNotEmpty() && href.isNotEmpty()) {
                    list.add(
                        AnimeCard(
                            id = href,
                            title = title,
                            posterUrl = posterUrl,
                            detailUrl = href,
                            source = name,
                            rating = rating,
                            episodeBadge = if (href.contains("/pelicula/")) "Película" else "Serie"
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
                            source = name,
                            episodeBadge = if (href.contains("/pelicula/")) "Película" else "Serie"
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

            val title = doc.selectFirst("h1, .entry-title, .title")?.text()?.trim() ?: "Título"
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
                val epNumMatch = Regex("""episodio-(\d+)""").find(href)
                val epNum = epNumMatch?.groupValues?.get(1)?.toIntOrNull() ?: episodes.size + 1
                val seasonNumMatch = Regex("""temporada-(\d+)""").find(href)
                val seasonNum = seasonNumMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1

                // Extract individual episode title (e.g. "Hora de empresarios")
                val titleEl = link.selectFirst(".text-sm, .font-semibold, h4, h3, .ep-title")
                val parsedTitle = titleEl?.text()?.trim()
                    ?.takeIf { it.isNotEmpty() && !it.startsWith("E", ignoreCase = true) }
                    ?: link.selectFirst("p:not(.ep-num):not(.line-clamp-2)")?.text()?.trim()
                val epTitle = if (!parsedTitle.isNullOrEmpty() && parsedTitle.length < 90) parsedTitle else "Episodio $epNum"

                // Extract individual episode synopsis
                val synopsisEl = link.selectFirst(".line-clamp-2, .ep-desc, .overview, .synopsis")
                val epSynopsis = synopsisEl?.text()?.trim()
                    ?: link.select("p.text-xs").firstOrNull { it.text().length > 20 }?.text()?.trim()
                    ?: ""

                // Extract air date if present (e.g. 26/04/2010)
                val dateEl = link.select("p.text-xs").lastOrNull()
                val releaseDate = dateEl?.text()?.trim()?.takeIf { it.contains("/") } ?: ""

                episodes.add(
                    AnimeEpisode(
                        episodeNumber = epNum,
                        seasonNumber = seasonNum,
                        title = epTitle,
                        episodeUrl = href,
                        releaseDate = releaseDate,
                        synopsis = epSynopsis
                    )
                )
            }

            if (episodes.isEmpty()) {
                // If it's a Movie (película) or single-stream page
                episodes.add(
                    AnimeEpisode(
                        episodeNumber = 1,
                        seasonNumber = 1,
                        title = "Ver Película Completa",
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
                episodes = episodes.sortedWith(compareBy({ it.seasonNumber }, { it.episodeNumber }))
            )
        } catch (e: Exception) {
            e.printStackTrace()
            AnimeDetail(
                title = "Contenido",
                posterUrl = "",
                synopsis = "No se pudo cargar la información.",
                source = name,
                detailUrl = detailUrl,
                episodes = listOf(AnimeEpisode(1, 1, "Reproducir", detailUrl))
            )
        }
    }

    override suspend fun resolveStream(episodeUrl: String): StreamResult? = withContext(Dispatchers.IO) {
        try {
            val result = SoloLatinoStreamResolver.resolve(episodeUrl)
            if (result != null) return@withContext result

            val html = fetchHtml(episodeUrl, referer = baseUrl)
            val m3u8 = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(html)
            if (m3u8 != null) {
                return@withContext StreamResult(
                    videoUrl = m3u8.value,
                    isHls = true,
                    serverName = "SoloStream Direct HLS"
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        null
    }
}
