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
            async { fetchFromYtsLu(query, originalQuery, seasonNumber, episodeNumber, isMovie, year) },
            async { fetchFromEliteTorrent(query, originalQuery, seasonNumber, episodeNumber, isMovie) },
            async { fetchFromNyaa(query, englishQuery, episodeNumber, isMovie, isLiveAction) },
            async { fetchFromAnimeTosho(originalQuery.ifEmpty { query }, episodeNumber, isLiveAction) }
        )

        jobs.forEach { runCatching { rawResults.addAll(it.await()) } }

        val maxFileSizeGb = TorrentSettingsStore.getMaxFileSizeGb(context)
        val disallow4k = TorrentSettingsStore.isDisallow4k(context)
        val qualityFilter = TorrentSettingsStore.getQualityFilter(context)
        val languageFilter = TorrentSettingsStore.getLanguageFilter(context)

        rawResults.distinctBy { extractInfoHash(it.magnetUrl).ifEmpty { it.title } }
            .filter { isTitleRelevant(it.title, query, originalQuery, seasonNumber, episodeNumber, isMovie) }
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
            .sortedWith(compareBy({ it.languagePriority }, { -it.seeders }))
    }

    private suspend fun fetchFromYtsLu(
        query: String,
        originalQuery: String,
        seasonNumber: Int,
        episodeNumber: Int,
        isMovie: Boolean,
        year: String = ""
    ): List<TorrentStreamItem> = withContext(Dispatchers.IO) {
        val list = mutableListOf<TorrentStreamItem>()
        val candidates = listOfNotNull(
            originalQuery.takeIf { it.isNotBlank() },
            query.takeIf { it.isNotBlank() }
        ).distinct()
        if (candidates.isEmpty()) return@withContext list

        for (candidate in candidates) {
            val yearMatch = Regex("""\b(19\d\d|20\d\d)\b""").find(candidate)
            val yr = if (year.isNotBlank()) year else (yearMatch?.groupValues?.get(1) ?: "")
            val cleanTitle = candidate.replace(Regex("""\b(19\d\d|20\d\d)\b"""), "").trim()
            if (cleanTitle.isEmpty()) continue

            val apiUrl = if (isMovie) {
                "https://en.yts.lu/?api=torrents&mode=movie&name=${URLEncoder.encode(cleanTitle, "UTF-8")}&year=$yr&quality=all"
            } else {
                val s = if (seasonNumber > 0) seasonNumber else 1
                val e = if (episodeNumber > 0) episodeNumber else 1
                "https://en.yts.lu/?api=torrents&mode=tv&name=${URLEncoder.encode(cleanTitle, "UTF-8")}&year=$yr&season=$s&episode=$e&quality=all"
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

                    val isDual = itemTitle.contains("dual", true) || itemTitle.contains("latino", true) ||
                            itemTitle.contains("castellano", true) || itemTitle.contains("spanish", true)
                    val langBadge = if (isDual) "🌐 Multi/Latino" else "🇬🇧 English"
                    val langPriority = if (isDual) 2 else 3

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
                            provider = "YTS ($source)"
                        )
                    )
                }
                if (list.isNotEmpty()) break
            } catch (e: Exception) {
                android.util.Log.e("YtsLu", "Error querying YtsLu API: $apiUrl", e)
            }
        }
        list
    }

    private suspend fun fetchFromEliteTorrent(
        query: String,
        originalQuery: String,
        seasonNumber: Int,
        episodeNumber: Int,
        isMovie: Boolean
    ): List<TorrentStreamItem> = coroutineScope {
        val list = mutableListOf<TorrentStreamItem>()
        val candidates = listOfNotNull(
            originalQuery.takeIf { it.isNotBlank() },
            query.takeIf { it.isNotBlank() }
        ).distinct()
        if (candidates.isEmpty()) return@coroutineScope list

        val epSuffix = if (!isMovie) String.format(Locale.US, "%dx%02d", seasonNumber, episodeNumber) else ""
        val searchQueries = candidates.map { if (isMovie) it.trim() else "${it.trim()} $epSuffix" }

        val mirrors = listOf("https://www.elitetorrent.com")
        for (baseUrl in mirrors) {
            try {
                for (searchQuery in searchQueries) {
                    val searchUrl = "$baseUrl/?s=${URLEncoder.encode(searchQuery, "UTF-8")}"
                    android.util.Log.d("EliteTorrent", "Searching: $searchUrl")
                    val request = Request.Builder()
                        .url(searchUrl)
                        .header("User-Agent", userAgent)
                        .header("Referer", "$baseUrl/")
                        .build()
                    val response = client.newCall(request).execute()
                    if (!response.isSuccessful) continue

                    val html = response.body?.string() ?: ""
                    val doc = Jsoup.parse(html, baseUrl)
                    var links = doc.select(".meta a.nombre, .miniboxs li a, a.nombre, .post_box a, article a")
                        .mapNotNull { it.absUrl("href").takeIf { u -> u.contains("/peliculas/") || u.contains("/series/") } }
                        .distinct()

                    // Fallback to searching all a tags with href matching /peliculas/ or /series/
                    if (links.isEmpty()) {
                        links = doc.select("a[href]")
                            .map { it.absUrl("href") }
                            .filter { u ->
                                (u.contains("/peliculas/") || u.contains("/series/")) &&
                                        !u.endsWith("/peliculas/") && !u.endsWith("/series/") &&
                                        !u.contains("/genero/") && !u.contains("/estreno/")
                            }
                            .distinct()
                    }

                    android.util.Log.d("EliteTorrent", "Found ${links.size} candidate links for $searchQuery")
                    if (links.isEmpty()) continue

                    val itemJobs = links.take(6).map { pageUrl ->
                        async(Dispatchers.IO) {
                            extractEliteTorrentItem(pageUrl, baseUrl)
                        }
                    }
                    val items = itemJobs.awaitAll().filterNotNull()
                    list.addAll(items)
                    if (list.isNotEmpty()) break
                }
                if (list.isNotEmpty()) break
            } catch (e: Exception) {
                android.util.Log.e("EliteTorrent", "Error querying mirror $baseUrl", e)
            }
        }
        list
    }

    private fun extractEliteTorrentItem(pageUrl: String, baseUrl: String): TorrentStreamItem? {
        return try {
            val pageReq = Request.Builder()
                .url(pageUrl)
                .header("User-Agent", userAgent)
                .header("Referer", "$baseUrl/")
                .build()
            client.newCall(pageReq).execute().use { pageRes ->
                if (!pageRes.isSuccessful) return null
                val html = pageRes.body?.string() ?: return null
                val doc = Jsoup.parse(html, baseUrl)

                val pageTitle = doc.selectFirst("h1, .titulo, .entry-title")?.text()?.trim() ?: "EliteTorrent Item"
                var resolvedMagnet = ""

                val iMatches = Regex("""[?&]i=([^"'\s&<>]+)""").findAll(html)
                for (m in iMatches) {
                    val encoded = m.groupValues[1]
                    val decoded = decodeEliteMagnet(encoded).replace("&amp;", "&")
                    android.util.Log.d("EliteTorrent", "Decoded magnet/link: $decoded")
                    if (decoded.startsWith("magnet:?") || decoded.startsWith("http")) {
                        resolvedMagnet = decoded
                        break
                    }
                }

                if (resolvedMagnet.isEmpty()) {
                    resolvedMagnet = Regex("""magnet:\?xt=urn:btih:[a-zA-Z0-9]+[^"' \s<>]*""").find(html)?.value?.replace("&amp;", "&") ?: ""
                }

                if (resolvedMagnet.isNotEmpty()) {
                    val isLatino = pageTitle.contains("latino", ignoreCase = true) || html.contains("latino", ignoreCase = true)
                    val (lang, priority) = if (isLatino) Pair("🇲🇽 Latino", 1) else Pair("🇪🇸 Castellano", 2)
                    val res = if (pageTitle.contains("1080p", ignoreCase = true) || html.contains("1080p", ignoreCase = true)) "1080p"
                    else if (pageTitle.contains("720p", ignoreCase = true) || html.contains("720p", ignoreCase = true)) "720p"
                    else "HDRip"

                    val sizeMatch = Regex("""(?i)(?:tamaño|peso)[^0-9]*([\d\.]+\s*(?:gb|mb))""").find(html)
                    val sizeFormatted = sizeMatch?.groupValues?.get(1)?.trim() ?: "Elite"
                    val sizeBytes = parseSizeToBytes(sizeFormatted)

                    TorrentStreamItem(
                        title = pageTitle,
                        magnetUrl = resolvedMagnet,
                        seeders = 40,
                        sizeBytes = sizeBytes,
                        sizeFormatted = sizeFormatted,
                        resolutionBadge = res,
                        languageBadge = lang,
                        languagePriority = priority,
                        provider = "EliteTorrent"
                    )
                } else {
                    android.util.Log.w("EliteTorrent", "No magnet resolved on page $pageUrl")
                    null
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("EliteTorrent", "Error extracting item from $pageUrl", e)
            null
        }
    }

    /**
     * EliteTorrent encodes magnet links in acortame-esto.com using 5-layer Base64 + ROT13 cipher.
     * Decodes it mathematically without visiting the ad-shortener site.
     */
    private fun decodeEliteMagnet(paramI: String): String {
        return try {
            // Keep '+' intact for Base64 (do NOT use URLDecoder directly as it turns '+' into ' ')
            var cur = paramI.replace(" ", "+").trim()
            for (i in 0 until 5) {
                val clean = cur.replace("\n", "").replace("\r", "").replace(" ", "+").trim()
                val bytes = android.util.Base64.decode(clean, android.util.Base64.DEFAULT)
                cur = String(bytes, Charsets.UTF_8).trim()
            }
            val sb = StringBuilder(cur.length)
            for (c in cur) {
                when (c) {
                    in 'a'..'z' -> sb.append(((c - 'a' + 13) % 26 + 'a'.code).toChar())
                    in 'A'..'Z' -> sb.append(((c - 'A' + 13) % 26 + 'A'.code).toChar())
                    else -> sb.append(c)
                }
            }
            val res = sb.toString()
            when {
                res.startsWith("magnet:?") -> res
                res.contains(".torrent") -> {
                    if (res.startsWith("http")) res else "https://www.elitetorrent.com" + if (res.startsWith("/")) res else "/$res"
                }
                else -> res
            }
        } catch (e: Exception) {
            android.util.Log.e("EliteTorrent", "Error decoding param: $paramI", e)
            ""
        }
    }

    private fun isTitleRelevant(
        torrentTitle: String,
        query: String,
        originalQuery: String,
        seasonNumber: Int,
        episodeNumber: Int,
        isMovie: Boolean
    ): Boolean {
        val tRaw = torrentTitle.replace(".", " ")
            .replace("_", " ")
            .replace("×", "x")
            .replace("X", "x")
        val tLower = tRaw.lowercase(Locale.ROOT)

        // 1. Episode verification for TV series: reject if it's explicitly a different episode
        if (!isMovie && seasonNumber > 0 && episodeNumber > 0) {
            val s = seasonNumber
            val e = episodeNumber
            val otherEpMatch = Regex("""(?i)\b(?:${s}x0*(\d+)|s0*${s}e0*(\d+))\b""").find(tLower)
            if (otherEpMatch != null) {
                val foundEp = (otherEpMatch.groupValues[1].ifEmpty { otherEpMatch.groupValues[2] }).toIntOrNull()
                if (foundEp != null && foundEp != e) {
                    return false
                }
            }
        }

        // 2. Anti-hijack for spinoffs and sequels (e.g. Fionna and Cake vs Adventure Time Finn & Jake)
        val combinedQueryLower = "${originalQuery.lowercase(Locale.ROOT)} ${query.lowercase(Locale.ROOT)}"
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
