package com.example.animetv.core.history

import android.content.Context

/**
 * Small, app-wide UI preferences that aren't tied to a specific show (unlike
 * [PlaybackHistoryStore]/[WatchedEpisodeStore], which are keyed per anime/episode).
 */
object UiPreferencesStore {
    private const val PREFS_NAME = "anime_tv_ui_preferences"
    private const val KEY_EPISODES_ASCENDING = "episodes_ascending"

    private fun getPrefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEpisodesAscending(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_EPISODES_ASCENDING, true)
    }

    fun setEpisodesAscending(context: Context, ascending: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_EPISODES_ASCENDING, ascending).apply()
    }
}
