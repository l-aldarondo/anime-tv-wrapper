package com.example.animetv.core.torrent

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

data class TorrentStreamItem(
    val title: String,
    val magnetUrl: String,
    val seeders: Int,
    val sizeBytes: Long,
    val sizeFormatted: String,
    val resolutionBadge: String, // "1080p", "720p", "HD"
    val languageBadge: String,   // "🇪🇸 Latino", "🇪🇸 Castellano", "🌐 Dual Audio", "🇺🇸 Inglés", "🌐 Multi"
    val languagePriority: Int,   // 1 = Latino/Castellano, 2 = Dual, 3 = English, 4 = other
    val provider: String         // "Jackett", "Torrentio", "Nyaa"
)

object TorrentSearchRepository {

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Searches for torrents across configured Jackett/Prowlarr and public zero-config backends (Torrentio / Nyaa).
     * Applies strict filters: <= 1080p, DISALLOW 4K/2160p, sort by Spanish/Latino > English > Seeders DESC.
     */
    suspend fun searchAndFilter(
        context: Context,
        query: String,
        originalQuery: String = "",
        imdbId: String = "",
        seasonNumber: Int = 1,
        episodeNumber: Int = 1,
        isMovie: Boolean = false
    ): List<TorrentStreamItem> = withContext(Dispatchers.IO) {
        val jackettUrl = TorrentSettingsStore.getJackettUrl(context)
        val jackettKey = TorrentSettingsStore.getJackettApiKey(context)
        val hasIndexer = jackettUrl.isNotEmpty() && jackettKey.isNotEmpty()

        val rawResults = mutableListOf<TorrentStreamItem>()

        // 1. Fast IMDb ID resolution if not yet provided by TMDB
        val resolvedImdbId = if (imdbId.isNotEmpty()) {
            imdbId
        } else {
            resolveImdbIdFast(query, originalQuery, isMovie)
        }

        // 2. Query Jackett or Prowlarr (if configured)
        val indexerDeferred = async {
            if (hasIndexer) {
                val searchQuery = if (originalQuery.isNotEmpty()) originalQuery else query
                fetchFromIndexer(jackettUrl, jackettKey, searchQuery, seasonNumber, episodeNumber, isMovie)
            } else emptyList()
        }

        // 3. Query Torrentio API (if imdbId available)
        val torrentioDeferred = async {
            if (resolvedImdbId.isNotEmpty()) {
                fetchFromTorrentio(resolvedImdbId, seasonNumber, episodeNumber, isMovie)
            } else emptyList()
        }

        // 4. Query Nyaa for anime (fallback keyword search)
        val nyaaDeferred = async {
            val q = if (originalQuery.isNotEmpty()) originalQuery else query
            fetchFromNyaa(q, episodeNumber, isMovie)
        }

        rawResults.addAll(indexerDeferred.await())
        rawResults.addAll(torrentioDeferred.await())
        rawResults.addAll(nyaaDeferred.await())

        // Deduplicate by info_hash or magnet
        val uniqueByHash = rawResults.distinctBy { extractInfoHash(it.magnetUrl).ifEmpty { it.title } }

        // Filter and sort according to user's strict rules
        filterAndRankTorrents(
            items = uniqueByHash,
            disallow4k = TorrentSettingsStore.isDisallow4k(context),
            preferSpanish = TorrentSettingsStore.isPreferSpanish(context),
            qualityFilter = TorrentSettingsStore.getQualityFilter(context),
            languageFilter = TorrentSettingsStore.getLanguageFilter(context)
        )
    }

    private fun resolveImdbIdFast(query: String, originalQuery: String, isMovie: Boolean): String {
        val searchTerms = listOf(
            originalQuery.trim(),
            query.replace(Regex("""(?i)\b(Temporada \d+|Season \d+|Audio Latino|Castellano|Latino|Dual)\b"""), "").trim()
        ).filter { it.isNotEmpty() }.distinct()

        val type = if (isMovie) "movie" else "series"
        for (term in searchTerms) {
            try {
                val encoded = URLEncoder.encode(term, "UTF-8")
                val url = "https://v3-cinemeta.strem.io/catalog/$type/top.json?search=$encoded"
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string() ?: return@use
                        val json = JSONObject(body)
                        val metas = json.optJSONArray("metas")
                        if (metas != null && metas.length() > 0) {
                            val first = metas.getJSONObject(0)
                            val id = first.optString("imdb_id", first.optString("id", ""))
                            if (id.startsWith("tt")) return id
                        }
                    }
                }
            } catch (e: Exception) {
                // Try next term
            }
        }
        return ""
    }

    private fun filterAndRankTorrents(
        items: List<TorrentStreamItem>,
        disallow4k: Boolean,
        preferSpanish: Boolean,
        qualityFilter: String = "1080p",
        languageFilter: String = "all"
    ): List<TorrentStreamItem> {
        val filtered = mutableListOf<TorrentStreamItem>()

        for (item in items) {
            val lower = item.title.lowercase(Locale.ROOT)

            // --- FILTRO 1: PROHIBIDO 4K/2160p/UHD ---
            val is4k = lower.contains("2160p") || lower.contains("4k") || lower.contains("uhd")
            if (disallow4k && is4k) continue

            // Disallow very low quality CAM / TS / Telesync
            if (lower.contains("camrip") || lower.contains("telesync") || lower.contains("hdcam")) continue

            val resBadge = when {
                lower.contains("1080p") || item.resolutionBadge.contains("1080") -> "1080p"
                lower.contains("720p") || item.resolutionBadge.contains("720") -> "720p"
                is4k -> "4K"
                else -> "HD"
            }

            // --- FILTRO 2: Calidad Máxima Solicitada por el Usuario ---
            if (qualityFilter == "1080p") {
                // Keep 1080p and general HD, discard if strictly 720p or lower
                if (resBadge == "720p" && !lower.contains("1080p")) continue
            } else if (qualityFilter == "720p") {
                // User wants 720p specifically
                if (resBadge == "1080p" || resBadge == "4K") continue
            }

            val (langBadge, priority) = detectLanguage(lower, preferSpanish)

            // --- FILTRO 3: Filtro de Idioma Solicitado por el Usuario ---
            if (languageFilter == "spanish_only" && priority > 1) {
                continue
            } else if (languageFilter == "dual_audio" && priority > 2) {
                continue
            } else if (languageFilter == "sub_only" && !langBadge.contains("Sub")) {
                continue
            }

            filtered.add(
                item.copy(
                    resolutionBadge = resBadge,
                    languageBadge = langBadge,
                    languagePriority = priority
                )
            )
        }

        // Graceful Fallback: If strict filters eliminated everything, but raw streams exist,
        // show the available non-4K streams instead of giving the user a blank empty screen!
        if (filtered.isEmpty() && items.isNotEmpty()) {
            return items.filter { item ->
                val lower = item.title.lowercase(Locale.ROOT)
                !(disallow4k && (lower.contains("2160p") || lower.contains("4k") || lower.contains("uhd")))
            }.sortedWith(compareBy({ it.languagePriority }, { -it.seeders }))
        }

        // Ordenar: Prioridad de idioma ASC (1=Latino/Español, 2=Dual, 3=Inglés), luego mayor número de Seeders DESC
        return filtered.sortedWith(compareBy({ it.languagePriority }, { -it.seeders }))
    }

    private fun detectLanguage(titleLower: String, preferSpanish: Boolean): Pair<String, Int> {
        val hasLatino = titleLower.contains("latino") || titleLower.contains("audio latino") || titleLower.contains("es-la")
        val hasCastellano = titleLower.contains("castellano") || titleLower.contains("spanish") || titleLower.contains("es-es")
        val hasDual = titleLower.contains("dual") || titleLower.contains("multi") || (hasLatino && titleLower.contains("eng"))

        return when {
            hasLatino -> Pair("🇪🇸 Latino", if (preferSpanish) 1 else 2)
            hasCastellano -> Pair("🇪🇸 Castellano", if (preferSpanish) 1 else 2)
            hasDual -> Pair("🌐 Dual Audio", 2)
            titleLower.contains("sub") || titleLower.contains("vostfr") -> Pair("💬 Subtitulado", 3)
            else -> Pair("🇺🇸 Inglés", 3)
        }
    }

    private fun fetchFromTorrentio(
        imdbId: String,
        seasonNumber: Int,
        episodeNumber: Int,
        isMovie: Boolean
    ): List<TorrentStreamItem> {
        val list = mutableListOf<TorrentStreamItem>()
        try {
            val path = if (isMovie) {
                "stream/movie/$imdbId.json"
            } else {
                "stream/series/$imdbId:$seasonNumber:$episodeNumber.json"
            }
            val url = "https://torrentio.strem.fun/$path"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0")
                .header("Accept", "application/json")
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return list
                val body = resp.body?.string() ?: return list
                val json = JSONObject(body)
                val streams = json.optJSONArray("streams") ?: return list

                for (i in 0 until streams.length()) {
                    val stream = streams.getJSONObject(i)
                    val rawTitle = stream.optString("title", "")
                    val name = stream.optString("name", "Torrentio")
                    val infoHash = stream.optString("infoHash", "")

                    val lines = rawTitle.split("\n")
                    val titleLine = lines.firstOrNull()?.trim() ?: "Stream $i"
                    val details = lines.drop(1).joinToString(" ")

                    // Extract seeders from Torrentio details (e.g. "👤 45")
                    val seedersMatch = Regex("""👤\s*(\d+)""").find(details)
                    val seeders = seedersMatch?.groupValues?.get(1)?.toIntOrNull() ?: 5

                    // Extract size
                    val sizeMatch = Regex("""💾\s*([\d\.]+\s*(?:GB|MB))""").find(details)
                    val sizeFormatted = sizeMatch?.groupValues?.get(1) ?: ""

                    val magnetUrl = if (infoHash.isNotEmpty()) {
                        "magnet:?xt=urn:btih:$infoHash&dn=${URLEncoder.encode(titleLine, "UTF-8")}&tr=udp%3A%2F%2Ftracker.opentrackr.org%3A1337%2Fannounce"
                    } else ""

                    if (magnetUrl.isNotEmpty()) {
                        list.add(
                            TorrentStreamItem(
                                title = "$name - $titleLine",
                                magnetUrl = magnetUrl,
                                seeders = seeders,
                                sizeBytes = 0L,
                                sizeFormatted = sizeFormatted,
                                resolutionBadge = if (titleLine.contains("1080p") || name.contains("1080p")) "1080p" else "720p",
                                languageBadge = "🌐 Multi",
                                languagePriority = 3,
                                provider = "Torrentio"
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    private fun fetchFromNyaa(
        query: String,
        episodeNumber: Int,
        isMovie: Boolean
    ): List<TorrentStreamItem> {
        val list = mutableListOf<TorrentStreamItem>()
        try {
            val cleanQ = query.replace(Regex("""(?i)\b(Temporada \d+|Season \d+|Audio Latino|Castellano|Latino|Dual)\b"""), "").trim()
            val epPattern = if (episodeNumber > 0 && !isMovie) {
                String.format(Locale.US, "%s %02d", cleanQ, episodeNumber)
            } else {
                cleanQ
            }
            val encoded = URLEncoder.encode(epPattern, "UTF-8")
            val url = "https://nyaa.si/?page=rss&q=$encoded&c=1_2&f=0"

            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0")
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return list
                val xml = resp.body?.string() ?: return list
                val doc = Jsoup.parse(xml, "", org.jsoup.parser.Parser.xmlParser())
                val items = doc.select("item")

                for (item in items) {
                    val title = item.selectFirst("title")?.text()?.trim() ?: continue
                    val magnet = item.selectFirst("nyaa\\:infoHash, infoHash")?.text()?.trim()
                        ?: item.selectFirst("link")?.text()?.trim() ?: ""
                    val seeders = item.selectFirst("nyaa\\:seeders, seeders")?.text()?.toIntOrNull() ?: 1
                    val sizeStr = item.selectFirst("nyaa\\:size, size")?.text()?.trim() ?: ""

                    val magnetUri = if (magnet.startsWith("magnet:")) {
                        magnet
                    } else if (magnet.length in 32..40) {
                        "magnet:?xt=urn:btih:$magnet&dn=${URLEncoder.encode(title, "UTF-8")}&tr=udp%3A%2F%2Ftracker.opentrackr.org%3A1337%2Fannounce"
                    } else ""

                    if (magnetUri.isNotEmpty()) {
                        list.add(
                            TorrentStreamItem(
                                title = title,
                                magnetUrl = magnetUri,
                                seeders = seeders,
                                sizeBytes = 0L,
                                sizeFormatted = sizeStr,
                                resolutionBadge = if (title.contains("1080p")) "1080p" else "720p",
                                languageBadge = "💬 Sub / Dual",
                                languagePriority = 3,
                                provider = "Nyaa"
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    /**
     * Unified Indexer query supporting both Jackett and Prowlarr native REST APIs.
     */
    private fun fetchFromIndexer(
        indexerUrl: String,
        apiKey: String,
        query: String,
        seasonNumber: Int,
        episodeNumber: Int,
        isMovie: Boolean
    ): List<TorrentStreamItem> {
        val q = if (!isMovie && seasonNumber > 0 && episodeNumber > 0) {
            String.format(Locale.US, "%s S%02dE%02d", query, seasonNumber, episodeNumber)
        } else {
            query
        }

        // 1. If URL targets Prowlarr (port 9696 or 'prowlarr' in path), query Prowlarr API first
        if (indexerUrl.contains(":9696") || indexerUrl.lowercase(Locale.ROOT).contains("prowlarr")) {
            val prowlarrResults = queryProwlarr(indexerUrl, apiKey, q)
            if (prowlarrResults.isNotEmpty()) return prowlarrResults
        }

        // 2. Query Jackett Torznab API
        val jackettResults = queryJackett(indexerUrl, apiKey, q)
        if (jackettResults.isNotEmpty()) return jackettResults

        // 3. Fallback: If Jackett returned 404 or empty, attempt Prowlarr
        return queryProwlarr(indexerUrl, apiKey, q)
    }

    private fun queryProwlarr(baseUrl: String, apiKey: String, query: String): List<TorrentStreamItem> {
        val list = mutableListOf<TorrentStreamItem>()
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "$baseUrl/api/v1/search?query=$encoded&type=search"
            val req = Request.Builder()
                .url(url)
                .header("X-Api-Key", apiKey)
                .header("User-Agent", "AnimeTV/2.8")
                .header("Accept", "application/json")
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return list
                val body = resp.body?.string() ?: return list
                val jsonArr = org.json.JSONArray(body)

                for (i in 0 until jsonArr.length()) {
                    val item = jsonArr.getJSONObject(i)
                    val title = item.optString("title", "")
                    var magnetUri = item.optString("magnetUrl", "")
                    val infoHash = item.optString("infoHash", "")
                    val downloadUrl = item.optString("downloadUrl", "")

                    if (magnetUri.isEmpty() && infoHash.isNotEmpty()) {
                        magnetUri = "magnet:?xt=urn:btih:$infoHash&dn=${URLEncoder.encode(title, "UTF-8")}&tr=udp%3A%2F%2Ftracker.opentrackr.org%3A1337%2Fannounce"
                    } else if (magnetUri.isEmpty() && downloadUrl.startsWith("magnet:")) {
                        magnetUri = downloadUrl
                    }

                    val seeders = item.optInt("seeders", 0)
                    val size = item.optLong("size", 0L)
                    val sizeFormatted = formatFileSize(size)
                    val indexer = item.optString("indexer", "Prowlarr")

                    if (magnetUri.isNotEmpty()) {
                        list.add(
                            TorrentStreamItem(
                                title = title,
                                magnetUrl = magnetUri,
                                seeders = seeders,
                                sizeBytes = size,
                                sizeFormatted = sizeFormatted,
                                resolutionBadge = if (title.contains("1080p")) "1080p" else "720p",
                                languageBadge = "🌐 Multi",
                                languagePriority = 2,
                                provider = "Prowlarr ($indexer)"
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    private fun queryJackett(baseUrl: String, apiKey: String, query: String): List<TorrentStreamItem> {
        val list = mutableListOf<TorrentStreamItem>()
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "$baseUrl/api/v2.0/indexers/all/results?apikey=$apiKey&Query=$encoded"

            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "AnimeTV/2.8")
                .header("Accept", "application/json")
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return list
                val body = resp.body?.string() ?: return list
                val json = JSONObject(body)
                val results = json.optJSONArray("Results") ?: return list

                for (i in 0 until results.length()) {
                    val item = results.getJSONObject(i)
                    val title = item.optString("Title", "")
                    val magnetUri = item.optString("MagnetUri", "")
                    val seeders = item.optInt("Seeders", 0)
                    val size = item.optLong("Size", 0L)
                    val sizeFormatted = formatFileSize(size)
                    val tracker = item.optString("Tracker", "Jackett")

                    if (magnetUri.isNotEmpty()) {
                        list.add(
                            TorrentStreamItem(
                                title = title,
                                magnetUrl = magnetUri,
                                seeders = seeders,
                                sizeBytes = size,
                                sizeFormatted = sizeFormatted,
                                resolutionBadge = if (title.contains("1080p")) "1080p" else "720p",
                                languageBadge = "🇪🇸 Latino",
                                languagePriority = 1,
                                provider = tracker
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    private fun extractInfoHash(magnet: String): String {
        val match = Regex("""xt=urn:btih:([a-zA-Z0-9]+)""").find(magnet)
        return match?.groupValues?.get(1)?.lowercase(Locale.ROOT) ?: ""
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return ""
        val gb = bytes / (1024.0 * 1024.0 * 1024.0)
        return if (gb >= 1.0) {
            String.format(Locale.US, "%.1f GB", gb)
        } else {
            val mb = bytes / (1024.0 * 1024.0)
            String.format(Locale.US, "%.0f MB", mb)
        }
    }
}
