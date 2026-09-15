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
import java.util.concurrent.ConcurrentHashMap
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
    val fileIndex: Int = 1,
    val tier: Int = 2            // 1 = Single episode exclusive, 2 = Season pack with target episode, 3 = Multi-season pack
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

        // 1. Fast IMDb ID resolution (with canonical override protection)
        val fastId = resolveImdbIdFast(query, originalQuery, englishQuery, isMovie, isLiveAction)
        val resolvedImdbId = if (fastId.isNotEmpty()) {
            fastId
        } else if (imdbId.isNotEmpty()) {
            imdbId
        } else ""

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
        val cleanQ = query
            .replace(Regex("""(?i)\b(Temporada \d+|Season \d+|Audio Latino|Castellano|Latino|Dual)\b"""), "")
            .replace(Regex("""[\[\(].*?[\]\)]"""), "") // Strip (2010), [Br-Rip]
            .replace(Regex("""\b(19\d{2}|20\d{2})\b"""), "") // Strip standalone 4-digit years
            .trim()
        val searchTerms = mutableListOf<String>()
        if (englishQuery.isNotEmpty()) searchTerms.add(englishQuery.trim())
        if (cleanQ.isNotEmpty()) searchTerms.add(cleanQ)
        if (originalQuery.isNotEmpty()) searchTerms.add(originalQuery.trim())
        val noColon = cleanQ.substringBefore(":").trim()
        if (noColon.isNotEmpty()) searchTerms.add(noColon)

        // Direct canonical identification for popular anime and cartoons to avoid fuzzy mismatch
        val normClean = cleanQ.lowercase(Locale.ROOT)
            .replace("á", "a").replace("é", "e").replace("í", "i").replace("ó", "o").replace("ú", "u")
        val normRaw = query.lowercase(Locale.ROOT)
            .replace("á", "a").replace("é", "e").replace("í", "i").replace("ó", "o").replace("ú", "u")
        val isAdventureTime = normClean.contains("adventure time") ||
                normRaw.contains("adventure time") ||
                originalQuery.contains("adventure time", true) ||
                englishQuery.contains("adventure time", true) ||
                normClean.contains("hora de aventura") ||
                normRaw.contains("hora de aventura") ||
                normClean.contains("hora de la aventura") ||
                normRaw.contains("hora de la aventura") ||
                normClean.contains("hora de aventuras") ||
                normRaw.contains("hora de aventuras")

        if (isAdventureTime) {
            if (normClean.contains("fionna") || normRaw.contains("fionna")) return "tt15248880"
            if (normClean.contains("tierras lejanas") || normRaw.contains("tierras lejanas") || normClean.contains("distant lands")) return "tt11165358"
            return "tt1305826"
        }

        if (normClean.contains("yomi no tsugai") || originalQuery.contains("yomi", true) || normClean.contains("shadow realm")) {
            return "tt37532356"
        }

        if (normClean.contains("one piece")) {
            return if (isLiveAction) "tt11737520" else "tt0388629"
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
        if (c == q) return true
        if (c.length >= 4 && q.length >= 4 && (c.contains(q) || q.contains(c))) return true
        val cWords = c.split(Regex("""\s+""")).filter { it.length > 2 }
        val qWords = q.split(Regex("""\s+""")).filter { it.length > 2 }
        if (qWords.isEmpty() || cWords.isEmpty()) return false
        val matches = qWords.count { qw -> cWords.any { cw -> cw == qw } }
        return matches.toDouble() / maxOf(qWords.size, cWords.size) >= 0.4
    }

    data class CanonicalVideo(
        val season: Int,
        val episode: Int,
        val title: String
    )

    private val cinemetaSeriesCache = ConcurrentHashMap<String, List<CanonicalVideo>>()

    /**
     * Queries Cinemeta canonical episode catalog for a series to accurately map
     * flat absolute numbering (e.g. Ep 27 on web scrapers -> Season 2 Ep 1 canonical).
     */
    private fun getCinemetaVideos(imdbId: String): List<CanonicalVideo> {
        cinemetaSeriesCache[imdbId]?.let { return it }
        try {
            val url = "https://v3-cinemeta.strem.io/meta/series/$imdbId.json"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0")
                .header("Accept", "application/json")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val body = resp.body?.string() ?: return emptyList()
                val json = JSONObject(body)
                val meta = json.optJSONObject("meta") ?: return emptyList()
                val vids = meta.optJSONArray("videos") ?: return emptyList()
                val list = mutableListOf<CanonicalVideo>()
                for (i in 0 until vids.length()) {
                    val v = vids.getJSONObject(i)
                    val s = v.optInt("season", 0)
                    val e = v.optInt("episode", v.optInt("number", 0))
                    val t = v.optString("title", "")
                    if (s > 0 && e > 0) {
                        list.add(CanonicalVideo(season = s, episode = e, title = t))
                    }
                }
                list.sortWith(compareBy({ it.season }, { it.episode }))
                if (list.isNotEmpty()) {
                    cinemetaSeriesCache[imdbId] = list
                }
                return list
            }
        } catch (e: Exception) {
            return emptyList()
        }
    }

    fun resolveCanonicalEpisode(imdbId: String, seasonNumber: Int, episodeNumber: Int): Pair<Int, Int> {
        if (imdbId.isEmpty() || !imdbId.startsWith("tt")) return Pair(seasonNumber, episodeNumber)
        val videos = getCinemetaVideos(imdbId)
        if (videos.isEmpty()) return Pair(seasonNumber, episodeNumber)

        val season1Vids = videos.filter { it.season == 1 }
        // Case 1: Scraper has flat absolute numbering (season=1, but episodeNumber exceeds Season 1 count)
        if (seasonNumber <= 1 && episodeNumber > season1Vids.size && episodeNumber <= videos.size) {
            val canonical = videos[episodeNumber - 1]
            return Pair(canonical.season, canonical.episode)
        }

        // Case 2: Exact (season, episode) exists in canonical Cinemeta list
        val exact = videos.find { it.season == seasonNumber && it.episode == episodeNumber }
        if (exact != null) {
            return Pair(exact.season, exact.episode)
        }

        return Pair(seasonNumber, episodeNumber)
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
        if (items.isEmpty()) return emptyList()

        val sPad = String.format(Locale.US, "%02d", seasonNumber)
        val ePad = String.format(Locale.US, "%02d", episodeNumber)

        val processed = items.map { item ->
            val lower = (item.title + " " + item.provider).lowercase(Locale.ROOT)

            val is4k = lower.contains("2160p") || lower.contains("4k") || lower.contains("uhd")
            val resBadge = when {
                lower.contains("1080p") || item.resolutionBadge.contains("1080") -> "1080p"
                lower.contains("720p") || item.resolutionBadge.contains("720") -> "720p"
                is4k -> "4K"
                else -> "HD"
            }
            val (langBadge, priority) = detectLanguage(lower, preferSpanish)

            val isMultiSeason = lower.contains("complete series") ||
                    lower.contains("all seasons") ||
                    lower.contains("temporadas 1-") ||
                    Regex("""(?i)\b(?:season|seasons|temporada|temporadas|s)\s*\d+\s*[-–]\s*s?\d+\b""").containsMatchIn(lower)

            // High-seed Complete Series packs (e.g. 10 seasons with 400+ seeds)
            val isHighHealthPack = isMultiSeason && item.seeders >= 30

            // Tag display title for packs cleanly
            val formattedTitle = if (isMultiSeason && !item.title.startsWith("[")) {
                val packBadge = if (lower.contains("s01-s10") || lower.contains("1-10") || lower.contains("complete")) "[Pack S1-S10]" else "[Pack Temporada]"
                "$packBadge ${item.title}"
            } else {
                item.title
            }

            // Tier 1: Direct single episode match or high-health pack (e.g. 10 seasons 400+ seeds)
            // Tier 2: Regular torrents
            val tier = when {
                !isSingleEpisode -> 1
                isHighHealthPack -> 1
                lower.contains("s${sPad}e${ePad}") || lower.contains("${seasonNumber}x${ePad}") -> 1
                else -> 2
            }

            item.copy(
                title = formattedTitle,
                resolutionBadge = resBadge,
                languageBadge = langBadge,
                languagePriority = priority,
                tier = tier
            )
        }

        // 1. Filter out 4k and CAM if requested
        var candidates = processed.filter { item ->
            val lower = item.title.lowercase(Locale.ROOT)
            val is4k = lower.contains("2160p") || lower.contains("4k") || lower.contains("uhd")
            val isCam = lower.contains("camrip") || lower.contains("telesync") || lower.contains("hdcam")
            if (disallow4k && is4k) false
            else if (isCam) false
            else true
        }

        // 2. Filter Live Action vs Anime
        candidates = candidates.filter { item ->
            val lower = item.title.lowercase(Locale.ROOT)
            val hasLiveActionTag = lower.contains("live action") || lower.contains("live-action") || lower.contains("accion real")
            if (isLiveAction && !hasLiveActionTag) {
                !(lower.contains("anime") || lower.contains("[subsplease]") || lower.contains("[erai-raws]"))
            } else if (!isLiveAction && hasLiveActionTag) {
                false
            } else true
        }

        // 3. Quality filter (soft filter: if matches exist, keep them; otherwise keep all)
        if (qualityFilter == "1080p") {
            val q1080 = candidates.filter { it.resolutionBadge == "1080p" || it.title.contains("1080p", ignoreCase = true) }
            if (q1080.isNotEmpty()) candidates = q1080
        } else if (qualityFilter == "720p") {
            val q720 = candidates.filter { it.resolutionBadge == "720p" }
            if (q720.isNotEmpty()) candidates = q720
        }

        // 4. Language filter (soft filter: if matches exist, keep them; otherwise keep all)
        if (languageFilter == "spanish_only") {
            val langFiltered = candidates.filter { it.languagePriority == 1 }
            if (langFiltered.isNotEmpty()) candidates = langFiltered
        } else if (languageFilter == "dual_audio") {
            val langFiltered = candidates.filter { it.languagePriority <= 2 }
            if (langFiltered.isNotEmpty()) candidates = langFiltered
        } else if (languageFilter == "sub_only") {
            val langFiltered = candidates.filter { it.languageBadge.contains("Sub", ignoreCase = true) }
            if (langFiltered.isNotEmpty()) candidates = langFiltered
        }

        // 5. Fallback: If strict filters eliminated everything, return non-4k candidates so the user NEVER gets an empty screen!
        if (candidates.isEmpty()) {
            candidates = processed.filter { item ->
                val lower = item.title.lowercase(Locale.ROOT)
                !(disallow4k && (lower.contains("2160p") || lower.contains("4k") || lower.contains("uhd")))
            }
        }

        // Sort: Language priority ASC (1=Spanish, 2=Dual, 3=Eng/Multi), then Seeders DESC (439 seeds first!)
        return candidates.sortedWith(
            compareBy(
                { it.languagePriority },
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
            val (targetSeason, targetEp) = if (!isMovie && seasonNumber <= 1 && episodeNumber > 25) {
                resolveCanonicalEpisode(imdbId, seasonNumber, episodeNumber)
            } else {
                Pair(seasonNumber, episodeNumber)
            }

            fun parseTorrentioStreams(queryPath: String): List<TorrentStreamItem> {
                val subList = mutableListOf<TorrentStreamItem>()
                val endpoints = listOf(
                    "https://torrentio.strem.fun/$queryPath",
                    "https://torrentio.strem.fun/providers=yts,eztv,rarbg,1337x,thepiratebay,kickasstorrents,torrentgalaxy,magnetdl,horriblesubs,nyaasi,tokyotosho,anidex/$queryPath"
                )

                for (url in endpoints) {
                    try {
                        val req = Request.Builder()
                            .url(url)
                            .header("User-Agent", "Mozilla/5.0")
                            .header("Accept", "application/json")
                            .build()

                        client.newCall(req).execute().use { resp ->
                            if (!resp.isSuccessful) return@use
                            val body = resp.body?.string() ?: return@use
                            val json = JSONObject(body)
                            val streams = json.optJSONArray("streams") ?: return@use
                            if (streams.length() == 0) return@use

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

                                // Extract seeders from Torrentio details (e.g. "👤 45")
                                val seedersMatch = Regex("""👤\s*(\d+)""").find(details)
                                val seeders = seedersMatch?.groupValues?.get(1)?.toIntOrNull() ?: 5

                                // Extract size
                                val sizeMatch = Regex("""💾\s*([\d\.]+\s*(?:GB|MB))""").find(details)
                                val sizeFormatted = sizeMatch?.groupValues?.get(1) ?: ""

                                // Construct clear episode title for display
                                val displayTitle = if (epFileName.isNotEmpty()) {
                                    val cleanEp = epFileName.substringAfterLast("/")
                                        .replace(Regex("""\.(?:mkv|mp4|avi)$""", RegexOption.IGNORE_CASE), "")
                                        .replace(Regex("""[._]"""), " ")
                                        .trim()
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
                                    subList.add(
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
                                            fileIndex = fileIdx,
                                            tier = 2
                                        )
                                    )
                                }
                            }
                        }
                        if (subList.isNotEmpty()) break
                    } catch (e: Exception) {
                        // try next endpoint
                    }
                }
                return subList
            }

            val mainPath = if (isMovie) {
                "stream/movie/$imdbId.json"
            } else {
                "stream/series/$imdbId:$targetSeason:$targetEp.json"
            }

            list.addAll(parseTorrentioStreams(mainPath))

            // Fallback 1: If original differed from canonical and had 0 streams, try original
            if (list.isEmpty() && !isMovie && (targetSeason != seasonNumber || targetEp != episodeNumber)) {
                list.addAll(parseTorrentioStreams("stream/series/$imdbId:$seasonNumber:$episodeNumber.json"))
            }

            // Fallback 2: If still 0 streams, query Season 1 Ep 1 to capture Complete Multi-Season Packs
            if (list.isEmpty() && !isMovie) {
                list.addAll(parseTorrentioStreams("stream/series/$imdbId:1:1.json"))
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
