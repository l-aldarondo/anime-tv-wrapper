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
    override val name: String = "Cinecalidad (Latino HD)"
    override var baseUrl: String = "https://www.cinecalidad.my"
    override val mirrors: List<String> = listOf(
        "https://www.cinecalidad.my",
        "https://cinecalidad.my",
        "https://v2.cinecalidad.vip",
        "https://www.cinecalidad.ro"
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

    private fun cleanTitle(raw: String): String {
        return raw.replace(Regex("""<!--.*?-->"""), "")
            .replace(Regex("""<[^>]*>"""), "")
            .trim()
    }

    override suspend fun getTrending(): List<AnimeCard> = withContext(Dispatchers.IO) {
        val list = mutableListOf<AnimeCard>()
        val html = fetchHtml(baseUrl)
        if (html.isEmpty()) return@withContext list

        val doc = Jsoup.parse(html, baseUrl)
        val items = doc.select("article, .home_post_cont, .post_box, .contenedor-list, .home-post, .item")
        for (item in items) {
            val a = item.selectFirst("a[href*='/pelicula/'], a[href*='/series/']") ?: item.selectFirst("a") ?: continue
            val href = a.absUrl("href")
            if (href.isEmpty() || href.endsWith("/peliculas/") || href.endsWith("/series/") ||
                href.contains("/genero-peliculas/") || href.contains("/curiosidades/")) continue

            val img = item.selectFirst("img")
            val posterUrl = img?.attr("data-src")?.ifEmpty { img.attr("src") } ?: ""
            val titleEl = item.selectFirst(".entry-title, h2, h3, .titulo, .in_title")?.text()?.trim()
            val rawTitle = when {
                !titleEl.isNullOrEmpty() -> titleEl
                !img?.attr("title").isNullOrBlank() -> img?.attr("title") ?: ""
                !img?.attr("alt").isNullOrBlank() -> img?.attr("alt") ?: ""
                a.text().trim().isNotEmpty() -> a.text().trim()
                else -> ""
            }
            val title = cleanTitle(rawTitle)

            if (title.isNotEmpty() && href.isNotEmpty()) {
                list.add(AnimeCard(
                    id = href,
                    title = title,
                    posterUrl = posterUrl,
                    detailUrl = href,
                    source = name,
                    episodeBadge = "Latino HD"
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
        val items = doc.select("article, .home_post_cont, .post_box, .contenedor-list, .home-post, .item")
        for (item in items) {
            val a = item.selectFirst("a[href*='/pelicula/'], a[href*='/series/']") ?: item.selectFirst("a") ?: continue
            val href = a.absUrl("href")
            if (href.isEmpty() || href.endsWith("/peliculas/") || href.endsWith("/series/") ||
                href.contains("/genero-peliculas/") || href.contains("/curiosidades/")) continue

            val img = item.selectFirst("img")
            val posterUrl = img?.attr("data-src")?.ifEmpty { img.attr("src") } ?: ""
            val titleEl = item.selectFirst(".entry-title, h2, h3, .titulo, .in_title")?.text()?.trim()
            val rawTitle = when {
                !titleEl.isNullOrEmpty() -> titleEl
                !img?.attr("title").isNullOrBlank() -> img?.attr("title") ?: ""
                !img?.attr("alt").isNullOrBlank() -> img?.attr("alt") ?: ""
                a.text().trim().isNotEmpty() -> a.text().trim()
                else -> ""
            }
            val title = cleanTitle(rawTitle)

            if (title.isNotEmpty() && href.isNotEmpty()) {
                list.add(AnimeCard(
                    id = href,
                    title = title,
                    posterUrl = posterUrl,
                    detailUrl = href,
                    source = name,
                    episodeBadge = "Latino HD"
                ))
            }
        }
        list.distinctBy { it.detailUrl }
    }

    override suspend fun getAnimeDetail(detailUrl: String): AnimeDetail = withContext(Dispatchers.IO) {
        val html = fetchHtml(detailUrl)
        val doc = Jsoup.parse(html, detailUrl)
        
        val rawTitle = doc.selectFirst("h1")?.text()?.trim() ?: "Cinecalidad Title"
        val title = cleanTitle(rawTitle)
        val synopsis = doc.selectFirst(".description, .sinopsis, .entry-content p, p.text")?.text()?.trim() ?: ""
        val posterUrl = doc.selectFirst(".poster img, img.wp-post-image")?.attr("src") ?: ""
        
        val trailerLink = doc.selectFirst("a[service='Trailer']")?.attr("data")?.trim() ?: ""
        val trailerUrl = if (trailerLink.isNotEmpty()) "https://www.youtube.com/watch?v=$trailerLink" else ""

        val episodes = listOf(AnimeEpisode(1, 1, "Película Completa", detailUrl))
        
        AnimeDetail(
            title = title,
            posterUrl = posterUrl,
            synopsis = synopsis,
            source = name,
            detailUrl = detailUrl,
            trailerUrl = trailerUrl,
            episodes = episodes
        )
    }

    override suspend fun resolveStream(episodeUrl: String): StreamResult? = withContext(Dispatchers.IO) {
        resolveAllStreams(episodeUrl).firstOrNull()
    }

    suspend fun resolveAllStreams(episodeUrl: String): List<StreamResult> = withContext(Dispatchers.IO) {
        val list = mutableListOf<StreamResult>()

        // If episodeUrl is already an embed URL
        if (episodeUrl.contains("filemoon") || episodeUrl.contains("voe") || episodeUrl.contains("dood") || episodeUrl.contains("mega")) {
            list.add(
                StreamResult(
                    videoUrl = episodeUrl,
                    isHls = episodeUrl.contains(".m3u8"),
                    isEmbed = true,
                    serverName = "Cinecalidad (Embed)",
                    headers = mapOf("Referer" to baseUrl)
                )
            )
            return@withContext list
        }

        val html = fetchHtml(episodeUrl)
        if (html.isEmpty()) return@withContext list
        val doc = Jsoup.parse(html, episodeUrl)

        // 1. Cinecalidad .my online servers (from scripts.min.js):
        // <a class="link onlinelink" service="OnlineFilemoon" data="advjsn8lcx9y"><li>Filemoon</li></a>
        // <a class="link onlinelink" service="OnlineVoe" data="sh0r7io7vn8e"><li>Voe</li></a>
        // <a class="link onlinelink" service="OnlineDoodstream" data="fmdarm4vs0jx"><li>Doodstream</li></a>
        val onlineLinks = doc.select("a.onlinelink, a[service]")

        // Priority 1: VOE (Clean Full HD, no bot verification)
        val voe = onlineLinks.firstOrNull { it.attr("service").equals("OnlineVoe", ignoreCase = true) }
        if (voe != null) {
            val data = voe.attr("data").trim()
            if (data.isNotEmpty()) {
                list.add(
                    StreamResult(
                        videoUrl = "https://voe.sx/e/$data",
                        isHls = false,
                        isEmbed = true,
                        serverName = "Cinecalidad (Latino - VOE Full HD)",
                        headers = mapOf("Referer" to baseUrl)
                    )
                )
            }
        }

        // Priority 2: Filemoon (Full HD / 4K)
        val filemoon = onlineLinks.firstOrNull { it.attr("service").equals("OnlineFilemoon", ignoreCase = true) }
        if (filemoon != null) {
            val data = filemoon.attr("data").trim()
            if (data.isNotEmpty()) {
                list.add(
                    StreamResult(
                        videoUrl = "https://filemoon.sx/e/$data",
                        isHls = false,
                        isEmbed = true,
                        serverName = "Cinecalidad (Latino - Filemoon Full HD)",
                        headers = mapOf("Referer" to baseUrl)
                    )
                )
            }
        }

        // Priority 3: Doodstream
        val dood = onlineLinks.firstOrNull { it.attr("service").equals("OnlineDoodstream", ignoreCase = true) }
        if (dood != null) {
            val data = dood.attr("data").trim()
            if (data.isNotEmpty()) {
                list.add(
                    StreamResult(
                        videoUrl = "https://doodstream.com/e/$data",
                        isHls = false,
                        isEmbed = true,
                        serverName = "Cinecalidad (Latino - Doodstream)",
                        headers = mapOf("Referer" to baseUrl)
                    )
                )
            }
        }

        // Priority 4: Other services (Mega, Netu)
        for (link in onlineLinks) {
            val s = link.attr("service")
            val d = link.attr("data").trim()
            if (d.isNotEmpty()) {
                val embedUrl = when (s) {
                    "OnlineMega" -> "https://mega.nz/embed/$d"
                    "OnlineNetu" -> "https://www.zarato.top/watch_video.php?v=$d"
                    else -> null
                }
                if (embedUrl != null) {
                    list.add(
                        StreamResult(
                            videoUrl = embedUrl,
                            isHls = false,
                            isEmbed = true,
                            serverName = "Cinecalidad (Latino - $s)",
                            headers = mapOf("Referer" to baseUrl)
                        )
                    )
                }
            }
        }

        // 2. Cinecalidad v2 fallback uses play.cinecalidad.vip/?link=ENCODED_URL
        val playRegex = Regex("""(?:src=["']|https?://)play\.cinecalidad\.vip/\?link=([^"'\s&]+)""")
        val playMatch = playRegex.find(html)
        if (playMatch != null) {
            val encodedLink = playMatch.groupValues[1]
            val decoded = try {
                java.net.URLDecoder.decode(encodedLink, "UTF-8")
            } catch (e: Exception) { encodedLink }
            if (decoded.startsWith("http")) {
                list.add(
                    StreamResult(
                        videoUrl = decoded,
                        isHls = decoded.contains(".m3u8"),
                        isEmbed = true,
                        serverName = "Cinecalidad (Latino - VIP)",
                        headers = mapOf("Referer" to baseUrl)
                    )
                )
            }
        }

        // 3. Direct iframes fallback (Filemoon, Vidoza, Upstream, etc.)
        val iframes = Regex("""<iframe[^>]*src=["']([^"']+)["']""").findAll(html)
        for (ifr in iframes) {
            val rawUrl = ifr.groupValues[1]
            val url = if (rawUrl.contains("play.cinecalidad.vip/?link=")) {
                val enc = rawUrl.substringAfter("link=").substringBefore("&")
                try { java.net.URLDecoder.decode(enc, "UTF-8") } catch (e: Exception) { rawUrl }
            } else rawUrl

            if (url.contains("filemoon") || url.contains("vidoza") || url.contains("upstream") ||
                url.contains("streamtape") || url.contains("dood") || url.contains("waaw")) {
                list.add(
                    StreamResult(
                        videoUrl = url,
                        isHls = url.contains(".m3u8"),
                        isEmbed = true,
                        serverName = "Cinecalidad (Latino - Embed)",
                        headers = mapOf("Referer" to baseUrl)
                    )
                )
            }
        }

        return@withContext list
    }
}
