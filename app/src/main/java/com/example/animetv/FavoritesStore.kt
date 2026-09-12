package com.example.animetv

import android.content.Context
import org.json.JSONArray

/**
 * Local persistent store for "Mi Lista" favorites using JSON in SharedPreferences.
 */
object FavoritesStore {
    private const val PREFS_NAME = "anime_tv_favorites"
    private const val KEY_ITEMS = "items"

    fun loadAll(context: Context): List<FavoriteItem> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ITEMS, null) ?: return emptyList()
        val result = mutableListOf<FavoriteItem>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                FavoriteItem.fromJson(arr.getJSONObject(i))?.let { result.add(it) }
            }
        } catch (e: Exception) {
            return emptyList()
        }
        return result.sortedByDescending { it.addedAt }
    }

    fun contains(context: Context, url: String): Boolean =
        loadAll(context).any { it.url == url }

    fun saveAll(context: Context, items: List<FavoriteItem>) {
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ITEMS, arr.toString())
            .apply()
    }

    /**
     * Toggles favorite status: adds if absent, removes if present.
     * Returns true if newly added, false if removed.
     */
    fun toggle(context: Context, item: FavoriteItem): Boolean {
        val current = loadAll(context).toMutableList()
        val existingIndex = current.indexOfFirst { it.url == item.url }
        return if (existingIndex >= 0) {
            current.removeAt(existingIndex)
            saveAll(context, current)
            false
        } else {
            current.add(0, item)
            saveAll(context, current)
            true
        }
    }

    fun remove(context: Context, url: String) {
        val current = loadAll(context).filterNot { it.url == url }
        saveAll(context, current)
    }

    fun isFavorite(context: Context, url: String): Boolean = contains(context, url)

    fun getFavorites(context: Context): List<FavoriteItem> = loadAll(context)

    fun add(context: Context, item: FavoriteItem) {
        if (!contains(context, item.url)) {
            toggle(context, item)
        }
    }
}
