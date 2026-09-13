package com.example.animetv.core.source

import android.util.Base64
import com.example.animetv.core.model.StreamResult
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object SoloLatinoStreamResolver {

    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

    private val cookieStore = HashMap<String, MutableList<Cookie>>()
    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val list = cookieStore.getOrPut(url.host) { mutableListOf() }
            for (c in cookies) {
                list.removeAll { it.name == c.name }
                list.add(c)
            }
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val list = mutableListOf<Cookie>()
            cookieStore.values.forEach { cookieList ->
                cookieList.forEach { c ->
                    if (c.matches(url) && !list.any { it.name == c.name }) {
                        list.add(c)
                    }
                }
            }
            return list
        }
    }

    private val client = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private fun fetch(url: String, referer: String = ""): String {
        val reqBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
        if (referer.isNotEmpty()) {
            reqBuilder.header("Referer", referer)
        }
        client.newCall(reqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) return ""
            return resp.body?.string() ?: ""
        }
    }

    private fun ensureFreshCsrf() {
        try {
            fetch("https://sololatino.net/sanctum/csrf-cookie", "https://sololatino.net")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Resolves a SoloLatino episode URL into a direct .m3u8 stream or clean embed URL
     * completely bypassing the "Prueba con Servidor 1" paywall screen.
     */
    fun resolve(episodeUrl: String): StreamResult? {
        try {
            // 1. Ensure sanctum CSRF cookie exists
            val hasXsrf = cookieStore.values.flatten().any { it.name == "XSRF-TOKEN" }
            if (!hasXsrf) {
                ensureFreshCsrf()
            }

            // 2. Fetch episode page
            val epHtml = fetch(episodeUrl, "https://sololatino.net")
            if (epHtml.isEmpty()) return null

            // 3. Find server candidate tokens (prioritizing LATINO / SERVIDOR 1 over PREMIUM/VIP)
            val doc = Jsoup.parse(epHtml, episodeUrl)
            val serverBtns = doc.select("[data-server-btn], [data-player-token]")
            val candidateTokens = mutableListOf<String>()

            for (btn in serverBtns) {
                val text = btn.text().uppercase()
                val token = btn.attr("data-player-token")
                if (token.isNotEmpty()) {
                    if (text.contains("LATINO") || text.contains("SERVIDOR 1") || (!text.contains("PREMIUM") && !text.contains("VIP"))) {
                        candidateTokens.add(0, token)
                    } else {
                        candidateTokens.add(token)
                    }
                }
            }

            if (candidateTokens.isEmpty()) return null

            // 4. Request /api/player-url trying candidate tokens
            var embedUrl = ""
            for (targetToken in candidateTokens.distinct()) {
                var rawXsrf = cookieStore.values.flatten().find { it.name == "XSRF-TOKEN" }?.value ?: ""
                if (rawXsrf.isEmpty()) {
                    ensureFreshCsrf()
                    rawXsrf = cookieStore.values.flatten().find { it.name == "XSRF-TOKEN" }?.value ?: ""
                }
                val decodedXsrf = try { URLDecoder.decode(rawXsrf, "UTF-8") } catch (e: Exception) { rawXsrf }

                val jsonBody = """{"t":"$targetToken"}""".toRequestBody("application/json; charset=utf-8".toMediaType())
                val apiReq = Request.Builder()
                    .url("https://sololatino.net/api/player-url")
                    .post(jsonBody)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .header("X-XSRF-TOKEN", decodedXsrf)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Referer", episodeUrl)
                    .build()

                var resBody = ""
                client.newCall(apiReq).execute().use { resp ->
                    if (resp.code == 419) {
                        // Stale CSRF, re-fetch and retry once
                        ensureFreshCsrf()
                        val retryXsrf = cookieStore.values.flatten().find { it.name == "XSRF-TOKEN" }?.value ?: ""
                        val retryDecoded = try { URLDecoder.decode(retryXsrf, "UTF-8") } catch (e: Exception) { retryXsrf }
                        val retryReq = apiReq.newBuilder().header("X-XSRF-TOKEN", retryDecoded).build()
                        client.newCall(retryReq).execute().use { retryResp ->
                            if (retryResp.isSuccessful) {
                                resBody = retryResp.body?.string() ?: ""
                            }
                        }
                    } else if (resp.isSuccessful) {
                        resBody = resp.body?.string() ?: ""
                    }
                }

                if (resBody.isNotEmpty()) {
                    val json = JSONObject(resBody)
                    val url = json.optString("url")
                    if (url.isNotEmpty() && !url.contains("premium") && !url.contains("vip")) {
                        embedUrl = url
                        break
                    } else if (url.isNotEmpty() && embedUrl.isEmpty()) {
                        embedUrl = url
                    }
                }
            }

            if (embedUrl.isEmpty()) return null
            if (embedUrl.contains("player.pelisserieshoy.com/f/")) {
                embedUrl = embedUrl.replace("player.pelisserieshoy.com", "embed69.org")
            }

            // 5. Fetch and decrypt embed69 to obtain direct VidHide stream or alternative embeds
            if (embedUrl.contains("embed69.org/f/")) {
                val directStream = resolveEmbed69(embedUrl, episodeUrl)
                if (directStream != null) {
                    return directStream
                }
            }

            // Fallback to embed URL if decryption was not applicable
            return StreamResult(
                videoUrl = embedUrl,
                isHls = false,
                isEmbed = true,
                serverName = "SoloLatino Servidor 1",
                headers = mapOf("Referer" to episodeUrl)
            )
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    private fun resolveEmbed69(embedUrl: String, episodeUrl: String): StreamResult? {
        try {
            val html = fetch(embedUrl, referer = "https://sololatino.net/")
            if (html.isEmpty()) return null

            // Extract Proof-of-Work parameters
            val challengeMatch = Regex("""POW_CHALLENGE\s*=\s*'([^']+)'""").find(html) ?: return null
            val challenge = challengeMatch.groupValues[1]

            val diffMatch = Regex("""POW_DIFFICULTY\s*=\s*(\d+)""").find(html) ?: return null
            val difficulty = diffMatch.groupValues[1].toInt()

            val saltMatch = Regex("""POW_SALT\s*=\s*'([^']+)'""").find(html) ?: return null
            val salt = saltMatch.groupValues[1]

            // Solve PoW in milliseconds
            val aesKey = solvePoW(challenge, difficulty, salt)

            // Extract and decrypt dataLink
            val dataLinkMatch = Regex("""let dataLink\s*=\s*(\[.*?\]);""", RegexOption.DOT_MATCHES_ALL).find(html) ?: return null
            val dataLinkJson = JSONArray(dataLinkMatch.groupValues[1])

            var vidhideUrl = ""
            var alternativeEmbedUrl = ""
            for (i in 0 until dataLinkJson.length()) {
                val fileObj = dataLinkJson.getJSONObject(i)
                val embeds = fileObj.optJSONArray("sortedEmbeds") ?: continue
                for (j in 0 until embeds.length()) {
                    val emb = embeds.getJSONObject(j)
                    val encLink = emb.optString("link")
                    val decrypted = decryptAes(encLink, aesKey)
                    if (decrypted.contains("morencius.com") || decrypted.contains("vidhide")) {
                        vidhideUrl = decrypted
                    } else if (alternativeEmbedUrl.isEmpty() && (decrypted.contains("streamwish") || decrypted.contains("hglink") || decrypted.contains("voe") || decrypted.contains("filemoon"))) {
                        alternativeEmbedUrl = decrypted
                    }
                }
                if (vidhideUrl.isNotEmpty()) break
            }

            if (vidhideUrl.isNotEmpty()) {
                // Fetch VidHide page and unpack packed JavaScript
                val vhHtml = fetch(vidhideUrl, referer = "https://embed69.org/")
                val unpacked = DeanEdwardsUnpacker.unpack(vhHtml)
                val m3u8Match = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(unpacked)
                if (m3u8Match != null) {
                    return StreamResult(
                        videoUrl = m3u8Match.value,
                        isHls = true,
                        isEmbed = false,
                        serverName = "SoloLatino Direct HLS (Servidor 1)",
                        headers = mapOf("Referer" to vidhideUrl, "User-Agent" to USER_AGENT)
                    )
                }
            }

            // If direct m3u8 extraction was not applicable, return the working alternative embed
            val chosenEmbed = when {
                alternativeEmbedUrl.isNotEmpty() -> alternativeEmbedUrl
                vidhideUrl.isNotEmpty() -> vidhideUrl
                else -> embedUrl
            }
            return StreamResult(
                videoUrl = chosenEmbed,
                isHls = false,
                isEmbed = true,
                serverName = "SoloLatino Embed (Servidor 1)",
                headers = mapOf("Referer" to if (chosenEmbed == embedUrl) episodeUrl else "https://embed69.org/")
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    private fun solvePoW(challenge: String, difficulty: Int, salt: String): ByteArray {
        val prefix = "0".repeat(difficulty)
        val md = MessageDigest.getInstance("SHA-256")
        var nonce = 0
        while (true) {
            val input = (challenge + nonce).toByteArray(Charsets.UTF_8)
            val hash = md.digest(input)
            val hex = bytesToHex(hash)
            if (hex.startsWith(prefix)) {
                val keyInput = (challenge + nonce + salt).toByteArray(Charsets.UTF_8)
                return md.digest(keyInput)
            }
            nonce++
        }
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val sb = java.lang.StringBuilder()
        for (b in bytes) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }

    private fun decryptAes(encBase64: String, key: ByteArray): String {
        val raw = Base64.decode(encBase64, Base64.DEFAULT)
        val iv = raw.copyOfRange(0, 16)
        val ciphertext = raw.copyOfRange(16, raw.size)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val keySpec = SecretKeySpec(key, "AES")
        val ivSpec = IvParameterSpec(iv)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
        val decrypted = cipher.doFinal(ciphertext)
        return String(decrypted, Charsets.UTF_8)
    }
}

object DeanEdwardsUnpacker {
    fun unpack(html: String): String {
        val regex = Regex("""eval\(function\(p,a,c,k,e,[rd]\)\{.*?return p\}\('(.+?)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'([^']+)'\.split\('\|'\)""", RegexOption.DOT_MATCHES_ALL)
        val match = regex.find(html)
            ?: Regex("""\}\('(.+)',(\d+),(\d+),'([^']+)'\.split\('\|'\)""").find(html)
            ?: return ""
        val p = match.groupValues[1]
        val a = match.groupValues[2].toInt()
        val c = match.groupValues[3].toInt()
        val k = match.groupValues[4].split("|")

        fun baseN(num: Int, base: Int): String {
            return if (num < base) {
                if (num > 35) (num + 29).toChar().toString() else Integer.toString(num, base)
            } else {
                baseN(num / base, base) + (if (num % base > 35) (num % base + 29).toChar().toString() else Integer.toString(num % base, base))
            }
        }

        val dict = HashMap<String, String>()
        for (i in c - 1 downTo 0) {
            val key = baseN(i, a)
            val value = if (i < k.size && k[i].isNotEmpty()) k[i] else key
            dict[key] = value
        }

        val wordRegex = Regex("""\b\w+\b""")
        return wordRegex.replace(p) { m -> dict[m.value] ?: m.value }
    }
}
