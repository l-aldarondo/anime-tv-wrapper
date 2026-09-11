package com.example.animetv

import android.content.Context
import org.json.JSONArray

// Local-only "My List" persistence - a flat JSON array in its own SharedPreferences file
// (deliberately separate from PREFS_NAME/anime_tv_prefs, which holds transient UI state like the
// last-active source, not user data worth keeping isolated). No sync, no server - a long-press-OK
// on a show anywhere in the app is meant to be an instant, always-available local bookmark.
object FavoritesStore {
    private const val PREFS_NAME = "anime_tv_favorites"
    private const val KEY_ITEMS = "items"

    fun loadAll(context: Context): List<FavoriteItem> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_ITEMS, null)
            ?: return emptyList()
        val result = mutableListOf<FavoriteItem>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                FavoriteItem.fromJson(arr.getJSONObject(i))?.let { result.add(it) }
            }
        } catch (e: Exception) {
            return emptyList()
        }
        // Newest-added first - matches how a "recently saved" list is expected to read.
        return result.sortedByDescending { it.addedAt }
    }

    fun contains(context: Context, url: String): Boolean =
        loadAll(context).any { it.url == url }

    private fun saveAll(context: Context, items: List<FavoriteItem>) {
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ITEMS, arr.toString())
            .apply()
    }

    // Adds the item if its url isn't already saved, removes it if it is. Returns true if the net
    // effect was an add, false if it was a remove - callers use this to pick the right Toast/label.
    fun toggle(context: Context, item: FavoriteItem): Boolean {
        val current = loadAll(context).toMutableList()
        val existingIndex = current.indexOfFirst { it.url == item.url }
        return if (existingIndex >= 0) {
            current.removeAt(existingIndex)
            saveAll(context, current)
            false
        } else {
            current.add(item)
            saveAll(context, current)
            true
        }
    }

    fun remove(context: Context, url: String) {
        val current = loadAll(context).filterNot { it.url == url }
        saveAll(context, current)
    }
}
