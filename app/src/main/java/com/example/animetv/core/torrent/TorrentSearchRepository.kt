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
    val provider: String,        // "Jackett", "Torrentio", "Nyaa"
    val fileIndex: Int = 1
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
        englishQuery: String = "",
        imdbId: String = "",
        seasonNumber: Int = 1,
        episodeNumber: Int = 1,
        isMovie: Boolean = false,
        isLiveAction: Boolean = false
    ): List<TorrentStreamItem> = withContext(Dispatchers.IO) {
        val jackettUrl = TorrentSettingsStore.getJackettUrl(context)
        val jackettKey = TorrentSettingsStore.getJackettApiKey(context)
        val hasIndexer = jackettUrl.isNotEmpty() && jackettKey.isNotEmpty()

        val rawResults = mutableListOf<TorrentStreamItem>()

        // 1. Fast IMDb ID resolution if not yet provided by TMDB
        val resolvedImdbId = if (imdbId.isNotEmpty()) {
            imdbId
        } else {
            resolveImdbIdFast(query, originalQuery, englishQuery, isMovie, isLiveAction)
        }

        // 2. Query Jackett or Prowlarr (if configured)
        val indexerDeferred = async {
            if (hasIndexer) {
                val searchQuery = if (englishQuery.isNotEmpty() && !isLiveAction) englishQuery
                else if (originalQuery.isNotEmpty()) originalQuery else query
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
            if (!isLiveAction) {
                fetchFromNyaa(query, originalQuery, englishQuery, episodeNumber, isMovie)
            } else emptyList()
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
            languageFilter = TorrentSettingsStore.getLanguageFilter(context),
            isSingleEpisode = episodeNumber > 0 && !isMovie,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            isLiveAction = isLiveAction
        )
    }

    private fun resolveImdbIdFast(
        query: String,
        originalQuery: String,
        englishQuery: String,
        isMovie: Boolean,
        isLiveAction: Boolean
    ): String {
        val cleanQ = query.replace(Regex("""(?i)\b(Temporada \d+|Season \d+|Audio Latino|Castellano|Latino|Dual)\b"""), "").trim()
        val searchTerms = mutableListOf<String>()
        if (englishQuery.isNotEmpty()) searchTerms.add(englishQuery.trim())
        if (cleanQ.isNotEmpty()) searchTerms.add(cleanQ)
        if (originalQuery.isNotEmpty()) searchTerms.add(originalQuery.trim())
        val noColon = cleanQ.substringBefore(":").trim()
        if (noColon.isNotEmpty()) searchTerms.add(noColon)

        // Known title aliases
        if (cleanQ.contains("yomi no tsugai", true) || originalQuery.contains("yomi", true)) {
            searchTerms.add(0, "Daemons of the Shadow Realm")
        }
        if (cleanQ.contains("hora de aventura", true) || originalQuery.contains("adventure time", true)) {
            searchTerms.add(0, "Adventure Time")
        }

        val type = if (isMovie) "movie" else "series"
        for (term in searchTerms.distinct()) {
            try {
                val encoded = URLEncoder.encode(term, "UTF-8")
                val url = "https://v3-cinemeta.strem.io/catalog/$type/top/search=$encoded.json"
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string() ?: return@use
                        val json = JSONObject(body)
                        val metas = json.optJSONArray("metas") ?: return@use
                        for (i in 0 until metas.length()) {
                            val metaObj = metas.getJSONObject(i)
                            val candidateName = metaObj.optString("name", "")
                            val id = metaObj.optString("imdb_id", metaObj.optString("id", ""))

                            // Precise matching for One Piece (Anime vs Live Action)
                            if (term.contains("one piece", true)) {
                                if (!isLiveAction && id == "tt0388629") return id
                                if (isLiveAction && id == "tt11737520") return id
                            }

                            // Precise matching for Yomi no Tsugai / Daemons of the Shadow Realm
                            if ((term.contains("yomi", true) || term.contains("shadow realm", true)) && id == "tt37532356") {
                                return id
                            }

                            if (id.startsWith("tt") && (isTitleSimilar(candidateName, term) || isTitleSimilar(term, candidateName))) {
                                return id
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Try next term
            }
        }
        return ""
    }

    private fun isTitleSimilar(candidateName: String, query: String): Boolean {
        val c = candidateName.lowercase(Locale.ROOT).replace(Regex("""[^a-z0-9\s]"""), " ").trim()
        val q = query.lowercase(Locale.ROOT).replace(Regex("""[^a-z0-9\s]"""), " ").trim()
        if (c.isEmpty() || q.isEmpty()) return false
        if (c == q || c.contains(q) || q.contains(c)) return true
        val cWords = c.split(Regex("""\s+""")).filter { it.length > 2 }
        val qWords = q.split(Regex("""\s+""")).filter { it.length > 2 }
        if (qWords.isEmpty()) return false
        val matches = qWords.count { qw -> cWords.any { cw -> cw == qw || cw.contains(qw) || qw.contains(cw) } }
        return matches.toDouble() / qWords.size >= 0.5
    }

    private fun filterAndRankTorrents(
        items: List<TorrentStreamItem>,
        disallow4k: Boolean,
        preferSpanish: Boolean,
        qualityFilter: String = "1080p",
        languageFilter: String = "all",
        isSingleEpisode: Boolean = false,
        seasonNumber: Int = 1,
        episodeNumber: Int = 1,
        isLiveAction: Boolean = false
    ): List<TorrentStreamItem> {
        val filtered = mutableListOf<TorrentStreamItem>()

        for (item in items) {
            val lower = item.title.lowercase(Locale.ROOT)

            // --- FILTRO LIVE ACTION VS ANIME ---
            val hasLiveActionTag = lower.contains("live action") || lower.contains("live-action") || lower.contains("accion real")
            if (isLiveAction && !hasLiveActionTag) {
                if (lower.contains("anime") || lower.contains("[subsplease]") || lower.contains("[erai-raws]")) continue
            } else if (!isLiveAction && hasLiveActionTag) {
                continue
            }

            // --- FILTRO EPISODIO AISLADO: descartar paquetes de temporada completa / series completas ---
            if (isSingleEpisode) {
                val isMultiSeasonOrComplete = lower.contains("complete series") ||
                        lower.contains("all seasons") ||
                        lower.contains("temporadas 1-") ||
                        lower.contains("temporadas completas") ||
                        lower.contains("temporada completa") ||
                        lower.contains("full season") ||
                        lower.contains("batch") ||
                        lower.contains("01-12") ||
                        lower.contains("01-24") ||
                        Regex("""(?i)\b(?:season|seasons|temporada|temporadas|s)\s*\d+\s*[-–]\s*s?\d+\b""").containsMatchIn(lower)
                if (isMultiSeasonOrComplete) continue

                // Check wrong season number
                if (seasonNumber > 0) {
                    val wrongSeasonMatch = Regex("""(?i)\b(?:s0?(\d+)e\d+|(\d+)x\d+)\b""").find(lower)
                    if (wrongSeasonMatch != null) {
                        val s = wrongSeasonMatch.groupValues[1].ifEmpty { wrongSeasonMatch.groupValues[2] }.toIntOrNull()
                        if (s != null && s != seasonNumber) continue
                    }
                }
            }

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

        // Ordenar: Prioridad de idioma ASC (1=Latino/Español, 2=Dual, 3=Inglés),
        // luego episodios individuales puros primero, luego mayor número de Seeders DESC
        return filtered.sortedWith(
            compareBy(
                { it.languagePriority },
                { item ->
                    if (isSingleEpisode) {
                        val lower = item.title.lowercase(Locale.ROOT)
                        val sPad = String.format(Locale.US, "%02d", seasonNumber)
                        val ePad = String.format(Locale.US, "%02d", episodeNumber)
                        val isPureSingle = (lower.contains("s${sPad}e${ePad}") || lower.contains("${seasonNumber}x${ePad}") || lower.contains("${seasonNumber}x${episodeNumber}")) &&
                                !lower.contains("season") && !lower.contains("temporada")
                        if (isPureSingle) 0 else 1
                    } else 0
                },
                { -it.seeders }
            )
        )
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
                    val fileIdx = if (stream.has("fileIdx")) stream.optInt("fileIdx", 0) + 1 else 1

                    val lines = rawTitle.split("\n")
                    val packName = lines.firstOrNull()?.trim() ?: "Stream $i"
                    val epFileName = if (lines.size > 1 && !lines[1].contains("👤")) lines[1].trim() else ""
                    val details = lines.find { it.contains("👤") } ?: lines.drop(1).joinToString(" ")

                    // Filter out multi-season / complete series packs when querying a specific episode
                    if (!isMovie && episodeNumber > 0) {
                        val lowerRaw = rawTitle.lowercase(Locale.ROOT)
                        val isMultiSeason = lowerRaw.contains("complete series") ||
                                lowerRaw.contains("all seasons") ||
                                lowerRaw.contains("temporadas 1-") ||
                                Regex("""(?i)\b(?:season|seasons|temporada|temporadas|s)\s*\d+\s*[-–]\s*s?\d+\b""").containsMatchIn(lowerRaw)
                        if (isMultiSeason) continue

                        // Filter out mismatched season
                        if (seasonNumber > 0) {
                            val wrongSeasonMatch = Regex("""(?i)\b(?:s0?(\d+)e\d+|(\d+)x\d+)\b""").find(lowerRaw)
                            if (wrongSeasonMatch != null) {
                                val s = wrongSeasonMatch.groupValues[1].ifEmpty { wrongSeasonMatch.groupValues[2] }.toIntOrNull()
                                if (s != null && s != seasonNumber) continue
                            }
                        }
                    }

                    // Extract seeders from Torrentio details (e.g. "👤 45")
                    val seedersMatch = Regex("""👤\s*(\d+)""").find(details)
                    val seeders = seedersMatch?.groupValues?.get(1)?.toIntOrNull() ?: 5

                    // Extract size
                    val sizeMatch = Regex("""💾\s*([\d\.]+\s*(?:GB|MB))""").find(details)
                    val sizeFormatted = sizeMatch?.groupValues?.get(1) ?: ""

                    // Construct clear episode title for display
                    val displayTitle = if (epFileName.isNotEmpty()) {
                        val cleanEp = epFileName.substringAfterLast("/").replace(Regex("""\.(?:mkv|mp4|avi)$""", RegexOption.IGNORE_CASE), "").replace(Regex("""[._]"""), " ").trim()
                        if (cleanEp.isNotEmpty() && !cleanEp.equals(packName, ignoreCase = true)) {
                            "$cleanEp  [$packName]"
                        } else {
                            packName
                        }
                    } else {
                        packName
                    }

                    val magnetUrl = if (infoHash.isNotEmpty()) {
                        "magnet:?xt=urn:btih:$infoHash&dn=${URLEncoder.encode(packName, "UTF-8")}&tr=udp%3A%2F%2Ftracker.opentrackr.org%3A1337%2Fannounce"
                    } else ""

                    if (magnetUrl.isNotEmpty()) {
                        list.add(
                            TorrentStreamItem(
                                title = displayTitle,
                                magnetUrl = magnetUrl,
                                seeders = seeders,
                                sizeBytes = 0L,
                                sizeFormatted = sizeFormatted,
                                resolutionBadge = if (displayTitle.contains("1080p") || name.contains("1080p")) "1080p" else "720p",
                                languageBadge = "🌐 Multi",
                                languagePriority = 3,
                                provider = "Torrentio",
                                fileIndex = fileIdx
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
        originalQuery: String,
        englishQuery: String,
        episodeNumber: Int,
        isMovie: Boolean
    ): List<TorrentStreamItem> {
        val list = mutableListOf<TorrentStreamItem>()
        fun cleanForNyaa(s: String): String {
            return s.replace(Regex("""(?i)\b(Temporada \d+|Season \d+|Audio Latino|Castellano|Latino|Dual)\b"""), "")
                .replace(Regex(""":.*"""), "")
                .trim()
        }

        val queriesToTry = mutableListOf<String>()
        val cQuery = cleanForNyaa(query)
        if (cQuery.isNotEmpty()) queriesToTry.add(cQuery)
        val cEng = cleanForNyaa(englishQuery)
        if (cEng.isNotEmpty() && !queriesToTry.contains(cEng)) queriesToTry.add(cEng)

        if (cQuery.contains("yomi", true) || cQuery.contains("shadow realm", true)) {
            queriesToTry.add(0, "Yomi no Tsugai")
        }

        for (q in queriesToTry.distinct()) {
            try {
                val epPattern = if (episodeNumber > 0 && !isMovie) {
                    String.format(Locale.US, "%s %02d", q, episodeNumber)
                } else {
                    q
                }
                val encoded = URLEncoder.encode(epPattern, "UTF-8")
                val url = "https://nyaa.si/?page=rss&q=$encoded&c=1_2&f=0"

                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0")
                    .build()

                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val xml = resp.body?.string() ?: return@use
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
                                    provider = "Nyaa",
                                    fileIndex = 1
                                )
                            )
                        }
                    }
                }
                if (list.isNotEmpty()) break
            } catch (e: Exception) {
                e.printStackTrace()
            }
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
