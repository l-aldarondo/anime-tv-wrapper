package com.example.animetv.core.source

import com.example.animetv.core.model.AnimeCard
import com.example.animetv.core.model.AnimeDetail
import com.example.animetv.core.model.StreamResult

interface AnimeSource {
    val name: String
    var baseUrl: String
    val mirrors: List<String>

    suspend fun getTrending(): List<AnimeCard>
    suspend fun getRecentEpisodes(): List<AnimeCard>
    suspend fun search(query: String): List<AnimeCard>
    suspend fun getAnimeDetail(detailUrl: String): AnimeDetail
    suspend fun resolveStream(episodeUrl: String): StreamResult?

    suspend fun rotateMirror() {
        val working = MirrorManager.findWorkingMirror(mirrors)
        if (working != null) {
            baseUrl = working
        }
    }
}
