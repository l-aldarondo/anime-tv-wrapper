package com.example.animetv.core.history

import android.content.Context
import android.content.SharedPreferences
import com.example.animetv.core.model.AnimeEpisode

object WatchedEpisodeStore {
    private const val PREFS_NAME = "anime_tv_watched_episodes"
    private const val KEY_WATCHED_SET = "watched_episodes_set"
    private const val KEY_FULLY_WATCHED_ANIMES = "fully_watched_animes_set"
    private const val KEY_EXPLICIT_UNWATCHED = "explicit_unwatched_episodes_set"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun getSet(context: Context, key: String): MutableSet<String> {
        val set = getPrefs(context).getStringSet(key, emptySet()) ?: emptySet()
        return HashSet(set)
    }

    private fun saveSet(context: Context, key: String, set: Set<String>) {
        getPrefs(context).edit().putStringSet(key, set).apply()
    }

    private fun cleanAnimeUrl(url: String): String = url.trimEnd('/')

    private fun buildEpisodeKey(animeDetailUrl: String, seasonNumber: Int, episodeNumber: Int, episodeUrl: String): String {
        val cleanAnime = cleanAnimeUrl(animeDetailUrl)
        val s = if (seasonNumber > 0) seasonNumber else 1
        return "$cleanAnime#s:$s#e:$episodeNumber"
    }

    fun isAnimeFullyWatched(context: Context, animeDetailUrl: String): Boolean {
        val fullySet = getSet(context, KEY_FULLY_WATCHED_ANIMES)
        return fullySet.contains(cleanAnimeUrl(animeDetailUrl))
    }

    fun setAnimeFullyWatched(
        context: Context,
        animeDetailUrl: String,
        episodes: List<AnimeEpisode> = emptyList(),
        watched: Boolean
    ) {
        val cleanAnime = cleanAnimeUrl(animeDetailUrl)
        val fullySet = getSet(context, KEY_FULLY_WATCHED_ANIMES)
        val watchedSet = getSet(context, KEY_WATCHED_SET)
        val unwatchSet = getSet(context, KEY_EXPLICIT_UNWATCHED)

        if (watched) {
            fullySet.add(cleanAnime)
            // Clear any explicit unwatches for this anime
            unwatchSet.removeAll { it.startsWith("$cleanAnime#") || it == cleanAnime }
            // Add all known episodes to watchedSet as well
            for (ep in episodes) {
                val key = buildEpisodeKey(animeDetailUrl, ep.seasonNumber, ep.episodeNumber, ep.episodeUrl)
                watchedSet.add(key)
                if (ep.episodeUrl.isNotEmpty()) watchedSet.add(ep.episodeUrl.trimEnd('/'))
            }
        } else {
            fullySet.remove(cleanAnime)
            unwatchSet.removeAll { it.startsWith("$cleanAnime#") || it == cleanAnime }
            // Remove all watched records belonging to this anime
            watchedSet.removeAll { it.startsWith("$cleanAnime#") || it == cleanAnime }
            for (ep in episodes) {
                if (ep.episodeUrl.isNotEmpty()) watchedSet.remove(ep.episodeUrl.trimEnd('/'))
            }
        }

        getPrefs(context).edit()
            .putStringSet(KEY_FULLY_WATCHED_ANIMES, fullySet)
            .putStringSet(KEY_WATCHED_SET, watchedSet)
            .putStringSet(KEY_EXPLICIT_UNWATCHED, unwatchSet)
            .apply()
    }

    fun isEpisodeWatched(
        context: Context,
        animeDetailUrl: String,
        seasonNumber: Int,
        episodeNumber: Int,
        episodeUrl: String = ""
    ): Boolean {
        val key = buildEpisodeKey(animeDetailUrl, seasonNumber, episodeNumber, episodeUrl)
        val unwatchSet = getSet(context, KEY_EXPLICIT_UNWATCHED)
        if (unwatchSet.contains(key) || (episodeUrl.isNotEmpty() && unwatchSet.contains(episodeUrl.trimEnd('/')))) {
            return false
        }

        if (isAnimeFullyWatched(context, animeDetailUrl)) {
            return true
        }

        val set = getSet(context, KEY_WATCHED_SET)
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
        val key = buildEpisodeKey(animeDetailUrl, seasonNumber, episodeNumber, episodeUrl)
        val watchedSet = getSet(context, KEY_WATCHED_SET)
        val unwatchSet = getSet(context, KEY_EXPLICIT_UNWATCHED)
        val cleanAnime = cleanAnimeUrl(animeDetailUrl)
        val isFully = isAnimeFullyWatched(context, animeDetailUrl)

        if (watched) {
            watchedSet.add(key)
            if (episodeUrl.isNotEmpty()) watchedSet.add(episodeUrl.trimEnd('/'))
            unwatchSet.remove(key)
            if (episodeUrl.isNotEmpty()) unwatchSet.remove(episodeUrl.trimEnd('/'))
        } else {
            watchedSet.remove(key)
            if (episodeUrl.isNotEmpty()) watchedSet.remove(episodeUrl.trimEnd('/'))
            if (isFully) {
                unwatchSet.add(key)
                if (episodeUrl.isNotEmpty()) unwatchSet.add(episodeUrl.trimEnd('/'))
            }
        }

        getPrefs(context).edit()
            .putStringSet(KEY_WATCHED_SET, watchedSet)
            .putStringSet(KEY_EXPLICIT_UNWATCHED, unwatchSet)
            .apply()
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
        for (ep in episodes) {
            setEpisodeWatched(context, animeDetailUrl, ep.seasonNumber, ep.episodeNumber, ep.episodeUrl, watched)
        }
    }

    fun getWatchedCount(
        context: Context,
        animeDetailUrl: String,
        episodes: List<AnimeEpisode>
    ): Int {
        return episodes.count { ep ->
            isEpisodeWatched(context, animeDetailUrl, ep.seasonNumber, ep.episodeNumber, ep.episodeUrl)
        }
    }
}
