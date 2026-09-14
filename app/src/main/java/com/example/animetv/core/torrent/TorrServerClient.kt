package com.example.animetv.core.torrent

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object TorrServerClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    /**
     * Checks if TorrServer is alive and responding on the specified host.
     */
    suspend fun isServerAlive(serverUrl: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val echoUrl = "$serverUrl/echo"
            val req = Request.Builder().url(echoUrl).build()
            client.newCall(req).execute().use { resp ->
                resp.isSuccessful
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Obtains a sequential HTTP streaming URL from TorrServer for ExoPlayer playback.
     */
    suspend fun getStreamUrl(serverUrl: String, magnetUrl: String, title: String): String = withContext(Dispatchers.IO) {
        try {
            // Register torrent with TorrServer to initiate pre-buffering
            val actionUrl = "$serverUrl/torrents/action"
            val payload = JSONObject().apply {
                put("action", "add")
                put("link", magnetUrl)
                put("title", title)
                put("save_to_db", false)
            }
            val body = payload.toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url(actionUrl).post(body).build()
            client.newCall(req).execute().close()
        } catch (e: Exception) {
            // Continue even if add action fails, as /stream?link handles auto-add
        }

        val encodedMagnet = URLEncoder.encode(magnetUrl, "UTF-8")
        val encodedTitle = URLEncoder.encode(title, "UTF-8")
        "$serverUrl/stream/$encodedTitle.mp4?link=$encodedMagnet&index=1&play=1"
    }

    /**
     * Dispatches magnet link to installed external TV players (VLC, Nova Video Player, JustPlayer, TorrSE).
     */
    fun openWithExternalPlayer(context: Context, magnetUrl: String, title: String): Boolean {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(magnetUrl), "video/*")
                putExtra("title", title)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            return true
        } catch (e: Exception) {
            try {
                val genericIntent = Intent(Intent.ACTION_VIEW, Uri.parse(magnetUrl)).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(genericIntent)
                return true
            } catch (e2: Exception) {
                Toast.makeText(context, "No se encontró un reproductor de torrents (VLC / TorrServer)", Toast.LENGTH_LONG).show()
                return false
            }
        }
    }
}
