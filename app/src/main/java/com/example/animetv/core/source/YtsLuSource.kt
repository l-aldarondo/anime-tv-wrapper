package com.example.animetv.core.source

import com.example.animetv.core.model.AnimeCard
import com.example.animetv.core.model.AnimeDetail
import com.example.animetv.core.model.AnimeEpisode
import com.example.animetv.core.model.CatalogRow
import com.example.animetv.core.model.StreamResult
import com.example.animetv.core.torrent.TorrentSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

class YtsLuSource : AnimeSource {
    override val name: String = "YTS (Películas y Series)"
    override var baseUrl: String = "https://en.yts.lu"
    override val mirrors: List<String> = listOf("https://en.yts.lu")

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

    private fun getJson(url: String): JSONObject? {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Referer", "$baseUrl/")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null
                else {
                    val body = response.body?.string() ?: return null
                    JSONObject(body)
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseCardFromJson(obj: JSONObject, isTv: Boolean): AnimeCard? {
        val id = obj.optInt("id", 0)
        if (id <= 0) return null
        val rawTitle = if (isTv) obj.optString("name", "") else obj.optString("title", "")
        val title = rawTitle.ifEmpty { obj.optString("original_title", "") }.trim()
        if (title.isEmpty()) return null

        val posterPath = obj.optString("poster_path", "")
        val posterUrl = if (posterPath.isNotEmpty()) "https://image.tmdb.org/t/p/w500$posterPath" else ""
        val backdropPath = obj.optString("backdrop_path", "")
        val backdropUrl = if (backdropPath.isNotEmpty()) "https://image.tmdb.org/t/p/w780$backdropPath" else posterUrl

        val voteAvg = obj.optDouble("vote_average", 0.0)
        val rating = if (voteAvg > 0) String.format(Locale.US, "★ %.1f", voteAvg) else ""
        val synopsis = obj.optString("overview", "").trim()

        val encTitle = URLEncoder.encode(title, "UTF-8")
        val detailUrl = if (isTv) "$baseUrl/tv/$id?title=$encTitle" else "$baseUrl/movie/$id?title=$encTitle"

        return AnimeCard(
            id = "yts_${if (isTv) "tv" else "movie"}_$id",
            title = title,
            detailUrl = detailUrl,
            posterUrl = posterUrl,
            backdropUrl = backdropUrl,
            rating = rating,
            synopsis = synopsis,
            source = name
        )
    }

    override suspend fun getTrending(): List<AnimeCard> = withContext(Dispatchers.IO) {
        val list = mutableListOf<AnimeCard>()
        val json = getJson("$baseUrl/?api=trending&mode=movie&page=1") ?: return@withContext list
        val results = json.optJSONArray("results") ?: return@withContext list
        for (i in 0 until results.length()) {
            val obj = results.optJSONObject(i) ?: continue
            parseCardFromJson(obj, isTv = false)?.let { list.add(it) }
        }
        list
    }

    override suspend fun getRecentEpisodes(): List<AnimeCard> = withContext(Dispatchers.IO) {
        val list = mutableListOf<AnimeCard>()
        val json = getJson("$baseUrl/?api=trending&mode=tv&page=1") ?: return@withContext list
        val results = json.optJSONArray("results") ?: return@withContext list
        for (i in 0 until results.length()) {
            val obj = results.optJSONObject(i) ?: continue
            parseCardFromJson(obj, isTv = true)?.let { list.add(it) }
        }
        list
    }

    suspend fun getHomeSections(): List<CatalogRow> = coroutineScope {
        val trendingMoviesDef = async { getTrending() }
        val trendingTvDef = async { getRecentEpisodes() }
        val popularMoviesDef = async {
            val list = mutableListOf<AnimeCard>()
            val json = getJson("$baseUrl/?api=discover&mode=movie&page=1&sort=popularity.desc")
            val results = json?.optJSONArray("results")
            if (results != null) {
                for (i in 0 until results.length()) {
                    results.optJSONObject(i)?.let { parseCardFromJson(it, isTv = false)?.let { c -> list.add(c) } }
                }
            }
            list
        }
        val popularTvDef = async {
            val list = mutableListOf<AnimeCard>()
            val json = getJson("$baseUrl/?api=discover&mode=tv&page=1&sort=popularity.desc")
            val results = json?.optJSONArray("results")
            if (results != null) {
                for (i in 0 until results.length()) {
                    results.optJSONObject(i)?.let { parseCardFromJson(it, isTv = true)?.let { c -> list.add(c) } }
                }
            }
            list
        }

        val sections = mutableListOf<CatalogRow>()
        val trMovies = trendingMoviesDef.await()
        if (trMovies.isNotEmpty()) {
            sections.add(CatalogRow("🔥 Películas en Tendencia (YTS)", trMovies))
        }
        val trTv = trendingTvDef.await()
        if (trTv.isNotEmpty()) {
            sections.add(CatalogRow("📺 Series en Tendencia (YTS)", trTv))
        }
        val popMovies = popularMoviesDef.await()
        if (popMovies.isNotEmpty()) {
            sections.add(CatalogRow("⭐ Películas Más Populares", popMovies))
        }
        val popTv = popularTvDef.await()
        if (popTv.isNotEmpty()) {
            sections.add(CatalogRow("🌟 Series Más Populares", popTv))
        }
        sections
    }

    override suspend fun search(query: String): List<AnimeCard> = coroutineScope {
        // en.yts.lu search API requires lowercase search term
        val lower = query.trim().lowercase(Locale.ROOT)
        val q = URLEncoder.encode(lower, "UTF-8")

        val moviesDef = async(Dispatchers.IO) {
            val list = mutableListOf<AnimeCard>()
            val json = getJson("$baseUrl/?api=search&mode=movie&q=$q")
            val results = json?.optJSONArray("results")
            if (results != null) {
                for (i in 0 until results.length()) {
                    results.optJSONObject(i)?.let { parseCardFromJson(it, isTv = false)?.let { c -> list.add(c) } }
                }
            }
            list
        }
        val tvDef = async(Dispatchers.IO) {
            val list = mutableListOf<AnimeCard>()
            val json = getJson("$baseUrl/?api=search&mode=tv&q=$q")
            val results = json?.optJSONArray("results")
            if (results != null) {
                for (i in 0 until results.length()) {
                    results.optJSONObject(i)?.let { parseCardFromJson(it, isTv = true)?.let { c -> list.add(c) } }
                }
            }
            list
        }

        val results = mutableListOf<AnimeCard>()
        results.addAll(moviesDef.await())
        results.addAll(tvDef.await())
        results
    }

    override suspend fun getAnimeDetail(detailUrl: String): AnimeDetail = withContext(Dispatchers.IO) {
        val isTv = detailUrl.contains("/tv/")
        val urlWithoutQuery = detailUrl.substringBefore("?")
        val idStr = urlWithoutQuery.substringAfterLast("/").trim()
        val id = idStr.toIntOrNull() ?: 0

        val queryParams = if (detailUrl.contains("?")) {
            detailUrl.substringAfter("?").split("&").associate {
                val p = it.split("=")
                p[0] to (if (p.size > 1) URLDecoder.decode(p[1], "UTF-8") else "")
            }
        } else emptyMap()

        val titleFromUrl = queryParams["title"].orEmpty()

        if (isTv) {
            val tvJson = getJson("$baseUrl/?api=tv_details&id=$id")
            val title = tvJson?.optString("name", "")?.ifEmpty { tvJson.optString("original_name", "") }
                ?.ifEmpty { titleFromUrl } ?: "Serie"
            val posterPath = tvJson?.optString("poster_path", "") ?: ""
            val posterUrl = if (posterPath.isNotEmpty()) "https://image.tmdb.org/t/p/w500$posterPath" else ""
            val backdropPath = tvJson?.optString("backdrop_path", "") ?: ""
            val backdropUrl = if (backdropPath.isNotEmpty()) "https://image.tmdb.org/t/p/w780$backdropPath" else posterUrl
            val synopsis = tvJson?.optString("overview", "").orEmpty()

            val seasonsArray = tvJson?.optJSONArray("seasons")
            val seasonNumbers = mutableListOf<Int>()
            if (seasonsArray != null) {
                for (i in 0 until seasonsArray.length()) {
                    val sObj = seasonsArray.optJSONObject(i) ?: continue
                    val sNum = sObj.optInt("season_number", 0)
                    if (sNum > 0) seasonNumbers.add(sNum)
                }
            }
            if (seasonNumbers.isEmpty()) seasonNumbers.add(1)

            val episodes = mutableListOf<AnimeEpisode>()
            val seasonJobs = seasonNumbers.map { sNum ->
                async {
                    val sJson = getJson("$baseUrl/?api=season_details&id=$id&season=$sNum")
                    val epArray = sJson?.optJSONArray("episodes")
                    val sEps = mutableListOf<AnimeEpisode>()
                    if (epArray != null) {
                        for (i in 0 until epArray.length()) {
                            val epObj = epArray.optJSONObject(i) ?: continue
                            val eNum = epObj.optInt("episode_number", i + 1)
                            val epName = epObj.optString("name", "").trim()
                            val epOverview = epObj.optString("overview", "").trim()
                            val stillPath = epObj.optString("still_path", "").trim()
                            val epStill = if (stillPath.isNotEmpty()) "https://image.tmdb.org/t/p/w500$stillPath" else ""
                            val airDate = epObj.optString("air_date", "").trim()

                            sEps.add(
                                AnimeEpisode(
                                    episodeNumber = eNum,
                                    seasonNumber = sNum,
                                    title = if (epName.isNotEmpty()) "$eNum. $epName" else "Episodio $eNum",
                                    episodeUrl = "$baseUrl/tv/$id/$sNum/$eNum?title=${URLEncoder.encode(title, "UTF-8")}",
                                    synopsis = epOverview,
                                    stillUrl = epStill,
                                    releaseDate = airDate
                                )
                            )
                        }
                    }
                    sEps
                }
            }
            val allSeasonsEps = seasonJobs.awaitAll()
            allSeasonsEps.forEach { episodes.addAll(it) }

            AnimeDetail(
                title = title,
                posterUrl = posterUrl,
                backdropUrl = backdropUrl,
                synopsis = synopsis,
                genres = emptyList(),
                episodes = episodes.sortedWith(compareBy({ it.seasonNumber }, { it.episodeNumber })),
                source = name,
                detailUrl = detailUrl,
                tmdbId = id,
                tmdbMediaType = "tv"
            )
        } else {
            // Movie detail: resolve by TMDB ID first, or fallback to search/title
            var movieTitle = titleFromUrl
            var moviePoster = ""
            var movieBackdrop = ""
            var movieSynopsis = ""

            if (id > 0) {
                val tmdbJson = getJson("https://api.themoviedb.org/3/movie/$id?api_key=${TorrentSettingsStore.DEFAULT_TMDB_API_KEY}&language=es-MX")
                if (tmdbJson != null && tmdbJson.has("title")) {
                    movieTitle = tmdbJson.optString("title", "").ifEmpty { tmdbJson.optString("original_title", movieTitle) }
                    val p = tmdbJson.optString("poster_path", "")
                    if (p.isNotEmpty()) moviePoster = "https://image.tmdb.org/t/p/w500$p"
                    val b = tmdbJson.optString("backdrop_path", "")
                    if (b.isNotEmpty()) movieBackdrop = "https://image.tmdb.org/t/p/w780$b"
                    movieSynopsis = tmdbJson.optString("overview", "")
                }
            }

            if (movieTitle.isEmpty()) {
                val searchParam = titleFromUrl.ifEmpty { idStr }
                val lowerSearch = searchParam.lowercase(Locale.ROOT)
                val searchJson = getJson("$baseUrl/?api=search&mode=movie&q=${URLEncoder.encode(lowerSearch, "UTF-8")}")
                val results = searchJson?.optJSONArray("results")
                if (results != null && results.length() > 0) {
                    var matched = results.optJSONObject(0)
                    for (i in 0 until results.length()) {
                        val obj = results.optJSONObject(i) ?: continue
                        if (obj.optInt("id", 0) == id) {
                            matched = obj
                            break
                        }
                    }
                    if (matched != null) {
                        movieTitle = matched.optString("title", "").ifEmpty { matched.optString("original_title", movieTitle) }
                        val p = matched.optString("poster_path", "")
                        if (p.isNotEmpty() && moviePoster.isEmpty()) moviePoster = "https://image.tmdb.org/t/p/w500$p"
                        val b = matched.optString("backdrop_path", "")
                        if (b.isNotEmpty() && movieBackdrop.isEmpty()) movieBackdrop = "https://image.tmdb.org/t/p/w780$b"
                        if (movieSynopsis.isEmpty()) movieSynopsis = matched.optString("overview", "")
                    }
                }
            }

            val episodes = listOf(
                AnimeEpisode(
                    episodeNumber = 1,
                    seasonNumber = 1,
                    title = "Película Completa",
                    episodeUrl = "$baseUrl/movie/$id?title=${URLEncoder.encode(movieTitle, "UTF-8")}",
                    synopsis = movieSynopsis
                )
            )

            AnimeDetail(
                title = movieTitle.ifEmpty { "Película" },
                posterUrl = moviePoster,
                backdropUrl = movieBackdrop,
                synopsis = movieSynopsis,
                genres = emptyList(),
                episodes = episodes,
                source = name,
                detailUrl = detailUrl,
                tmdbId = id,
                tmdbMediaType = "movie"
            )
        }
    }

    override suspend fun resolveStream(episodeUrl: String): StreamResult? = withContext(Dispatchers.IO) {
        val isTv = episodeUrl.contains("/tv/")
        val urlWithoutQuery = episodeUrl.substringBefore("?")
        val segments = urlWithoutQuery.removePrefix("$baseUrl/").split("/").filter { it.isNotEmpty() }

        val id = if (segments.size >= 2) segments[1].toIntOrNull() ?: 0 else 0
        val sNum = if (isTv && segments.size >= 3) segments[2].toIntOrNull() ?: 1 else 1
        val eNum = if (isTv && segments.size >= 4) segments[3].toIntOrNull() ?: 1 else 1

        val queryParams = if (episodeUrl.contains("?")) {
            episodeUrl.substringAfter("?").split("&").associate {
                val p = it.split("=")
                p[0] to (if (p.size > 1) URLDecoder.decode(p[1], "UTF-8") else "")
            }
        } else emptyMap()

        val title = queryParams["title"].orEmpty()
        val torrentName = if (title.isNotEmpty()) title else id.toString()

        val torrentApiUrl = if (isTv) {
            "$baseUrl/?api=torrents&mode=tv&name=${URLEncoder.encode(torrentName, "UTF-8")}&season=$sNum&episode=$eNum&quality=all"
        } else {
            "$baseUrl/?api=torrents&mode=movie&name=${URLEncoder.encode(torrentName, "UTF-8")}&quality=all"
        }

        val json = getJson(torrentApiUrl)
        val hits = json?.optJSONArray("hits")
        if (hits != null && hits.length() > 0) {
            // Pick top hit by seeders
            var bestHit: JSONObject? = null
            var maxSeeds = -1
            for (i in 0 until hits.length()) {
                val hit = hits.optJSONObject(i) ?: continue
                val seeds = hit.optInt("seeds", 0)
                if (seeds > maxSeeds && hit.optString("magnetUrl", "").startsWith("magnet:?")) {
                    maxSeeds = seeds
                    bestHit = hit
                }
            }
            if (bestHit != null) {
                val magnet = bestHit.optString("magnetUrl", "").replace("&amp;", "&")
                val src = bestHit.optString("source", "Torrent")
                return@withContext StreamResult(
                    videoUrl = magnet,
                    isHls = false,
                    isEmbed = false,
                    serverName = "YTS Torrent ($src - $maxSeeds seeds)",
                    headers = mapOf("Referer" to "$baseUrl/")
                )
            }
        }

        // Fallback to VidSrc embed stream
        val embedUrl = if (isTv) {
            "https://vidsrc.mov/embed/tv/$id/$sNum/$eNum"
        } else {
            "https://vidsrc.mov/embed/movie/$id"
        }

        StreamResult(
            videoUrl = embedUrl,
            isHls = false,
            isEmbed = true,
            serverName = "VidSrc (Stream Online)",
            headers = mapOf("Referer" to "$baseUrl/")
        )
    }
}
