package com.example.animetv.core.source

import com.example.animetv.core.model.AnimeCard
import com.example.animetv.core.model.AnimeDetail
import com.example.animetv.core.model.AnimeEpisode
import com.example.animetv.core.model.StreamResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class LaMovieSource : AnimeSource {
    override val name: String = "LaMovie (Latino HD)"
    override var baseUrl: String = "https://lamovie.org"
    override val mirrors: List<String> = listOf("https://lamovie.org")

    private val fastApiUrl: String get() = "$baseUrl/wp-api/v1"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

    private fun fetchJson(url: String, referer: String = baseUrl): String {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Referer", referer)
                .header("Accept", "application/json, text/plain, */*")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) "" else resp.body?.string() ?: ""
            }
        } catch (e: Exception) {
            ""
        }
    }

    private fun fetchText(url: String, referer: String = baseUrl): String {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Referer", referer)
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) "" else resp.body?.string() ?: ""
            }
        } catch (e: Exception) {
            ""
        }
    }

    private fun formatImageUrl(path: String?): String {
        if (path.isNullOrEmpty()) return ""
        return if (path.startsWith("http")) path else "$baseUrl/wp-content/uploads$path"
    }

    override suspend fun getTrending(): List<AnimeCard> = withContext(Dispatchers.IO) {
        val list = mutableListOf<AnimeCard>()
        try {
            val jsonStr = fetchJson("$fastApiUrl/listing/movies?page=1&postsPerPage=20")
            if (jsonStr.isNotEmpty()) {
                val json = JSONObject(jsonStr)
                val posts = json.optJSONObject("data")?.optJSONArray("posts") ?: JSONArray()
                for (i in 0 until posts.length()) {
                    val p = posts.getJSONObject(i)
                    parseCard(p)?.let { list.add(it) }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        list
    }

    override suspend fun getRecentEpisodes(): List<AnimeCard> = withContext(Dispatchers.IO) {
        val list = mutableListOf<AnimeCard>()
        try {
            val jsonStr = fetchJson("$fastApiUrl/listing/tvshows?page=1&postsPerPage=20")
            if (jsonStr.isNotEmpty()) {
                val json = JSONObject(jsonStr)
                val posts = json.optJSONObject("data")?.optJSONArray("posts") ?: JSONArray()
                for (i in 0 until posts.length()) {
                    val p = posts.getJSONObject(i)
                    parseCard(p)?.let { list.add(it) }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        list
    }

    override suspend fun search(query: String): List<AnimeCard> = withContext(Dispatchers.IO) {
        val list = mutableListOf<AnimeCard>()
        try {
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            val url = "$fastApiUrl/search?q=$encoded&postType=any&postsPerPage=20"
            val jsonStr = fetchJson(url)
            if (jsonStr.isNotEmpty()) {
                val json = JSONObject(jsonStr)
                val posts = json.optJSONObject("data")?.optJSONArray("posts") ?: JSONArray()
                for (i in 0 until posts.length()) {
                    val p = posts.getJSONObject(i)
                    parseCard(p)?.let { list.add(it) }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        list
    }

    private fun parseCard(p: JSONObject): AnimeCard? {
        val id = p.optInt("_id")
        val title = p.optString("title").ifEmpty { p.optString("original_title") }
        if (title.isEmpty()) return null

        val slug = p.optString("slug")
        val type = p.optString("type", "movies")
        val images = p.optJSONObject("images")
        val poster = formatImageUrl(images?.optString("poster"))
        val rating = p.optString("rating").ifEmpty { p.optString("imdb_rating") }

        val badge = when (type) {
            "movies" -> "Película"
            "animes" -> "Anime"
            else -> "Serie"
        }

        val detailUrl = "$fastApiUrl/single/$type?slug=$slug&postType=$type&_id=$id"

        return AnimeCard(
            id = "lamovie_$id",
            title = title,
            posterUrl = poster,
            detailUrl = detailUrl,
            source = name,
            episodeBadge = badge,
            rating = rating
        )
    }

    override suspend fun getAnimeDetail(detailUrl: String): AnimeDetail = withContext(Dispatchers.IO) {
        try {
            val (reqUrl, idHint) = if (detailUrl.contains("wp-api/v1/single/")) {
                detailUrl to Regex("""_id=(\d+)""").find(detailUrl)?.groupValues?.get(1)?.toIntOrNull()
            } else {
                // web url: e.g. https://lamovie.org/peliculas/deadpool-wolverine-2024
                val path = detailUrl.removePrefix(baseUrl).trim('/')
                val parts = path.split('/')
                val postType = when {
                    parts.firstOrNull()?.contains("pelicula") == true -> "movies"
                    parts.firstOrNull()?.contains("serie") == true -> "tvshows"
                    parts.firstOrNull()?.contains("anime") == true -> "animes"
                    else -> "movies"
                }
                val slug = parts.getOrNull(1) ?: parts.lastOrNull() ?: ""
                "$fastApiUrl/single/$postType?slug=$slug&postType=$postType" to null
            }

            val jsonStr = fetchJson(reqUrl)
            if (jsonStr.isEmpty()) throw IllegalStateException("Empty detail response")

            val json = JSONObject(jsonStr)
            val data = json.optJSONObject("data") ?: JSONObject()

            val id = data.optInt("_id").takeIf { it > 0 } ?: idHint ?: 0
            val title = data.optString("title").ifEmpty { data.optString("original_title") }
            val overview = data.optString("overview")
            val type = data.optString("type", "movies")

            val images = data.optJSONObject("images")
            val posterUrl = formatImageUrl(images?.optString("poster"))

            val trailerId = data.optString("trailer")
            val trailerUrl = if (trailerId.isNotEmpty()) "https://www.youtube.com/embed/$trailerId?autoplay=1" else ""

            val episodes = mutableListOf<AnimeEpisode>()

            if (type == "movies" || type == "wwe") {
                episodes.add(
                    AnimeEpisode(
                        episodeNumber = 1,
                        seasonNumber = 1,
                        title = "Película Completa",
                        episodeUrl = "$fastApiUrl/player?postId=$id&demo=0",
                        synopsis = overview
                    )
                )
            } else {
                // Series or Anime: query episodes
                val latestEp = data.optJSONObject("latest_episode")
                val totalSeasons = latestEp?.optInt("season", 1)?.coerceAtLeast(1) ?: 1

                for (s in 1..totalSeasons) {
                    val epListUrl = "$fastApiUrl/single/episodes/list?_id=$id&season=$s&page=1&postsPerPage=100"
                    val epJsonStr = fetchJson(epListUrl)
                    if (epJsonStr.isNotEmpty()) {
                        val epJson = JSONObject(epJsonStr)
                        val posts = epJson.optJSONObject("data")?.optJSONArray("posts") ?: JSONArray()
                        for (i in 0 until posts.length()) {
                            val epObj = posts.getJSONObject(i)
                            val epId = epObj.optInt("_id")
                            val epNum = epObj.optInt("episode_number", i + 1)
                            val sNum = epObj.optInt("season_number", s)
                            val epTitle = epObj.optString("title").ifEmpty { "Episodio $epNum" }
                            val epOverview = epObj.optString("overview")
                            val epDate = epObj.optString("date")

                            episodes.add(
                                AnimeEpisode(
                                    episodeNumber = epNum,
                                    seasonNumber = sNum,
                                    title = epTitle,
                                    episodeUrl = "$fastApiUrl/player?postId=$epId&demo=0",
                                    releaseDate = epDate,
                                    synopsis = epOverview
                                )
                            )
                        }
                    }
                }

                if (episodes.isEmpty()) {
                    episodes.add(
                        AnimeEpisode(
                            episodeNumber = 1,
                            seasonNumber = 1,
                            title = "Episodio 1",
                            episodeUrl = "$fastApiUrl/player?postId=$id&demo=0",
                            synopsis = overview
                        )
                    )
                }
            }

            AnimeDetail(
                title = title,
                posterUrl = posterUrl,
                synopsis = overview,
                genres = emptyList(),
                source = name,
                detailUrl = detailUrl,
                trailerUrl = trailerUrl,
                episodes = episodes.sortedWith(compareBy({ it.seasonNumber }, { it.episodeNumber }))
            )
        } catch (e: Exception) {
            e.printStackTrace()
            AnimeDetail(
                title = "LaMovie",
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
            val postId = if (episodeUrl.contains("postId=")) {
                Regex("""postId=(\d+)""").find(episodeUrl)?.groupValues?.get(1)?.toIntOrNull()
            } else null

            if (postId != null) {
                return@withContext resolvePlayerPost(postId)
            }

            // Direct embed resolution fallback
            if (episodeUrl.startsWith("http")) {
                val direct = extractDirectHls(episodeUrl)
                if (direct != null) {
                    return@withContext StreamResult(
                        videoUrl = direct,
                        isHls = true,
                        isEmbed = false,
                        serverName = "LaMovie Direct HLS",
                        headers = mapOf("Referer" to episodeUrl)
                    )
                }
                return@withContext StreamResult(
                    videoUrl = episodeUrl,
                    isHls = episodeUrl.contains(".m3u8"),
                    isEmbed = true,
                    serverName = "LaMovie Embed",
                    headers = mapOf("Referer" to baseUrl)
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        null
    }

    private fun resolvePlayerPost(postId: Int): StreamResult? {
        val playerUrl = "$fastApiUrl/player?postId=$postId&demo=0"
        val jsonStr = fetchJson(playerUrl)
        if (jsonStr.isEmpty()) return null

        val json = JSONObject(jsonStr)
        val embeds = json.optJSONObject("data")?.optJSONArray("embeds") ?: JSONArray()
        if (embeds.length() == 0) return null

        var fallbackEmbed: StreamResult? = null

        // 1. Try to extract direct HLS from embeds prioritizing Latino audio
        val sortedEmbeds = mutableListOf<JSONObject>()
        for (i in 0 until embeds.length()) {
            sortedEmbeds.add(embeds.getJSONObject(i))
        }
        sortedEmbeds.sortByDescending { it.optString("lang").contains("Latino", ignoreCase = true) }

        for (item in sortedEmbeds) {
            val embedUrl = item.optString("url")
            val server = item.optString("server", "Online")
            val lang = item.optString("lang", "Latino")

            if (embedUrl.isNotEmpty()) {
                val directHls = extractDirectHls(embedUrl)
                if (directHls != null) {
                    return StreamResult(
                        videoUrl = directHls,
                        isHls = true,
                        isEmbed = false,
                        serverName = "LaMovie ($lang - $server)",
                        headers = mapOf("Referer" to embedUrl)
                    )
                }

                if (fallbackEmbed == null) {
                    fallbackEmbed = StreamResult(
                        videoUrl = embedUrl,
                        isHls = embedUrl.contains(".m3u8"),
                        isEmbed = true,
                        serverName = "LaMovie ($lang - $server)",
                        headers = mapOf("Referer" to baseUrl)
                    )
                }
            }
        }

        return fallbackEmbed
    }

    /**
     * Extracts direct .m3u8 URLs from Goodstream, Vimeos, and Hlswish embeds.
     */
    private fun extractDirectHls(embedUrl: String): String? {
        try {
            val html = fetchText(embedUrl, referer = baseUrl)
            if (html.isEmpty()) return null

            // Direct m3u8 in page
            val directMatch = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(html)
            if (directMatch != null) {
                return directMatch.value
            }

            // Unpack Dean Edwards packed scripts
            val packedScripts = Regex("""eval\(function\(p,a,c,k,e,d\).+?\.split\('\|'\)\)\)""", RegexOption.DOT_MATCHES_ALL).findAll(html)
            for (p in packedScripts) {
                val unpacked = JsPackerUnpacker.unpack(p.value)
                val m3u8 = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(unpacked)
                if (m3u8 != null) {
                    return m3u8.value
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    /**
     * Resolves backup stream when SoloLatino/SoloStream fails or needs alternative sources.
     * Extracts show/movie title, season and episode numbers from SoloLatino URLs.
     */
    suspend fun resolveBackupStream(episodeUrl: String): StreamResult? = withContext(Dispatchers.IO) {
        try {
            val seasonMatch = Regex("""temporada-(\d+)""").find(episodeUrl)
            val epMatch = Regex("""episodio-(\d+)""").find(episodeUrl)

            if (seasonMatch != null && epMatch != null) {
                // TV Series Episode
                val seasonNum = seasonMatch.groupValues[1].toIntOrNull() ?: 1
                val epNum = epMatch.groupValues[1].toIntOrNull() ?: 1

                val showSlug = Regex("""/serie/([^/]+)/""").find(episodeUrl)?.groupValues?.get(1)
                    ?: episodeUrl.trimEnd('/').substringBeforeLast('/').substringAfterLast('/')
                val showQuery = cleanTitleQuery(showSlug)

                val searchCards = search(showQuery)
                val targetShow = searchCards.firstOrNull { it.episodeBadge.contains("Serie", ignoreCase = true) }
                    ?: searchCards.firstOrNull() ?: return@withContext null

                val showId = Regex("""_id=(\d+)""").find(targetShow.detailUrl)?.groupValues?.get(1)?.toIntOrNull()
                    ?: targetShow.id.removePrefix("lamovie_").toIntOrNull()
                    ?: return@withContext null

                val epListUrl = "$fastApiUrl/single/episodes/list?_id=$showId&season=$seasonNum&page=1&postsPerPage=100"
                val epJsonStr = fetchJson(epListUrl)
                if (epJsonStr.isNotEmpty()) {
                    val epJson = JSONObject(epJsonStr)
                    val posts = epJson.optJSONObject("data")?.optJSONArray("posts") ?: JSONArray()
                    for (i in 0 until posts.length()) {
                        val epObj = posts.getJSONObject(i)
                        if (epObj.optInt("episode_number") == epNum) {
                            val epId = epObj.optInt("_id")
                            return@withContext resolvePlayerPost(epId)
                        }
                    }
                }
            } else {
                // Movie
                val rawTitle = episodeUrl.trimEnd('/').substringAfterLast('/')
                val movieQuery = cleanTitleQuery(rawTitle)

                val searchCards = search(movieQuery)
                val targetMovie = searchCards.firstOrNull() ?: return@withContext null

                val movieId = Regex("""_id=(\d+)""").find(targetMovie.detailUrl)?.groupValues?.get(1)?.toIntOrNull()
                    ?: targetMovie.id.removePrefix("lamovie_").toIntOrNull()
                    ?: return@withContext null

                return@withContext resolvePlayerPost(movieId)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        null
    }

    private fun cleanTitleQuery(raw: String): String {
        return raw.replace("-", " ")
            .replace(Regex("""\b(19\d\d|20\d\d)\b"""), "") // Remove 4-digit years
            .replace(Regex("""\b(hd|latino|online|completa|pelicula|serie)\b""", RegexOption.IGNORE_CASE), "")
            .trim()
            .split(Regex("""\s+"""))
            .take(3) // Primary words
            .joinToString(" ")
    }
}

/**
 * Lightweight Dean Edwards JS Packer Unpacker
 */
object JsPackerUnpacker {
    private val PACKER_REGEX = Regex("""\}\('(.*)',\s*(\d+),\s*(\d+),\s*'([^']*)'\.split\('\|'\)""", RegexOption.DOT_MATCHES_ALL)

    fun unpack(script: String): String {
        val match = PACKER_REGEX.find(script) ?: return ""
        val (payload, aStr, cStr, symTabStr) = match.destructured
        val radix = aStr.toIntOrNull() ?: return ""
        val count = cStr.toIntOrNull() ?: return ""
        val symTab = symTabStr.split('|')

        fun encode(c: Int): String {
            if (c == 0) return "0"
            val chars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
            var num = c
            val sb = StringBuilder()
            while (num > 0) {
                sb.append(chars[num % radix])
                num /= radix
            }
            return sb.reverse().toString()
        }

        val dict = HashMap<String, String>()
        for (i in 0 until count) {
            val key = if (i == 0) "0" else encode(i)
            val value = if (i < symTab.size && symTab[i].isNotEmpty()) symTab[i] else key
            dict[key] = value
        }

        return Regex("""\b\w+\b""").replace(payload) { m ->
            dict[m.value] ?: m.value
        }
    }
}
