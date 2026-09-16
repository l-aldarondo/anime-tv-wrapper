package com.example.animetv.core.tmdb

import android.content.Context
import com.example.animetv.core.torrent.TorrentSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
    val titleEnglish: String = "",
    val overview: String,
    val posterUrl: String,
    val backdropUrl: String,
    val ratingText: String,
    val releaseYear: String,
    val mediaType: String, // "tv" or "movie"
    val isAnimation: Boolean = false,
    val numberOfSeasons: Int = 1,
    val seasonEpisodeCounts: Map<Int, Int> = emptyMap(),
    val director: String = "",
    val cast: List<String> = emptyList(),
    val runtimeMinutes: Int = 0,
    val genres: List<String> = emptyList(),
    val certification: String = ""
)

private data class EnDetails(
    val englishTitle: String,
    val numSeasons: Int,
    val seasonCounts: Map<Int, Int>,
    val runtimeMinutes: Int,
    val genres: List<String>
)

private data class Credits(val director: String, val cast: List<String>)

private data class ExtendedDetails(
    val imdbId: String = "",
    val englishTitle: String = "",
    val numSeasons: Int = 1,
    val seasonCounts: Map<Int, Int> = emptyMap(),
    val runtimeMinutes: Int = 0,
    val genres: List<String> = emptyList(),
    val director: String = "",
    val cast: List<String> = emptyList(),
    val certification: String = "",
    val trailerUrl: String = ""
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

    // Fetches the handful of secondary TMDB calls (external ids, English-language details,
    // credits, certification, trailer) needed to fully populate a TmdbMetadata once a tmdbId is
    // known — shared by both the fuzzy title-search path and the direct id-based path. Runs them
    // concurrently: doing them sequentially was slow enough on real devices to make the
    // scraper's own (already-loaded) data seem to "win" more often, purely on timing.
    private suspend fun fetchExtendedDetails(apiKey: String, tmdbId: Int, mType: String): ExtendedDetails = coroutineScope {
        val imdbDeferred = async {
            try {
                val extUrl = "$BASE_URL/$mType/$tmdbId/external_ids?api_key=$apiKey"
                val extReq = Request.Builder().url(extUrl).build()
                client.newCall(extReq).execute().use { extResp ->
                    if (extResp.isSuccessful) {
                        JSONObject(extResp.body?.string() ?: "").optString("imdb_id", "")
                    } else ""
                }
            } catch (e: Exception) { "" }
        }

        val enDetailsDeferred = async {
            var et = ""
            var ns = 1
            val sc = mutableMapOf<Int, Int>()
            var rt = 0
            val genres = mutableListOf<String>()
            try {
                val enUrl = "$BASE_URL/$mType/$tmdbId?api_key=$apiKey&language=en-US"
                val enReq = Request.Builder().url(enUrl).build()
                client.newCall(enReq).execute().use { enResp ->
                    if (enResp.isSuccessful) {
                        val enJson = JSONObject(enResp.body?.string() ?: "")
                        et = enJson.optString("name", enJson.optString("title", "")).trim()
                        if (mType == "tv") {
                            val nSeasons = enJson.optInt("number_of_seasons", 1)
                            if (nSeasons > 1) ns = nSeasons
                            val sArray = enJson.optJSONArray("seasons")
                            if (sArray != null) {
                                for (s in 0 until sArray.length()) {
                                    val sObj = sArray.getJSONObject(s)
                                    val sNum = sObj.optInt("season_number", -1)
                                    val count = sObj.optInt("episode_count", 0)
                                    if (sNum > 0 && count > 0) sc[sNum] = count
                                }
                            }
                            val runtimeArray = enJson.optJSONArray("episode_run_time")
                            if (runtimeArray != null && runtimeArray.length() > 0) {
                                rt = runtimeArray.optInt(0, 0)
                            }
                        } else {
                            rt = enJson.optInt("runtime", 0)
                        }
                        val genresArray = enJson.optJSONArray("genres")
                        if (genresArray != null) {
                            for (g in 0 until genresArray.length()) {
                                val name = genresArray.getJSONObject(g).optString("name", "")
                                if (name.isNotEmpty()) genres.add(name)
                            }
                        }
                    }
                }
            } catch (e: Exception) {}
            EnDetails(et, ns, sc, rt, genres)
        }

        val creditsDeferred = async {
            var dir = ""
            val cast = mutableListOf<String>()
            try {
                val creditsUrl = "$BASE_URL/$mType/$tmdbId/credits?api_key=$apiKey&language=es-MX"
                val creditsReq = Request.Builder().url(creditsUrl).build()
                client.newCall(creditsReq).execute().use { creditsResp ->
                    if (creditsResp.isSuccessful) {
                        val creditsJson = JSONObject(creditsResp.body?.string() ?: "")
                        val crew = creditsJson.optJSONArray("crew")
                        if (crew != null) {
                            for (c in 0 until crew.length()) {
                                val crewObj = crew.getJSONObject(c)
                                if (crewObj.optString("job", "").equals("Director", true)) {
                                    dir = crewObj.optString("name", "")
                                    break
                                }
                            }
                        }
                        val castArray = creditsJson.optJSONArray("cast")
                        if (castArray != null) {
                            for (c in 0 until minOf(castArray.length(), 5)) {
                                val name = castArray.getJSONObject(c).optString("name", "")
                                if (name.isNotEmpty()) cast.add(name)
                            }
                        }
                    }
                }
            } catch (e: Exception) {}
            Credits(dir, cast)
        }

        val certificationDeferred = async {
            var cert = ""
            try {
                if (mType == "movie") {
                    val relUrl = "$BASE_URL/movie/$tmdbId/release_dates?api_key=$apiKey"
                    val relReq = Request.Builder().url(relUrl).build()
                    client.newCall(relReq).execute().use { relResp ->
                        if (relResp.isSuccessful) {
                            val relJson = JSONObject(relResp.body?.string() ?: "")
                            val results2 = relJson.optJSONArray("results")
                            if (results2 != null) {
                                for (r in 0 until results2.length()) {
                                    val countryObj = results2.getJSONObject(r)
                                    if (countryObj.optString("iso_3166_1", "") == "US") {
                                        val dates = countryObj.optJSONArray("release_dates")
                                        if (dates != null) {
                                            for (d in 0 until dates.length()) {
                                                val c2 = dates.getJSONObject(d).optString("certification", "")
                                                if (c2.isNotEmpty()) { cert = c2; break }
                                            }
                                        }
                                        break
                                    }
                                }
                            }
                        }
                    }
                } else {
                    val ratUrl = "$BASE_URL/tv/$tmdbId/content_ratings?api_key=$apiKey"
                    val ratReq = Request.Builder().url(ratUrl).build()
                    client.newCall(ratReq).execute().use { ratResp ->
                        if (ratResp.isSuccessful) {
                            val ratJson = JSONObject(ratResp.body?.string() ?: "")
                            val results2 = ratJson.optJSONArray("results")
                            if (results2 != null) {
                                for (r in 0 until results2.length()) {
                                    val countryObj = results2.getJSONObject(r)
                                    if (countryObj.optString("iso_3166_1", "") == "US") {
                                        cert = countryObj.optString("rating", "")
                                        break
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {}
            cert
        }

        val trailerDeferred = async {
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
                var t = getTrailer("es-MX")
                if (t.isEmpty()) t = getTrailer("es-ES")
                if (t.isEmpty()) t = getTrailer("en-US")
                t
            } catch (e: Exception) { "" }
        }

        val enDetails = enDetailsDeferred.await()
        val credits = creditsDeferred.await()
        ExtendedDetails(
            imdbId = imdbDeferred.await(),
            englishTitle = enDetails.englishTitle,
            numSeasons = enDetails.numSeasons,
            seasonCounts = enDetails.seasonCounts,
            runtimeMinutes = enDetails.runtimeMinutes,
            genres = enDetails.genres,
            director = credits.director,
            cast = credits.cast,
            certification = certificationDeferred.await(),
            trailerUrl = trailerDeferred.await()
        )
    }

    fun getCanonicalSearchTitle(rawTitle: String): String {
        val lower = rawTitle.lowercase(Locale.ROOT).trim()

        // Adventure Time variations
        if (Regex("""(?i)\b(?:la\s+)?hora\s+de\s+(?:la\s+)?aventuras?\b""").containsMatchIn(lower) || lower.contains("adventure time")) {
            if (lower.contains("fionna")) return "Adventure Time: Fionna and Cake"
            if (lower.contains("tierras lejanas") || lower.contains("distant lands")) return "Adventure Time: Distant Lands"
            return "Adventure Time"
        }

        // Yomi no Tsugai / Daemons of the Shadow Realm
        if (lower.contains("yomi no tsugai") || lower.contains("shadow realm") || lower.contains("daemons of the shadow")) {
            return "Daemons of the Shadow Realm"
        }

        // One Piece
        if (lower.contains("one piece")) {
            return "One Piece"
        }

        return sanitizeTitle(rawTitle)
    }

    // Connector words to ignore when comparing titles. Deliberately does NOT filter by length —
    // short franchise suffixes like "Z", "GT" are exactly what distinguishes e.g. "Dragon Ball"
    // from "Dragon Ball Z" from "Dragon Ball GT", so dropping them caused those to be scored as
    // equally similar to the base title, hijacking the wrong show/season.
    private val TITLE_STOPWORDS = setOf(
        "the", "a", "an", "of", "el", "la", "los", "las", "de", "del", "un", "una"
    )

    private fun titleSimilarity(s1: String, s2: String): Double {
        val c1 = s1.lowercase(Locale.ROOT).replace(Regex("""[^a-z0-9\s]"""), " ").trim()
        val c2 = s2.lowercase(Locale.ROOT).replace(Regex("""[^a-z0-9\s]"""), " ").trim()
        if (c1.isEmpty() || c2.isEmpty()) return 0.0
        if (c1 == c2) return 1.0

        val w1 = c1.split(Regex("""\s+""")).filter { it.isNotEmpty() && it !in TITLE_STOPWORDS }
        val w2 = c2.split(Regex("""\s+""")).filter { it.isNotEmpty() && it !in TITLE_STOPWORDS }
        if (w1.isEmpty() || w2.isEmpty()) return 0.0

        // Penalize when one title is a strict superset of the other's words (e.g. query "dragon
        // ball" vs candidate "dragon ball z"/"dragon ball super") — same word-overlap ratio as an
        // exact match, but it is NOT the same show, so it must never tie with a true exact match.
        if (w1.size != w2.size) {
            val (shorter, longer) = if (w1.size < w2.size) w1 to w2 else w2 to w1
            if (shorter.all { it in longer }) {
                return 0.999 * shorter.size / longer.size
            }
        }

        var matches = 0
        for (qw in w1) {
            if (w2.any { cw -> cw == qw }) matches++
        }
        return matches.toDouble() / maxOf(w1.size, w2.size)
    }

    /**
     * Searches TMDB for rich metadata, high-resolution artwork, and original title.
     * Accurately distinguishes between Anime and Live Action adaptations (e.g. One Piece).
     */
    suspend fun searchMetadata(
        context: Context,
        rawTitle: String,
        isMovie: Boolean = false,
        isLiveAction: Boolean = false
    ): TmdbMetadata? = withContext(Dispatchers.IO) {
        val detectedLiveAction = isLiveAction ||
                rawTitle.contains("Live Action", ignoreCase = true) ||
                rawTitle.contains("Acción Real", ignoreCase = true)

        val canonicalQuery = getCanonicalSearchTitle(rawTitle)
        val cleanQuery = sanitizeTitle(rawTitle)
        val queryToSearch = canonicalQuery.ifEmpty { cleanQuery }
        if (queryToSearch.isEmpty()) return@withContext null

        val cacheKey = "${queryToSearch.lowercase(Locale.ROOT)}_m${isMovie}_la$detectedLiveAction"
        memoryCache[cacheKey]?.let {
            android.util.Log.d("TmdbMatch", "CACHE HIT raw='$rawTitle' query='$queryToSearch' key='$cacheKey' -> tmdbId=${it.tmdbId} title='${it.titleSpanish}'")
            return@withContext it
        }

        val apiKey = TorrentSettingsStore.getTmdbApiKey(context)
        if (apiKey.isEmpty()) return@withContext null

        try {
            val encodedQuery = URLEncoder.encode(queryToSearch, "UTF-8")
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

                // Rank results to properly distinguish Anime vs Live Action and Movie vs TV
                var bestObj: JSONObject? = null
                var bestScore = -999

                for (i in 0 until results.length()) {
                    val obj = results.getJSONObject(i)
                    val mType = obj.optString("media_type", "")
                    if (mType != "movie" && mType != "tv") continue

                    val candName = obj.optString("name", obj.optString("title", ""))
                    val origName = obj.optString("original_name", obj.optString("original_title", ""))

                    val sim = maxOf(
                        titleSimilarity(queryToSearch, candName),
                        titleSimilarity(queryToSearch, origName),
                        titleSimilarity(cleanQuery, candName),
                        titleSimilarity(cleanQuery, origName),
                        titleSimilarity(rawTitle, candName),
                        titleSimilarity(rawTitle, origName)
                    )

                    val gArray = obj.optJSONArray("genre_ids")
                    val genreIds = mutableListOf<Int>()
                    if (gArray != null) {
                        for (g in 0 until gArray.length()) genreIds.add(gArray.getInt(g))
                    }
                    val origLang = obj.optString("original_language", "").lowercase(Locale.ROOT)
                    val hasAnimGenre = genreIds.contains(16)
                    val isJapanese = origLang == "ja"

                    var score = 0

                    // Title similarity is highest priority: prevent unrelated anime hijacking
                    if (sim < 0.2) {
                        score -= 200
                    } else {
                        score += (sim * 100).toInt()
                    }

                    // Type preference
                    if (isMovie && mType == "movie") score += 50
                    if (!isMovie && mType == "tv") score += 50

                    // Live Action vs Anime preference
                    if (detectedLiveAction) {
                        if (!hasAnimGenre) score += 50
                        if (!isJapanese) score += 20
                    } else {
                        // Standard Anime: bonus for Animation genre and Japanese origin
                        if (hasAnimGenre) score += 40
                        if (isJapanese) score += 20
                        // Heavy penalty if live action English series is returned for an anime
                        if (!hasAnimGenre && origLang == "en") score -= 80
                    }

                    val p = obj.optString("poster_path", "")
                    val ov = obj.optString("overview", "")
                    if (p.isNotEmpty()) score += 10
                    if (ov.isNotEmpty()) score += 10

                    if (score > bestScore) {
                        bestScore = score
                        bestObj = obj
                    }
                }

                // Reject a clearly poor match outright (e.g. only unrelated/low-similarity
                // candidates came back) rather than returning "the least bad option" — showing
                // no TMDB metadata (falls back to the scraper's own data) is better than showing
                // a different show's info.
                if (bestScore < 0) {
                    android.util.Log.d("TmdbMatch", "REJECTED raw='$rawTitle' query='$queryToSearch' bestScore=$bestScore bestCandidate='${bestObj?.optString("name")}'")
                    return@withContext null
                }
                val target = bestObj ?: return@withContext null
                val mType = target.optString("media_type", if (isMovie) "movie" else "tv")
                val posterPath = target.optString("poster_path", "")
                val backdropPath = target.optString("backdrop_path", "")
                val overview = target.optString("overview", "").trim()
                val voteAvg = target.optDouble("vote_average", 0.0)

                val spanishTitle = target.optString("name", target.optString("title", cleanQuery)).trim()
                val originalTitle = target.optString("original_name", target.optString("original_title", cleanQuery)).trim()
                val dateStr = target.optString("first_air_date", target.optString("release_date", ""))
                val year = if (dateStr.length >= 4) dateStr.substring(0, 4) else ""

                val gArray = target.optJSONArray("genre_ids")
                var isAnimation = false
                if (gArray != null) {
                    for (g in 0 until gArray.length()) {
                        if (gArray.getInt(g) == 16) {
                            isAnimation = true
                            break
                        }
                    }
                }

                val ratingFormatted = if (voteAvg > 0) {
                    String.format(Locale.US, "★ %.1f (TMDB)", voteAvg)
                } else ""

                val tmdbId = target.optInt("id", 0)
                var imdbId = ""
                var englishTitle = ""
                var trailerUrl = ""
                var numSeasons = 1
                var seasonCounts: Map<Int, Int> = emptyMap()
                var runtimeMinutes = 0
                var genreNames: List<String> = emptyList()
                var director = ""
                var castNames: List<String> = emptyList()
                var certification = ""

                if (tmdbId > 0) {
                    val ext = fetchExtendedDetails(apiKey, tmdbId, mType)
                    imdbId = ext.imdbId
                    englishTitle = ext.englishTitle
                    numSeasons = ext.numSeasons
                    seasonCounts = ext.seasonCounts
                    runtimeMinutes = ext.runtimeMinutes
                    genreNames = ext.genres
                    director = ext.director
                    castNames = ext.cast
                    certification = ext.certification
                    trailerUrl = ext.trailerUrl
                }

                // Fallback: If IMDb ID is still missing, query Cinemeta search
                if (imdbId.isEmpty()) {
                    try {
                        val cinemetaType = if (mType == "movie") "movie" else "series"
                        val cinemetaSearch = englishTitle.ifEmpty { cleanQuery }
                        val cinemetaUrl = "https://v3-cinemeta.strem.io/catalog/$cinemetaType/top/search=${URLEncoder.encode(cinemetaSearch, "UTF-8")}.json"
                        val cinemetaReq = Request.Builder().url(cinemetaUrl).header("User-Agent", "Mozilla/5.0").build()
                        client.newCall(cinemetaReq).execute().use { cinResp ->
                            if (cinResp.isSuccessful) {
                                val cinJson = JSONObject(cinResp.body?.string() ?: "")
                                val metas = cinJson.optJSONArray("metas")
                                if (metas != null) {
                                    val qClean = cinemetaSearch.lowercase(Locale.ROOT)
                                    for (m in 0 until metas.length()) {
                                        val mObj = metas.getJSONObject(m)
                                        val mName = mObj.optString("name", "").lowercase(Locale.ROOT)
                                        val mId = mObj.optString("imdb_id", mObj.optString("id", ""))
                                        if (mId.startsWith("tt") && titleSimilarity(mName, qClean) >= 0.4) {
                                            imdbId = mId
                                            break
                                        }
                                    }
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
                    titleEnglish = englishTitle,
                    overview = overview,
                    posterUrl = if (posterPath.isNotEmpty()) "$IMAGE_BASE_W500$posterPath" else "",
                    backdropUrl = if (backdropPath.isNotEmpty()) "$IMAGE_BASE_W1280$backdropPath" else "",
                    ratingText = ratingFormatted,
                    releaseYear = year,
                    mediaType = mType,
                    isAnimation = isAnimation,
                    numberOfSeasons = numSeasons,
                    seasonEpisodeCounts = seasonCounts,
                    director = director,
                    cast = castNames,
                    runtimeMinutes = runtimeMinutes,
                    genres = genreNames,
                    certification = certification
                )

                android.util.Log.d("TmdbMatch", "RESOLVED raw='$rawTitle' query='$queryToSearch' key='$cacheKey' -> tmdbId=$tmdbId title='$spanishTitle' orig='$originalTitle' score=$bestScore poster=${meta.posterUrl}")
                memoryCache[cacheKey] = meta
                meta
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Fetches metadata directly by a known TMDB id, bypassing fuzzy title search entirely.
     * Used when the scraper found an exact TMDB link in the source page's own JSON-LD — the
     * most reliable way to disambiguate franchise entries (One Piece vs. its live-action
     * adaptation, Dragon Ball vs. Z vs. GT, etc.) since it isn't guessing from a title string.
     */
    suspend fun getMetadataByTmdbId(
        context: Context,
        tmdbId: Int,
        mediaType: String
    ): TmdbMetadata? = withContext(Dispatchers.IO) {
        if (tmdbId <= 0 || (mediaType != "tv" && mediaType != "movie")) return@withContext null

        val cacheKey = "id_${mediaType}_$tmdbId"
        memoryCache[cacheKey]?.let {
            android.util.Log.d("TmdbMatch", "CACHE HIT (by id) tmdbId=$tmdbId -> title='${it.titleSpanish}'")
            return@withContext it
        }

        val apiKey = TorrentSettingsStore.getTmdbApiKey(context)
        if (apiKey.isEmpty()) return@withContext null

        try {
            val url = "$BASE_URL/$mediaType/$tmdbId?api_key=$apiKey&language=es-MX"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "AnimeTV/2.8")
                .header("Accept", "application/json")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val bodyStr = response.body?.string() ?: return@withContext null
                val target = JSONObject(bodyStr)

                val posterPath = target.optString("poster_path", "")
                val backdropPath = target.optString("backdrop_path", "")
                val overview = target.optString("overview", "").trim()
                val voteAvg = target.optDouble("vote_average", 0.0)
                val spanishTitle = target.optString("name", target.optString("title", "")).trim()
                val originalTitle = target.optString("original_name", target.optString("original_title", spanishTitle)).trim()
                val dateStr = target.optString("first_air_date", target.optString("release_date", ""))
                val year = if (dateStr.length >= 4) dateStr.substring(0, 4) else ""

                var isAnimation = false
                val genresArray = target.optJSONArray("genres")
                if (genresArray != null) {
                    for (g in 0 until genresArray.length()) {
                        if (genresArray.getJSONObject(g).optInt("id", -1) == 16) {
                            isAnimation = true
                            break
                        }
                    }
                }

                val ratingFormatted = if (voteAvg > 0) String.format(Locale.US, "★ %.1f (TMDB)", voteAvg) else ""

                val ext = fetchExtendedDetails(apiKey, tmdbId, mediaType)

                val meta = TmdbMetadata(
                    tmdbId = tmdbId,
                    imdbId = ext.imdbId,
                    trailerUrl = ext.trailerUrl,
                    titleSpanish = spanishTitle,
                    titleOriginal = originalTitle,
                    titleEnglish = ext.englishTitle,
                    overview = overview,
                    posterUrl = if (posterPath.isNotEmpty()) "$IMAGE_BASE_W500$posterPath" else "",
                    backdropUrl = if (backdropPath.isNotEmpty()) "$IMAGE_BASE_W1280$backdropPath" else "",
                    ratingText = ratingFormatted,
                    releaseYear = year,
                    mediaType = mediaType,
                    isAnimation = isAnimation,
                    numberOfSeasons = ext.numSeasons,
                    seasonEpisodeCounts = ext.seasonCounts,
                    director = ext.director,
                    cast = ext.cast,
                    runtimeMinutes = ext.runtimeMinutes,
                    genres = ext.genres,
                    certification = ext.certification
                )

                android.util.Log.d("TmdbMatch", "RESOLVED (by id) tmdbId=$tmdbId -> title='$spanishTitle' orig='$originalTitle' poster=${meta.posterUrl}")
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
            .replace(Regex("""\b(19\d{2}|20\d{2})\b"""), "") // Remove standalone 4-digit release years like 2010
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
