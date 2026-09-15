package com.example.animetv.core.history

import android.content.Context
import com.example.animetv.core.util.CoverUtils
import org.json.JSONArray
import org.json.JSONObject

data class PlaybackRecord(
    val animeDetailUrl: String,
    val animeTitle: String = "",
    val posterUrl: String = "",
    val source: String = "",
    val episodeUrl: String,
    val episodeTitle: String,
    val episodeNumber: Int,
    val positionMs: Long,
    val durationMs: Long,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("animeDetailUrl", animeDetailUrl)
            put("animeTitle", animeTitle)
            put("posterUrl", posterUrl)
            put("source", source)
            put("episodeUrl", episodeUrl)
            put("episodeTitle", episodeTitle)
            put("episodeNumber", episodeNumber)
            put("positionMs", positionMs)
            put("durationMs", durationMs)
            put("timestamp", timestamp)
        }
    }

    companion object {
        fun fromJson(obj: JSONObject): PlaybackRecord? {
            val animeUrl = obj.optString("animeDetailUrl")
            val epUrl = obj.optString("episodeUrl")
            if (animeUrl.isEmpty() && epUrl.isEmpty()) return null
            return PlaybackRecord(
                animeDetailUrl = animeUrl,
                animeTitle = obj.optString("animeTitle", ""),
                posterUrl = obj.optString("posterUrl", ""),
                source = obj.optString("source", ""),
                episodeUrl = epUrl,
                episodeTitle = obj.optString("episodeTitle", "Episodio"),
                episodeNumber = obj.optInt("episodeNumber", 1),
                positionMs = obj.optLong("positionMs", 0L),
                durationMs = obj.optLong("durationMs", 0L),
                timestamp = obj.optLong("timestamp", System.currentTimeMillis())
            )
        }
    }
}

object PlaybackHistoryStore {
    private const val PREFS_NAME = "anime_tv_playback_history"
    private const val KEY_RECORDS = "records"
    private const val MAX_RECORDS = 100

    fun saveProgress(
        context: Context,
        animeDetailUrl: String,
        animeTitle: String = "",
        posterUrl: String = "",
        source: String = "",
        episodeUrl: String,
        episodeTitle: String,
        episodeNumber: Int,
        positionMs: Long,
        durationMs: Long
    ) {
        if (episodeUrl.isEmpty()) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val all = loadAllRaw(context).toMutableList()

        // Find if we already had a record with a valid cover poster
        val existing = all.firstOrNull { it.animeDetailUrl == animeDetailUrl || it.episodeUrl == episodeUrl }
        val finalPoster = when {
            CoverUtils.isValidCover(posterUrl) -> posterUrl.trim()
            existing != null && CoverUtils.isValidCover(existing.posterUrl) -> existing.posterUrl.trim()
            else -> ""
        }

        // Remove previous entry for this anime or episode
        all.removeAll { it.animeDetailUrl == animeDetailUrl || it.episodeUrl == episodeUrl }

        all.add(0, PlaybackRecord(
            animeDetailUrl = animeDetailUrl,
            animeTitle = animeTitle,
            posterUrl = finalPoster,
            source = source,
            episodeUrl = episodeUrl,
            episodeTitle = episodeTitle,
            episodeNumber = episodeNumber,
            positionMs = positionMs,
            durationMs = durationMs,
            timestamp = System.currentTimeMillis()
        ))

        saveAll(prefs, all)
    }

    fun updatePoster(context: Context, animeDetailUrl: String, episodeUrl: String, newPosterUrl: String) {
        if (!CoverUtils.isValidCover(newPosterUrl)) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val all = loadAllRaw(context).toMutableList()
        var updated = false

        for (i in 0 until all.size) {
            val r = all[i]
            if ((animeDetailUrl.isNotEmpty() && r.animeDetailUrl == animeDetailUrl) ||
                (episodeUrl.isNotEmpty() && r.episodeUrl == episodeUrl)) {
                all[i] = r.copy(posterUrl = newPosterUrl.trim())
                updated = true
            }
        }

        if (updated) {
            saveAll(prefs, all)
        }
    }

    fun removeRecord(context: Context, animeDetailUrl: String, episodeUrl: String = "") {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val all = loadAllRaw(context).toMutableList()
        val targetAnime = animeDetailUrl.trimEnd('/')
        val targetEp = episodeUrl.trimEnd('/')
        val removed = all.removeAll { r ->
            val rAnime = r.animeDetailUrl.trimEnd('/')
            val rEp = r.episodeUrl.trimEnd('/')
            (targetAnime.isNotEmpty() && (rAnime == targetAnime || 
                (targetAnime.contains("/serie/") && rAnime.contains(targetAnime.substringAfter("/serie/").trimEnd('/'))) ||
                (targetAnime.contains("/anime/") && rAnime.contains(targetAnime.substringAfter("/anime/").trimEnd('/'))))) ||
            (targetEp.isNotEmpty() && rEp == targetEp)
        }
        if (removed) {
            saveAll(prefs, all)
        }
    }

    private fun saveAll(prefs: android.content.SharedPreferences, list: List<PlaybackRecord>) {
        val trimmed = list.take(MAX_RECORDS)
        val arr = JSONArray()
        trimmed.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_RECORDS, arr.toString()).apply()
    }

    fun getRecordForAnime(context: Context, animeDetailUrl: String): PlaybackRecord? {
        if (animeDetailUrl.isEmpty()) return null
        val target = animeDetailUrl.trimEnd('/')
        return loadAll(context).firstOrNull {
            val rAnime = it.animeDetailUrl.trimEnd('/')
            val rEp = it.episodeUrl.trimEnd('/')
            rAnime == target || rEp == target ||
            (target.contains("/anime/") && rAnime.contains(target.substringAfter("/anime/").trimEnd('/'))) ||
            (target.contains("/serie/") && rAnime.contains(target.substringAfter("/serie/").trimEnd('/'))) ||
            (target.contains("/pelicula/") && rAnime.contains(target.substringAfter("/pelicula/").trimEnd('/')))
        }
    }

    fun getRecordForEpisode(context: Context, episodeUrl: String): PlaybackRecord? {
        if (episodeUrl.isEmpty()) return null
        val target = episodeUrl.trimEnd('/')
        return loadAll(context).firstOrNull { it.episodeUrl.trimEnd('/') == target || it.animeDetailUrl.trimEnd('/') == target }
    }

    private fun loadAllRaw(context: Context): List<PlaybackRecord> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_RECORDS, null) ?: return emptyList()
        val list = mutableListOf<PlaybackRecord>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                PlaybackRecord.fromJson(arr.getJSONObject(i))?.let { list.add(it) }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list.sortedByDescending { it.timestamp }
    }

    fun loadAll(context: Context): List<PlaybackRecord> {
        return loadAllRaw(context).map { rec ->
            if (CoverUtils.isLogoOrInvalidCover(rec.posterUrl)) {
                rec.copy(posterUrl = "")
            } else {
                rec
            }
        }
    }
}
