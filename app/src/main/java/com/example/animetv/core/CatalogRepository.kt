package com.example.animetv.core

import android.content.Context
import com.example.animetv.core.extractor.HeadlessStreamExtractor
import com.example.animetv.core.model.AnimeCard
import com.example.animetv.core.model.AnimeDetail
import com.example.animetv.core.model.StreamResult
import com.example.animetv.core.source.AnimeSource
import com.example.animetv.core.source.AnimeYTSource
import com.example.animetv.core.source.GogoAnimeSource
import com.example.animetv.core.source.JKAnimeSource
import com.example.animetv.core.source.NineAnimeSource
import com.example.animetv.core.source.SoloLatinoSource
import com.example.animetv.core.source.SoloStreamSource
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

object CatalogRepository {

    val soloLatinoSource: AnimeSource = SoloLatinoSource()
    val soloStreamSource: AnimeSource = SoloStreamSource()
    val nineAnimeSource: AnimeSource = NineAnimeSource()
    val jkAnimeSource: AnimeSource = JKAnimeSource()
    val animeYtSource: AnimeSource = AnimeYTSource()
    val gogoAnimeSource: AnimeSource = GogoAnimeSource()

    private var cachedLatinoTrending: List<AnimeCard> = emptyList()
    private var cachedSoloStream: List<AnimeCard> = emptyList()
    private var cachedNineAnime: List<AnimeCard> = emptyList()
    private var cachedJKRecent: List<AnimeCard> = emptyList()
    private var cachedAnimeYt: List<AnimeCard> = emptyList()
    private var cachedGogo: List<AnimeCard> = emptyList()

    suspend fun loadHomeContent(context: Context? = null, forceRefresh: Boolean = false): HomeCatalogData = coroutineScope {
        if (!forceRefresh) {
            if (cachedLatinoTrending.isNotEmpty() && cachedJKRecent.isNotEmpty()) {
                return@coroutineScope HomeCatalogData(
                    latinoTrending = cachedLatinoTrending,
                    soloStreamTrending = cachedSoloStream,
                    nineAnimeTrending = cachedNineAnime,
                    recentEpisodes = cachedJKRecent,
                    animeYtTrending = cachedAnimeYt,
                    gogoTrending = cachedGogo
                )
            }
            if (context != null) {
                val diskCached = HomeCatalogCache.load(context)
                if (diskCached != null && (diskCached.latinoTrending.isNotEmpty() || diskCached.recentEpisodes.isNotEmpty())) {
                    cachedLatinoTrending = diskCached.latinoTrending
                    cachedSoloStream = diskCached.soloStreamTrending
                    cachedNineAnime = diskCached.nineAnimeTrending
                    cachedJKRecent = diskCached.recentEpisodes
                    cachedAnimeYt = diskCached.animeYtTrending
                    cachedGogo = diskCached.gogoTrending
                    return@coroutineScope diskCached
                }
            }
        }

        val latinoDeferred = async { runCatching { soloLatinoSource.getTrending() }.getOrDefault(emptyList()) }
        val streamDeferred = async { runCatching { soloStreamSource.getTrending() }.getOrDefault(emptyList()) }
        val nineDeferred = async { runCatching { nineAnimeSource.getTrending() }.getOrDefault(emptyList()) }
        val jkDeferred = async { runCatching { jkAnimeSource.getRecentEpisodes() }.getOrDefault(emptyList()) }
        val ytDeferred = async { runCatching { animeYtSource.getTrending() }.getOrDefault(emptyList()) }
        val gogoDeferred = async { runCatching { gogoAnimeSource.getTrending() }.getOrDefault(emptyList()) }

        val latino = latinoDeferred.await()
        val stream = streamDeferred.await()
        val nine = nineDeferred.await()
        val jk = jkDeferred.await()
        val yt = ytDeferred.await()
        val gogo = gogoDeferred.await()

        if (latino.isNotEmpty()) cachedLatinoTrending = latino
        if (stream.isNotEmpty()) cachedSoloStream = stream
        if (nine.isNotEmpty()) cachedNineAnime = nine
        if (jk.isNotEmpty()) cachedJKRecent = jk
        if (yt.isNotEmpty()) cachedAnimeYt = yt
        if (gogo.isNotEmpty()) cachedGogo = gogo

        val freshData = HomeCatalogData(
            latinoTrending = cachedLatinoTrending,
            soloStreamTrending = cachedSoloStream,
            nineAnimeTrending = cachedNineAnime,
            recentEpisodes = cachedJKRecent,
            animeYtTrending = cachedAnimeYt,
            gogoTrending = cachedGogo
        )

        if (context != null && (freshData.latinoTrending.isNotEmpty() || freshData.recentEpisodes.isNotEmpty())) {
            HomeCatalogCache.save(context, freshData)
        }

        freshData
    }

    private val TITLE_ALIASES: Map<String, List<String>> = mapOf(
        "hidden murder" to listOf("Parecido a un asesinato"),
        "parecido a un asesinato" to listOf("Hidden Murder")
    )

    private fun getSearchQueries(query: String): List<String> {
        val qClean = query.trim().lowercase()
        val list = mutableListOf(query.trim())
        for ((k, aliases) in TITLE_ALIASES) {
            if (qClean.contains(k) || k.contains(qClean)) {
                list.addAll(aliases)
            }
        }
        return list.distinct()
    }

    suspend fun searchAll(query: String): List<AnimeCard> = coroutineScope {
        val queries = getSearchQueries(query)
        val list = mutableListOf<AnimeCard>()

        for (q in queries) {
            val latinoDef = async { runCatching { soloLatinoSource.search(q) }.getOrDefault(emptyList()) }
            val streamDef = async { runCatching { soloStreamSource.search(q) }.getOrDefault(emptyList()) }
            val nineDef = async { runCatching { nineAnimeSource.search(q) }.getOrDefault(emptyList()) }
            val jkDef = async { runCatching { jkAnimeSource.search(q) }.getOrDefault(emptyList()) }
            val ytDef = async { runCatching { animeYtSource.search(q) }.getOrDefault(emptyList()) }
            val gogoDef = async { runCatching { gogoAnimeSource.search(q) }.getOrDefault(emptyList()) }

            list.addAll(latinoDef.await())
            list.addAll(streamDef.await())
            list.addAll(nineDef.await())
            list.addAll(jkDef.await())
            list.addAll(ytDef.await())
            list.addAll(gogoDef.await())
        }
        list.distinctBy { it.detailUrl }
    }

    suspend fun getAnimeDetail(card: AnimeCard): AnimeDetail {
        return when {
            card.source.contains("SoloStream", ignoreCase = true) ->
                soloStreamSource.getAnimeDetail(card.detailUrl)
            card.source.contains("9Anime", ignoreCase = true) || card.detailUrl.contains("9anime") ->
                nineAnimeSource.getAnimeDetail(card.detailUrl)
            card.source.contains("SoloLatino", ignoreCase = true) || card.detailUrl.contains("sololatino") ->
                soloLatinoSource.getAnimeDetail(card.detailUrl)
            card.source.contains("JKAnime", ignoreCase = true) || card.detailUrl.contains("jkanime") ->
                jkAnimeSource.getAnimeDetail(card.detailUrl)
            card.source.contains("AnimeYT", ignoreCase = true) || card.detailUrl.contains("animeyt") ->
                animeYtSource.getAnimeDetail(card.detailUrl)
            card.source.contains("GogoAnime", ignoreCase = true) || card.detailUrl.contains("gogoanime") ->
                gogoAnimeSource.getAnimeDetail(card.detailUrl)
            else -> jkAnimeSource.getAnimeDetail(card.detailUrl)
        }
    }

    suspend fun resolveStream(context: Context, episodeUrl: String, source: String): StreamResult? {
        // 1. Direct resolution from source
        val directResult = when {
            source.contains("SoloStream", ignoreCase = true) ->
                soloStreamSource.resolveStream(episodeUrl)
            source.contains("9Anime", ignoreCase = true) || episodeUrl.contains("9anime") ->
                nineAnimeSource.resolveStream(episodeUrl)
            source.contains("JKAnime", ignoreCase = true) || episodeUrl.contains("jkanime") ->
                jkAnimeSource.resolveStream(episodeUrl)
            source.contains("AnimeYT", ignoreCase = true) || episodeUrl.contains("animeyt") ->
                animeYtSource.resolveStream(episodeUrl)
            source.contains("GogoAnime", ignoreCase = true) || episodeUrl.contains("gogoanime") ->
                gogoAnimeSource.resolveStream(episodeUrl)
            else -> soloLatinoSource.resolveStream(episodeUrl)
        }

        if (directResult != null) return directResult

        // 2. Offscreen media interception via HeadlessStreamExtractor
        return HeadlessStreamExtractor.extractStreamUrl(context, episodeUrl)
    }
}

data class HomeCatalogData(
    val latinoTrending: List<AnimeCard>,
    val soloStreamTrending: List<AnimeCard> = emptyList(),
    val nineAnimeTrending: List<AnimeCard> = emptyList(),
    val recentEpisodes: List<AnimeCard>,
    val animeYtTrending: List<AnimeCard> = emptyList(),
    val gogoTrending: List<AnimeCard> = emptyList()
)
