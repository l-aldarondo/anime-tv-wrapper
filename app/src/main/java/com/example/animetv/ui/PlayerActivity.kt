package com.example.animetv.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.example.animetv.R
import com.example.animetv.adblock.AdBlockEngine
import java.util.Locale

class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_VIDEO_URL = "extra_video_url"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_IS_HLS = "extra_is_hls"
        const val EXTRA_IS_EMBED = "extra_is_embed"
        const val EXTRA_REFERER = "extra_referer"
        const val EXTRA_ANIME_URL = "extra_anime_url"
        const val EXTRA_ANIME_TITLE = "extra_anime_title"
        const val EXTRA_POSTER_URL = "extra_poster_url"
        const val EXTRA_SOURCE = "extra_source"
        const val EXTRA_EPISODE_URL = "extra_episode_url"
        const val EXTRA_EPISODE_TITLE = "extra_episode_title"
        const val EXTRA_EPISODE_NUMBER = "extra_episode_number"

        fun start(
            context: Context,
            videoUrl: String,
            title: String,
            isHls: Boolean = true,
            isEmbed: Boolean = false,
            referer: String = "",
            animeDetailUrl: String = "",
            animeTitle: String = "",
            posterUrl: String = "",
            source: String = "",
            episodeUrl: String = "",
            episodeTitle: String = "",
            episodeNumber: Int = 1
        ) {
            val intent = Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_VIDEO_URL, videoUrl)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_IS_HLS, isHls)
                putExtra(EXTRA_IS_EMBED, isEmbed)
                putExtra(EXTRA_REFERER, referer)
                putExtra(EXTRA_ANIME_URL, animeDetailUrl)
                putExtra(EXTRA_ANIME_TITLE, animeTitle)
                putExtra(EXTRA_POSTER_URL, posterUrl)
                putExtra(EXTRA_SOURCE, source)
                putExtra(EXTRA_EPISODE_URL, episodeUrl)
                putExtra(EXTRA_EPISODE_TITLE, episodeTitle)
                putExtra(EXTRA_EPISODE_NUMBER, episodeNumber)
            }
            context.startActivity(intent)
        }
    }

    private lateinit var playerView: PlayerView
    private lateinit var cleanWebPlayer: WebView
    private lateinit var osdOverlay: FrameLayout
    private lateinit var txtPlayerTitle: TextView
    private lateinit var txtFeedback: TextView
    private lateinit var playerProgressBar: ProgressBar
    private lateinit var txtCurrentTime: TextView
    private lateinit var txtTotalTime: TextView
    private lateinit var playerBuffering: ProgressBar

    private var exoPlayer: ExoPlayer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isOsdVisible = false
    private var isEmbedMode = false
    private var hasExoPlayerFailed = false

    private var animeDetailUrl: String = ""
    private var animeTitle: String = ""
    private var posterUrl: String = ""
    private var sourceName: String = ""
    private var episodeUrl: String = ""
    private var episodeTitle: String = ""
    private var episodeNumber: Int = 1
    private var hasAutoResumed: Boolean = false

    inner class AndroidMediaBridge {
        @android.webkit.JavascriptInterface
        fun onProgressUpdate(currentTimeSec: Float, durationSec: Float) {
            val posMs = (currentTimeSec * 1000).toLong()
            val durMs = (durationSec * 1000).toLong()
            saveCurrentPlaybackPosition(posMs, durMs)
        }
    }

    private fun saveCurrentPlaybackPosition(posMs: Long, durMs: Long) {
        if (episodeUrl.isEmpty() && animeDetailUrl.isEmpty()) return
        if (posMs > 1000) {
            com.example.animetv.core.history.PlaybackHistoryStore.saveProgress(
                this,
                animeDetailUrl = animeDetailUrl,
                animeTitle = animeTitle,
                posterUrl = posterUrl,
                source = sourceName,
                episodeUrl = episodeUrl,
                episodeTitle = episodeTitle,
                episodeNumber = episodeNumber,
                positionMs = posMs,
                durationMs = durMs
            )
        }
    }

    private val hideOsdRunnable = Runnable {
        hideOsd()
    }

    private val progressRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            if (exoPlayer?.isPlaying == true) {
                mainHandler.postDelayed(this, 1000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        // Immersive sticky fullscreen
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())

        playerView = findViewById(R.id.playerView)
        cleanWebPlayer = findViewById(R.id.cleanWebPlayer)
        osdOverlay = findViewById(R.id.osdOverlay)
        txtPlayerTitle = findViewById(R.id.txtPlayerTitle)
        txtFeedback = findViewById(R.id.txtTransportFeedback)
        playerProgressBar = findViewById(R.id.playerProgressBar)
        txtCurrentTime = findViewById(R.id.txtCurrentTime)
        txtTotalTime = findViewById(R.id.txtTotalTime)
        playerBuffering = findViewById(R.id.playerBuffering)

        var videoUrl = intent.getStringExtra(EXTRA_VIDEO_URL) ?: ""
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Reproductor"
        val referer = intent.getStringExtra(EXTRA_REFERER) ?: ""
        isEmbedMode = intent.getBooleanExtra(EXTRA_IS_EMBED, false)

        animeDetailUrl = intent.getStringExtra(EXTRA_ANIME_URL) ?: ""
        animeTitle = intent.getStringExtra(EXTRA_ANIME_TITLE) ?: ""
        posterUrl = intent.getStringExtra(EXTRA_POSTER_URL) ?: ""
        sourceName = intent.getStringExtra(EXTRA_SOURCE) ?: ""
        episodeUrl = intent.getStringExtra(EXTRA_EPISODE_URL) ?: ""
        episodeTitle = intent.getStringExtra(EXTRA_EPISODE_TITLE) ?: ""
        episodeNumber = intent.getIntExtra(EXTRA_EPISODE_NUMBER, 1)

        // Always rewrite pelisserieshoy (rate-limited VIP server) to embed69 (Servidor 1)
        if (videoUrl.contains("player.pelisserieshoy.com/f/")) {
            videoUrl = videoUrl.replace("player.pelisserieshoy.com", "embed69.org")
            isEmbedMode = true
        }

        android.util.Log.d("PlayerActivity", "onCreate: videoUrl=$videoUrl, isEmbedMode=$isEmbedMode, ep=$episodeNumber")
        txtPlayerTitle.text = title

        if (videoUrl.isEmpty()) {
            Toast.makeText(this, "Enlace de video no válido", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        if (isEmbedMode || !videoUrl.contains(".m3u8") && !videoUrl.contains(".mp4")) {
            startCleanWebPlayer(videoUrl, referer)
        } else {
            initializeExoPlayer(videoUrl, referer)
        }
    }

    private fun initializeExoPlayer(videoUrl: String, referer: String) {
        playerView.visibility = View.VISIBLE
        cleanWebPlayer.visibility = View.GONE

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36")
            .setAllowCrossProtocolRedirects(true)

        val reqHeaders = mutableMapOf<String, String>()
        if (referer.isNotEmpty()) {
            reqHeaders["Referer"] = referer
            try {
                val uri = Uri.parse(referer)
                val scheme = uri.scheme ?: "https"
                val host = uri.host
                if (!host.isNullOrEmpty()) {
                    reqHeaders["Origin"] = "$scheme://$host"
                }
            } catch (e: Exception) {}
        }
        httpDataSourceFactory.setDefaultRequestProperties(reqHeaders)

        val mediaSourceFactory = DefaultMediaSourceFactory(httpDataSourceFactory)

        exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .apply {
                playerView.player = this
                val mediaItem = MediaItem.Builder()
                    .setUri(Uri.parse(videoUrl))
                    .setMimeType(if (videoUrl.contains(".m3u8")) androidx.media3.common.MimeTypes.APPLICATION_M3U8 else null)
                    .build()
                setMediaItem(mediaItem)
                prepare()
                playWhenReady = true

                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_BUFFERING -> {
                                playerBuffering.visibility = View.VISIBLE
                            }
                            Player.STATE_READY -> {
                                playerBuffering.visibility = View.GONE
                                if (!hasAutoResumed) {
                                    val saved = com.example.animetv.core.history.PlaybackHistoryStore.getRecordForEpisode(this@PlayerActivity, episodeUrl)
                                        ?: com.example.animetv.core.history.PlaybackHistoryStore.getRecordForAnime(this@PlayerActivity, animeDetailUrl)
                                    if (saved != null && (saved.episodeUrl == episodeUrl || saved.episodeNumber == episodeNumber)) {
                                        val dur = this@apply.duration
                                        if (saved.positionMs > 5000 && (dur <= 0 || saved.positionMs < dur - 15000)) {
                                            this@apply.seekTo(saved.positionMs)
                                            showFeedback("▶ Reanudando en ${formatTime(saved.positionMs)}")
                                        }
                                    }
                                    hasAutoResumed = true
                                }
                                updateProgress()
                                showOsdBriefly()
                            }
                            Player.STATE_ENDED -> {
                                playerBuffering.visibility = View.GONE
                                showOsd()
                            }
                            else -> {
                                playerBuffering.visibility = View.GONE
                            }
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        playerBuffering.visibility = View.GONE
                        android.util.Log.e("PlayerActivity", "ExoPlayer error: ${error.errorCodeName} - ${error.message}", error)
                        hasExoPlayerFailed = true
                        // Fallback gracefully without showing a black screen or infinite loops
                        Toast.makeText(this@PlayerActivity, "Cargando en reproductor alternativo...", Toast.LENGTH_SHORT).show()
                        val fallbackUrl = if (videoUrl.isNotEmpty()) videoUrl else episodeUrl
                        startCleanWebPlayer(fallbackUrl, referer)
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (isPlaying) {
                            mainHandler.post(progressRunnable)
                        } else {
                            mainHandler.removeCallbacks(progressRunnable)
                        }
                    }
                })
            }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun startCleanWebPlayer(url: String, referer: String) {
        isEmbedMode = true
        exoPlayer?.release()
        exoPlayer = null
        playerView.visibility = View.GONE
        cleanWebPlayer.visibility = View.VISIBLE
        playerBuffering.visibility = View.VISIBLE

        val playUrl = if (url.contains("player.pelisserieshoy.com/f/")) {
            url.replace("player.pelisserieshoy.com", "embed69.org")
        } else {
            url
        }
        cleanWebPlayer.addJavascriptInterface(AndroidMediaBridge(), "AndroidMediaBridge")

        val saved = com.example.animetv.core.history.PlaybackHistoryStore.getRecordForEpisode(this, episodeUrl)
            ?: com.example.animetv.core.history.PlaybackHistoryStore.getRecordForAnime(this, animeDetailUrl)
        val savedPosSec = if (saved != null && (saved.episodeUrl == episodeUrl || saved.episodeNumber == episodeNumber) && saved.positionMs > 5000) {
            saved.positionMs / 1000
        } else 0

        cleanWebPlayer.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
            setSupportMultipleWindows(false)
        }

        // If URL is an .m3u8, Android WebView cannot load it raw via loadUrl(). We must load an HTML5 player!
        if (playUrl.contains(".m3u8")) {
            val html = """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
                    <script src="https://cdn.jsdelivr.net/npm/hls.js@latest"></script>
                    <style>
                        * { margin: 0; padding: 0; box-sizing: border-box; }
                        html, body { width: 100vw; height: 100vh; background: #000; overflow: hidden; display: flex; justify-content: center; align-items: center; }
                        video { width: 100vw; height: 100vh; object-fit: contain; }
                    </style>
                </head>
                <body>
                    <video id="hlsVideo" controls autoplay playsinline></video>
                    <script>
                        var video = document.getElementById('hlsVideo');
                        if (Hls.isSupported()) {
                            var hls = new Hls({ enableWorker: true });
                            hls.loadSource('$playUrl');
                            hls.attachMedia(video);
                            hls.on(Hls.Events.MANIFEST_PARSED, function() {
                                if ($savedPosSec > 5) {
                                    video.currentTime = $savedPosSec;
                                }
                                video.play();
                            });
                        } else if (video.canPlayType('application/vnd.apple.mpegurl')) {
                            video.src = '$playUrl';
                            if ($savedPosSec > 5) {
                                video.currentTime = $savedPosSec;
                            }
                            video.play();
                        }

                        setInterval(function() {
                            try {
                                if (video && !video.paused && video.duration > 0 && typeof AndroidMediaBridge !== 'undefined') {
                                    AndroidMediaBridge.onProgressUpdate(video.currentTime, video.duration);
                                }
                            } catch(e) {}
                        }, 3500);
                    </script>
                </body>
                </html>
            """.trimIndent()
            val base = if (referer.isNotEmpty()) referer else "https://morencius.com/"
            cleanWebPlayer.loadDataWithBaseURL(base, html, "text/html", "UTF-8", null)
            return
        }

        cleanWebPlayer.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                android.util.Log.d("PlayerActivityWeb", "${consoleMessage?.message()} -- (${consoleMessage?.sourceId()}:${consoleMessage?.lineNumber()})")
                return true
            }
        }

        cleanWebPlayer.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val target = request?.url?.toString() ?: ""
                // Prevent pop-up redirects or ad domains
                if (AdBlockEngine.shouldBlock(target)) return true
                if (target.contains("player.pelisserieshoy.com/f/")) {
                    view?.loadUrl(target.replace("player.pelisserieshoy.com", "embed69.org"))
                    return true
                }
                // Block external app intents
                if (target.startsWith("intent:") || target.startsWith("market:") || target.startsWith("tg:") || target.startsWith("whatsapp:")) {
                    return true
                }
                return false
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val reqUrl = request?.url?.toString() ?: ""
                if (AdBlockEngine.shouldBlock(reqUrl)) {
                    return AdBlockEngine.EMPTY_RESPONSE
                }

                // Direct video stream detected inside embed (only if ExoPlayer hasn't failed)!
                if (!hasExoPlayerFailed && (reqUrl.contains(".m3u8") || (reqUrl.contains(".mp4") && !reqUrl.contains("favicon") && !reqUrl.contains(".xml")))) {
                    android.util.Log.d("PlayerActivityNet", "INTERCEPTED STREAM IN EMBED: $reqUrl")
                    val currentWebUrl = view?.url ?: ""
                    val refererToUse = request?.requestHeaders?.get("Referer") ?: currentWebUrl
                    mainHandler.post {
                        if (exoPlayer == null && !hasExoPlayerFailed) {
                            initializeExoPlayer(reqUrl, refererToUse)
                        }
                    }
                }

                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                playerBuffering.visibility = View.VISIBLE
                try {
                    view?.evaluateJavascript("""
                        try {
                            Object.defineProperty(navigator, 'webdriver', { get: () => undefined });
                            window.chrome = { runtime: {} };
                            Object.defineProperty(navigator, 'plugins', { get: () => [1, 2, 3, 4, 5] });
                            Object.defineProperty(navigator, 'languages', { get: () => ['es-ES', 'es', 'en-US', 'en'] });
                        } catch(e) {}
                    """.trimIndent(), null)
                } catch(e: Exception) {}
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?) {
                super.onReceivedError(view, request, error)
                android.util.Log.e("PlayerActivityWeb", "onReceivedError: ${error?.description} (${error?.errorCode}) for ${request?.url}")
            }

            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                super.onReceivedHttpError(view, request, errorResponse)
                android.util.Log.e("PlayerActivityWeb", "onReceivedHttpError: ${errorResponse?.statusCode} for ${request?.url}")
            }

            override fun onReceivedSslError(view: WebView?, handler: android.webkit.SslErrorHandler?, error: android.net.http.SslError?) {
                handler?.proceed()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                playerBuffering.visibility = View.GONE

                // Inject CSS and JavaScript Decoy Annihilator
                val cssScript = """
                    (function() {
                        try {
                            Object.defineProperty(navigator, 'webdriver', { get: () => undefined });
                            window.chrome = { runtime: {} };
                            Object.defineProperty(navigator, 'plugins', { get: () => [1, 2, 3, 4, 5] });
                            Object.defineProperty(navigator, 'languages', { get: () => ['es-ES', 'es', 'en-US', 'en'] });
                        } catch(e) {}

                        // 0. Auto-redirect away from pelisserieshoy paywall to embed69
                        if (location.hostname.includes('pelisserieshoy.com') && location.pathname.includes('/f/')) {
                            location.href = location.href.replace('pelisserieshoy.com', 'embed69.org');
                            return;
                        }

                        var style = document.createElement('style');
                        style.innerHTML = 'html, body { margin: 0 !important; padding: 0 !important; background: #000 !important; overflow: hidden !important; width: 100vw !important; height: 100vh !important; }'
                            + ' #DisplayContent, #PlayerDisplay, #player-frame, #iframeContainer.active, #iframePlayer, #iframe-embed, .wb_-playerarea, .mytsumi-player, .mytsumi-stage, #mytsumi-media, video { position: fixed !important; top: 0 !important; left: 0 !important; width: 100vw !important; height: 100vh !important; z-index: 999999 !important; border: none !important; }'
                            + ' .mytsumi-media iframe, #player-embed iframe, #iframe-embed, iframe[src*="stream"], iframe[src*="embed"], iframe[src*="bysesukior"], iframe[src*="megaplay"], iframe[src*="1anime"], iframe[src*="mega.nz"], iframe[src*="ok.ru"] { position: fixed !important; top: 0 !important; left: 0 !important; width: 100vw !important; height: 100vh !important; z-index: 999999 !important; border: none !important; }'
                            + ' #tutorialOverlay, #tutorialBackdrop, .tutorial-overlay, #fakePlayer, .fake-player-container, header, footer, .banner, .ads, .wb__-cover { display: none !important; }';
                        document.head.appendChild(style);

                        function nukeDecoysAndPlay() {
                            // 1. Dismiss tutorial if present
                            try {
                                var skip = document.getElementById('tutorialSkip');
                                if (skip) skip.click();
                                var ov = document.getElementById('tutorialOverlay');
                                if (ov) ov.remove();
                                var bd = document.getElementById('tutorialBackdrop');
                                if (bd) bd.remove();
                            } catch(e) {}

                            // 2. Annihilate invisible click-jacking overlays & fake players (protecting legit players)
                            try {
                                var allElements = document.querySelectorAll('div, a, span, p');
                                allElements.forEach(function(el) {
                                    if (el.id === 'mytsumi-intro-play' || el.id === 'azakuPlayButton' || el.classList.contains('mytsumi-tab')) return;
                                    var s = window.getComputedStyle(el);
                                    var z = parseInt(s.zIndex) || 0;
                                    if (z > 500 && (s.position === 'fixed' || s.position === 'absolute')) {
                                        if (!el.querySelector('video, iframe') && el.id !== 'mytsumi-player' && el.id !== 'servers-content') {
                                            var opacity = parseFloat(s.opacity) || 1;
                                            var bg = s.backgroundColor;
                                            if (opacity < 0.1 || bg === 'rgba(0, 0, 0, 0)' || bg === 'transparent' || el.id.includes('ad') || el.className.includes('ad') || el.className.includes('overlay')) {
                                                el.style.pointerEvents = 'none';
                                                el.remove();
                                            }
                                        }
                                    }
                                });
                            } catch(e) {}

                            // 3. Trigger selectServer(0) on embed69
                            try {
                                if (typeof selectServer === 'function' && typeof powSolved !== 'undefined' && powSolved) {
                                    var ifr = document.getElementById('iframePlayer');
                                    if (!ifr || !ifr.src || ifr.src === 'about:blank' || !ifr.src.startsWith('http')) {
                                        selectServer(0);
                                    }
                                }
                            } catch(e) {}

                            // 4. Select Servidor 1 or LATINO on SoloLatino if present
                            try {
                                var btns = document.querySelectorAll('[data-server-btn], button.server-btn');
                                for (var i = 0; i < btns.length; i++) {
                                    var t = (btns[i].textContent || '').toUpperCase();
                                    if ((t.includes('LATINO') || t.includes('SERVIDOR 1') || btns.length > 1) && !t.includes('PREMIUM') && !t.includes('VIP')) {
                                        btns[i].click();
                                        break;
                                    }
                                }
                            } catch(e) {}

                            // 4b. Auto-click intro / container play buttons for AnimeYT / Mytsumi
                            try {
                                var azaku = document.getElementById('azakuPlayButton');
                                if (azaku && !azaku.disabled) {
                                    azaku.click();
                                }
                                var intro = document.getElementById('mytsumi-intro-play');
                                if (intro) {
                                    intro.click();
                                }
                                var firstTab = document.querySelector('.mytsumi-tab:not(.mytsumi-download-tab)');
                                if (firstTab && !document.querySelector('.mytsumi-media iframe, .mytsumi-media video')) {
                                    firstTab.click();
                                }
                                var firstServer = document.querySelector('#servers-content .server-item');
                                if (firstServer) {
                                    firstServer.click();
                                }
                            } catch(e) {}

                            // 5. Trigger play on native video element or JWPlayer
                            try {
                                if (window.jwplayer && typeof window.jwplayer === 'function') {
                                    window.jwplayer().play();
                                }
                            } catch(e) {}

                            try {
                                var v = document.querySelector('video');
                                if (v && v.paused) {
                                    v.muted = false;
                                    v.play().catch(function(){});
                                }
                            } catch(e) {}

                            // 6. Click real play icons
                            try {
                                var playButtons = document.querySelectorAll('.jw-display-icon-display, .vjs-big-play-button, .play-button-circle, button[aria-label="Play"], .play-btn');
                                playButtons.forEach(function(btn) {
                                    btn.click();
                                });
                            } catch(e) {}

                            // 7. Auto-resume saved time
                            try {
                                var v = document.querySelector('video');
                                if (v && !v.dataset.hasResumed && $savedPosSec > 5) {
                                    v.currentTime = $savedPosSec;
                                    v.dataset.hasResumed = 'true';
                                }
                            } catch(e) {}
                        }

                        nukeDecoysAndPlay();
                        var intv = setInterval(nukeDecoysAndPlay, 600);
                        setTimeout(function() { clearInterval(intv); }, 6000);

                        // 8. Periodic progress updates to Android
                        setInterval(function() {
                            try {
                                var v = document.querySelector('video');
                                if (v && !v.paused && v.duration > 0 && typeof AndroidMediaBridge !== 'undefined') {
                                    AndroidMediaBridge.onProgressUpdate(v.currentTime, v.duration);
                                }
                            } catch(e) {}
                        }, 4000);
                    })();
                """.trimIndent()
                view?.evaluateJavascript(cssScript, null)
            }
        }

        val headers = if (referer.isNotEmpty()) mapOf("Referer" to referer) else emptyMap()
        cleanWebPlayer.loadUrl(playUrl, headers)
    }

    private fun showOsdBriefly(delayMs: Long = 3500L) {
        if (isEmbedMode) return
        osdOverlay.visibility = View.VISIBLE
        isOsdVisible = true
        mainHandler.removeCallbacks(hideOsdRunnable)
        mainHandler.postDelayed(hideOsdRunnable, delayMs)
    }

    private fun showOsd() {
        if (isEmbedMode) return
        osdOverlay.visibility = View.VISIBLE
        isOsdVisible = true
        mainHandler.removeCallbacks(hideOsdRunnable)
    }

    private fun hideOsd() {
        osdOverlay.visibility = View.GONE
        txtFeedback.visibility = View.GONE
        isOsdVisible = false
    }

    private fun showFeedback(text: String) {
        txtFeedback.text = text
        txtFeedback.visibility = View.VISIBLE
        showOsdBriefly()
    }

    private fun updateProgress() {
        val player = exoPlayer ?: return
        val current = player.currentPosition
        val duration = player.duration

        if (player.isPlaying && current > 2000) {
            saveCurrentPlaybackPosition(current, duration)
        }

        if (duration > 0) {
            val progress = (current * 1000 / duration).toInt()
            playerProgressBar.progress = progress
            txtCurrentTime.text = formatTime(current)
            txtTotalTime.text = formatTime(duration)
        } else {
            txtCurrentTime.text = formatTime(current)
            txtTotalTime.text = "--:--"
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = (ms / 1000).toInt()
        val seconds = totalSeconds % 60
        val minutes = (totalSeconds / 60) % 60
        val hours = totalSeconds / 3600
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (isEmbedMode) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    cleanWebPlayer.evaluateJavascript(
                        """
                        (function() {
                            var v = document.querySelector('video');
                            if (v) { v.paused ? v.play() : v.pause(); }
                            else {
                                var btn = document.querySelector('.play-button-overlay, #play-button, .fake-player-container');
                                if (btn) btn.click();
                            }
                        })();
                        """.trimIndent(), null
                    )
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    cleanWebPlayer.evaluateJavascript(
                        "try { var v = document.querySelector('video'); if (v) v.currentTime += 10; } catch(e){}", null
                    )
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    cleanWebPlayer.evaluateJavascript(
                        "try { var v = document.querySelector('video'); if (v) v.currentTime -= 10; } catch(e){}", null
                    )
                    return true
                }
                KeyEvent.KEYCODE_BACK -> {
                    finish()
                    return true
                }
            }
            return super.onKeyDown(keyCode, event)
        }

        val player = exoPlayer ?: return super.onKeyDown(keyCode, event)

        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (player.isPlaying) {
                    player.pause()
                    showFeedback("⏸ Pausa")
                } else {
                    player.play()
                    showFeedback("▶ Reproduciendo")
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                val newPos = (player.currentPosition + 10000).coerceAtMost(player.duration)
                player.seekTo(newPos)
                showFeedback("⏩ +10s")
                updateProgress()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                val newPos = (player.currentPosition - 10000).coerceAtLeast(0)
                player.seekTo(newPos)
                showFeedback("⏪ -10s")
                updateProgress()
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                val newPos = (player.currentPosition + 85000).coerceAtMost(player.duration)
                player.seekTo(newPos)
                showFeedback("⏩ Salto de Intro (+85s)")
                updateProgress()
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (!isOsdVisible) {
                    showOsdBriefly()
                } else {
                    val newPos = (player.currentPosition - 30000).coerceAtLeast(0)
                    player.seekTo(newPos)
                    showFeedback("⏪ -30s")
                    updateProgress()
                }
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (isOsdVisible) {
                    hideOsd()
                    return true
                }
                finish()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onPause() {
        super.onPause()
        exoPlayer?.let {
            saveCurrentPlaybackPosition(it.currentPosition, it.duration)
            it.pause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        exoPlayer?.let {
            saveCurrentPlaybackPosition(it.currentPosition, it.duration)
        }
        mainHandler.removeCallbacksAndMessages(null)
        exoPlayer?.release()
        exoPlayer = null
        try {
            cleanWebPlayer.stopLoading()
            cleanWebPlayer.destroy()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
