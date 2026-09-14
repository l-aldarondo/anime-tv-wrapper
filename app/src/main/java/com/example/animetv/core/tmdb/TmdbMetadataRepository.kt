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
    val titleSpanish: String,
    val titleOriginal: String,
    val overview: String,
    val posterUrl: String,
    val backdropUrl: String,
    val ratingText: String,
    val releaseYear: String,
    val mediaType: String // "tv" or "movie"
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
                }

                val meta = TmdbMetadata(
                    tmdbId = tmdbId,
                    imdbId = imdbId,
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
}
