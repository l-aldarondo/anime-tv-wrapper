package com.example.animetv.core.history

import android.content.Context
import android.content.SharedPreferences
import com.example.animetv.core.model.AnimeEpisode

object WatchedEpisodeStore {
    private const val PREFS_NAME = "anime_tv_watched_episodes"
    private const val KEY_WATCHED_SET = "watched_episodes_set"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun getWatchedSet(context: Context): MutableSet<String> {
        val set = getPrefs(context).getStringSet(KEY_WATCHED_SET, emptySet()) ?: emptySet()
        return HashSet(set)
    }

    private fun buildEpisodeKey(animeDetailUrl: String, seasonNumber: Int, episodeNumber: Int, episodeUrl: String): String {
        val cleanAnime = animeDetailUrl.trimEnd('/')
        val s = if (seasonNumber > 0) seasonNumber else 1
        return "$cleanAnime#s:$s#e:$episodeNumber"
    }

    fun isEpisodeWatched(
        context: Context,
        animeDetailUrl: String,
        seasonNumber: Int,
        episodeNumber: Int,
        episodeUrl: String = ""
    ): Boolean {
        val set = getWatchedSet(context)
        val key = buildEpisodeKey(animeDetailUrl, seasonNumber, episodeNumber, episodeUrl)
        if (set.contains(key)) return true
        if (episodeUrl.isNotEmpty() && set.contains(episodeUrl.trimEnd('/'))) return true
        return false
    }

    fun setEpisodeWatched(
        context: Context,
        animeDetailUrl: String,
        seasonNumber: Int,
        episodeNumber: Int,
        episodeUrl: String = "",
        watched: Boolean
    ) {
        val set = getWatchedSet(context)
        val key = buildEpisodeKey(animeDetailUrl, seasonNumber, episodeNumber, episodeUrl)
        if (watched) {
            set.add(key)
            if (episodeUrl.isNotEmpty()) set.add(episodeUrl.trimEnd('/'))
        } else {
            set.remove(key)
            if (episodeUrl.isNotEmpty()) set.remove(episodeUrl.trimEnd('/'))
        }
        getPrefs(context).edit().putStringSet(KEY_WATCHED_SET, set).apply()
    }

    fun toggleEpisodeWatched(
        context: Context,
        animeDetailUrl: String,
        seasonNumber: Int,
        episodeNumber: Int,
        episodeUrl: String = ""
    ): Boolean {
        val current = isEpisodeWatched(context, animeDetailUrl, seasonNumber, episodeNumber, episodeUrl)
        val target = !current
        setEpisodeWatched(context, animeDetailUrl, seasonNumber, episodeNumber, episodeUrl, target)
        return target
    }

    fun markSeasonWatched(
        context: Context,
        animeDetailUrl: String,
        episodes: List<AnimeEpisode>,
        watched: Boolean
    ) {
        val set = getWatchedSet(context)
        for (ep in episodes) {
            val key = buildEpisodeKey(animeDetailUrl, ep.seasonNumber, ep.episodeNumber, ep.episodeUrl)
            if (watched) {
                set.add(key)
                if (ep.episodeUrl.isNotEmpty()) set.add(ep.episodeUrl.trimEnd('/'))
            } else {
                set.remove(key)
                if (ep.episodeUrl.isNotEmpty()) set.remove(ep.episodeUrl.trimEnd('/'))
            }
        }
        getPrefs(context).edit().putStringSet(KEY_WATCHED_SET, set).apply()
    }

    fun getWatchedCount(
        context: Context,
        animeDetailUrl: String,
        episodes: List<AnimeEpisode>
    ): Int {
        val set = getWatchedSet(context)
        return episodes.count { ep ->
            val key = buildEpisodeKey(animeDetailUrl, ep.seasonNumber, ep.episodeNumber, ep.episodeUrl)
            set.contains(key) || (ep.episodeUrl.isNotEmpty() && set.contains(ep.episodeUrl.trimEnd('/')))
        }
    }
}
