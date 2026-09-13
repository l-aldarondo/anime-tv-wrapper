package com.example.animetv.core

import android.content.Context
import com.example.animetv.core.model.AnimeCard
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * High-performance disk cache for the Android TV Home Screen catalog.
 * Enables zero-latency startup by loading previously scraped rows and posters instantly
 * from local storage while performing silent background revalidation.
 */
object HomeCatalogCache {
    private const val CACHE_FILE_NAME = "home_catalog_cache.json"

    fun save(context: Context, data: HomeCatalogData) {
        try {
            val root = JSONObject().apply {
                put("timestamp", System.currentTimeMillis())
                put("latinoTrending", cardsToJson(data.latinoTrending))
                put("soloStreamTrending", cardsToJson(data.soloStreamTrending))
                put("nineAnimeTrending", cardsToJson(data.nineAnimeTrending))
                put("recentEpisodes", cardsToJson(data.recentEpisodes))
                put("animeYtTrending", cardsToJson(data.animeYtTrending))
                put("gogoTrending", cardsToJson(data.gogoTrending))
            }
            val file = File(context.filesDir, CACHE_FILE_NAME)
            file.writeText(root.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun load(context: Context): HomeCatalogData? {
        return try {
            val file = File(context.filesDir, CACHE_FILE_NAME)
            if (!file.exists()) return null
            val raw = file.readText()
            if (raw.isEmpty()) return null

            val root = JSONObject(raw)
            val latino = jsonToCards(root.optJSONArray("latinoTrending"))
            val soloStream = jsonToCards(root.optJSONArray("soloStreamTrending"))
            val nineAnime = jsonToCards(root.optJSONArray("nineAnimeTrending"))
            val recent = jsonToCards(root.optJSONArray("recentEpisodes"))
            val animeYt = jsonToCards(root.optJSONArray("animeYtTrending"))
            val gogo = jsonToCards(root.optJSONArray("gogoTrending"))

            if (latino.isEmpty() && recent.isEmpty()) return null

            HomeCatalogData(
                latinoTrending = latino,
                soloStreamTrending = soloStream,
                nineAnimeTrending = nineAnime,
                recentEpisodes = recent,
                animeYtTrending = animeYt,
                gogoTrending = gogo
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun cardsToJson(cards: List<AnimeCard>): JSONArray {
        val arr = JSONArray()
        for (card in cards) {
            val obj = JSONObject().apply {
                put("id", card.id)
                put("title", card.title)
                put("posterUrl", card.posterUrl)
                put("backdropUrl", card.backdropUrl)
                put("detailUrl", card.detailUrl)
                put("source", card.source)
                put("episodeBadge", card.episodeBadge)
                put("rating", card.rating)
                put("synopsis", card.synopsis)
            }
            arr.put(obj)
        }
        return arr
    }

    private fun jsonToCards(arr: JSONArray?): List<AnimeCard> {
        if (arr == null) return emptyList()
        val list = mutableListOf<AnimeCard>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            list.add(
                AnimeCard(
                    id = obj.optString("id"),
                    title = obj.optString("title"),
                    posterUrl = obj.optString("posterUrl"),
                    backdropUrl = obj.optString("backdropUrl", ""),
                    detailUrl = obj.optString("detailUrl"),
                    source = obj.optString("source"),
                    episodeBadge = obj.optString("episodeBadge", ""),
                    rating = obj.optString("rating", ""),
                    synopsis = obj.optString("synopsis", "")
                )
            )
        }
        return list
    }
}
