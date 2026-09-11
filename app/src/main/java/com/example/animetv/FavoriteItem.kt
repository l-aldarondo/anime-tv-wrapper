package com.example.animetv

import org.json.JSONObject

/**
 * Represents a bookmarked anime series or episode saved in "Mi Lista".
 */
data class FavoriteItem(
    val url: String,
    val title: String,
    val poster: String,
    val source: String,
    val addedAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("url", url)
        put("title", title)
        put("poster", poster)
        put("source", source)
        put("addedAt", addedAt)
    }

    companion object {
        fun fromJson(json: JSONObject): FavoriteItem? {
            val url = json.optString("url")
            val title = json.optString("title")
            if (url.isEmpty() || title.isEmpty()) return null
            return FavoriteItem(
                url = url,
                title = title,
                poster = json.optString("poster"),
                source = json.optString("source"),
                addedAt = json.optLong("addedAt", System.currentTimeMillis())
            )
        }
    }
}
