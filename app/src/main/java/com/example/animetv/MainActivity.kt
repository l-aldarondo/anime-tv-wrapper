package com.example.animetv

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.example.animetv.adblock.AdBlockEngine
import com.example.animetv.tv.VirtualCursorView

class MainActivity : AppCompatActivity() {

    companion object {
        const val TAG = "AnimeTVLite"

        const val PREFS_NAME = "anime_tv_prefs"
        const val KEY_ACTIVE_SOURCE = "active_source"
        const val KEY_CINEMA_MODE = "cinema_mode"
        const val KEY_NAV_MODE = "nav_mode"

        const val SOURCE_9ANIME = "9anime"
        const val SOURCE_GOGOANIME = "gogoanime"
        const val SOURCE_SOLOLATINO = "sololatino"
        const val SOURCE_SOLOLATINO_HOME = "sololatino_home"
        const val SOURCE_ANIMEFLIX = "animeflix"
        const val SOURCE_ANIMEYT = "animeyt"
        const val SOURCE_JKANIME = "jkanime"

        const val URL_9ANIME = "https://9anime.or.at/"
        const val URL_GOGOANIME = "https://gogoanime.by/"
        const val URL_SOLOLATINO = "https://sololatino.net/animes"
        const val URL_SOLOLATINO_HOME = "https://sololatino.net/"
        const val URL_ANIMEFLIX = "https://animeflix.team/"
        const val URL_ANIMEYT = "https://animeyt.cc/"
        const val URL_JKANIME = "https://jkanime.net/"

        const val MODE_POINTER = 0
        const val MODE_SCROLL = 1

        private const val BACK_PRESS_INTERVAL = 2000L
        private const val HUD_AUTO_HIDE_DELAY_MS = 6000L

        // Primary anime domains and trusted media embed providers
        private val ALLOWED_MAIN_HOSTS = setOf(
            "9anime.or.at",
            "gogoanime.by",
            "anitaku.to",
            "gogoanime3.co",
            "sololatino.net",
            "sololatino.co",
            "animeflix.team",
            "9animes.me.uk",
            "animeyt.cc",
            "jkanime.net",
            "pelisserieshoy.com",
            "mediafire.com",
            "cloudwindow-route.com",
            "voe.sx",
            "gofile.io",
            "embed69.org",
            "xupalace.org",
            "mega.nz",
            "mega.co.nz",
            "mega.io",
            "ok.ru",
            "vk.com"
        )
    }

    private var isTv = false
    private var isCinemaMode = false
    private var currentNavMode = MODE_SCROLL
    private var currentSource = SOURCE_9ANIME
    private var lastBackPressTime = 0L

    // Core Views
    private lateinit var rootContainer: FrameLayout
    private lateinit var topBar: LinearLayout
    private lateinit var webView: WebView
    private lateinit var pageLoadingBar: ProgressBar
    private lateinit var virtualCursorView: VirtualCursorView
    private lateinit var hudPlayerBar: LinearLayout
    private lateinit var osdControlsGuide: LinearLayout

    // Top Bar Tab Views
    private lateinit var btnSource9Anime: TextView
    private lateinit var btnSourceGogoAnime: TextView
    private lateinit var btnSourceSoloLatino: TextView
    private lateinit var btnSourceSoloLatinoHome: TextView
    private lateinit var btnSourceAnimeFlix: TextView
    private lateinit var btnSourceAnimeYT: TextView
    private lateinit var btnSourceJKAnime: TextView

    // Top Bar Actions
    private lateinit var btnTopBarCinema: TextView
    private lateinit var btnTopBarMode: TextView
    private lateinit var btnTopBarReload: TextView

    // HUD Player Controls
    private lateinit var btnHudRewind: TextView
    private lateinit var btnHudPlayPause: TextView
    private lateinit var btnHudForward: TextView
    private lateinit var btnHudSkipIntro: TextView
    private lateinit var btnHudNextEp: TextView
    private lateinit var btnHudFullscreen: TextView

    // Fullscreen Custom View Container (for HTML5 video tag fullscreen expansion)
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private val handler = Handler(Looper.getMainLooper())
    private val hideHudRunnable = Runnable { hideHudPlayerBar() }
    private val hideGuideRunnable = Runnable {
        osdControlsGuide.animate()
            .alpha(0f)
            .setDuration(600)
            .withEndAction { osdControlsGuide.visibility = View.GONE }
            .start()
    }

    // Cached asset scripts
    private var cachedAdblockGuardJs = ""
    private var cachedNetflixCinemaCss = ""
    private var cachedFixesCss = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        currentSource = prefs.getString(KEY_ACTIVE_SOURCE, SOURCE_9ANIME) ?: SOURCE_9ANIME
        isCinemaMode = prefs.getBoolean(KEY_CINEMA_MODE, false)
        currentNavMode = prefs.getInt(KEY_NAV_MODE, MODE_SCROLL)

        val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as? android.app.UiModeManager
        isTv = (uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION)
            || !packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN)

        requestedOrientation = if (isTv) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableImmersiveMode()

        setContentView(R.layout.activity_main)

        // Preload AdBlockEngine & assets
        AdBlockEngine.initialize(this)
        loadCachedAssets()

        initViews()
        setupTopBar()
        setupHudPlayerBar()
        setupWebView()
        setupBackPressedHandler()

        val startUrl = intent?.dataString ?: urlForSource(currentSource)
        webView.loadUrl(startUrl)

        if (isTv) {
            scheduleGuideDismiss()
        }
    }

    private fun loadCachedAssets() {
        try {
            cachedAdblockGuardJs = assets.open("adblock_guard.js").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading adblock_guard.js", e)
        }
        try {
            cachedNetflixCinemaCss = assets.open("netflix_cinema.css").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading netflix_cinema.css", e)
        }
        try {
            cachedFixesCss = assets.open("page_patches/fixes.css").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.d(TAG, "No page_patches/fixes.css found, using netflix_cinema.css")
        }
    }

    private fun urlForSource(source: String): String = when (source) {
        SOURCE_GOGOANIME -> URL_GOGOANIME
        SOURCE_SOLOLATINO -> URL_SOLOLATINO
        SOURCE_SOLOLATINO_HOME -> URL_SOLOLATINO_HOME
        SOURCE_ANIMEFLIX -> URL_ANIMEFLIX
        SOURCE_ANIMEYT -> URL_ANIMEYT
        SOURCE_JKANIME -> URL_JKANIME
        else -> URL_9ANIME
    }

    private fun initViews() {
        rootContainer = findViewById(R.id.rootContainer)
        topBar = findViewById(R.id.topBar)
        webView = findViewById(R.id.webView)
        pageLoadingBar = findViewById(R.id.pageLoadingBar)
        virtualCursorView = findViewById(R.id.virtualCursorView)
        hudPlayerBar = findViewById(R.id.hudPlayerBar)
        osdControlsGuide = findViewById(R.id.osdControlsGuide)

        virtualCursorView.targetView = webView
        virtualCursorView.isDirectScrollMode = (currentNavMode == MODE_SCROLL)

        // Top Bar Tabs
        btnSource9Anime = findViewById(R.id.btnSource9Anime)
        btnSourceGogoAnime = findViewById(R.id.btnSourceGogoAnime)
        btnSourceSoloLatino = findViewById(R.id.btnSourceSoloLatino)
        btnSourceSoloLatinoHome = findViewById(R.id.btnSourceSoloLatinoHome)
        btnSourceAnimeFlix = findViewById(R.id.btnSourceAnimeFlix)
        btnSourceAnimeYT = findViewById(R.id.btnSourceAnimeYT)
        btnSourceJKAnime = findViewById(R.id.btnSourceJKAnime)

        // Top Bar Actions
        btnTopBarCinema = findViewById(R.id.btnTopBarCinema)
        btnTopBarMode = findViewById(R.id.btnTopBarMode)
        btnTopBarReload = findViewById(R.id.btnTopBarReload)

        // HUD Buttons
        btnHudRewind = findViewById(R.id.btnHudRewind)
        btnHudPlayPause = findViewById(R.id.btnHudPlayPause)
        btnHudForward = findViewById(R.id.btnHudForward)
        btnHudSkipIntro = findViewById(R.id.btnHudSkipIntro)
        btnHudNextEp = findViewById(R.id.btnHudNextEp)
        btnHudFullscreen = findViewById(R.id.btnHudFullscreen)

        if (!isTv) {
            virtualCursorView.visibility = View.GONE
            osdControlsGuide.visibility = View.GONE
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.loadsImagesAutomatically = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true

        // Suppress popups via multi-window intercept
        settings.setSupportMultipleWindows(true)
        settings.javaScriptCanOpenWindowsAutomatically = false

        // Desktop / TV Chrome User-Agent for standard 16:9 HTML5 playback
        settings.userAgentString = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36 TV/GoogleTV"

        // Cache policy
        settings.cacheMode = WebSettings.LOAD_DEFAULT

        webView.addJavascriptInterface(AnimeTvBridge(), "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url?.toString()
                if (AdBlockEngine.shouldBlock(url)) {
                    return AdBlockEngine.EMPTY_RESPONSE
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                val scheme = uri.scheme?.lowercase() ?: ""

                if (scheme == "about" || scheme == "data" || scheme == "blob") return false
                if (scheme != "http" && scheme != "https") return true // block external protocols

                val host = uri.host?.lowercase() ?: ""

                // Check if host matches any allowed streaming or embed provider
                val isAllowed = ALLOWED_MAIN_HOSTS.any { host == it || host.endsWith(".$it") }
                if (!isAllowed) {
                    Log.w(TAG, "Blocked external navigation to: $host")
                    return true // block popup/redirect
                }

                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                pageLoadingBar.visibility = View.VISIBLE
                // Inject early guard
                injectScript(
                    """
                    (function() {
                        try {
                            window.open = function() { return null; };
                            var sl = document.getElementById('__sl_ads');
                            if (sl) sl.textContent = '{"h":"","b":""}';
                        } catch(e) {}
                    })();
                    """.trimIndent()
                )
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                pageLoadingBar.visibility = View.GONE

                // Inject full guard script and styles
                if (cachedAdblockGuardJs.isNotEmpty()) {
                    injectScript(cachedAdblockGuardJs)
                }
                if (cachedNetflixCinemaCss.isNotEmpty()) {
                    injectCss(cachedNetflixCinemaCss)
                }
                if (cachedFixesCss.isNotEmpty()) {
                    injectCss(cachedFixesCss)
                }

                // Apply Cinema Mode state if active
                if (isCinemaMode) {
                    injectScript("document.body.classList.add('animetv-cinema-mode');")
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {

            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
                // Completely suppress new windows/popups
                Log.d(TAG, "Blocked popup attempt in onCreateWindow")
                return false
            }

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                pageLoadingBar.progress = newProgress
                if (newProgress >= 90) {
                    pageLoadingBar.visibility = View.GONE
                }
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                Log.d(TAG, "WebConsole: ${consoleMessage?.message()} (line ${consoleMessage?.lineNumber()})")
                return true
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                topBar.visibility = View.GONE
                hudPlayerBar.visibility = View.GONE
                webView.visibility = View.GONE

                val decor = window.decorView as ViewGroup
                decor.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                enableImmersiveMode()
            }

            override fun onHideCustomView() {
                if (customView == null) return
                val decor = window.decorView as ViewGroup
                decor.removeView(customView)
                customView = null
                customViewCallback?.onCustomViewHidden()
                customViewCallback = null

                webView.visibility = View.VISIBLE
                topBar.visibility = View.VISIBLE
                enableImmersiveMode()
            }
        }
    }

    private fun injectScript(js: String) {
        webView.evaluateJavascript(js, null)
    }

    private fun injectCss(css: String) {
        val cleanCss = css.replace("'", "\\'").replace("\n", "\\n").replace("\r", "")
        val script = """
            (function() {
                var parent = document.head || document.documentElement;
                var style = document.createElement('style');
                style.type = 'text/css';
                style.textContent = '$cleanCss';
                parent.appendChild(style);
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    inner class AnimeTvBridge {
        @JavascriptInterface
        fun onVideoPlay() {
            runOnUiThread {
                showHudPlayerBarBriefly()
            }
        }

        @JavascriptInterface
        fun onVideoPause() {
            runOnUiThread {
            }
        }
    }

    // ── Netflix / Prime Style Top Bar Setup ──────────────────────────────────

    private fun setupTopBar() {
        btnSource9Anime.setOnClickListener { switchSource(SOURCE_9ANIME) }
        btnSourceGogoAnime.setOnClickListener { switchSource(SOURCE_GOGOANIME) }
        btnSourceSoloLatino.setOnClickListener { switchSource(SOURCE_SOLOLATINO) }
        btnSourceSoloLatinoHome.setOnClickListener { switchSource(SOURCE_SOLOLATINO_HOME) }
        btnSourceAnimeFlix.setOnClickListener { switchSource(SOURCE_ANIMEFLIX) }
        btnSourceAnimeYT.setOnClickListener { switchSource(SOURCE_ANIMEYT) }
        btnSourceJKAnime.setOnClickListener { switchSource(SOURCE_JKANIME) }

        btnTopBarCinema.setOnClickListener { toggleCinemaMode() }
        btnTopBarMode.setOnClickListener { toggleNavMode() }
        btnTopBarReload.setOnClickListener { webView.reload() }

        updateTopBarUi()
    }

    private val topBarFocusOrder: List<View> by lazy {
        listOf(
            btnSource9Anime, btnSourceGogoAnime, btnSourceSoloLatino, btnSourceSoloLatinoHome,
            btnSourceAnimeFlix, btnSourceAnimeYT, btnSourceJKAnime,
            btnTopBarCinema, btnTopBarMode, btnTopBarReload
        )
    }

    private fun updateTopBarUi() {
        val sources = listOf(
            Triple(btnSource9Anime, SOURCE_9ANIME, "9Anime"),
            Triple(btnSourceGogoAnime, SOURCE_GOGOANIME, "GogoAnime"),
            Triple(btnSourceSoloLatino, SOURCE_SOLOLATINO, "SoloAnime"),
            Triple(btnSourceSoloLatinoHome, SOURCE_SOLOLATINO_HOME, "SoloStream"),
            Triple(btnSourceAnimeFlix, SOURCE_ANIMEFLIX, "AnimeFlix"),
            Triple(btnSourceAnimeYT, SOURCE_ANIMEYT, "AnimeYT"),
            Triple(btnSourceJKAnime, SOURCE_JKANIME, "JKAnime")
        )

        for ((btn, src, name) in sources) {
            if (currentSource == src) {
                btn.text = "● $name"
                btn.setBackgroundResource(R.drawable.bg_netflix_active_tab)
                btn.setTextColor(Color.WHITE)
            } else {
                btn.text = "○ $name"
                btn.setBackgroundResource(R.drawable.bg_netflix_tab)
                btn.setTextColor(Color.parseColor("#E0E0FF"))
            }
        }

        btnTopBarCinema.text = if (isCinemaMode) "🍿 Cinema: ON" else "🍿 Cinema"
        btnTopBarCinema.setTextColor(if (isCinemaMode) Color.parseColor("#FF5252") else Color.parseColor("#FFD54F"))

        btnTopBarMode.text = if (currentNavMode == MODE_SCROLL) "📜 Scroll" else "🖱️ Pointer"
        btnTopBarMode.setTextColor(if (currentNavMode == MODE_SCROLL) Color.parseColor("#00E676") else Color.parseColor("#BB86FC"))
    }

    private fun switchSource(source: String) {
        if (source == currentSource) return
        currentSource = source
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit().putString(KEY_ACTIVE_SOURCE, source).apply()
        updateTopBarUi()

        webView.loadUrl(urlForSource(source))
        handler.postDelayed({ moveFocusToPage() }, 200)
    }

    private fun toggleCinemaMode() {
        isCinemaMode = !isCinemaMode
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(KEY_CINEMA_MODE, isCinemaMode).apply()
        updateTopBarUi()
        if (isCinemaMode) {
            injectScript("document.body.classList.add('animetv-cinema-mode');")
            Toast.makeText(this, "🍿 Cinema Mode Enabled", Toast.LENGTH_SHORT).show()
        } else {
            injectScript("document.body.classList.remove('animetv-cinema-mode');")
            Toast.makeText(this, "Standard View", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleNavMode() {
        currentNavMode = if (currentNavMode == MODE_SCROLL) MODE_POINTER else MODE_SCROLL
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putInt(KEY_NAV_MODE, currentNavMode).apply()
        virtualCursorView.isDirectScrollMode = (currentNavMode == MODE_SCROLL)
        updateTopBarUi()
    }

    // ── Netflix / Prime Style HUD Player Controls ────────────────────────────

    private fun setupHudPlayerBar() {
        btnHudRewind.setOnClickListener {
            sendPlayerCommand("seek", -10)
            showHudPlayerBarBriefly()
        }
        btnHudPlayPause.setOnClickListener {
            sendPlayerCommand("toggle")
            showHudPlayerBarBriefly()
        }
        btnHudForward.setOnClickListener {
            sendPlayerCommand("seek", 10)
            showHudPlayerBarBriefly()
        }
        btnHudSkipIntro.setOnClickListener {
            sendPlayerCommand("skipIntro")
            Toast.makeText(this, "⏩ Skipping Intro (+85s)", Toast.LENGTH_SHORT).show()
            showHudPlayerBarBriefly()
        }
        btnHudNextEp.setOnClickListener {
            sendPlayerCommand("nextEpisode")
            Toast.makeText(this, "⏭ Loading Next Episode...", Toast.LENGTH_SHORT).show()
            showHudPlayerBarBriefly()
        }
        btnHudFullscreen.setOnClickListener {
            toggleFullscreen()
            hideHudPlayerBar()
        }
    }

    private val hudFocusOrder: List<View> by lazy {
        listOf(btnHudRewind, btnHudPlayPause, btnHudForward, btnHudSkipIntro, btnHudNextEp, btnHudFullscreen)
    }

    private fun sendPlayerCommand(action: String, seconds: Int = 0) {
        val js = when (action) {
            "toggle" -> "window.__animePlayerBridge && window.__animePlayerBridge.toggle();"
            "play" -> "window.__animePlayerBridge && window.__animePlayerBridge.play();"
            "pause" -> "window.__animePlayerBridge && window.__animePlayerBridge.pause();"
            "seek" -> "window.__animePlayerBridge && window.__animePlayerBridge.seek($seconds);"
            "skipIntro" -> "window.__animePlayerBridge && window.__animePlayerBridge.skipIntro();"
            "nextEpisode" -> "window.__animePlayerBridge && window.__animePlayerBridge.nextEpisode();"
            else -> ""
        }
        if (js.isNotEmpty()) {
            webView.evaluateJavascript(js, null)
        }
    }

    private fun showHudPlayerBar() {
        handler.removeCallbacks(hideHudRunnable)
        hudPlayerBar.visibility = View.VISIBLE
        hudPlayerBar.alpha = 1f
        btnHudPlayPause.requestFocus()
        handler.postDelayed(hideHudRunnable, HUD_AUTO_HIDE_DELAY_MS)
    }

    private fun showHudPlayerBarBriefly() {
        handler.removeCallbacks(hideHudRunnable)
        hudPlayerBar.visibility = View.VISIBLE
        hudPlayerBar.alpha = 1f
        handler.postDelayed(hideHudRunnable, HUD_AUTO_HIDE_DELAY_MS)
    }

    private fun hideHudPlayerBar() {
        hudPlayerBar.animate()
            .alpha(0f)
            .setDuration(300)
            .withEndAction {
                hudPlayerBar.visibility = View.GONE
                webView.requestFocus()
            }
            .start()
    }

    private fun toggleFullscreen() {
        if (customView != null) {
            customViewCallback?.onCustomViewHidden()
        } else {
            injectScript(
                """
                (function() {
                    var vid = document.querySelector('video');
                    if (vid) {
                        if (vid.requestFullscreen) vid.requestFullscreen();
                        else if (vid.webkitRequestFullscreen) vid.webkitRequestFullscreen();
                    }
                })();
                """.trimIndent()
            )
        }
    }

    // ── TV D-Pad Focus & Key Navigation ──────────────────────────────────────

    private fun moveFocusToTopBar() {
        virtualCursorView.isCursorVisible = false
        val active = topBarFocusOrder.firstOrNull { it.id == btnSource9Anime.id }
        active?.requestFocus()
    }

    private fun moveFocusToPage() {
        webView.requestFocus()
        if (isTv) {
            virtualCursorView.isCursorVisible = true
        }
    }

    private fun scheduleGuideDismiss() {
        osdControlsGuide.visibility = View.VISIBLE
        osdControlsGuide.alpha = 1f
        handler.removeCallbacks(hideGuideRunnable)
        handler.postDelayed(hideGuideRunnable, 5000)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val action = event.action
        val keyCode = event.keyCode

        // Hardware Media Controls
        if (action == KeyEvent.ACTION_DOWN) {
            when (keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> {
                    sendPlayerCommand("toggle")
                    showHudPlayerBarBriefly()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY -> {
                    sendPlayerCommand("play")
                    showHudPlayerBarBriefly()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    sendPlayerCommand("pause")
                    showHudPlayerBarBriefly()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    sendPlayerCommand("seek", 10)
                    showHudPlayerBarBriefly()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    sendPlayerCommand("seek", -10)
                    showHudPlayerBarBriefly()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_NEXT -> {
                    sendPlayerCommand("nextEpisode")
                    return true
                }
                KeyEvent.KEYCODE_MENU -> {
                    if (hudPlayerBar.visibility == View.VISIBLE) {
                        hideHudPlayerBar()
                    } else {
                        showHudPlayerBar()
                    }
                    return true
                }
            }
        }

        // When HUD bar is visible, allow D-pad to navigate HUD buttons
        if (hudPlayerBar.visibility == View.VISIBLE) {
            if (action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        hideHudPlayerBar()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        moveFocusInList(hudFocusOrder, false)
                        handler.removeCallbacks(hideHudRunnable)
                        handler.postDelayed(hideHudRunnable, HUD_AUTO_HIDE_DELAY_MS)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        moveFocusInList(hudFocusOrder, true)
                        handler.removeCallbacks(hideHudRunnable)
                        handler.postDelayed(hideHudRunnable, HUD_AUTO_HIDE_DELAY_MS)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        val focused = hudFocusOrder.find { it.isFocused }
                        focused?.performClick()
                        return true
                    }
                }
            }
            return super.dispatchKeyEvent(event)
        }

        // When Top Bar has focus, D-pad LEFT/RIGHT moves between tabs, DOWN moves into page
        val isTopBarFocused = topBarFocusOrder.any { it.isFocused }
        if (isTopBarFocused) {
            if (action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        moveFocusInList(topBarFocusOrder, false)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        moveFocusInList(topBarFocusOrder, true)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        moveFocusToPage()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        val focused = topBarFocusOrder.find { it.isFocused }
                        focused?.performClick()
                        return true
                    }
                }
            }
            return super.dispatchKeyEvent(event)
        }

        // When Page/WebView has focus:
        if (webView.hasFocus() || (!isTopBarFocused && hudPlayerBar.visibility != View.VISIBLE)) {
            val isDpadDirection = keyCode in listOf(
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT
            )

            if (isDpadDirection) {
                // Check if UP at top of page should move focus into top bar
                if (action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP && webView.scrollY <= 4) {
                    moveFocusToTopBar()
                    return true
                }
                val isDown = (action == KeyEvent.ACTION_DOWN)
                if (isDown && isTv) {
                    virtualCursorView.visibility = View.VISIBLE
                    virtualCursorView.isCursorVisible = true
                }
                virtualCursorView.onDpadKey(keyCode, isDown)
                return true
            }

            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                if (action == KeyEvent.ACTION_UP) {
                    virtualCursorView.dispatchClick(webView)
                }
                return true
            }
        }

        return super.dispatchKeyEvent(event)
    }

    private fun moveFocusInList(list: List<View>, forward: Boolean) {
        val currentIndex = list.indexOfFirst { it.isFocused }
        val nextIndex = when {
            currentIndex == -1 -> 0
            forward -> (currentIndex + 1).coerceAtMost(list.size - 1)
            else -> (currentIndex - 1).coerceAtLeast(0)
        }
        list[nextIndex].requestFocus()
    }

    private fun setupBackPressedHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (customView != null) {
                    customViewCallback?.onCustomViewHidden()
                    return
                }
                if (hudPlayerBar.visibility == View.VISIBLE) {
                    hideHudPlayerBar()
                    return
                }
                if (webView.canGoBack()) {
                    webView.goBack()
                    return
                }
                val now = System.currentTimeMillis()
                if (now - lastBackPressTime < BACK_PRESS_INTERVAL) {
                    finish()
                } else {
                    lastBackPressTime = now
                    Toast.makeText(this@MainActivity, "Press BACK again to exit", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    private fun enableImmersiveMode() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
