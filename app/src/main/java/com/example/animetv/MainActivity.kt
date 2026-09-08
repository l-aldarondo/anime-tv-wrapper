package com.example.animetv

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.example.animetv.adblock.AdBlocker
import com.example.animetv.tv.VirtualCursorView
import com.example.animetv.webview.AnimeWebChromeClient
import com.example.animetv.webview.AnimeWebViewClient

class MainActivity : AppCompatActivity() {

    companion object {
        const val PREFS_NAME = "anime_tv_prefs"
        const val KEY_ACTIVE_SOURCE = "active_source"
        const val SOURCE_9ANIME = "9anime"
        const val SOURCE_GOGOANIME = "gogoanime"
        const val SOURCE_SOLOLATINO = "sololatino"
        const val SOURCE_ANIMEFLIX = "animeflix"
        const val SOURCE_ANIMEYT = "animeyt"
        const val SOURCE_JKANIME = "jkanime"

        const val URL_9ANIME = "https://9anime.or.at/"
        const val URL_GOGOANIME = "https://gogoanime.by/"
        const val URL_SOLOLATINO = "https://sololatino.net/animes"
        const val URL_ANIMEFLIX = "https://animeflix.team/"
        const val URL_ANIMEYT = "https://animeyt.cc/"
        const val URL_JKANIME = "https://jkanime.net/"

        const val MODE_POINTER = 0
        const val MODE_SCROLL = 1
        private const val BACK_PRESS_INTERVAL = 2000L
    }

    private var isTv = false

    private lateinit var rootContainer: FrameLayout
    private lateinit var webView: WebView
    private lateinit var videoContainer: FrameLayout
    private lateinit var pageLoadingBar: ProgressBar
    private lateinit var virtualCursorView: VirtualCursorView
    private lateinit var osdTopBar: View
    private lateinit var osdControlsGuide: View
    private lateinit var txtAdBlockBadge: TextView
    private lateinit var txtNavModeBadge: TextView
    private lateinit var btnFullscreen: TextView

    // Hidden Sidebar UI elements
    private lateinit var btnSidebarTrigger: View
    private lateinit var sidebarDrawer: LinearLayout
    private lateinit var btnSidebarFullscreen: TextView
    private lateinit var btnSidebarAdBlock: TextView
    private lateinit var btnSidebarMode: TextView
    private lateinit var btnSource9Anime: TextView
    private lateinit var btnSourceGogoAnime: TextView
    private lateinit var btnSourceSoloLatino: TextView
    private lateinit var btnSourceAnimeFlix: TextView
    private lateinit var btnSourceAnimeYT: TextView
    private lateinit var btnSourceJKAnime: TextView
    private lateinit var btnSidebarHome: TextView
    private lateinit var btnSidebarReload: TextView
    private lateinit var btnSidebarClose: TextView

    private lateinit var webChromeClient: AnimeWebChromeClient
    private lateinit var webViewClient: AnimeWebViewClient

    private var isSidebarOpen = false
    private var isAdBlockEnabled = true
    private var currentSource = SOURCE_9ANIME
    private var currentNavMode = MODE_POINTER
    private var isPlayerFullscreen = false
    private var lastBackPressTime = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val hideGuideRunnable = Runnable {
        osdControlsGuide.animate()
            .alpha(0f)
            .setDuration(600)
            .withEndAction { osdControlsGuide.visibility = View.GONE }
            .start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Load saved anime source preference
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        currentSource = prefs.getString(KEY_ACTIVE_SOURCE, SOURCE_9ANIME) ?: SOURCE_9ANIME

        // Detect if device is an Android TV / Google TV or a Phone / Tablet
        val uiModeManager = getSystemService(android.content.Context.UI_MODE_SERVICE) as? android.app.UiModeManager
        isTv = (uiModeManager?.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION)
            || !packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN)

        // TV devices lock to landscape; Phones/Tablets auto-rotate freely
        requestedOrientation = if (isTv) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }

        // Keep screen on for continuous video watching
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Enable immersive sticky fullscreen
        enableImmersiveMode()

        setContentView(R.layout.activity_main)

        initViews()
        setupWebView()
        setupAdBlockListener()
        setupBackPressedHandler()

        if (isTv) {
            scheduleGuideDismiss()
        } else {
            // On phones with touch screens, hide the virtual D-Pad cursor and TV remote hints
            virtualCursorView.visibility = View.GONE
            osdControlsGuide.visibility = View.GONE
            txtNavModeBadge.visibility = View.GONE
        }

        // Load starting URL based on selected source
        val defaultUrl = when (currentSource) {
            SOURCE_GOGOANIME -> URL_GOGOANIME
            SOURCE_SOLOLATINO -> URL_SOLOLATINO
            SOURCE_ANIMEFLIX -> URL_ANIMEFLIX
            SOURCE_ANIMEYT -> URL_ANIMEYT
            SOURCE_JKANIME -> URL_JKANIME
            else -> URL_9ANIME
        }
        val startUrl = intent?.dataString ?: defaultUrl
        if (savedInstanceState == null) {
            webView.loadUrl(startUrl)
        } else {
            webView.restoreState(savedInstanceState)
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        intent.dataString?.let { newUrl ->
            webView.loadUrl(newUrl)
        }
    }

    private fun initViews() {
        rootContainer = findViewById(R.id.rootContainer)
        webView = findViewById(R.id.webView)
        videoContainer = findViewById(R.id.videoContainer)
        pageLoadingBar = findViewById(R.id.pageLoadingBar)
        virtualCursorView = findViewById(R.id.virtualCursorView)
        virtualCursorView.targetView = webView
        osdTopBar = findViewById(R.id.osdTopBar)
        osdControlsGuide = findViewById(R.id.osdControlsGuide)
        txtAdBlockBadge = findViewById(R.id.txtAdBlockBadge)
        txtNavModeBadge = findViewById(R.id.txtNavModeBadge)
        btnFullscreen = findViewById(R.id.btnFullscreen)

        // Sidebar references
        btnSidebarTrigger = findViewById(R.id.btnSidebarTrigger)
        sidebarDrawer = findViewById(R.id.sidebarDrawer)
        btnSidebarFullscreen = findViewById(R.id.btnSidebarFullscreen)
        btnSidebarAdBlock = findViewById(R.id.btnSidebarAdBlock)
        btnSidebarMode = findViewById(R.id.btnSidebarMode)
        btnSource9Anime = findViewById(R.id.btnSource9Anime)
        btnSourceGogoAnime = findViewById(R.id.btnSourceGogoAnime)
        btnSourceSoloLatino = findViewById(R.id.btnSourceSoloLatino)
        btnSourceAnimeFlix = findViewById(R.id.btnSourceAnimeFlix)
        btnSourceAnimeYT = findViewById(R.id.btnSourceAnimeYT)
        btnSourceJKAnime = findViewById(R.id.btnSourceJKAnime)
        btnSidebarHome = findViewById(R.id.btnSidebarHome)
        btnSidebarReload = findViewById(R.id.btnSidebarReload)
        btnSidebarClose = findViewById(R.id.btnSidebarClose)

        btnSidebarTrigger.setOnClickListener {
            openSidebar()
        }
        if (isTv) {
            btnSidebarTrigger.visibility = View.GONE
        }

        btnFullscreen.setOnClickListener {
            togglePlayerFullscreen()
        }

        setupSidebar()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webChromeClient = AnimeWebChromeClient(
            webView = webView,
            videoContainer = videoContainer,
            progressBar = pageLoadingBar,
            onFullscreenChanged = { isFullscreen ->
                handleFullscreenChange(isFullscreen)
            }
        )
        webViewClient = AnimeWebViewClient()

        webView.webChromeClient = webChromeClient
        webView.webViewClient = webViewClient

        // Hardware acceleration
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        webView.overScrollMode = View.OVER_SCROLL_NEVER

        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true

        // Essential for seamless TV playback without requiring touch gestures
        settings.mediaPlaybackRequiresUserGesture = false

        // Enable multiple windows so ChromeClient.onCreateWindow catches and drops popups
        settings.setSupportMultipleWindows(true)
        settings.javaScriptCanOpenWindowsAutomatically = false

        // Widescreen 16:9 layout formatting
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false

        // Enable devtools debugging
        WebView.setWebContentsDebuggingEnabled(true)

        // Set Desktop/TV Chrome User Agent for optimal 16:9 widescreen layout on TV
        if (isTv) {
            settings.userAgentString = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36 TV/GoogleTV"
        }

        // Register JavaScript interface for auto-fullscreen and player event callbacks
        webView.addJavascriptInterface(WebAppInterface(), "AndroidBridge")

        // Cache & Cookies
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
    }

    private fun setupSidebar() {
        // Toggle Fullscreen
        btnSidebarFullscreen.setOnClickListener {
            togglePlayerFullscreen()
            closeSidebar()
        }

        // Toggle AdBlocker
        btnSidebarAdBlock.setOnClickListener {
            isAdBlockEnabled = !isAdBlockEnabled
            AdBlocker.isEnabled = isAdBlockEnabled
            updateSidebarUi()
            Toast.makeText(this, if (isAdBlockEnabled) "🛡️ AdBlocker Enabled" else "⚠️ AdBlocker Disabled", Toast.LENGTH_SHORT).show()
        }

        // Toggle Navigation Mode
        btnSidebarMode.setOnClickListener {
            toggleNavigationMode()
            updateSidebarUi()
        }

        // Source 1: 9Anime
        btnSource9Anime.setOnClickListener {
            switchSource(SOURCE_9ANIME)
        }

        // Source 2: GogoAnime
        btnSourceGogoAnime.setOnClickListener {
            switchSource(SOURCE_GOGOANIME)
        }

        // Source 3: SoloLatino
        btnSourceSoloLatino.setOnClickListener {
            switchSource(SOURCE_SOLOLATINO)
        }

        // Source 4: AnimeFlix
        btnSourceAnimeFlix.setOnClickListener {
            switchSource(SOURCE_ANIMEFLIX)
        }

        // Source 5: AnimeYT
        btnSourceAnimeYT.setOnClickListener {
            switchSource(SOURCE_ANIMEYT)
        }

        // Source 6: JKAnime
        btnSourceJKAnime.setOnClickListener {
            switchSource(SOURCE_JKANIME)
        }

        // Home
        btnSidebarHome.setOnClickListener {
            val url = when (currentSource) {
                SOURCE_GOGOANIME -> URL_GOGOANIME
                SOURCE_SOLOLATINO -> URL_SOLOLATINO
                SOURCE_ANIMEFLIX -> URL_ANIMEFLIX
                SOURCE_ANIMEYT -> URL_ANIMEYT
                SOURCE_JKANIME -> URL_JKANIME
                else -> URL_9ANIME
            }
            webView.loadUrl(url)
            closeSidebar()
        }

        // Reload
        btnSidebarReload.setOnClickListener {
            webView.reload()
            closeSidebar()
        }

        // Close
        btnSidebarClose.setOnClickListener {
            closeSidebar()
        }

        // Virtual Cursor edge triggers
        virtualCursorView.onLeftEdgeTrigger = {
            runOnUiThread {
                if (!isSidebarOpen && !isPlayerFullscreen && !webChromeClient.isFullscreen) {
                    openSidebar()
                }
            }
        }

        virtualCursorView.onCursorMoved = { x, _ ->
            if (isSidebarOpen && x > 330f * resources.displayMetrics.density) {
                runOnUiThread {
                    closeSidebar()
                }
            }
        }

        updateSidebarUi()
    }

    private fun openSidebar() {
        if (isSidebarOpen || isPlayerFullscreen || webChromeClient.isFullscreen) return
        isSidebarOpen = true
        btnSidebarTrigger.visibility = View.GONE
        val density = resources.displayMetrics.density
        val startX = -sidebarDrawer.width.toFloat().let { if (it <= 0f) -330f * density else -it }
        sidebarDrawer.translationX = startX
        sidebarDrawer.visibility = View.VISIBLE
        sidebarDrawer.animate()
            .translationX(0f)
            .setDuration(220)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()

        updateSidebarUi()

        // Gently steer virtual cursor slightly onto the sidebar if it was pinned against the screen edge
        if (virtualCursorView.cursorX < 30f * density) {
            virtualCursorView.setCursorPosition(150f * density, virtualCursorView.cursorY)
        }
    }

    private fun closeSidebar() {
        if (!isSidebarOpen) return
        isSidebarOpen = false
        val density = resources.displayMetrics.density
        val targetX = -sidebarDrawer.width.toFloat().let { if (it <= 0f) -330f * density else -it }
        sidebarDrawer.animate()
            .translationX(targetX)
            .setDuration(200)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction {
                if (!isSidebarOpen) {
                    sidebarDrawer.visibility = View.GONE
                    if (!isTv && !isPlayerFullscreen && !webChromeClient.isFullscreen) {
                        btnSidebarTrigger.visibility = View.VISIBLE
                    }
                }
            }
            .start()
    }

    private fun toggleSidebar() {
        if (isSidebarOpen) closeSidebar() else openSidebar()
    }

    private fun updateSidebarUi() {
        // Fullscreen
        btnSidebarFullscreen.text = if (isPlayerFullscreen) "⛶  Exit Fullscreen" else "⛶  Enter Fullscreen"
        btnSidebarFullscreen.setTextColor(
            if (isPlayerFullscreen) android.graphics.Color.parseColor("#FF5252")
            else android.graphics.Color.parseColor("#FFD600")
        )

        // AdBlocker
        btnSidebarAdBlock.text = if (isAdBlockEnabled) "🛡️  AdBlocker: ON" else "🛡️  AdBlocker: OFF"
        btnSidebarAdBlock.setTextColor(
            if (isAdBlockEnabled) android.graphics.Color.parseColor("#00E676")
            else android.graphics.Color.parseColor("#FF5252")
        )

        // Mode
        btnSidebarMode.text = if (currentNavMode == MODE_POINTER) "🖱️  Mode: Pointer" else "📜  Mode: Scroll"
        btnSidebarMode.setTextColor(
            if (currentNavMode == MODE_POINTER) android.graphics.Color.parseColor("#E0AAFF")
            else android.graphics.Color.parseColor("#00E676")
        )

        // Sources active styling
        val sources = listOf(
            Triple(btnSource9Anime, SOURCE_9ANIME, "9Anime"),
            Triple(btnSourceGogoAnime, SOURCE_GOGOANIME, "GogoAnime"),
            Triple(btnSourceSoloLatino, SOURCE_SOLOLATINO, "SoloLatino (Español)"),
            Triple(btnSourceAnimeFlix, SOURCE_ANIMEFLIX, "AnimeFlix"),
            Triple(btnSourceAnimeYT, SOURCE_ANIMEYT, "AnimeYT (Español)"),
            Triple(btnSourceJKAnime, SOURCE_JKANIME, "JKAnime (Español)")
        )

        for ((btn, src, name) in sources) {
            if (currentSource == src) {
                btn.text = "●  $name (Active)"
                btn.setBackgroundResource(R.drawable.bg_sidebar_active_source)
                btn.setTextColor(android.graphics.Color.WHITE)
            } else {
                btn.text = "○  $name"
                btn.setBackgroundResource(R.drawable.bg_sidebar_item)
                btn.setTextColor(android.graphics.Color.parseColor("#F0F0FF"))
            }
        }
    }

    private fun switchSource(source: String) {
        currentSource = source
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit().putString(KEY_ACTIVE_SOURCE, source).apply()
        updateSidebarUi()

        val (url, label) = when (source) {
            SOURCE_GOGOANIME -> Pair(URL_GOGOANIME, "GogoAnime")
            SOURCE_SOLOLATINO -> Pair(URL_SOLOLATINO, "SoloLatino")
            SOURCE_ANIMEFLIX -> Pair(URL_ANIMEFLIX, "AnimeFlix")
            SOURCE_ANIMEYT -> Pair(URL_ANIMEYT, "AnimeYT")
            SOURCE_JKANIME -> Pair(URL_JKANIME, "JKAnime")
            else -> Pair(URL_9ANIME, "9Anime")
        }
        Toast.makeText(this, "🎌 Loading $label...", Toast.LENGTH_SHORT).show()
        webView.loadUrl(url)
        closeSidebar()
    }

    private fun setupAdBlockListener() {
        AdBlocker.onBlockListener = { count ->
            runOnUiThread {
                txtAdBlockBadge.text = "🛡️ AdBlock ON ($count)"
            }
        }
    }

    private fun setupBackPressedHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isSidebarOpen) {
                    closeSidebar()
                } else if (webChromeClient.isFullscreen) {
                    webChromeClient.onHideCustomView()
                } else if (isPlayerFullscreen) {
                    setPlayerFullscreen(false)
                } else if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    val now = System.currentTimeMillis()
                    if (now - lastBackPressTime < BACK_PRESS_INTERVAL) {
                        finish()
                    } else {
                        lastBackPressTime = now
                        Toast.makeText(this@MainActivity, "Press BACK again to exit", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })
    }

    private fun handleFullscreenChange(isFullscreen: Boolean) {
        isPlayerFullscreen = isFullscreen
        if (!isTv) {
            requestedOrientation = if (isFullscreen) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
        if (isFullscreen) {
            closeSidebar()
            btnSidebarTrigger.visibility = View.GONE
            virtualCursorView.visibility = View.GONE
            osdTopBar.visibility = View.GONE
            osdControlsGuide.visibility = View.GONE
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            enableImmersiveMode()
        } else {
            if (!isTv) btnSidebarTrigger.visibility = View.VISIBLE
            virtualCursorView.visibility = if (currentNavMode == MODE_POINTER && isTv) View.VISIBLE else View.GONE
            osdTopBar.visibility = View.GONE
            btnFullscreen.text = "⛶ Fullscreen"
            btnFullscreen.setTextColor(android.graphics.Color.parseColor("#FFD600"))
            enableImmersiveMode()
        }
    }

    fun setPlayerFullscreen(enabled: Boolean) {
        isPlayerFullscreen = enabled
        if (!isTv) {
            requestedOrientation = if (enabled) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
        runOnUiThread {
            if (enabled) {
                closeSidebar()
                btnSidebarTrigger.visibility = View.GONE
            }
            webView.evaluateJavascript("if (window.expandPlayerFullscreen) window.expandPlayerFullscreen($enabled);", null)
            if (enabled) {
                btnFullscreen.text = "✖ Exit Fullscreen"
                btnFullscreen.setTextColor(android.graphics.Color.parseColor("#FF5252"))
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                enableImmersiveMode()
                Toast.makeText(this, "⛶ Fullscreen Active (Press BACK to exit)", Toast.LENGTH_SHORT).show()
                // Auto-play the video if paused / waiting on overlay play button
                handler.postDelayed({
                    playVideo()
                }, 300L)
            } else {
                if (!isTv && !webChromeClient.isFullscreen) {
                    btnSidebarTrigger.visibility = View.VISIBLE
                }
                btnFullscreen.text = "⛶ Fullscreen"
                btnFullscreen.setTextColor(android.graphics.Color.parseColor("#FFD600"))
                enableImmersiveMode()
            }
        }
    }

    fun togglePlayerFullscreen() {
        setPlayerFullscreen(!isPlayerFullscreen)
    }

    private fun enableImmersiveMode() {
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun scheduleGuideDismiss() {
        handler.removeCallbacks(hideGuideRunnable)
        osdControlsGuide.alpha = 1f
        osdControlsGuide.visibility = View.VISIBLE
        handler.postDelayed(hideGuideRunnable, 5000L)
    }

    private fun toggleNavigationMode() {
        currentNavMode = if (currentNavMode == MODE_POINTER) MODE_SCROLL else MODE_POINTER
        virtualCursorView.isDirectScrollMode = (currentNavMode == MODE_SCROLL)
        if (currentNavMode == MODE_POINTER) {
            virtualCursorView.isCursorVisible = true
            txtNavModeBadge.text = "🖱️ Pointer Mode"
            txtNavModeBadge.setTextColor(android.graphics.Color.parseColor("#E0AAFF"))
            Toast.makeText(this, "Pointer Mode: D-Pad glides cursor, OK clicks", Toast.LENGTH_SHORT).show()
        } else {
            virtualCursorView.isCursorVisible = false
            txtNavModeBadge.text = "📜 Scroll Mode"
            txtNavModeBadge.setTextColor(android.graphics.Color.parseColor("#80D8FF"))
            Toast.makeText(this, "Scroll Mode: D-Pad glides page directly", Toast.LENGTH_SHORT).show()
        }
        scheduleGuideDismiss()
    }

    inner class WebAppInterface {
        @android.webkit.JavascriptInterface
        fun onVideoPlayDetected() {
            runOnUiThread {
                if (!isPlayerFullscreen && !webChromeClient.isFullscreen) {
                    setPlayerFullscreen(true)
                }
            }
        }
    }

    /**
     * Intercept and handle TV Remote D-Pad, Trackpad, and Media Keys.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val isDown = event.action == KeyEvent.ACTION_DOWN
        val isUp = event.action == KeyEvent.ACTION_UP

        // ── FULLSCREEN VIDEO REMOTE CONTROLS ────────────────────────────────
        if (webChromeClient.isFullscreen || isPlayerFullscreen) {
            when (event.keyCode) {
                // Exit fullscreen
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BUTTON_B -> {
                    if (isUp) {
                        if (webChromeClient.isFullscreen) {
                            webChromeClient.onHideCustomView()
                        }
                        if (isPlayerFullscreen) {
                            setPlayerFullscreen(false)
                        }
                    }
                    return true
                }

                // Explicit Pause
                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    if (isUp) {
                        pauseVideo()
                    }
                    return true
                }

                // Explicit Play
                KeyEvent.KEYCODE_MEDIA_PLAY -> {
                    if (isUp) {
                        playVideo()
                    }
                    return true
                }

                // Play / Pause toggle
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER,
                KeyEvent.KEYCODE_BUTTON_A,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    if (isUp) {
                        toggleVideoPlayback()
                    }
                    return true
                }

                // Fast forward 10 seconds
                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                KeyEvent.KEYCODE_MEDIA_STEP_FORWARD,
                KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD -> {
                    if (isUp) {
                        seekVideo(10)
                        Toast.makeText(this, "⏩ +10s", Toast.LENGTH_SHORT).show()
                    }
                    return true
                }

                // Rewind 10 seconds
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_MEDIA_REWIND,
                KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD,
                KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD -> {
                    if (isUp) {
                        seekVideo(-10)
                        Toast.makeText(this, "⏪ -10s", Toast.LENGTH_SHORT).show()
                    }
                    return true
                }

                // Skip Intro (+85 seconds, standard anime opening duration)
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (isUp) {
                        seekVideo(85)
                        Toast.makeText(this, "⏩ Skipped Intro (+85s)", Toast.LENGTH_SHORT).show()
                    }
                    return true
                }

                // Rewind 30 seconds
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (isUp) {
                        seekVideo(-30)
                        Toast.makeText(this, "⏪ -30s", Toast.LENGTH_SHORT).show()
                    }
                    return true
                }

                // Next Episode shortcut
                KeyEvent.KEYCODE_MEDIA_NEXT -> {
                    if (isUp) {
                        triggerNextEpisode()
                    }
                    return true
                }

                // Previous Episode shortcut
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                    if (isUp) {
                        triggerPreviousEpisode()
                    }
                    return true
                }
            }
            return super.dispatchKeyEvent(event)
        }

        // ── BROWSING MODE REMOTE CONTROLS ──────────────────────────────────
        when (event.keyCode) {
            // Mode / Settings sidebar toggle button (Menu, Info, Guide, Settings, or Gamepad Y)
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_INFO,
            KeyEvent.KEYCODE_GUIDE,
            KeyEvent.KEYCODE_SETTINGS,
            KeyEvent.KEYCODE_BUTTON_Y -> {
                if (isUp) {
                    toggleSidebar()
                }
                return true
            }

            // Quick Play/Pause keys when browsing
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (isUp) {
                    toggleVideoPlayback()
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                if (isUp) {
                    playVideo()
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (isUp) {
                    pauseVideo()
                }
                return true
            }

            // D-Pad Directions
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (isDown) {
                    scheduleGuideDismiss()
                    if (currentNavMode == MODE_POINTER) {
                        virtualCursorView.visibility = View.VISIBLE
                        virtualCursorView.isCursorVisible = true
                    }
                }
                virtualCursorView.onDpadKey(event.keyCode, isDown)
                return true
            }

            // D-Pad OK / Center Click
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BUTTON_A -> {
                if (currentNavMode == MODE_POINTER) {
                    if (isUp) {
                        scheduleGuideDismiss()
                        val density = resources.displayMetrics.density
                        val clickTarget = if (isSidebarOpen && virtualCursorView.cursorX <= 320f * density) {
                            rootContainer
                        } else {
                            webView
                        }
                        virtualCursorView.dispatchClick(clickTarget)
                    }
                    return true
                }
                // In Scroll Mode, pass enter through to web elements
            }

            // Back button
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BUTTON_B -> {
                if (isUp) {
                    if (isSidebarOpen) {
                        closeSidebar()
                        return true
                    } else if (isPlayerFullscreen) {
                        setPlayerFullscreen(false)
                        return true
                    } else if (webView.canGoBack()) {
                        webView.goBack()
                        return true
                    } else {
                        val now = System.currentTimeMillis()
                        if (now - lastBackPressTime < BACK_PRESS_INTERVAL) {
                            finish()
                        } else {
                            lastBackPressTime = now
                            Toast.makeText(this, "Press BACK again to exit", Toast.LENGTH_SHORT).show()
                        }
                        return true
                    }
                }
                return true
            }
        }

        return super.dispatchKeyEvent(event)
    }

    /**
     * Handle trackpad and air-mouse motion from Google TV remotes and remote mobile apps.
     */
    override fun onGenericMotionEvent(event: MotionEvent?): Boolean {
        if (event != null && event.action == MotionEvent.ACTION_SCROLL) {
            val vScroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            val hScroll = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
            if (vScroll != 0f || hScroll != 0f) {
                val scrollFactor = 60 * resources.displayMetrics.density
                webView.scrollBy((-hScroll * scrollFactor).toInt(), (-vScroll * scrollFactor).toInt())
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }


    private var touchStartX = 0f
    private var touchStartY = 0f
    private var isSwipeConsumed = false

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev != null) {
            val density = resources.displayMetrics.density
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchStartX = ev.rawX
                    touchStartY = ev.rawY
                    isSwipeConsumed = false

                    // If sidebar is open and user taps outside the drawer (x > 310dp), close drawer immediately
                    if (isSidebarOpen && ev.rawX > 310f * density) {
                        closeSidebar()
                        return true
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    // Trigger sidebar open on quick rightward swipe starting near the left edge or trigger tab
                    if (!isSwipeConsumed && !isSidebarOpen && !isPlayerFullscreen && !webChromeClient.isFullscreen) {
                        val deltaX = ev.rawX - touchStartX
                        val deltaY = Math.abs(ev.rawY - touchStartY)
                        if (touchStartX < 90f * density && deltaX > 35f * density && deltaY < 80f * density) {
                            isSwipeConsumed = true
                            openSidebar()
                            return true
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isSwipeConsumed = false
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun sendPlayerCommand(action: String, seconds: Int = 0) {
        val js = """
            (function() {
                var payload = { action: '$action', seconds: $seconds };
                
                // 1. Broadcast to all iframes
                var iframes = document.querySelectorAll('iframe');
                for (var i = 0; i < iframes.length; i++) {
                    try {
                        iframes[i].contentWindow.postMessage(payload, '*');
                    } catch(e) {}
                }
                
                // 2. Control top-level video elements if any
                var videos = document.querySelectorAll('video');
                for (var j = 0; j < videos.length; j++) {
                    var v = videos[j];
                    try {
                        if ('$action' === 'toggle') {
                            if (v.paused) v.play(); else v.pause();
                        } else if ('$action' === 'play') {
                            if (v.paused) v.play();
                        } else if ('$action' === 'pause') {
                            if (!v.paused) v.pause();
                        } else if ('$action' === 'seek') {
                            v.currentTime = Math.max(0, v.currentTime + $seconds);
                        }
                    } catch(e) {}
                }
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun toggleVideoPlayback() {
        sendPlayerCommand("toggle")
    }

    private fun playVideo() {
        sendPlayerCommand("play")
    }

    private fun pauseVideo() {
        sendPlayerCommand("pause")
        Toast.makeText(this, "⏸️ Paused", Toast.LENGTH_SHORT).show()
    }

    private fun seekVideo(seconds: Int) {
        sendPlayerCommand("seek", seconds)
    }

    private fun triggerNextEpisode() {
        val js = """
            (function() {
                var nextBtn = document.querySelector('.next-episode, .btn-next, a[rel="next"], .naveps .next, a.next');
                if (nextBtn) {
                    nextBtn.click();
                    return true;
                }
                return false;
            })();
        """.trimIndent()
        webView.evaluateJavascript(js) { res ->
            if (res == "true") {
                Toast.makeText(this, "⏭️ Next Episode...", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun triggerPreviousEpisode() {
        val js = """
            (function() {
                var prevBtn = document.querySelector('.prev-episode, .btn-prev, a[rel="prev"], .naveps .prev, a.prev');
                if (prevBtn) {
                    prevBtn.click();
                    return true;
                }
                return false;
            })();
        """.trimIndent()
        webView.evaluateJavascript(js) { res ->
            if (res == "true") {
                Toast.makeText(this, "⏮️ Previous Episode...", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        enableImmersiveMode()
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        webView.destroy()
    }
}
