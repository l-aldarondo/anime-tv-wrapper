package com.example.animetv.core.source

import android.util.Log
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object MirrorManager {
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * Tries to find the fastest working mirror from a list in parallel.
     * Returns the first one that successfully responds to a HEAD request.
     */
    suspend fun findWorkingMirror(mirrors: List<String>): String? = coroutineScope {
        if (mirrors.isEmpty()) return@coroutineScope null
        
        // If there's only one, just return it (or validate it quickly)
        if (mirrors.size == 1) return@coroutineScope mirrors[0]

        val resultDeferred = CompletableDeferred<String?>()
        val jobs = mutableListOf<Job>()

        mirrors.forEach { url ->
            val job = launch(Dispatchers.IO) {
                try {
                    val request = Request.Builder()
                        .url(url)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36")
                        .head()
                        .build()
                    
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful && !resultDeferred.isCompleted) {
                            Log.d("MirrorManager", "First winner found: $url")
                            resultDeferred.complete(url)
                        }
                    }
                } catch (e: Exception) {
                    // Ignore failures
                }
            }
            jobs.add(job)
        }

        // Race condition: if all fail, we need to complete with null after all jobs are done
        launch {
            jobs.joinAll()
            if (!resultDeferred.isCompleted) {
                resultDeferred.complete(null)
            }
        }

        val winner = resultDeferred.await()
        jobs.forEach { it.cancel() } // Stop other pending checks
        winner
    }
}
