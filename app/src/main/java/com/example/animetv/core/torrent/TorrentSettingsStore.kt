package com.example.animetv.core.torrent

import android.content.Context
import android.content.SharedPreferences

object TorrentSettingsStore {
    private const val PREFS_NAME = "anime_tv_torrent_settings"

    private const val KEY_TMDB_API_KEY = "tmdb_api_key"
    private const val KEY_TORRSERVER_URL = "torrserver_url"
    private const val KEY_JACKETT_URL = "jackett_url"
    private const val KEY_JACKETT_API_KEY = "jackett_api_key"
    private const val KEY_PREFER_SPANISH = "prefer_spanish"
    private const val KEY_DISALLOW_4K = "disallow_4k"
    private const val KEY_QUALITY_FILTER = "quality_filter" // "1080p", "720p", "all"
    private const val KEY_LANGUAGE_FILTER = "language_filter" // "all", "spanish_only", "dual_audio", "sub_only"
    private const val KEY_EPISODE_CLICK_ACTION = "episode_click_action" // "web", "torrent", "ask"

    // Default public community TMDB API v3 key for out-of-the-box metadata enrichment
    const val DEFAULT_TMDB_API_KEY = "3b0e14112e1a3848b61e27a6f2be7e1c"
    const val DEFAULT_TORRSERVER_URL = "http://127.0.0.1:8090"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getTmdbApiKey(context: Context): String {
        val key = getPrefs(context).getString(KEY_TMDB_API_KEY, "") ?: ""
        return if (key.isNotEmpty()) key else DEFAULT_TMDB_API_KEY
    }

    fun setTmdbApiKey(context: Context, key: String) {
        getPrefs(context).edit().putString(KEY_TMDB_API_KEY, key.trim()).apply()
    }

    fun getTorrServerUrl(context: Context): String {
        val url = getPrefs(context).getString(KEY_TORRSERVER_URL, "") ?: ""
        return if (url.isNotEmpty()) url.trimEnd('/') else DEFAULT_TORRSERVER_URL
    }

    fun setTorrServerUrl(context: Context, url: String) {
        getPrefs(context).edit().putString(KEY_TORRSERVER_URL, url.trim().trimEnd('/')).apply()
    }

    fun getJackettUrl(context: Context): String {
        return (getPrefs(context).getString(KEY_JACKETT_URL, "") ?: "").trimEnd('/')
    }

    fun setJackettUrl(context: Context, url: String) {
        getPrefs(context).edit().putString(KEY_JACKETT_URL, url.trim().trimEnd('/')).apply()
    }

    fun getJackettApiKey(context: Context): String {
        return getPrefs(context).getString(KEY_JACKETT_API_KEY, "") ?: ""
    }

    fun setJackettApiKey(context: Context, key: String) {
        getPrefs(context).edit().putString(KEY_JACKETT_API_KEY, key.trim()).apply()
    }

    fun isPreferSpanish(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_PREFER_SPANISH, true)
    }

    fun setPreferSpanish(context: Context, prefer: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_PREFER_SPANISH, prefer).apply()
    }

    fun isDisallow4k(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_DISALLOW_4K, true)
    }

    fun setDisallow4k(context: Context, disallow: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_DISALLOW_4K, disallow).apply()
    }

    fun getQualityFilter(context: Context): String {
        return getPrefs(context).getString(KEY_QUALITY_FILTER, "1080p") ?: "1080p"
    }

    fun setQualityFilter(context: Context, quality: String) {
        getPrefs(context).edit().putString(KEY_QUALITY_FILTER, quality).apply()
    }

    fun getLanguageFilter(context: Context): String {
        return getPrefs(context).getString(KEY_LANGUAGE_FILTER, "all") ?: "all"
    }

    fun setLanguageFilter(context: Context, lang: String) {
        getPrefs(context).edit().putString(KEY_LANGUAGE_FILTER, lang).apply()
    }

    fun getEpisodeClickAction(context: Context): String {
        return getPrefs(context).getString(KEY_EPISODE_CLICK_ACTION, "web") ?: "web"
    }

    fun setEpisodeClickAction(context: Context, action: String) {
        getPrefs(context).edit().putString(KEY_EPISODE_CLICK_ACTION, action).apply()
    }
}
