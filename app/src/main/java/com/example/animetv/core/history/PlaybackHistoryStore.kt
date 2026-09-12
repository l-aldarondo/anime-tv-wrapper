package com.example.animetv.core.history

import android.content.Context
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
        val all = loadAll(context).toMutableList()

        // Remove previous entry for this anime or episode
        all.removeAll { it.animeDetailUrl == animeDetailUrl || it.episodeUrl == episodeUrl }

        all.add(0, PlaybackRecord(
            animeDetailUrl = animeDetailUrl,
            animeTitle = animeTitle,
            posterUrl = posterUrl,
            source = source,
            episodeUrl = episodeUrl,
            episodeTitle = episodeTitle,
            episodeNumber = episodeNumber,
            positionMs = positionMs,
            durationMs = durationMs,
            timestamp = System.currentTimeMillis()
        ))

        val trimmed = all.take(MAX_RECORDS)
        val arr = JSONArray()
        trimmed.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_RECORDS, arr.toString()).apply()
    }

    fun getRecordForAnime(context: Context, animeDetailUrl: String): PlaybackRecord? {
        if (animeDetailUrl.isEmpty()) return null
        return loadAll(context).firstOrNull { it.animeDetailUrl == animeDetailUrl }
    }

    fun getRecordForEpisode(context: Context, episodeUrl: String): PlaybackRecord? {
        if (episodeUrl.isEmpty()) return null
        return loadAll(context).firstOrNull { it.episodeUrl == episodeUrl }
    }

    fun loadAll(context: Context): List<PlaybackRecord> {
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
}
