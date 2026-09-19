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

    suspend fun resolveStream(context: Context, episodeUrl: String, source: String): StreamResult? = coroutineScope {
        // Parallel extraction: Primary vs Cinecalidad (1st backup) vs LaMovie (2nd backup)
        val primaryDeferred = async {
            runCatching {
                when {
                    source.contains("LaMovie") -> laMovieSource.resolveStream(episodeUrl)
                    source.contains("YTS") -> ytsLuSource.resolveStream(episodeUrl)
                    source.contains("JKAnime") -> jkAnimeSource.resolveStream(episodeUrl)
                    source.contains("9Anime") -> nineAnimeSource.resolveStream(episodeUrl)
                    source.contains("Cinecalidad") -> cinecalidadSource.resolveStream(episodeUrl)
                    source.contains("Gogo") -> gogoAnimeSource.resolveStream(episodeUrl)
                    source.contains("AnimeYT") -> animeYtSource.resolveStream(episodeUrl)
                    else -> soloLatinoSource.resolveStream(episodeUrl)
                }
            }.getOrNull()
        }

        val backupDeferred = async {
            if (source.contains("SoloLatino") || source.contains("SoloStream")) {
                runCatching {
                    val title = episodeUrl.trimEnd('/').substringAfterLast('/').replace("-", " ")
                    cinecalidadSource.search(title).firstOrNull()?.let { cinecalidadSource.resolveStream(it.detailUrl) }
                }.getOrNull()
            } else null
        }

        val backup2Deferred = async {
            if (source.contains("SoloLatino") || source.contains("SoloStream")) {
                runCatching {
                    laMovieSource.resolveBackupStream(episodeUrl)
                }.getOrNull()
            } else null
        }

        val primary = primaryDeferred.await()
        val backup = backupDeferred.await()
        val backup2 = backup2Deferred.await()

        // QUALITY RANKING: Direct HLS is always better than Embed
        // Order: 1. SoloLatino primary -> 2. Cinecalidad backup -> 3. LaMovie backup
        val rawResult = when {
            primary?.isHls == true && !primary.isEmbed -> primary
            backup?.isHls == true && !backup.isEmbed -> backup
            backup2?.isHls == true && !backup2.isEmbed -> backup2
            primary != null -> primary
            backup != null -> backup
            backup2 != null -> backup2
            else -> null
        }

        val bestResult = if (rawResult != null && rawResult.isEmbed) {
            val referer = rawResult.headers["Referer"] ?: "https://v2.cinecalidad.vip/"
            val sniffed = HeadlessStreamExtractor.extractStreamUrl(context, rawResult.videoUrl, referer)
            if (sniffed != null && !sniffed.isEmbed) sniffed else rawResult
        } else {
            rawResult
        }

        bestResult ?: HeadlessStreamExtractor.extractStreamUrl(context, episodeUrl)
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
