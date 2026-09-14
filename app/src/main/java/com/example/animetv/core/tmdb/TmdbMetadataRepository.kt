package com.example.animetv.core.tmdb

import android.content.Context
import com.example.animetv.core.torrent.TorrentSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class TmdbMetadata(
    val tmdbId: Int,
    val imdbId: String = "",
    val trailerUrl: String = "",
    val titleSpanish: String,
    val titleOriginal: String,
    val overview: String,
    val posterUrl: String,
    val backdropUrl: String,
    val ratingText: String,
    val releaseYear: String,
    val mediaType: String // "tv" or "movie"
)

data class TmdbEpisode(
    val episodeNumber: Int,
    val seasonNumber: Int,
    val name: String,
    val overview: String,
    val stillUrl: String,
    val airDate: String,
    val voteAverage: Double = 0.0
)

object TmdbMetadataRepository {

    private const val BASE_URL = "https://api.themoviedb.org/3"
    private const val IMAGE_BASE_W500 = "https://image.tmdb.org/t/p/w500"
    private const val IMAGE_BASE_W1280 = "https://image.tmdb.org/t/p/w1280"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // In-memory cache to make screen loads instantaneous
    private val memoryCache = ConcurrentHashMap<String, TmdbMetadata>()
    private val seasonCache = ConcurrentHashMap<String, List<TmdbEpisode>>()

    /**
     * Searches TMDB for rich metadata, high-resolution artwork, and original title.
     */
    suspend fun searchMetadata(context: Context, rawTitle: String): TmdbMetadata? = withContext(Dispatchers.IO) {
        val cleanQuery = sanitizeTitle(rawTitle)
        if (cleanQuery.isEmpty()) return@withContext null

        val cacheKey = cleanQuery.lowercase(Locale.ROOT)
        memoryCache[cacheKey]?.let { return@withContext it }

        val apiKey = TorrentSettingsStore.getTmdbApiKey(context)
        if (apiKey.isEmpty()) return@withContext null

        try {
            val encodedQuery = URLEncoder.encode(cleanQuery, "UTF-8")
            // Use multi-search to capture both Movies and TV Shows/Anime in one call
            val url = "$BASE_URL/search/multi?api_key=$apiKey&query=$encodedQuery&language=es-MX&include_adult=false"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "AnimeTV/2.8")
                .header("Accept", "application/json")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val bodyStr = response.body?.string() ?: return@withContext null
                val json = JSONObject(bodyStr)
                val results = json.optJSONArray("results") ?: return@withContext null
                if (results.length() == 0) return@withContext null

                // Pick first valid movie or tv result with a poster
                var bestObj: JSONObject? = null
                for (i in 0 until results.length()) {
                    val obj = results.getJSONObject(i)
                    val mType = obj.optString("media_type", "")
                    if (mType == "movie" || mType == "tv") {
                        if (bestObj == null) bestObj = obj
                        // Prefer one that has both poster and overview
                        val p = obj.optString("poster_path", "")
                        val ov = obj.optString("overview", "")
                        if (p.isNotEmpty() && ov.isNotEmpty()) {
                            bestObj = obj
                            break
                        }
                    }
                }

                val target = bestObj ?: return@withContext null
                val mType = target.optString("media_type", "tv")
                val posterPath = target.optString("poster_path", "")
                val backdropPath = target.optString("backdrop_path", "")
                val overview = target.optString("overview", "").trim()
                val voteAvg = target.optDouble("vote_average", 0.0)

                val spanishTitle = target.optString("name", target.optString("title", cleanQuery)).trim()
                val originalTitle = target.optString("original_name", target.optString("original_title", cleanQuery)).trim()
                val dateStr = target.optString("first_air_date", target.optString("release_date", ""))
                val year = if (dateStr.length >= 4) dateStr.substring(0, 4) else ""

                val ratingFormatted = if (voteAvg > 0) {
                    String.format(Locale.US, "★ %.1f (TMDB)", voteAvg)
                } else ""

                val tmdbId = target.optInt("id", 0)
                var imdbId = ""
                var trailerUrl = ""

                if (tmdbId > 0) {
                    try {
                        val extUrl = "$BASE_URL/$mType/$tmdbId/external_ids?api_key=$apiKey"
                        val extReq = Request.Builder().url(extUrl).build()
                        client.newCall(extReq).execute().use { extResp ->
                            if (extResp.isSuccessful) {
                                val extJson = JSONObject(extResp.body?.string() ?: "")
                                imdbId = extJson.optString("imdb_id", "")
                            }
                        }
                    } catch (e: Exception) {}

                    // Fetch Official YouTube Trailer from TMDB
                    try {
                        fun getTrailer(lang: String): String {
                            val vidUrl = "$BASE_URL/$mType/$tmdbId/videos?api_key=$apiKey&language=$lang"
                            val vidReq = Request.Builder().url(vidUrl).build()
                            client.newCall(vidReq).execute().use { vidResp ->
                                if (vidResp.isSuccessful) {
                                    val vidJson = JSONObject(vidResp.body?.string() ?: "")
                                    val vids = vidJson.optJSONArray("results") ?: return ""
                                    for (j in 0 until vids.length()) {
                                        val v = vids.getJSONObject(j)
                                        val site = v.optString("site", "")
                                        val type = v.optString("type", "")
                                        val key = v.optString("key", "")
                                        if (site.equals("YouTube", true) && key.isNotEmpty()) {
                                            if (type.equals("Trailer", true)) {
                                                return "https://www.youtube.com/watch?v=$key"
                                            }
                                        }
                                    }
                                    if (vids.length() > 0) {
                                        val firstKey = vids.getJSONObject(0).optString("key", "")
                                        if (firstKey.isNotEmpty()) return "https://www.youtube.com/watch?v=$firstKey"
                                    }
                                }
                            }
                            return ""
                        }

                        trailerUrl = getTrailer("es-MX")
                        if (trailerUrl.isEmpty()) {
                            trailerUrl = getTrailer("es-ES")
                        }
                        if (trailerUrl.isEmpty()) {
                            trailerUrl = getTrailer("en-US")
                        }
                    } catch (e: Exception) {}
                }

                // Fallback: If IMDb ID is still missing (common in anime), query Cinemeta search
                if (imdbId.isEmpty()) {
                    try {
                        val cinemetaType = if (mType == "movie") "movie" else "series"
                        val cinemetaUrl = "https://v3-cinemeta.strem.io/catalog/$cinemetaType/top.json?search=${URLEncoder.encode(cleanQuery, "UTF-8")}"
                        val cinemetaReq = Request.Builder().url(cinemetaUrl).header("User-Agent", "Mozilla/5.0").build()
                        client.newCall(cinemetaReq).execute().use { cinResp ->
                            if (cinResp.isSuccessful) {
                                val cinJson = JSONObject(cinResp.body?.string() ?: "")
                                val metas = cinJson.optJSONArray("metas")
                                if (metas != null && metas.length() > 0) {
                                    val first = metas.getJSONObject(0)
                                    imdbId = first.optString("imdb_id", first.optString("id", ""))
                                }
                            }
                        }
                    } catch (e: Exception) {}
                }

                val meta = TmdbMetadata(
                    tmdbId = tmdbId,
                    imdbId = imdbId,
                    trailerUrl = trailerUrl,
                    titleSpanish = spanishTitle,
                    titleOriginal = originalTitle,
                    overview = overview,
                    posterUrl = if (posterPath.isNotEmpty()) "$IMAGE_BASE_W500$posterPath" else "",
                    backdropUrl = if (backdropPath.isNotEmpty()) "$IMAGE_BASE_W1280$backdropPath" else "",
                    ratingText = ratingFormatted,
                    releaseYear = year,
                    mediaType = mType
                )

                memoryCache[cacheKey] = meta
                meta
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Cleans titles from web wrappers by removing season suffixes, dub notices, etc.
     */
    fun sanitizeTitle(title: String): String {
        return title
            .replace(Regex("""(?i)\b(temporada|season|temp|s)\s*\d+.*"""), "")
            .replace(Regex("""(?i)\b(episodio|episode|ep)\s*\d+.*"""), "")
            .replace(Regex("""(?i)\b(audio\s+latino|latino|castellano|sub\s+español|subtitulado|subbed|dubbed|dual)\b.*"""), "")
            .replace(Regex("""(?i)\b(1080p|720p|4k|hd|fhd|bluray|web-dl)\b.*"""), "")
            .replace(Regex("""[\[\(].*?[\]\)]"""), "") // Remove [Br-Rip] or (2024)
            .replace("-", " ")
            .replace("_", " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    /**
     * Fetches detailed episode metadata for a specific season from TMDB, including
     * real chapter titles, individual synopses, still images, and air dates.
     */
    suspend fun getSeasonEpisodes(
        context: Context,
        tmdbId: Int,
        seasonNumber: Int
    ): List<TmdbEpisode> = withContext(Dispatchers.IO) {
        if (tmdbId <= 0) return@withContext emptyList()
        val cacheKey = "$tmdbId-s$seasonNumber"
        seasonCache[cacheKey]?.let { return@withContext it }

        val apiKey = TorrentSettingsStore.getTmdbApiKey(context)
        if (apiKey.isEmpty()) return@withContext emptyList()

        fun parseSeasonJson(jsonStr: String): List<TmdbEpisode> {
            val list = mutableListOf<TmdbEpisode>()
            try {
                val json = JSONObject(jsonStr)
                val epArray = json.optJSONArray("episodes") ?: return list
                for (i in 0 until epArray.length()) {
                    val epObj = epArray.getJSONObject(i)
                    val epNum = epObj.optInt("episode_number", i + 1)
                    val sNum = epObj.optInt("season_number", seasonNumber)
                    val name = epObj.optString("name", "").trim()
                    val overview = epObj.optString("overview", "").trim()
                    val stillPath = epObj.optString("still_path", "").trim()
                    val airDate = epObj.optString("air_date", "").trim()
                    val voteAvg = epObj.optDouble("vote_average", 0.0)

                    list.add(
                        TmdbEpisode(
                            episodeNumber = epNum,
                            seasonNumber = sNum,
                            name = name,
                            overview = overview,
                            stillUrl = if (stillPath.isNotEmpty()) "$IMAGE_BASE_W500$stillPath" else "",
                            airDate = airDate,
                            voteAverage = voteAvg
                        )
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return list
        }

        try {
            // First attempt: Spanish Latin America (es-MX)
            val urlEs = "$BASE_URL/tv/$tmdbId/season/$seasonNumber?api_key=$apiKey&language=es-MX"
            val reqEs = Request.Builder().url(urlEs).header("User-Agent", "AnimeTV/2.8").build()
            var episodes = client.newCall(reqEs).execute().use { resp ->
                if (resp.isSuccessful) parseSeasonJson(resp.body?.string() ?: "") else emptyList()
            }

            // Fallback: If missing titles or overviews, attempt en-US to complement
            val hasMissingTitles = episodes.isEmpty() || episodes.all { it.name.isEmpty() || it.name.startsWith("Episode", true) }
            if (hasMissingTitles) {
                val urlEn = "$BASE_URL/tv/$tmdbId/season/$seasonNumber?api_key=$apiKey&language=en-US"
                val reqEn = Request.Builder().url(urlEn).header("User-Agent", "AnimeTV/2.8").build()
                val fallbackEps = client.newCall(reqEn).execute().use { resp ->
                    if (resp.isSuccessful) parseSeasonJson(resp.body?.string() ?: "") else emptyList()
                }

                if (episodes.isEmpty()) {
                    episodes = fallbackEps
                } else if (fallbackEps.isNotEmpty()) {
                    val fallbackMap = fallbackEps.associateBy { it.episodeNumber }
                    episodes = episodes.map { ep ->
                        val fb = fallbackMap[ep.episodeNumber]
                        if (fb != null) {
                            ep.copy(
                                name = ep.name.ifEmpty { fb.name },
                                overview = ep.overview.ifEmpty { fb.overview },
                                stillUrl = ep.stillUrl.ifEmpty { fb.stillUrl }
                            )
                        } else ep
                    }
                }
            }

            if (episodes.isNotEmpty()) {
                seasonCache[cacheKey] = episodes
            }
            episodes
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }
}
