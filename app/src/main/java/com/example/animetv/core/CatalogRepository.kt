package com.example.animetv.core

import android.content.Context
import android.util.Log
import com.example.animetv.core.extractor.HeadlessStreamExtractor
import com.example.animetv.core.model.AnimeCard
import com.example.animetv.core.model.AnimeDetail
import com.example.animetv.core.model.StreamResult
import com.example.animetv.core.source.*
import com.example.animetv.core.util.CoverUtils
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

object CatalogRepository {

    val soloLatinoSource = SoloLatinoSource()
    val soloStreamSource: AnimeSource = SoloStreamSource()
    val nineAnimeSource: AnimeSource = NineAnimeSource()
    val jkAnimeSource: AnimeSource = JKAnimeSource()
    val animeYtSource: AnimeSource = AnimeYTSource()
    val gogoAnimeSource: AnimeSource = GogoAnimeSource()
    val cinecalidadSource = CinecalidadSource()
    val ytsLuSource = YtsLuSource()
    val laMovieSource = LaMovieSource()

    suspend fun loadHomeContent(context: Context? = null, forceRefresh: Boolean = false): HomeCatalogData = coroutineScope {
        val rotationJob = async {
            listOf(
                async { runCatching { soloLatinoSource.rotateMirror() } },
                async { runCatching { nineAnimeSource.rotateMirror() } },
                async { runCatching { jkAnimeSource.rotateMirror() } },
                async { runCatching { gogoAnimeSource.rotateMirror() } },
                async { runCatching { animeYtSource.rotateMirror() } },
                async { runCatching { cinecalidadSource.rotateMirror() } },
                async { runCatching { laMovieSource.rotateMirror() } }
            ).awaitAll()
        }

        if (!forceRefresh && context != null) {
            HomeCatalogCache.load(context)?.let { return@coroutineScope it }
        }

        rotationJob.await()
        
        val latinoDef = async { runCatching { soloLatinoSource.getTrending() }.getOrDefault(emptyList()) }
        val jkDef = async { runCatching { jkAnimeSource.getRecentEpisodes() }.getOrDefault(emptyList()) }
        val nineDef = async { runCatching { nineAnimeSource.getTrending() }.getOrDefault(emptyList()) }
        val gogoDef = async { runCatching { gogoAnimeSource.getTrending() }.getOrDefault(emptyList()) }
        val animeYtDef = async { runCatching { animeYtSource.getTrending() }.getOrDefault(emptyList()) }
        val sectionsDef = async { runCatching { soloLatinoSource.getHomeSections() }.getOrDefault(emptyList()) }

        val freshData = HomeCatalogData(
            latinoTrending = latinoDef.await(),
            nineAnimeTrending = nineDef.await(),
            recentEpisodes = jkDef.await(),
            gogoTrending = gogoDef.await(),
            animeYtTrending = animeYtDef.await(),
            soloLatinoSections = sectionsDef.await()
        )

        if (context != null) HomeCatalogCache.save(context, freshData)
        freshData
    }

    suspend fun searchAll(query: String): List<AnimeCard> = coroutineScope {
        val jobs = listOf(
            async { runCatching { soloLatinoSource.search(query) }.getOrDefault(emptyList()) },
            async { runCatching { cinecalidadSource.search(query) }.getOrDefault(emptyList()) },
            async { runCatching { laMovieSource.search(query) }.getOrDefault(emptyList()) },
            async { runCatching { ytsLuSource.search(query) }.getOrDefault(emptyList()) },
            async { runCatching { jkAnimeSource.search(query) }.getOrDefault(emptyList()) },
            async { runCatching { nineAnimeSource.search(query) }.getOrDefault(emptyList()) },
            async { runCatching { gogoAnimeSource.search(query) }.getOrDefault(emptyList()) },
            async { runCatching { animeYtSource.search(query) }.getOrDefault(emptyList()) }
        )
        jobs.awaitAll().flatten().distinctBy { it.detailUrl }
    }

    suspend fun getAnimeDetail(card: AnimeCard): AnimeDetail {
        return when {
            card.source.contains("LaMovie") -> laMovieSource.getAnimeDetail(card.detailUrl)
            card.source.contains("YTS") -> ytsLuSource.getAnimeDetail(card.detailUrl)
            card.source.contains("Cinecalidad") -> cinecalidadSource.getAnimeDetail(card.detailUrl)
            card.source.contains("9Anime") -> nineAnimeSource.getAnimeDetail(card.detailUrl)
            card.source.contains("JKAnime") -> jkAnimeSource.getAnimeDetail(card.detailUrl)
            card.source.contains("GogoAnime") -> gogoAnimeSource.getAnimeDetail(card.detailUrl)
            card.source.contains("AnimeYT") -> animeYtSource.getAnimeDetail(card.detailUrl)
            else -> soloLatinoSource.getAnimeDetail(card.detailUrl)
        }
    }

    fun scoreStream(stream: StreamResult): Int {
        var score = 0
        val lowerUrl = stream.videoUrl.lowercase()
        val lowerServer = stream.serverName.lowercase()
        val combined = "$lowerServer $lowerUrl"

        // 1. Direct HLS (.m3u8) vs Direct MP4 vs Embed
        if (stream.isHls && !stream.isEmbed) {
            score += 1000
        } else if (!stream.isEmbed && (lowerUrl.contains(".mp4") || lowerUrl.contains(".mkv"))) {
            score += 600
        } else if (stream.isEmbed) {
            score += 100
        }

        // 2. Adaptive Master playlist (.m3u8 containing multi-bitrate 1080p/720p/480p)
        if (lowerUrl.contains("master.m3u8")) {
            score += 250
        }

        // 3. Resolution / Quality detected
        when {
            combined.contains("4k") || combined.contains("2160") -> score += 400
            combined.contains("1080") || combined.contains("full hd") || combined.contains("fhd") -> score += 300
            combined.contains("720") || combined.contains("hd") -> score += 200
            combined.contains("480") || combined.contains("sd") -> score += 100
        }

        // 4. Audio Language (Latino confirmed)
        if (combined.contains("latino") || combined.contains("spanish") || combined.contains("español")) {
            score += 150
        }

        // 5. Server CDN Reliability & Speed
        when {
            combined.contains("goodstream") -> score += 140
            combined.contains("vimeos") -> score += 130
            combined.contains("hlswish") -> score += 120
            combined.contains("servidor 1") || combined.contains("embed69") -> score += 110
            combined.contains("filemoon") -> score += 80
            combined.contains("voe") -> score += 70
            combined.contains("dood") -> score += 50
        }

        return score
    }

    suspend fun resolveAllStreams(context: Context, episodeUrl: String, source: String): List<StreamResult> = coroutineScope {
        val isLatinoTrio = source.contains("SoloLatino") || source.contains("SoloStream") || source.contains("Cinecalidad") || source.contains("LaMovie")

        if (isLatinoTrio) {
            // Query all 3 sources in parallel
            val soloLatinoJob = async {
                runCatching {
                    if (source.contains("SoloLatino") || source.contains("SoloStream")) {
                        soloLatinoSource.resolveStream(episodeUrl)?.let { listOf(it) } ?: emptyList()
                    } else {
                        val cleanTitle = extractCleanTitle(episodeUrl)
                        soloLatinoSource.search(cleanTitle).firstOrNull()?.let {
                            soloLatinoSource.resolveStream(it.detailUrl)?.let { s -> listOf(s) }
                        } ?: emptyList()
                    }
                }.getOrDefault(emptyList())
            }

            val cinecalidadJob = async {
                runCatching {
                    if (source.contains("Cinecalidad")) {
                        cinecalidadSource.resolveAllStreams(episodeUrl)
                    } else {
                        val cleanTitle = extractCleanTitle(episodeUrl)
                        cinecalidadSource.search(cleanTitle).firstOrNull()?.let {
                            cinecalidadSource.resolveAllStreams(it.detailUrl)
                        } ?: emptyList()
                    }
                }.getOrDefault(emptyList())
            }

            val laMovieJob = async {
                runCatching {
                    if (source.contains("LaMovie")) {
                        laMovieSource.resolveAllStreams(episodeUrl)
                    } else {
                        laMovieSource.resolveAllBackupStreams(episodeUrl)
                    }
                }.getOrDefault(emptyList())
            }

            val soloStreams = soloLatinoJob.await().map { it.copy(source = "SoloLatino") }
            val cineStreams = cinecalidadJob.await().map { it.copy(source = "Cinecalidad") }
            val laMovieStreams = laMovieJob.await().map { it.copy(source = "LaMovie") }
            val allCandidates = (soloStreams + cineStreams + laMovieStreams).toMutableList()

            // Try sniffing the best embed if no direct HLS was found
            val hasDirectHls = allCandidates.any { it.isHls && !it.isEmbed }
            if (!hasDirectHls) {
                val firstEmbed = allCandidates.firstOrNull { it.isEmbed }
                if (firstEmbed != null) {
                    val referer = firstEmbed.headers["Referer"] ?: "https://lamovie.org/"
                    val sniffed = HeadlessStreamExtractor.extractStreamUrl(context, firstEmbed.videoUrl, referer)
                    if (sniffed != null && !sniffed.isEmbed) {
                        allCandidates.add(0, sniffed.copy(source = firstEmbed.source))
                    }
                }
            }

            // Return ranked streams: HLS Direct > Master m3u8 > 1080p > Latino > CDN reliability > Embeds
            return@coroutineScope allCandidates
                .distinctBy { it.videoUrl }
                .sortedByDescending { scoreStream(it) }
        }

        // For other sources (YTS, JKAnime, 9Anime, GogoAnime, AnimeYT):
        val single = runCatching {
            when {
                source.contains("YTS") -> ytsLuSource.resolveStream(episodeUrl)
                source.contains("JKAnime") -> jkAnimeSource.resolveStream(episodeUrl)
                source.contains("9Anime") -> nineAnimeSource.resolveStream(episodeUrl)
                source.contains("Gogo") -> gogoAnimeSource.resolveStream(episodeUrl)
                source.contains("AnimeYT") -> animeYtSource.resolveStream(episodeUrl)
                else -> soloLatinoSource.resolveStream(episodeUrl)
            }
        }.getOrNull()

        listOfNotNull(single)
    }

    suspend fun resolveStream(context: Context, episodeUrl: String, source: String): StreamResult? {
        val ranked = resolveAllStreams(context, episodeUrl, source)
        return ranked.firstOrNull() ?: HeadlessStreamExtractor.extractStreamUrl(context, episodeUrl)
    }

    private fun extractCleanTitle(url: String): String {
        return url.trimEnd('/')
            .substringAfterLast('/')
            .replace("-", " ")
            .replace(Regex("""\b(19\d\d|20\d\d)\b"""), "")
            .replace(Regex("""\b(hd|latino|online|completa|pelicula|serie|temporada|episodio)\b""", RegexOption.IGNORE_CASE), "")
            .trim()
            .split(Regex("""\s+"""))
            .take(3)
            .joinToString(" ")
    }
}

data class HomeCatalogData(
    val latinoTrending: List<AnimeCard>,
    val soloStreamTrending: List<AnimeCard> = emptyList(),
    val nineAnimeTrending: List<AnimeCard> = emptyList(),
    val recentEpisodes: List<AnimeCard>,
    val animeYtTrending: List<AnimeCard> = emptyList(),
    val gogoTrending: List<AnimeCard> = emptyList(),
    val soloLatinoSections: List<com.example.animetv.core.model.CatalogRow> = emptyList()
)
