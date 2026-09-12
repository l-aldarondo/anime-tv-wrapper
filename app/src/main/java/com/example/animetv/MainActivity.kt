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
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.animetv.adblock.AdBlockEngine
import com.example.animetv.auth.AccountStore
import com.example.animetv.tv.VirtualCursorView
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    companion object {
        const val TAG = "AnimeTVLite"

        const val PREFS_NAME = "anime_tv_prefs"
        const val KEY_ACTIVE_SOURCE = "active_source"
        const val KEY_CINEMA_MODE = "cinema_mode"
        const val KEY_NAV_MODE = "nav_mode"

        const val SOURCE_HUB = "hub"
        const val SOURCE_FAVORITES = "favorites"
        const val SOURCE_9ANIME = "9anime"
        const val SOURCE_GOGOANIME = "gogoanime"
        const val SOURCE_SOLOLATINO = "sololatino"
        const val SOURCE_SOLOLATINO_HOME = "sololatino_home"
        const val SOURCE_ANIMEFLIX = "animeflix"
        const val SOURCE_ANIMEYT = "animeyt"
        const val SOURCE_JKANIME = "jkanime"

        private const val LONG_PRESS_FAVORITE_MS = 600L

        const val URL_9ANIME = "https://9anime.or.at/"
        const val URL_GOGOANIME = "https://gogoanime.by/"
        const val URL_SOLOLATINO = "https://sololatino.net/animes"
        const val URL_SOLOLATINO_HOME = "https://sololatino.net/"
        const val URL_ANIMEFLIX = "https://animeflix.team/"
        const val URL_ANIMEYT = "https://animeyt.cc/"
        const val URL_JKANIME = "https://jkanime.net/"

        const val MODE_SPATIAL_CARDS = 0
        const val MODE_POINTER = 1
        const val MODE_SCROLL = 2

        private const val BACK_PRESS_INTERVAL = 2000L
        private const val HUD_AUTO_HIDE_DELAY_MS = 6000L

        const val USER_AGENT_DESKTOP = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

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
            "voe.sx",
            "voe-network.net",
            "gofile.io",
            "embed69.org",
            "morencius.com",
            "vidhide.com",
            "vidhidepre.com",
            "vidhidepro.com",
            "streamwish.to",
            "streamwish.com",
            "hglink.to",
            "minochinos.com",
            "ghbrisk.com",
            "audinifer.com",
            "f7hyg4q.org",
            "xupalace.org",
            "mega.nz",
            "mega.co.nz",
            "mega.io",
            "ok.ru",
            "vk.com",
            "megaplay.buzz",
            "megaplay.top",
            "snapcdn.top",
            "filemoon.sx",
            "streamtape.com",
            "mp4upload.com",
            "dood.to"
        )
    }

    private var isTv = false
    private var isCinemaMode = false
    private var currentNavMode = MODE_POINTER
    private var currentDomScrollY = 0f
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
    private lateinit var btnTopBarSearch: TextView
    private lateinit var btnSourceHub: TextView
    private lateinit var btnSourceFavorites: TextView
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
    private lateinit var btnTopBarFavorite: TextView
    private lateinit var btnTopBarAccount: TextView
    private lateinit var btnTopBarReload: TextView

    // TV Hub & Favorites Screen Views
    private lateinit var favoritesScreen: androidx.core.widget.NestedScrollView
    private lateinit var favoritesRecyclerView: RecyclerView
    private lateinit var favoritesEmptyLayout: LinearLayout
    private lateinit var favoritesAdapter: FavoritesAdapter

    // TV Hub Quick Actions & Source Cards
    private lateinit var hubBannerSearch: View
    private lateinit var hubCardSoloAnime: View
    private lateinit var hubCardSoloStream: View
    private lateinit var hubCard9Anime: View
    private lateinit var hubCardGogoAnime: View
    private lateinit var hubCardAnimeFlix: View
    private lateinit var hubCardAnimeYT: View
    private lateinit var hubCardJKAnime: View

    // HUD Player Controls
    private lateinit var btnHudRewind: TextView
    private lateinit var btnHudPlayPause: TextView
    private lateinit var btnHudForward: TextView
    private lateinit var btnHudSkipIntro: TextView
    private lateinit var btnHudNextEp: TextView
    private lateinit var btnHudFullscreen: TextView
    private lateinit var btnHudFavorite: TextView

    // Fullscreen Custom View Container (for HTML5 video tag fullscreen expansion)
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var isPlayerFullscreen = false

    private var lastOkPressTime = 0L
    private val DOUBLE_CLICK_TIMEOUT_MS = 550L
    private val pendingOkClickRunnable = Runnable {
        virtualCursorView.dispatchClick(webView)
    }

    private var longPressArmed = false
    private val longPressFavoriteRunnable = Runnable {
        extractAndSaveCurrentFavorite(isLongPress = true)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val hideHudRunnable = Runnable { hideHudPlayerBar() }
    private val hideGuideRunnable = Runnable {
        osdControlsGuide.animate()
            .alpha(0f)
            .setDuration(600)
            .withEndAction { osdControlsGuide.visibility = View.GONE }
            .start()
    }

    // TV Center OSD & Media Transport
    private lateinit var hudSeekBadge: TextView
    private var isVideoPlaying = false
    private val hideSeekBadgeRunnable = Runnable {
        hudSeekBadge.animate()
            .alpha(0f)
            .setDuration(250)
            .withEndAction { hudSeekBadge.visibility = View.GONE }
            .start()
    }

    // Cached asset scripts
    private var cachedAdblockGuardJs = ""
    private var cachedNetflixCinemaCss = ""
    private var cachedFixesCss = ""
    private var cachedSpatialNavJs = ""
    private var cachedPlayerControllerJs = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        currentSource = prefs.getString(KEY_ACTIVE_SOURCE, SOURCE_HUB) ?: SOURCE_HUB
        isCinemaMode = false
        currentNavMode = prefs.getInt(KEY_NAV_MODE, MODE_SPATIAL_CARDS)

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
        setupFavoritesScreen()
        setupWebView()
        setupBackPressedHandler()

        if (intent?.dataString != null) {
            webView.loadUrl(intent.dataString!!)
        } else if (currentSource == SOURCE_HUB || currentSource == SOURCE_FAVORITES) {
            webView.visibility = View.GONE
            virtualCursorView.visibility = View.GONE
            favoritesScreen.visibility = View.VISIBLE
            refreshFavoritesGrid()
            if (currentSource == SOURCE_HUB) {
                favoritesScreen.scrollTo(0, 0)
                hubBannerSearch.requestFocus()
            } else {
                favoritesRecyclerView.requestFocus()
            }
        } else {
            webView.loadUrl(urlForSource(currentSource))
        }

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
        try {
            cachedSpatialNavJs = assets.open("tv_spatial_nav.js").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading tv_spatial_nav.js", e)
        }
        try {
            cachedPlayerControllerJs = assets.open("tv_player_controller.js").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading tv_player_controller.js", e)
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

        // Favorites Screen
        favoritesScreen = findViewById(R.id.favoritesScreen)
        favoritesRecyclerView = findViewById(R.id.favoritesRecyclerView)
        favoritesEmptyLayout = findViewById(R.id.favoritesEmptyLayout)

        virtualCursorView.targetView = webView
        virtualCursorView.isDirectScrollMode = (currentNavMode == MODE_SCROLL)
        virtualCursorView.onScrollRequest = { dy ->
            val density = resources.displayMetrics.density
            val cssDy = dy / density
            currentDomScrollY = (currentDomScrollY + cssDy).coerceAtLeast(0f)
            webView.evaluateJavascript("window.scrollBy(0, $cssDy);", null)
        }
        virtualCursorView.onTopEdgeTrigger = {
            if (currentDomScrollY <= 15f) {
                moveFocusToTopBar()
            }
        }

        // Top Bar Tabs & Actions
        btnTopBarSearch = findViewById(R.id.btnTopBarSearch)
        btnSourceHub = findViewById(R.id.btnSourceHub)
        btnSourceFavorites = findViewById(R.id.btnSourceFavorites)
        btnSource9Anime = findViewById(R.id.btnSource9Anime)
        btnSourceGogoAnime = findViewById(R.id.btnSourceGogoAnime)
        btnSourceSoloLatino = findViewById(R.id.btnSourceSoloLatino)
        btnSourceSoloLatinoHome = findViewById(R.id.btnSourceSoloLatinoHome)
        btnSourceAnimeFlix = findViewById(R.id.btnSourceAnimeFlix)
        btnSourceAnimeYT = findViewById(R.id.btnSourceAnimeYT)
        btnSourceJKAnime = findViewById(R.id.btnSourceJKAnime)

        // TV Hub Interactive Cards
        hubBannerSearch = findViewById(R.id.hubBannerSearch)
        hubCardSoloAnime = findViewById(R.id.hubCardSoloAnime)
        hubCardSoloStream = findViewById(R.id.hubCardSoloStream)
        hubCard9Anime = findViewById(R.id.hubCard9Anime)
        hubCardGogoAnime = findViewById(R.id.hubCardGogoAnime)
        hubCardAnimeFlix = findViewById(R.id.hubCardAnimeFlix)
        hubCardAnimeYT = findViewById(R.id.hubCardAnimeYT)
        hubCardJKAnime = findViewById(R.id.hubCardJKAnime)

        hubBannerSearch.setOnClickListener { showUniversalSearchDialog() }
        hubCardSoloAnime.setOnClickListener { switchSource(SOURCE_SOLOLATINO) }
        hubCardSoloStream.setOnClickListener { switchSource(SOURCE_SOLOLATINO_HOME) }
        hubCard9Anime.setOnClickListener { switchSource(SOURCE_9ANIME) }
        hubCardGogoAnime.setOnClickListener { switchSource(SOURCE_GOGOANIME) }
        hubCardAnimeFlix.setOnClickListener { switchSource(SOURCE_ANIMEFLIX) }
        hubCardAnimeYT.setOnClickListener { switchSource(SOURCE_ANIMEYT) }
        hubCardJKAnime.setOnClickListener { switchSource(SOURCE_JKANIME) }

        // Top Bar Actions
        btnTopBarCinema = findViewById(R.id.btnTopBarCinema)
        btnTopBarMode = findViewById(R.id.btnTopBarMode)
        btnTopBarFavorite = findViewById(R.id.btnTopBarFavorite)
        btnTopBarAccount = findViewById(R.id.btnTopBarAccount)
        btnTopBarReload = findViewById(R.id.btnTopBarReload)

        // HUD Buttons
        btnHudRewind = findViewById(R.id.btnHudRewind)
        btnHudPlayPause = findViewById(R.id.btnHudPlayPause)
        btnHudForward = findViewById(R.id.btnHudForward)
        btnHudSkipIntro = findViewById(R.id.btnHudSkipIntro)
        btnHudNextEp = findViewById(R.id.btnHudNextEp)
        btnHudFullscreen = findViewById(R.id.btnHudFullscreen)
        btnHudFavorite = findViewById(R.id.btnHudFavorite)

        // TV Center Transport OSD Badge
        hudSeekBadge = findViewById(R.id.hudSeekBadge)

        if (!isTv || currentNavMode == MODE_SPATIAL_CARDS) {
            virtualCursorView.visibility = View.GONE
            virtualCursorView.isCursorVisible = false
        }
        if (!isTv) {
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

        // Allow mixed content for HLS chunk streams across CDNs
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        // Standard Windows 10 Chrome User-Agent for maximum video CDN & JWPlayer compatibility
        settings.userAgentString = USER_AGENT_DESKTOP

        // Cache policy
        settings.cacheMode = WebSettings.LOAD_DEFAULT

        // Cookie persistence across sessions
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.addJavascriptInterface(AnimeTvBridge(), "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                if (AdBlockEngine.shouldBlock(url)) {
                    return AdBlockEngine.EMPTY_RESPONSE
                }

                // Intercept embed69.org VAST ad player & tutorial overlay
                if (url.contains("embed69.org/player-oxserver.js")) {
                    val cleanScript = """
                        window.__playerOxServerLoaded = true;
                        window.go_to_playerVast = function(u, u2) {
                            console.log('[AnimeTV] VAST ad bypassed');
                        };
                        window.startTutorial = function() {};
                        window.shouldShowTutorial = function() { return false; };
                        (function() {
                            var style = document.createElement('style');
                            style.textContent = '.modal-vast, #modal, #tutorialOverlay, .tutorial-overlay { display: none !important; visibility: hidden !important; pointer-events: none !important; width: 0 !important; height: 0 !important; opacity: 0 !important; z-index: -9999 !important; }';
                            (document.head || document.documentElement).appendChild(style);

                            function autoStart() {
                                try {
                                    if (typeof showPlayerInterface === 'function') {
                                        var fake = document.getElementById('fakePlayer');
                                        if (fake && fake.style.display !== 'none') {
                                            showPlayerInterface();
                                        }
                                    }
                                } catch(e) {}
                            }
                            if (document.readyState === 'loading') {
                                document.addEventListener('DOMContentLoaded', function() { setTimeout(autoStart, 200); });
                            } else {
                                setTimeout(autoStart, 200);
                            }
                        })();
                    """.trimIndent()
                    return WebResourceResponse("application/javascript", "UTF-8", ByteArrayInputStream(cleanScript.toByteArray()))
                }

                if (url.contains("embed69.org/styles-player-oxserver.css")) {
                    val cleanCss = """
                        .modal-vast, #modal, #tutorialOverlay, .tutorial-overlay,
                        .video-title-overlay, .selector-container {
                            display: none !important;
                            visibility: hidden !important;
                            pointer-events: none !important;
                            width: 0 !important;
                            height: 0 !important;
                            opacity: 0 !important;
                            z-index: -9999 !important;
                        }
                        body.animetv-clean-player .language-tab-container,
                        body.animetv-clean-player .tab-container,
                        body.animetv-clean-player .video-title-overlay,
                        body.animetv-clean-player .selector-container {
                            display: none !important;
                            height: 0 !important;
                        }
                        body.animetv-clean-player #DisplayContent,
                        body.animetv-clean-player #PlayerDisplay,
                        body.animetv-clean-player .iframe-container,
                        body.animetv-clean-player .plyr-wrap,
                        body.animetv-clean-player .plyr,
                        body.animetv-clean-player video,
                        body.animetv-clean-player iframe {
                            width: 100vw !important;
                            height: 100vh !important;
                            max-width: 100vw !important;
                            max-height: 100vh !important;
                            margin: 0 !important;
                            padding: 0 !important;
                            border: none !important;
                            aspect-ratio: auto !important;
                        }
                    """.trimIndent()
                    return WebResourceResponse("text/css", "UTF-8", ByteArrayInputStream(cleanCss.toByteArray()))
                }

                return super.shouldInterceptRequest(view, request)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                val urlStr = uri.toString()
                val scheme = uri.scheme?.lowercase() ?: ""

                if (scheme == "about" || scheme == "data" || scheme == "blob") return false
                if (scheme != "http" && scheme != "https") return true // block external protocols

                // Check AdBlockEngine first (blocks ad links and popunders)
                if (AdBlockEngine.shouldBlock(urlStr)) {
                    Log.w(TAG, "Blocked ad navigation in shouldOverrideUrlLoading: $urlStr")
                    return true
                }

                val host = uri.host?.lowercase() ?: ""

                // Check if host matches any allowed streaming or embed provider
                val isAllowed = ALLOWED_MAIN_HOSTS.any { host == it || host.endsWith(".$it") }
                if (!isAllowed) {
                    Log.w(TAG, "Blocked external navigation to: $host ($urlStr)")
                    return true // block popup/redirect
                }

                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                currentDomScrollY = 0f
                pageLoadingBar.visibility = View.VISIBLE
                // Inject early guard into DOM before page scripts evaluate
                injectScript(
                    """
                    (function() {
                        try {
                            window.open = function() { return null; };

                            // Intercept SoloLatino __sl_ads retrieval idempotently
                            if (!Document.prototype._origGetById) {
                                Document.prototype._origGetById = Document.prototype.getElementById;
                                Document.prototype.getElementById = function(id) {
                                    var el = this._origGetById.apply(this, arguments);
                                    if (id === '__sl_ads' && el) {
                                        el.textContent = '{"h":"","b":""}';
                                    }
                                    return el;
                                };
                            }

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

                // Inject TV Spatial Navigation engine
                if (cachedSpatialNavJs.isNotEmpty()) {
                    injectScript(cachedSpatialNavJs)
                }

                // Inject TV Player Controller & Decoy Annihilator
                if (cachedPlayerControllerJs.isNotEmpty()) {
                    injectScript(cachedPlayerControllerJs)
                }

                // Apply Cinema Mode state if active
                if (isCinemaMode) {
                    injectScript("document.body.classList.add('animetv-cinema-mode');")
                }

                // Flush cookies to persistent storage
                CookieManager.getInstance().flush()

                // Inject DOM scroll position listener
                injectScrollBridge()

                // Silent auto-fill if master credentials exist
                if (AccountStore.hasMasterCredentials(this@MainActivity)) {
                    autoFillCredentialsSilently()
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
                isPlayerFullscreen = false

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

    private fun injectScrollBridge() {
        val script = """
            (function() {
                if (window.__animeScrollAttached) return;
                window.__animeScrollAttached = true;
                function reportScroll() {
                    try {
                        var sy = window.scrollY || (document.documentElement && document.documentElement.scrollTop) || (document.body && document.body.scrollTop) || 0;
                        if (window.AndroidBridge && window.AndroidBridge.onScrollPositionChanged) {
                            window.AndroidBridge.onScrollPositionChanged(sy);
                        }
                    } catch(e) {}
                }
                window.addEventListener('scroll', reportScroll, { passive: true });
                reportScroll();
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    inner class AnimeTvBridge {
        @JavascriptInterface
        fun onScrollPositionChanged(scrollY: Float) {
            runOnUiThread {
                currentDomScrollY = scrollY
            }
        }

        @JavascriptInterface
        fun onVideoPlay() {
            runOnUiThread {
                isVideoPlaying = true
                showHudPlayerBarBriefly()
            }
        }

        @JavascriptInterface
        fun onVideoPause() {
            runOnUiThread {
                isVideoPlaying = false
            }
        }

        @JavascriptInterface
        fun onSpatialTopReached() {
            runOnUiThread {
                moveFocusToTopBar()
            }
        }
    }

    // ── Netflix / Prime Style Top Bar Setup ──────────────────────────────────

    private fun setupTopBar() {
        btnTopBarSearch.setOnClickListener { showUniversalSearchDialog() }
        btnSourceHub.setOnClickListener { switchSource(SOURCE_HUB) }
        btnSourceFavorites.setOnClickListener { switchSource(SOURCE_FAVORITES) }
        btnSource9Anime.setOnClickListener { switchSource(SOURCE_9ANIME) }
        btnSourceGogoAnime.setOnClickListener { switchSource(SOURCE_GOGOANIME) }
        btnSourceSoloLatino.setOnClickListener { switchSource(SOURCE_SOLOLATINO) }
        btnSourceSoloLatinoHome.setOnClickListener { switchSource(SOURCE_SOLOLATINO_HOME) }
        btnSourceAnimeFlix.setOnClickListener { switchSource(SOURCE_ANIMEFLIX) }
        btnSourceAnimeYT.setOnClickListener { switchSource(SOURCE_ANIMEYT) }
        btnSourceJKAnime.setOnClickListener { switchSource(SOURCE_JKANIME) }

        btnTopBarCinema.setOnClickListener { toggleCinemaMode() }
        btnTopBarMode.setOnClickListener { toggleNavMode() }
        btnTopBarFavorite.setOnClickListener { extractAndSaveCurrentFavorite() }
        btnTopBarAccount.setOnClickListener { showAccountSettingsDialog() }
        btnTopBarReload.setOnClickListener {
            if (currentSource == SOURCE_FAVORITES || currentSource == SOURCE_HUB) {
                refreshFavoritesGrid()
            } else {
                webView.reload()
            }
        }

        updateTopBarUi()
    }

    private val topBarFocusOrder: List<View> by lazy {
        listOf(
            btnTopBarSearch,
            btnSourceHub, btnSourceFavorites,
            btnSource9Anime, btnSourceGogoAnime, btnSourceSoloLatino, btnSourceSoloLatinoHome,
            btnSourceAnimeFlix, btnSourceAnimeYT, btnSourceJKAnime,
            btnTopBarCinema, btnTopBarMode,
            btnTopBarFavorite, btnTopBarAccount, btnTopBarReload
        )
    }

    private fun updateTopBarUi() {
        val sources = listOf(
            Triple(btnSourceHub, SOURCE_HUB, "Inicio"),
            Triple(btnSourceFavorites, SOURCE_FAVORITES, "Mi Lista"),
            Triple(btnSource9Anime, SOURCE_9ANIME, "9Anime"),
            Triple(btnSourceGogoAnime, SOURCE_GOGOANIME, "GogoAnime"),
            Triple(btnSourceSoloLatino, SOURCE_SOLOLATINO, "SoloAnime"),
            Triple(btnSourceSoloLatinoHome, SOURCE_SOLOLATINO_HOME, "SoloStream"),
            Triple(btnSourceAnimeFlix, SOURCE_ANIMEFLIX, "AnimeFlix"),
            Triple(btnSourceAnimeYT, SOURCE_ANIMEYT, "AnimeYT"),
            Triple(btnSourceJKAnime, SOURCE_JKANIME, "JKAnime")
        )

        for ((btn, src, name) in sources) {
            val prefix = if (src == SOURCE_HUB) "🏠 " else if (src == SOURCE_FAVORITES) "⭐ " else ""
            if (currentSource == src) {
                btn.text = "● $prefix$name"
                btn.setBackgroundResource(R.drawable.bg_netflix_active_tab)
                btn.setTextColor(Color.WHITE)
            } else {
                btn.text = "○ $prefix$name"
                btn.setBackgroundResource(R.drawable.bg_netflix_tab)
                btn.setTextColor(when (src) {
                    SOURCE_HUB -> Color.parseColor("#00E5FF")
                    SOURCE_FAVORITES -> Color.parseColor("#FFD54F")
                    else -> Color.parseColor("#E0E0FF")
                })
            }
        }

        btnTopBarCinema.text = if (isCinemaMode) "🍿 Cinema: ON" else "🍿 Cinema"
        btnTopBarCinema.setTextColor(if (isCinemaMode) Color.parseColor("#FF5252") else Color.parseColor("#FFD54F"))

        btnTopBarMode.text = when (currentNavMode) {
            MODE_SPATIAL_CARDS -> "📺 Tarjetas (Netflix)"
            MODE_POINTER -> "🖱️ Puntero"
            else -> "📜 Scroll"
        }
        btnTopBarMode.setTextColor(when (currentNavMode) {
            MODE_SPATIAL_CARDS -> Color.parseColor("#00E5FF")
            MODE_POINTER -> Color.parseColor("#BB86FC")
            else -> Color.parseColor("#00E676")
        })
    }

    private fun switchSource(source: String) {
        if (source == currentSource) return
        currentSource = source
        currentDomScrollY = 0f
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit().putString(KEY_ACTIVE_SOURCE, source).apply()
        updateTopBarUi()

        if (source == SOURCE_FAVORITES || source == SOURCE_HUB) {
            webView.visibility = View.GONE
            virtualCursorView.visibility = View.GONE
            favoritesScreen.visibility = View.VISIBLE
            refreshFavoritesGrid()
            if (source == SOURCE_HUB) {
                favoritesScreen.scrollTo(0, 0)
                hubBannerSearch.requestFocus()
            } else {
                favoritesRecyclerView.requestFocus()
            }
        } else {
            favoritesScreen.visibility = View.GONE
            webView.visibility = View.VISIBLE
            if (isTv) {
                if (currentNavMode == MODE_SPATIAL_CARDS) {
                    virtualCursorView.visibility = View.GONE
                    virtualCursorView.isCursorVisible = false
                } else {
                    virtualCursorView.visibility = View.VISIBLE
                    virtualCursorView.isCursorVisible = true
                }
            }
            webView.loadUrl(urlForSource(source))
            handler.postDelayed({ moveFocusToPage() }, 200)
        }
    }

    private fun toggleCinemaMode() {
        isCinemaMode = !isCinemaMode
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(KEY_CINEMA_MODE, isCinemaMode).apply()
        updateTopBarUi()
        if (isCinemaMode) {
            topBar.visibility = View.GONE
            hudPlayerBar.visibility = View.GONE
            enableImmersiveMode()
            injectScript("""
                (function() {
                    document.body.classList.add('animetv-cinema-mode');
                    document.documentElement.classList.add('animetv-cinema-mode');
                    var p = document.querySelector('#main-player-wrap, #player-frame, .player-wrap, .wb_-playerarea, #player-section, #player');
                    if (p) {
                        try { p.scrollIntoView({ behavior: 'instant', block: 'start' }); } catch(e){}
                    }
                    document.querySelectorAll('iframe').forEach(function(f) {
                        try {
                            f.setAttribute('allowfullscreen', 'true');
                            f.setAttribute('allow', 'autoplay; fullscreen; picture-in-picture; encrypted-media');
                            f.contentWindow.postMessage({ type: 'animetv-cinema', enabled: true }, '*');
                        } catch(e) {}
                    });
                })();
            """.trimIndent())
            Toast.makeText(this, "🍿 Modo Cine Activado (Pulsa Atrás para salir)", Toast.LENGTH_SHORT).show()
        } else {
            topBar.visibility = View.VISIBLE
            enableImmersiveMode()
            injectScript("""
                (function() {
                    document.body.classList.remove('animetv-cinema-mode');
                    document.documentElement.classList.remove('animetv-cinema-mode');
                    document.querySelectorAll('iframe').forEach(function(f) {
                        try {
                            f.contentWindow.postMessage({ type: 'animetv-cinema', enabled: false }, '*');
                        } catch(e) {}
                    });
                })();
            """.trimIndent())
            Toast.makeText(this, "Vista Estándar", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleNavMode() {
        currentNavMode = when (currentNavMode) {
            MODE_SPATIAL_CARDS -> MODE_POINTER
            MODE_POINTER -> MODE_SCROLL
            else -> MODE_SPATIAL_CARDS
        }
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putInt(KEY_NAV_MODE, currentNavMode).apply()
        virtualCursorView.isDirectScrollMode = (currentNavMode == MODE_SCROLL)
        if (isTv) {
            if (currentNavMode == MODE_SPATIAL_CARDS) {
                virtualCursorView.visibility = View.GONE
                virtualCursorView.isCursorVisible = false
                injectScript("if (window.AnimeTvSpatialNav) window.AnimeTvSpatialNav.focusInitial();")
            } else {
                virtualCursorView.visibility = View.VISIBLE
                virtualCursorView.isCursorVisible = true
                injectScript("if (window.AnimeTvSpatialNav) window.AnimeTvSpatialNav.clearFocus();")
            }
        }
        updateTopBarUi()
        val modeName = when (currentNavMode) {
            MODE_SPATIAL_CARDS -> "📺 Navegación de Carátulas (Netflix / Prime)"
            MODE_POINTER -> "🖱️ Ratón Virtual"
            else -> "📜 Desplazamiento Directo"
        }
        Toast.makeText(this, modeName, Toast.LENGTH_SHORT).show()
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
            togglePlayerFullscreen()
            hideHudPlayerBar()
        }
        btnHudFavorite.setOnClickListener {
            extractAndSaveCurrentFavorite()
            showHudPlayerBarBriefly()
        }
    }

    private val hudFocusOrder: List<View> by lazy {
        listOf(btnHudRewind, btnHudPlayPause, btnHudForward, btnHudSkipIntro, btnHudNextEp, btnHudFullscreen, btnHudFavorite)
    }

    // ── Favorites ("Mi Lista") Screen Setup ──────────────────────────────────

    private fun setupFavoritesScreen() {
        val spanCount = if (isTv) 4 else 2
        favoritesRecyclerView.layoutManager = GridLayoutManager(this, spanCount)
        favoritesAdapter = FavoritesAdapter(
            items = mutableListOf(),
            onOpen = { fav ->
                favoritesScreen.visibility = View.GONE
                webView.visibility = View.VISIBLE
                if (isTv) virtualCursorView.visibility = View.VISIBLE
                currentSource = when {
                    fav.source.contains("9anime", ignoreCase = true) -> SOURCE_9ANIME
                    fav.source.contains("gogo", ignoreCase = true) -> SOURCE_GOGOANIME
                    fav.source.contains("solostream", ignoreCase = true) -> SOURCE_SOLOLATINO_HOME
                    fav.source.contains("solo", ignoreCase = true) -> SOURCE_SOLOLATINO
                    fav.source.contains("flix", ignoreCase = true) -> SOURCE_ANIMEFLIX
                    fav.source.contains("yt", ignoreCase = true) -> SOURCE_ANIMEYT
                    fav.source.contains("jk", ignoreCase = true) -> SOURCE_JKANIME
                    else -> SOURCE_9ANIME
                }
                updateTopBarUi()
                webView.loadUrl(fav.url)
                handler.postDelayed({ moveFocusToPage() }, 300)
            },
            onRemove = { fav ->
                FavoritesStore.remove(this, fav.url)
                Toast.makeText(this, "Eliminado de Mi Lista: ${fav.title}", Toast.LENGTH_SHORT).show()
                refreshFavoritesGrid()
            }
        )
        favoritesRecyclerView.adapter = favoritesAdapter
        refreshFavoritesGrid()
    }

    private fun refreshFavoritesGrid() {
        val items = FavoritesStore.loadAll(this)
        favoritesAdapter.submit(items)
        favoritesEmptyLayout.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun extractAndSaveCurrentFavorite(isLongPress: Boolean = false) {
        if (currentSource == SOURCE_FAVORITES || currentSource == SOURCE_HUB) {
            Toast.makeText(this, "Navega a un anime para guardarlo en Mi Lista", Toast.LENGTH_SHORT).show()
            return
        }

        if (isLongPress) {
            longPressArmed = true
        }

        val extractScript = """
            (function() {
                var title = "";
                var ogTitle = document.querySelector('meta[property="og:title"]');
                if (ogTitle && ogTitle.content) title = ogTitle.content;
                if (!title) {
                    var h1 = document.querySelector('h1, .film-name, .anime-title, .entry-title, .title, .name');
                    if (h1) title = h1.innerText.trim();
                }
                if (!title) title = document.title;
                title = title.replace(/\s*[-–|].*$/g, '').trim();

                var poster = "";
                var ogImg = document.querySelector('meta[property="og:image"]');
                if (ogImg && ogImg.content) poster = ogImg.content;
                if (!poster) {
                    var img = document.querySelector('.anime-poster img, .poster img, img[src*="cover"], img[src*="poster"], .film-poster img, .thumb img');
                    if (img) poster = img.src;
                }

                return JSON.stringify({
                    url: window.location.href,
                    title: title,
                    poster: poster
                });
            })();
        """.trimIndent()

        webView.evaluateJavascript(extractScript) { resultJson ->
            try {
                Log.d(TAG, "extractAndSaveCurrentFavorite raw: $resultJson")
                val rawStr = resultJson?.trim() ?: ""
                val cleanJson = if (rawStr.startsWith("\"") && rawStr.endsWith("\"")) {
                    try {
                        JSONTokener(rawStr).nextValue().toString()
                    } catch (e: Exception) {
                        rawStr.substring(1, rawStr.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
                    }
                } else rawStr

                Log.d(TAG, "extractAndSaveCurrentFavorite clean: $cleanJson")
                val obj = JSONObject(if (cleanJson.isNotEmpty()) cleanJson else "{}")
                val url = obj.optString("url", webView.url ?: "")
                val title = obj.optString("title", "Anime").ifEmpty { "Anime" }
                val poster = obj.optString("poster", "")
                val sourceName = when {
                    url.contains("9anime") -> "9Anime"
                    url.contains("gogoanime") -> "GogoAnime"
                    url.contains("sololatino") -> "SoloAnime"
                    url.contains("animeflix") -> "AnimeFlix"
                    url.contains("animeyt") -> "AnimeYT"
                    url.contains("jkanime") -> "JKAnime"
                    else -> "Anime"
                }

                val item = FavoriteItem(url = url, title = title, poster = poster, source = sourceName)
                val added = FavoritesStore.toggle(this, item)
                Log.d(TAG, "extractAndSaveCurrentFavorite toggle result: $added, total: ${FavoritesStore.loadAll(this).size}")
                if (added) {
                    Toast.makeText(this, "⭐ Guardado en Mi Lista: $title", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "❌ Eliminado de Mi Lista: $title", Toast.LENGTH_SHORT).show()
                }
                refreshFavoritesGrid()
            } catch (e: Exception) {
                Log.e(TAG, "Error saving favorite", e)
            }
        }
    }

    // ── Universal TV Search Dialog ───────────────────────────────────────────

    private fun showUniversalSearchDialog(defaultQuery: String = "") {
        val dialogView = layoutInflater.inflate(R.layout.dialog_universal_search, null)
        val editQuery = dialogView.findViewById<EditText>(R.id.editSearchQuery)
        val pillSoloAnime = dialogView.findViewById<TextView>(R.id.pillSearchSoloLatino)
        val pillSoloStream = dialogView.findViewById<TextView>(R.id.pillSearchSoloStream)
        val pill9Anime = dialogView.findViewById<TextView>(R.id.pillSearch9Anime)
        val pillGogoAnime = dialogView.findViewById<TextView>(R.id.pillSearchGogoAnime)
        val pillAnimeFlix = dialogView.findViewById<TextView>(R.id.pillSearchAnimeFlix)
        val pillAnimeYT = dialogView.findViewById<TextView>(R.id.pillSearchAnimeYT)
        val pillJKAnime = dialogView.findViewById<TextView>(R.id.pillSearchJKAnime)
        val btnCancel = dialogView.findViewById<TextView>(R.id.btnCancelSearch)
        val btnSearch = dialogView.findViewById<TextView>(R.id.btnExecuteSearch)

        var targetSource = when (currentSource) {
            SOURCE_SOLOLATINO, SOURCE_SOLOLATINO_HOME, SOURCE_9ANIME, SOURCE_GOGOANIME,
            SOURCE_ANIMEFLIX, SOURCE_ANIMEYT, SOURCE_JKANIME -> currentSource
            else -> SOURCE_SOLOLATINO
        }

        val pills = listOf(
            Pair(pillSoloAnime, SOURCE_SOLOLATINO),
            Pair(pillSoloStream, SOURCE_SOLOLATINO_HOME),
            Pair(pill9Anime, SOURCE_9ANIME),
            Pair(pillGogoAnime, SOURCE_GOGOANIME),
            Pair(pillAnimeFlix, SOURCE_ANIMEFLIX),
            Pair(pillAnimeYT, SOURCE_ANIMEYT),
            Pair(pillJKAnime, SOURCE_JKANIME)
        )

        fun updatePillStyles() {
            for ((pill, src) in pills) {
                if (src == targetSource) {
                    pill.setBackgroundResource(R.drawable.bg_netflix_active_tab)
                    pill.setTextColor(Color.WHITE)
                } else {
                    pill.setBackgroundResource(R.drawable.bg_netflix_tab)
                    pill.setTextColor(Color.parseColor("#E0E0FF"))
                }
            }
        }

        for ((pill, src) in pills) {
            pill.setOnClickListener {
                targetSource = src
                updatePillStyles()
            }
        }
        updatePillStyles()

        if (defaultQuery.isNotEmpty()) {
            editQuery.setText(defaultQuery)
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        fun executeSearch() {
            val q = editQuery.text.toString().trim()
            if (q.isEmpty()) {
                Toast.makeText(this, "Por favor escribe qué deseas buscar", Toast.LENGTH_SHORT).show()
                return
            }
            val encoded = Uri.encode(q)
            val searchUrl = when (targetSource) {
                SOURCE_SOLOLATINO -> "https://sololatino.net/animes?buscar=$encoded"
                SOURCE_SOLOLATINO_HOME -> "https://sololatino.net/buscar?q=$encoded"
                SOURCE_9ANIME -> "https://9anime.or.at/filter?keyword=$encoded"
                SOURCE_GOGOANIME -> "https://gogoanime.by/search.html?keyword=$encoded"
                SOURCE_ANIMEFLIX -> "https://animeflix.team/?s=$encoded"
                SOURCE_ANIMEYT -> "https://animeyt.cc/?s=$encoded"
                SOURCE_JKANIME -> "https://jkanime.net/buscar/$encoded/"
                else -> "https://sololatino.net/animes?buscar=$encoded"
            }

            currentSource = targetSource
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            prefs.edit().putString(KEY_ACTIVE_SOURCE, targetSource).apply()
            updateTopBarUi()

            favoritesScreen.visibility = View.GONE
            webView.visibility = View.VISIBLE
            if (isTv && currentNavMode != MODE_SPATIAL_CARDS) {
                virtualCursorView.visibility = View.VISIBLE
            }
            webView.loadUrl(searchUrl)
            dialog.dismiss()
            Toast.makeText(this, "🔍 Buscando: $q", Toast.LENGTH_SHORT).show()
            handler.postDelayed({ moveFocusToPage() }, 300)
        }

        editQuery.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH ||
                actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                executeSearch()
                true
            } else false
        }

        btnSearch.setOnClickListener { executeSearch() }
        btnCancel.setOnClickListener { dialog.dismiss() }

        dialog.show()
        editQuery.requestFocus()
    }

    // ── Master Account & Credentials Dialog ──────────────────────────────────

    private fun showAccountSettingsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_account_settings, null)
        val editUser = dialogView.findViewById<EditText>(R.id.editMasterUser)
        val editPass = dialogView.findViewById<EditText>(R.id.editMasterPass)
        val btnCancel = dialogView.findViewById<TextView>(R.id.btnCancelCredentials)
        val btnAutoFill = dialogView.findViewById<TextView>(R.id.btnAutoFillNow)
        val btnSave = dialogView.findViewById<TextView>(R.id.btnSaveCredentials)

        editUser.setText(AccountStore.getMasterUsername(this))
        editPass.setText(AccountStore.getMasterPassword(this))

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnSave.setOnClickListener {
            val u = editUser.text.toString().trim()
            val p = editPass.text.toString()
            AccountStore.saveMasterCredentials(this, u, p)
            Toast.makeText(this, "💾 Credenciales Maestras Guardadas", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        btnAutoFill.setOnClickListener {
            val u = editUser.text.toString().trim()
            val p = editPass.text.toString()
            if (u.isNotEmpty() && p.isNotEmpty()) {
                AccountStore.saveMasterCredentials(this, u, p)
            }
            autoFillCredentialsOnPage()
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun autoFillCredentialsOnPage() {
        val host = Uri.parse(webView.url ?: "").host?.lowercase() ?: ""
        val (user, pass) = AccountStore.getCredentialsForHost(this, host)
        if (user.isEmpty() || pass.isEmpty()) {
            Toast.makeText(this, "⚠️ Por favor ingresa tus credenciales primero", Toast.LENGTH_SHORT).show()
            showAccountSettingsDialog()
            return
        }

        val escapedUser = user.replace("'", "\\'")
        val escapedPass = pass.replace("'", "\\'")

        val autoFillJs = """
            (function() {
                var userSelectors = 'input[type="email"], input[type="text"][name*="user" i], input[type="text"][name*="email" i], input[type="text"][name*="login" i], input[name*="user" i], input[id*="user" i], input[id*="login" i], input[name*="email" i], input[id*="email" i]';
                var userInputs = document.querySelectorAll(userSelectors);
                var passInputs = document.querySelectorAll('input[type="password"]');
                var filled = false;

                if (userInputs.length > 0) {
                    var uInput = userInputs[0];
                    uInput.value = '$escapedUser';
                    uInput.dispatchEvent(new Event('input', { bubbles: true }));
                    uInput.dispatchEvent(new Event('change', { bubbles: true }));
                    filled = true;
                }
                if (passInputs.length > 0) {
                    var pInput = passInputs[0];
                    pInput.value = '$escapedPass';
                    pInput.dispatchEvent(new Event('input', { bubbles: true }));
                    pInput.dispatchEvent(new Event('change', { bubbles: true }));
                    filled = true;
                }
                return filled;
            })();
        """.trimIndent()

        webView.evaluateJavascript(autoFillJs) { result ->
            if (result == "true") {
                Toast.makeText(this, "🔑 Formulario completado con Cuenta Maestra", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "ℹ️ No se detectó formulario de login en esta pantalla", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun autoFillCredentialsSilently() {
        val host = Uri.parse(webView.url ?: "").host?.lowercase() ?: ""
        val (user, pass) = AccountStore.getCredentialsForHost(this, host)
        if (user.isEmpty() || pass.isEmpty()) return

        val escapedUser = user.replace("'", "\\'")
        val escapedPass = pass.replace("'", "\\'")

        val silentJs = """
            (function() {
                var passInput = document.querySelector('input[type="password"]');
                if (!passInput) return false;
                var userSelectors = 'input[type="email"], input[type="text"][name*="user" i], input[type="text"][name*="email" i], input[type="text"][name*="login" i], input[name*="user" i], input[id*="user" i], input[id*="login" i], input[name*="email" i], input[id*="email" i]';
                var uInput = document.querySelector(userSelectors);
                if (uInput && !uInput.value) {
                    uInput.value = '$escapedUser';
                    uInput.dispatchEvent(new Event('input', { bubbles: true }));
                    uInput.dispatchEvent(new Event('change', { bubbles: true }));
                }
                if (passInput && !passInput.value) {
                    passInput.value = '$escapedPass';
                    passInput.dispatchEvent(new Event('input', { bubbles: true }));
                    passInput.dispatchEvent(new Event('change', { bubbles: true }));
                }
                return true;
            })();
        """.trimIndent()

        webView.evaluateJavascript(silentJs, null)
    }

    private fun showSeekBadge(text: String) {
        hudSeekBadge.text = text
        hudSeekBadge.visibility = View.VISIBLE
        hudSeekBadge.alpha = 1f
        handler.removeCallbacks(hideSeekBadgeRunnable)
        handler.postDelayed(hideSeekBadgeRunnable, 1200)
    }

    private fun sendPlayerCommand(action: String, seconds: Int = 0) {
        val js = when (action) {
            "toggle" -> "if (window.AnimeTvPlayer) window.AnimeTvPlayer.togglePlay(); else if (window.__animePlayerBridge) window.__animePlayerBridge.toggle();"
            "play" -> "if (window.AnimeTvPlayer) window.AnimeTvPlayer.togglePlay(); else if (window.__animePlayerBridge) window.__animePlayerBridge.play();"
            "pause" -> "if (window.AnimeTvPlayer) window.AnimeTvPlayer.togglePlay(); else if (window.__animePlayerBridge) window.__animePlayerBridge.pause();"
            "seek" -> "if (window.AnimeTvPlayer) window.AnimeTvPlayer.seek($seconds); else if (window.__animePlayerBridge) window.__animePlayerBridge.seek($seconds);"
            "skipIntro" -> "if (window.AnimeTvPlayer) window.AnimeTvPlayer.seek(85); else if (window.__animePlayerBridge) window.__animePlayerBridge.skipIntro();"
            "nextEpisode" -> "window.__animePlayerBridge && window.__animePlayerBridge.nextEpisode();"
            else -> ""
        }
        if (js.isNotEmpty()) {
            webView.evaluateJavascript(js, null)
        }
    }

    private fun showHudPlayerBarBriefly() {
        showHudPlayerBar()
        handler.removeCallbacks(hideHudRunnable)
        handler.postDelayed(hideHudRunnable, HUD_AUTO_HIDE_DELAY_MS)
    }

    private fun showHudPlayerBar() {
        hudPlayerBar.visibility = View.VISIBLE
        hudPlayerBar.animate()
            .alpha(1f)
            .setDuration(200)
            .withEndAction {
                btnHudPlayPause.requestFocus()
            }
            .start()
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

    private fun togglePlayerFullscreen() {
        if (customView != null) {
            customViewCallback?.onCustomViewHidden()
            customView = null
            isPlayerFullscreen = false
            return
        }

        isPlayerFullscreen = !isPlayerFullscreen

        if (isPlayerFullscreen) {
            topBar.visibility = View.GONE
            hudPlayerBar.visibility = View.GONE
            enableImmersiveMode()
            injectScript(
                """
                (function() {
                    document.body.classList.add('animetv-fullscreen-player');
                    document.documentElement.classList.add('animetv-fullscreen-player');
                    var p = document.querySelector('#main-player-wrap, #player-frame, .player-wrap, .wb_-playerarea, #player-section, #player');
                    if (p) {
                        try { p.scrollIntoView({ behavior: 'instant', block: 'start' }); } catch(e){}
                    }
                    document.querySelectorAll('iframe').forEach(function(f) {
                        try {
                            f.setAttribute('allowfullscreen', 'true');
                            f.setAttribute('allow', 'autoplay; fullscreen; picture-in-picture; encrypted-media');
                            f.contentWindow.postMessage({ type: 'animetv-fullscreen', enabled: true }, '*');
                        } catch(e) {}
                    });
                    var vid = document.querySelector('video');
                    if (vid && vid.requestFullscreen) {
                        try { vid.requestFullscreen().catch(function(){}); } catch(e){}
                    }
                })();
                """.trimIndent()
            )
            Toast.makeText(this, "📺 Pantalla Completa (Doble clic OK o Atrás para salir)", Toast.LENGTH_SHORT).show()
        } else {
            topBar.visibility = View.VISIBLE
            enableImmersiveMode()
            injectScript(
                """
                (function() {
                    document.body.classList.remove('animetv-fullscreen-player');
                    document.documentElement.classList.remove('animetv-fullscreen-player');
                    document.querySelectorAll('iframe').forEach(function(f) {
                        try {
                            f.contentWindow.postMessage({ type: 'animetv-fullscreen', enabled: false }, '*');
                        } catch(e) {}
                    });
                    try {
                        if (document.exitFullscreen) document.exitFullscreen().catch(function(){});
                    } catch(e){}
                })();
                """.trimIndent()
            )
            Toast.makeText(this, "Vista Estándar", Toast.LENGTH_SHORT).show()
        }
    }

    // ── TV D-Pad Focus & Key Navigation ──────────────────────────────────────

    private fun getButtonForSource(source: String): View = when (source) {
        SOURCE_HUB -> btnSourceHub
        SOURCE_FAVORITES -> btnSourceFavorites
        SOURCE_9ANIME -> btnSource9Anime
        SOURCE_GOGOANIME -> btnSourceGogoAnime
        SOURCE_SOLOLATINO -> btnSourceSoloLatino
        SOURCE_SOLOLATINO_HOME -> btnSourceSoloLatinoHome
        SOURCE_ANIMEFLIX -> btnSourceAnimeFlix
        SOURCE_ANIMEYT -> btnSourceAnimeYT
        SOURCE_JKANIME -> btnSourceJKAnime
        else -> btnSourceHub
    }

    private fun moveFocusToTopBar() {
        virtualCursorView.clearHeldKeys()
        virtualCursorView.isCursorVisible = false
        val active = getButtonForSource(currentSource)
        active.requestFocus()
    }

    private fun moveFocusToPage() {
        if (currentSource == SOURCE_HUB) {
            hubBannerSearch.requestFocus()
            return
        }
        if (currentSource == SOURCE_FAVORITES) {
            favoritesRecyclerView.requestFocus()
            return
        }
        webView.requestFocus()
        if (isTv) {
            if (currentNavMode == MODE_SPATIAL_CARDS) {
                virtualCursorView.visibility = View.GONE
                virtualCursorView.isCursorVisible = false
                injectScript("if (window.AnimeTvSpatialNav) window.AnimeTvSpatialNav.focusInitial();")
            } else {
                virtualCursorView.visibility = View.VISIBLE
                virtualCursorView.isCursorVisible = true
                val density = resources.displayMetrics.density
                if (virtualCursorView.cursorY < 70f * density) {
                    virtualCursorView.setCursorPosition(
                        virtualCursorView.cursorX,
                        90f * density
                    )
                }
            }
        }
    }

    private fun isFocusInFavoritesTopRow(): Boolean {
        if (favoritesScreen.visibility != View.VISIBLE) return false
        val focused = currentFocus ?: return true
        if (focused == hubBannerSearch ||
            focused == hubCardSoloAnime || focused == hubCardSoloStream ||
            focused == hubCard9Anime || focused == hubCardGogoAnime ||
            focused == hubCardAnimeFlix || focused == hubCardAnimeYT ||
            focused == hubCardJKAnime) {
            return true
        }
        val layoutManager = favoritesRecyclerView.layoutManager as? GridLayoutManager ?: return true
        val position = favoritesRecyclerView.getChildAdapterPosition(focused)
        return position == RecyclerView.NO_POSITION || position < layoutManager.spanCount
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

        // When Favorites Grid has focus:
        if (favoritesScreen.visibility == View.VISIBLE) {
            if (action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP && isFocusInFavoritesTopRow()) {
                moveFocusToTopBar()
                return true
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

            // When in Fullscreen or when video is actively playing, LEFT/RIGHT acts as Seek +/-10s
            if (isPlayerFullscreen || customView != null || isVideoPlaying) {
                if (action == KeyEvent.ACTION_DOWN) {
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            sendPlayerCommand("seek", -10)
                            showSeekBadge("⏪ -10s")
                            return true
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            sendPlayerCommand("seek", 10)
                            showSeekBadge("⏩ +10s")
                            return true
                        }
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            showHudPlayerBarBriefly()
                            return true
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            hideHudPlayerBar()
                            return true
                        }
                    }
                }
            }

            // Mode 0: SPATIAL CARDS (Netflix / Prime Video TV card-by-card snap)
            if (currentNavMode == MODE_SPATIAL_CARDS) {
                if (isDpadDirection) {
                    if (action == KeyEvent.ACTION_DOWN) {
                        val dirStr = when (keyCode) {
                            KeyEvent.KEYCODE_DPAD_RIGHT -> "right"
                            KeyEvent.KEYCODE_DPAD_LEFT -> "left"
                            KeyEvent.KEYCODE_DPAD_DOWN -> "down"
                            KeyEvent.KEYCODE_DPAD_UP -> "up"
                            else -> ""
                        }
                        if (dirStr.isNotEmpty()) {
                            injectScript("if (window.AnimeTvSpatialNav) window.AnimeTvSpatialNav.navigate('$dirStr');")
                        }
                    }
                    return true
                }

                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                    if (currentSource == SOURCE_FAVORITES || currentSource == SOURCE_HUB) {
                        return super.dispatchKeyEvent(event)
                    }

                    if (action == KeyEvent.ACTION_DOWN) {
                        if (event.repeatCount == 0) {
                            longPressArmed = false
                            handler.removeCallbacks(longPressFavoriteRunnable)
                            handler.postDelayed(longPressFavoriteRunnable, LONG_PRESS_FAVORITE_MS)
                        }
                        return true
                    }

                    if (action == KeyEvent.ACTION_UP) {
                        handler.removeCallbacks(longPressFavoriteRunnable)
                        if (longPressArmed) {
                            longPressArmed = false
                            return true
                        }

                        val now = System.currentTimeMillis()
                        if (now - lastOkPressTime < DOUBLE_CLICK_TIMEOUT_MS) {
                            lastOkPressTime = 0L
                            handler.removeCallbacks(pendingOkClickRunnable)
                            togglePlayerFullscreen()
                            return true
                        } else {
                            lastOkPressTime = now
                            handler.removeCallbacks(pendingOkClickRunnable)
                            // In Spatial mode, single click activates the selected card or toggles video if in fullscreen
                            if (isPlayerFullscreen || customView != null) {
                                sendPlayerCommand("toggle")
                                showSeekBadge("⏯ Play/Pausa")
                            } else {
                                injectScript("if (window.AnimeTvSpatialNav) window.AnimeTvSpatialNav.click();")
                            }
                            return true
                        }
                    }
                    return true
                }
            }

            // Fallback: Mode 1 (Virtual Pointer) and Mode 2 (Direct Scroll)
            if (isDpadDirection) {
                // In Scroll Mode, if page is already at the very top, DPAD_UP transitions focus to top bar
                if (action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP && currentNavMode == MODE_SCROLL && currentDomScrollY <= 15f) {
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
                if (currentSource == SOURCE_FAVORITES || currentSource == SOURCE_HUB) {
                    return super.dispatchKeyEvent(event)
                }

                if (action == KeyEvent.ACTION_DOWN) {
                    if (event.repeatCount == 0) {
                        longPressArmed = false
                        handler.removeCallbacks(longPressFavoriteRunnable)
                        handler.postDelayed(longPressFavoriteRunnable, LONG_PRESS_FAVORITE_MS)
                    }
                    return true
                }

                if (action == KeyEvent.ACTION_UP) {
                    handler.removeCallbacks(longPressFavoriteRunnable)
                    if (longPressArmed) {
                        longPressArmed = false
                        return true
                    }

                    val now = System.currentTimeMillis()
                    if (now - lastOkPressTime < DOUBLE_CLICK_TIMEOUT_MS) {
                        lastOkPressTime = 0L
                        handler.removeCallbacks(pendingOkClickRunnable)
                        togglePlayerFullscreen()
                        return true
                    } else {
                        lastOkPressTime = now
                        handler.removeCallbacks(pendingOkClickRunnable)
                        handler.postDelayed(pendingOkClickRunnable, DOUBLE_CLICK_TIMEOUT_MS)
                        return true
                    }
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
                if (isPlayerFullscreen) {
                    togglePlayerFullscreen()
                    return
                }
                if (isCinemaMode) {
                    toggleCinemaMode()
                    return
                }
                if (hudPlayerBar.visibility == View.VISIBLE) {
                    hideHudPlayerBar()
                    return
                }
                if (currentSource == SOURCE_FAVORITES || currentSource == SOURCE_HUB) {
                    switchSource(SOURCE_9ANIME)
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
