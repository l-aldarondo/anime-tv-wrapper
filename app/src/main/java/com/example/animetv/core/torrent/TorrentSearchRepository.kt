package com.example.animetv.core.torrent

import android.content.Context
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
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
    val resolutionBadge: String,
    val languageBadge: String,
    val languagePriority: Int, // 1 = Latino, 2 = Castellano, 3 = Dual, 4 = Eng
    val provider: String,
    val fileIndex: Int = 1,
    val tier: Int = 2
)

object TorrentSearchRepository {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

    suspend fun searchAndFilter(
        context: Context,
        query: String,
        originalQuery: String = "",
        englishQuery: String = "",
        imdbId: String = "",
        seasonNumber: Int = 1,
        episodeNumber: Int = 1,
        isMovie: Boolean = false,
        isLiveAction: Boolean = false,
        year: String = ""
    ): List<TorrentStreamItem> = withContext(Dispatchers.IO) {
        val jackettUrl = TorrentSettingsStore.getJackettUrl(context)
        val jackettKey = TorrentSettingsStore.getJackettApiKey(context)

        val rawResults = mutableListOf<TorrentStreamItem>()
        val resolvedImdbId = imdbId.ifEmpty { resolveImdbIdFast(query, originalQuery, isMovie) }

        val jobs = listOf(
            async { if (jackettUrl.isNotEmpty()) fetchFromIndexer(jackettUrl, jackettKey, query, seasonNumber, episodeNumber, isMovie) else emptyList() },
            async { if (resolvedImdbId.isNotEmpty()) fetchFromTorrentio(resolvedImdbId, seasonNumber, episodeNumber, isMovie) else emptyList() },
            async { fetchFromYtsLu(query, originalQuery, englishQuery, seasonNumber, episodeNumber, isMovie, year) },
            async { fetchFromNyaa(query, englishQuery, episodeNumber, isMovie, isLiveAction) },
            async { fetchFromAnimeTosho(originalQuery.ifEmpty { query }, episodeNumber, isLiveAction) }
        )

        jobs.forEach { runCatching { rawResults.addAll(it.await()) } }

        val maxFileSizeGb = TorrentSettingsStore.getMaxFileSizeGb(context)
        val disallow4k = TorrentSettingsStore.isDisallow4k(context)
        val qualityFilter = TorrentSettingsStore.getQualityFilter(context)
        val languageFilter = TorrentSettingsStore.getLanguageFilter(context)
        val preferSpanish = TorrentSettingsStore.isPreferSpanish(context)

        val filtered = rawResults.distinctBy { extractInfoHash(it.magnetUrl).ifEmpty { it.title } }
            .filter { isTitleRelevant(it.title, query, originalQuery, englishQuery, seasonNumber, episodeNumber, isMovie) }
            .filter { item ->
                // 1. Max File Size filter (e.g. <= 1.5 GB, <= 3 GB)
                if (maxFileSizeGb > 0.05f) {
                    val maxBytes = (maxFileSizeGb * 1024L * 1024L * 1024L).toLong()
                    val itemBytes = if (item.sizeBytes > 0L) item.sizeBytes else parseSizeToBytes(item.sizeFormatted)
                    if (itemBytes > 0L && itemBytes > maxBytes) {
                        return@filter false
                    }
                }

                // 2. Disallow 4K filter
                if (disallow4k) {
                    if (item.resolutionBadge.equals("4K", ignoreCase = true) ||
                        item.title.contains("4k", ignoreCase = true) ||
                        item.title.contains("2160p", ignoreCase = true)) {
                        return@filter false
                    }
                }

                // 3. Quality filter ("720p", "1080p", "all")
                if (qualityFilter == "720p") {
                    if (item.resolutionBadge.contains("1080p", ignoreCase = true) ||
                        item.resolutionBadge.contains("4k", ignoreCase = true)) {
                        return@filter false
                    }
                }

                // 4. Language filter ("all", "spanish_only", "dual_audio", "sub_only")
                when (languageFilter) {
                    "spanish_only" -> item.languagePriority == 1 || item.languagePriority == 2
                    "dual_audio" -> item.languagePriority == 3
                    "sub_only" -> item.languagePriority == 4
                    else -> true
                }
            }

        if (preferSpanish) {
            filtered.sortedWith(compareBy({ it.languagePriority }, { -it.seeders }))
        } else {
            filtered.sortedWith(compareBy({ -it.seeders }))
        }
    }

    suspend fun searchTorrentsDirectForTest(
        query: String,
        originalQuery: String = "",
        englishQuery: String = "",
        seasonNumber: Int = 1,
        episodeNumber: Int = 1,
        isMovie: Boolean = false,
        year: String = ""
    ): List<TorrentStreamItem> = withContext(Dispatchers.IO) {
        val ytsResults = fetchFromYtsLu(query, originalQuery, englishQuery, seasonNumber, episodeNumber, isMovie, year)
        ytsResults.filter { isTitleRelevant(it.title, query, originalQuery, englishQuery, seasonNumber, episodeNumber, isMovie) }
    }

    private suspend fun fetchFromYtsLu(
        query: String,
        originalQuery: String,
        englishQuery: String = "",
        seasonNumber: Int,
        episodeNumber: Int,
        isMovie: Boolean,
        year: String = ""
    ): List<TorrentStreamItem> = withContext(Dispatchers.IO) {
        val list = mutableListOf<TorrentStreamItem>()
        val candidates = listOfNotNull(
            englishQuery.takeIf { it.isNotBlank() },
            originalQuery.takeIf { it.isNotBlank() },
            query.takeIf { it.isNotBlank() }
        ).distinct()
        if (candidates.isEmpty()) return@withContext list

        for (candidate in candidates) {
            val yearMatch = Regex("""\b(19\d\d|20\d\d)\b""").find(candidate)
            val yr = if (year.isNotBlank()) year else (yearMatch?.groupValues?.get(1) ?: "")
            val cleanTitle = candidate.replace(Regex("""\b(19\d\d|20\d\d)\b"""), "").trim()
            if (cleanTitle.isEmpty()) continue

            val yearsToTry = if (yr.isNotBlank()) listOf(yr, "") else listOf("")
            for (currYear in yearsToTry) {
                val apiUrl = if (isMovie) {
                    "https://en.yts.lu/?api=torrents&mode=movie&name=${URLEncoder.encode(cleanTitle, "UTF-8")}&year=$currYear&quality=all"
                } else {
                    val s = if (seasonNumber > 0) seasonNumber else 1
                    val e = if (episodeNumber > 0) episodeNumber else 1
                    "https://en.yts.lu/?api=torrents&mode=tv&name=${URLEncoder.encode(cleanTitle, "UTF-8")}&year=$currYear&season=$s&episode=$e&quality=all"
                }

                try {
                    val request = Request.Builder()
                        .url(apiUrl)
                        .header("User-Agent", userAgent)
                        .header("Referer", "https://en.yts.lu/")
                        .build()
                    val response = client.newCall(request).execute()
                    if (!response.isSuccessful) continue
                    val body = response.body?.string() ?: continue
                    val json = JSONObject(body)
                    val hits = json.optJSONArray("hits") ?: continue

                    for (i in 0 until hits.length()) {
                        val hit = hits.getJSONObject(i)
                        val rawMagnet = hit.optString("magnetUrl", "").trim()
                        val magnet = rawMagnet.replace("&amp;", "&")
                        if (!magnet.startsWith("magnet:?")) continue

                        val itemTitle = hit.optString("title", "").trim()
                        val seeds = hit.optInt("seeds", 0)
                        val bytes = hit.optLong("bytes", 0L)
                        val source = hit.optString("source", "YTS").trim()

                        val res = when {
                            itemTitle.contains("2160p", true) || itemTitle.contains("4k", true) -> "4K"
                            itemTitle.contains("1080p", true) -> "1080p"
                            itemTitle.contains("720p", true) -> "720p"
                            itemTitle.contains("480p", true) -> "480p"
                            else -> "HD"
                        }

                        val sizeFormatted = if (bytes > 0L) {
                            val gb = bytes.toDouble() / (1024 * 1024 * 1024)
                            if (gb >= 1.0) String.format(Locale.US, "%.1f GB", gb)
                            else String.format(Locale.US, "%.0f MB", bytes.toDouble() / (1024 * 1024))
                        } else "Torrent"

                        val (langBadge, langPriority) = detectLanguage(itemTitle.lowercase(Locale.ROOT))
                        val providerLabel = if (source.equals("YTS", ignoreCase = true) || source.isEmpty()) "YTS" else "YTS ($source)"

                        list.add(
                            TorrentStreamItem(
                                title = itemTitle,
                                magnetUrl = magnet,
                                seeders = seeds,
                                sizeBytes = bytes,
                                sizeFormatted = sizeFormatted,
                                resolutionBadge = res,
                                languageBadge = langBadge,
                                languagePriority = langPriority,
                                provider = providerLabel
                            )
                        )
                    }
                    if (list.size >= 10) break
                } catch (e: Exception) {
                    android.util.Log.e("YtsLu", "Error querying YtsLu API: $apiUrl", e)
                }
            }
            if (list.size >= 15) break
        }
        list.distinctBy { extractInfoHash(it.magnetUrl).ifEmpty { it.title } }
    }

    private fun isTitleRelevant(
        torrentTitle: String,
        query: String,
        originalQuery: String,
        englishQuery: String = "",
        seasonNumber: Int,
        episodeNumber: Int,
        isMovie: Boolean
    ): Boolean {
        val tRaw = torrentTitle.replace(".", " ")
            .replace("_", " ")
            .replace("×", "x")
            .replace("X", "x")
        val tLower = tRaw.lowercase(Locale.ROOT)

        // 1. Season & Episode verification for TV series: reject if it's explicitly a different season or episode
        if (!isMovie && seasonNumber > 0) {
            val s = seasonNumber
            val e = episodeNumber

            // A. Complete series / All seasons packs (e.g. S01-S10, S1-8, Seasons 1-10, S01 2 3 4 5...)
            val isCompleteSeries = tLower.contains("complete series") || tLower.contains("serie completa") ||
                    tLower.contains("all seasons") || tLower.contains("todas las temporadas") ||
                    Regex("""(?i)\bs0*1\s+2\s+3\b""").containsMatchIn(tLower)

            val multiSeasonRange = Regex("""(?i)\b(?:s|seasons?\s*|temporadas?\s*|temp\s*)0*(\d+)\s*(?:[-–—~]|\bto\b|\bal?\b)\s*(?:s|seasons?\s*|temporadas?\s*|temp\s*)?0*(\d+)\b""").find(tLower)
            if (multiSeasonRange != null) {
                val sStart = multiSeasonRange.groupValues[1].toIntOrNull()
                val sEnd = multiSeasonRange.groupValues[2].toIntOrNull()
                if (sStart != null && sEnd != null) {
                    if (s < sStart || s > sEnd) {
                        return false // Torrent is a multi-season pack that does NOT contain target season s
                    }
                }
            } else if (!isCompleteSeries) {
                // B. Explicit Season + Episode (e.g. S06E05, 6x01, S01E01, 1x01, S06E01E02, S01E01-13)
                val seMatch = Regex("""(?i)\b(?:s0*(\d+)e0*(\d+)(?:[-–—~e]0*(\d+))?|0*(\d+)x0*(\d+)(?:[-–—~]0*(\d+))?)""").find(tLower)
                if (seMatch != null) {
                    val foundS = (seMatch.groupValues[1].ifEmpty { seMatch.groupValues[4] }).toIntOrNull()
                    val startE = (seMatch.groupValues[2].ifEmpty { seMatch.groupValues[5] }).toIntOrNull()
                    val endE = (seMatch.groupValues[3].ifEmpty { seMatch.groupValues[6] }).toIntOrNull()

                    if (foundS != null && foundS != s) {
                        return false // Mismatched season (e.g. S06E01 or S06E01E02 when looking for S01)
                    }

                    if (foundS == s && startE != null && e > 0) {
                        if (endE != null) {
                            if (e < startE || e > endE) {
                                return false // Target episode e is outside this batch range
                            }
                        } else if (startE != e) {
                            return false // Single episode, but wrong episode number
                        }
                    }
                } else {
                    // C. Standalone season word (e.g. Season 6, Temporada 8, Temp 2)
                    val seasonWordMatch = Regex("""(?i)\b(?:season|temporada|temp)\s*0*(\d+)\b""").find(tLower)
                    if (seasonWordMatch != null) {
                        val foundS = seasonWordMatch.groupValues[1].toIntOrNull()
                        if (foundS != null && foundS != s) {
                            return false // Different season
                        }
                    }

                    // D. Standalone 'S(\d+)' or 'T(\d+)' (e.g. S06, S8, S2, T06)
                    val standaloneSeasonMatches = Regex("""(?i)\b[st]0*(\d{1,2})\b""").findAll(tLower)
                    for (sm in standaloneSeasonMatches) {
                        val numStr = sm.groupValues[1]
                        val foundS = numStr.toIntOrNull() ?: continue
                        if (foundS >= 1900 && foundS <= 2099) continue // Skip year

                        val nextChar = tLower.getOrNull(sm.range.last + 1)
                        if (nextChar == 'e' || nextChar == 'x') continue

                        val prevChar = tLower.getOrNull(sm.range.first - 1)
                        if (prevChar != null && (prevChar.isLetterOrDigit() || prevChar == '.')) continue

                        if (foundS != s) {
                            return false // Different season (e.g. S06 or S08 when looking for S01)
                        }
                    }
                }
            }
        }

        // 2. Anti-hijack for spinoffs and sequels (e.g. Fionna and Cake vs Adventure Time Finn & Jake)
        val combinedQueryLower = "${englishQuery.lowercase(Locale.ROOT)} ${originalQuery.lowercase(Locale.ROOT)} ${query.lowercase(Locale.ROOT)}"
        val isTargetAdventureTime = combinedQueryLower.contains("adventure time") || combinedQueryLower.contains("hora de aventura")
        val isTargetFionna = combinedQueryLower.contains("fionna") || combinedQueryLower.contains("cake")
        val isTorrentFionna = tLower.contains("fionna") || tLower.contains("cake")
        val isTorrentDistant = tLower.contains("distant lands") || tLower.contains("tierras lejanas")
        val isTorrentSideQuests = tLower.contains("side quests")

        if (isTargetAdventureTime) {
            if (!isTargetFionna && isTorrentFionna) return false
            if (!combinedQueryLower.contains("distant") && isTorrentDistant) return false
            if (!combinedQueryLower.contains("side quest") && isTorrentSideQuests) return false
            if (isTargetFionna && !isTorrentFionna) return false
        }

        // 3. Title matching
        val stopWords = setOf("the", "a", "an", "el", "la", "los", "las", "un", "una", "de", "del", "y", "en", "por", "para", "con")
        val candidates = listOfNotNull(
            englishQuery.takeIf { it.isNotBlank() },
            originalQuery.takeIf { it.isNotBlank() },
            query.takeIf { it.isNotBlank() }
        ).distinct()

        var seriesPart = tRaw.replace(Regex("""^(?:4k|2160p|1080p|720p|480p|hdrip|webrip)\s*:\s*""", RegexOption.IGNORE_CASE), "")
        val cutPatterns = listOf(
            Regex("""(?i)\s*[-–—]\s*\d+x\d+.*"""),
            Regex("""(?i)\s+\d+x\d+.*"""),
            Regex("""(?i)\s+[sS]\d+[eE]\d+.*"""),
            Regex("""(?i)\s+[sS]\d+\b.*"""),
            Regex("""(?i)\s*\[cap[\.\s]*\d+.*"""),
            Regex("""(?i)\s*\[hdtv.*"""),
            Regex("""(?i)\s*\[web.*"""),
            Regex("""(?i)\s*\[1080p.*"""),
            Regex("""(?i)\s*\[720p.*"""),
            Regex("""(?i)\s+(?:19|20)\d{2}\b.*""")
        )
        for (cp in cutPatterns) {
            val match = cp.find(seriesPart)
            if (match != null && match.range.first > 0) {
                seriesPart = seriesPart.substring(0, match.range.first)
                break
            }
        }

        val extractedWords = seriesPart.lowercase(Locale.ROOT)
            .replace(Regex("""[^a-z0-9áéíóúñ\s]"""), " ")
            .split(Regex("""\s+"""))
            .filter { it.isNotBlank() && it !in stopWords }

        for (cand in candidates) {
            val candWords = cand.lowercase(Locale.ROOT)
                .replace(Regex("""[^a-z0-9áéíóúñ\s]"""), " ")
                .split(Regex("""\s+"""))
                .filter { it.isNotBlank() && it !in stopWords }

            if (candWords.isEmpty()) continue

            val candJoined = candWords.joinToString(" ")
            val extJoined = extractedWords.joinToString(" ")

            if (candJoined == extJoined) return true

            if (candWords.size <= 2) {
                if (extractedWords.size > candWords.size) {
                    continue
                }
                if (candJoined == extJoined) {
                    return true
                }
            } else {
                val allInExtracted = candWords.all { cw -> extractedWords.contains(cw) }
                if (allInExtracted && extractedWords.size <= candWords.size + 1) {
                    return true
                }
            }
        }

        for (cand in candidates) {
            val candWords = cand.lowercase(Locale.ROOT)
                .replace(Regex("""[^a-z0-9áéíóúñ\s]"""), " ")
                .split(Regex("""\s+"""))
                .filter { it.isNotBlank() && it !in stopWords }

            if (candWords.isEmpty()) continue

            val phrase = candWords.joinToString("""\s+""")
            if (Regex("""(?i)\b$phrase\b""").containsMatchIn(tLower)) {
                val afterMatch = Regex("""(?i)\b$phrase\s+([a-z0-9]+)""").find(tLower)
                if (afterMatch != null) {
                    val nextWord = afterMatch.groupValues[1]
                    val allowedNext = setOf(
                        "s01", "s02", "s03", "s04", "s05", "s06", "s07", "s08", "s09", "s10",
                        "s1", "s2", "s3", "s4", "s5", "s6", "s7", "s8", "s9",
                        "1x01", "1x1", "2x01", "season", "temporada", "complete", "completa",
                        "1080p", "720p", "4k", "2160p", "hdtv", "web", "bdrip", "bluray", "dual", "latino",
                        "the", "movie", "pelicula"
                    )
                    val isYear = nextWord.matches(Regex("""(19|20)\d{2}"""))
                    val isSeasonMarker = nextWord.startsWith("s0") || nextWord.startsWith("s1") || nextWord.startsWith("1x") || nextWord.startsWith("2x")
                    if (!isYear && !isSeasonMarker && nextWord !in allowedNext) {
                        continue
                    }
                }
                return true
            }
        }

        return false
    }

    private fun detectLanguage(titleLower: String): Pair<String, Int> {
        val isLatino = titleLower.contains("latino") || titleLower.contains("audio latino") ||
                titleLower.contains("mex") || titleLower.contains("cinecalidad") || titleLower.contains("es-la") ||
                titleLower.contains("🇲🇽") || titleLower.contains("[lat]") || titleLower.contains(".lat.") ||
                titleLower.contains("lat-") || titleLower.contains("latam")
        val isCastellano = titleLower.contains("castellano") || titleLower.contains("español") ||
                titleLower.contains("spanish") || titleLower.contains("es-es") || titleLower.contains("🇪🇸") ||
                titleLower.contains("[spa]") || titleLower.contains(".spa.") || titleLower.contains("[esp]") ||
                titleLower.contains(".esp.")
        return when {
            isLatino -> Pair("🇲🇽 Latino", 1)
            isCastellano -> Pair("🇪🇸 Castellano", 2)
            titleLower.contains("dual") || titleLower.contains("multi") || titleLower.contains("tri") -> Pair("🌐 Dual", 3)
            else -> Pair("🇺🇸 Inglés", 4)
        }
    }

    private suspend fun fetchFromNyaa(query: String, eng: String, ep: Int, isMovie: Boolean, isLiveAction: Boolean): List<TorrentStreamItem> {
        if (isLiveAction) return emptyList() // FIX
        val term = eng.ifEmpty { query }
        val search = if (ep > 0 && !isMovie) "$term $ep" else term
        val url = "https://nyaa.si/?page=rss&q=${URLEncoder.encode(search, "UTF-8")}&c=1_2&f=0"
        val list = mutableListOf<TorrentStreamItem>()
        try {
            val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
            client.newCall(req).execute().use { resp ->
                val doc = Jsoup.parse(resp.body?.string() ?: "", "", org.jsoup.parser.Parser.xmlParser())
                doc.select("item").forEach { item ->
                    val t = item.selectFirst("title")?.text() ?: return@forEach
                    val h = item.selectFirst("nyaa\\:infoHash")?.text() ?: return@forEach
                    val m = "magnet:?xt=urn:btih:$h&dn=${URLEncoder.encode(t, "UTF-8")}"
                    list.add(TorrentStreamItem(t, m, 10, 0, "Nyaa", "1080p", "💬 Sub", 4, "Nyaa"))
                }
            }
        } catch (e: Exception) {}
        return list
    }

    private suspend fun fetchFromAnimeTosho(q: String, ep: Int, isLiveAction: Boolean): List<TorrentStreamItem> {
        if (isLiveAction) return emptyList() // FIX
        val search = if (ep > 0) "$q $ep" else q
        val url = "https://feed.animetosho.org/json?q=${URLEncoder.encode(search, "UTF-8")}"
        val list = mutableListOf<TorrentStreamItem>()
        try {
            val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val arr = JSONArray(resp.body?.string() ?: "[]")
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val t = obj.optString("title")
                    val h = obj.optString("info_hash")
                    if (h.isNotEmpty()) {
                        val m = "magnet:?xt=urn:btih:$h&dn=${URLEncoder.encode(t, "UTF-8")}"
                        list.add(TorrentStreamItem(t, m, 8, 0, "Tosho", "1080p", "🌐 Multi", 3, "AnimeTosho"))
                    }
                }
            }
        } catch (e: Exception) {}
        return list
    }

    private suspend fun fetchFromTorrentio(
        imdbId: String,
        seasonNumber: Int,
        episodeNumber: Int,
        isMovie: Boolean
    ): List<TorrentStreamItem> = withContext(Dispatchers.IO) {
        val url = if (isMovie) {
            "https://torrentio.strem.fun/stream/movie/$imdbId.json"
        } else {
            "https://torrentio.strem.fun/stream/series/$imdbId:$seasonNumber:$episodeNumber.json"
        }
        val list = mutableListOf<TorrentStreamItem>()
        try {
            val req = Request.Builder().url(url).header("User-Agent", userAgent).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use
                val body = resp.body?.string() ?: return@use
                val json = JSONObject(body)
                val streams = json.optJSONArray("streams") ?: return@use
                for (i in 0 until streams.length()) {
                    val stream = streams.getJSONObject(i)
                    val rawTitle = stream.optString("title", "")
                    val name = stream.optString("name", "Torrentio")
                    val infoHash = stream.optString("infoHash", "")

                    val lines = rawTitle.split("\n")
                    val titleLine = lines.firstOrNull()?.trim() ?: "Stream $i"
                    val details = lines.drop(1).joinToString(" ")

                    val seedersMatch = Regex("""👤\s*(\d+)""").find(details)
                    val seeders = seedersMatch?.groupValues?.get(1)?.toIntOrNull() ?: 5

                    val sizeMatch = Regex("""💾\s*([\d\.]+\s*(?:GB|MB))""").find(details)
                    val sizeFormatted = sizeMatch?.groupValues?.get(1) ?: "HQ"

                    val magnetUrl = if (infoHash.isNotEmpty()) {
                        "magnet:?xt=urn:btih:$infoHash&dn=${URLEncoder.encode(titleLine, "UTF-8")}&tr=udp%3A%2F%2Ftracker.opentrackr.org%3A1337%2Fannounce"
                    } else ""

                    if (magnetUrl.isNotEmpty()) {
                        val fullTitle = "$name - $titleLine $details"
                        val (langBadge, langPriority) = detectLanguage(fullTitle.lowercase(Locale.ROOT))
                        val res = if (fullTitle.contains("1080p")) "1080p" else if (fullTitle.contains("720p")) "720p" else if (fullTitle.contains("4k") || fullTitle.contains("2160p")) "4K" else "HD"

                        list.add(
                            TorrentStreamItem(
                                title = "$name: $titleLine",
                                magnetUrl = magnetUrl,
                                seeders = seeders,
                                sizeBytes = parseSizeToBytes(sizeFormatted),
                                sizeFormatted = sizeFormatted,
                                resolutionBadge = res,
                                languageBadge = langBadge,
                                languagePriority = langPriority,
                                provider = "Torrentio"
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        list
    }

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

        if (indexerUrl.contains(":9696") || indexerUrl.lowercase(Locale.ROOT).contains("prowlarr")) {
            val prowlarrResults = queryProwlarr(indexerUrl, apiKey, q)
            if (prowlarrResults.isNotEmpty()) return prowlarrResults
        }

        val jackettResults = queryJackett(indexerUrl, apiKey, q)
        if (jackettResults.isNotEmpty()) return jackettResults

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
                val jsonArr = JSONArray(body)

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
                        val (lang, priority) = detectLanguage(title.lowercase(Locale.ROOT))
                        list.add(
                            TorrentStreamItem(
                                title = title,
                                magnetUrl = magnetUri,
                                seeders = seeders,
                                sizeBytes = size,
                                sizeFormatted = sizeFormatted,
                                resolutionBadge = if (title.contains("1080p")) "1080p" else "720p",
                                languageBadge = lang,
                                languagePriority = priority,
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
                        val (lang, priority) = detectLanguage(title.lowercase(Locale.ROOT))
                        list.add(
                            TorrentStreamItem(
                                title = title,
                                magnetUrl = magnetUri,
                                seeders = seeders,
                                sizeBytes = size,
                                sizeFormatted = sizeFormatted,
                                resolutionBadge = if (title.contains("1080p")) "1080p" else "720p",
                                languageBadge = lang,
                                languagePriority = priority,
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

    private fun resolveImdbIdFast(query: String, originalQuery: String, isMovie: Boolean): String {
        val type = if (isMovie) "movie" else "series"
        val candidates = listOfNotNull(
            originalQuery.takeIf { it.isNotBlank() },
            query.takeIf { it.isNotBlank() }
        ).distinct()

        for (term in candidates) {
            try {
                val url = "https://v3-cinemeta.strem.io/catalog/$type/top/search=${URLEncoder.encode(term, "UTF-8")}.json"
                val req = Request.Builder().url(url).header("User-Agent", userAgent).build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val body = resp.body?.string() ?: return@use
                    val json = JSONObject(body)
                    val metas = json.optJSONArray("metas") ?: return@use
                    if (metas.length() > 0) {
                        val meta = metas.getJSONObject(0)
                        val id = meta.optString("imdb_id").ifEmpty { meta.optString("id") }
                        if (id.startsWith("tt")) return id
                    }
                }
            } catch (e: Exception) {}
        }
        return ""
    }

    private fun extractInfoHash(magnet: String): String = Regex("""xt=urn:btih:([a-zA-Z0-9]+)""").find(magnet)?.groupValues?.get(1)?.lowercase(Locale.ROOT) ?: ""

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

    fun parseSizeToBytes(sizeStr: String): Long {
        if (sizeStr.isBlank()) return 0L
        val clean = sizeStr.trim().uppercase(Locale.US)
        val num = Regex("""([\d\.]+)""").find(clean)?.groupValues?.get(1)?.toDoubleOrNull() ?: return 0L
        return when {
            clean.contains("GB") -> (num * 1024L * 1024L * 1024L).toLong()
            clean.contains("MB") -> (num * 1024L * 1024L).toLong()
            clean.contains("KB") -> (num * 1024L).toLong()
            else -> 0L
        }
    }
}
