package com.example.animetv

import org.json.JSONObject

// A show/movie the user long-pressed OK on, saved to the native "My List" screen. `url` is
// always the site's own detail-page URL (never a mirror/OG-tag URL - see content_script.js's
// extractFavoriteCandidate(), which deliberately never trusts og:url) and doubles as the
// dedup/identity key, since two different shows never share one detail-page URL.
data class FavoriteItem(
    val url: String,
    val title: String,
    val poster: String,
    val source: String,
    val addedAt: Long
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
                addedAt = json.optLong("addedAt")
            )
        }
    }
}
