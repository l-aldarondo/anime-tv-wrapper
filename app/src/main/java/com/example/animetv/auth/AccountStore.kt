package com.example.animetv.auth

import android.content.Context
import org.json.JSONObject

/**
 * Local store for user authentication credentials (Master User/Password and site-specific overrides).
 */
object AccountStore {
    private const val PREFS_NAME = "anime_tv_credentials"
    private const val KEY_MASTER_USER = "master_user"
    private const val KEY_MASTER_PASS = "master_pass"
    private const val KEY_SITE_OVERRIDES = "site_overrides"

    fun getMasterUsername(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_MASTER_USER, "") ?: ""

    fun getMasterPassword(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_MASTER_PASS, "") ?: ""

    fun hasMasterCredentials(context: Context): Boolean =
        getMasterUsername(context).isNotEmpty() && getMasterPassword(context).isNotEmpty()

    fun saveMasterCredentials(context: Context, username: String, password: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MASTER_USER, username.trim())
            .putString(KEY_MASTER_PASS, password)
            .apply()
    }

    /**
     * Gets credentials for a specific host, falling back to master credentials.
     */
    fun getCredentialsForHost(context: Context, host: String): Pair<String, String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val overridesJson = prefs.getString(KEY_SITE_OVERRIDES, null)
        if (overridesJson != null) {
            try {
                val json = JSONObject(overridesJson)
                for (key in json.keys()) {
                    if (host.contains(key, ignoreCase = true)) {
                        val obj = json.getJSONObject(key)
                        val u = obj.optString("user")
                        val p = obj.optString("pass")
                        if (u.isNotEmpty() && p.isNotEmpty()) return Pair(u, p)
                    }
                }
            } catch (e: Exception) {}
        }
        return Pair(getMasterUsername(context), getMasterPassword(context))
    }
}
