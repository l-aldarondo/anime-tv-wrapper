package com.example.animetv

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.example.animetv.tv.VirtualCursorView
import org.json.JSONObject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension

class MainActivity : AppCompatActivity() {

    companion object {
        const val PREFS_NAME = "anime_tv_prefs"
        const val KEY_ACTIVE_SOURCE = "active_source"
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
        private const val DOUBLE_OK_INTERVAL_MS = 400L

        private const val UBLOCK_EXTENSION_ID = "uBlock0@raymondhill.net"
        private const val UBLOCK_ASSET_PATH = "resource://android/assets/ublock_origin/"
        private const val PATCHES_EXTENSION_ID = "patches@animetv.app"
        private const val PATCHES_ASSET_PATH = "resource://android/assets/page_patches/"
        private const val BRIDGE_NATIVE_APP_ID = "anime_tv_bridge"

        // Main-frame navigation lock: the viewport must stay on a recognized anime provider.
        // uBlock Origin + the page-patches content script handle ad/overlay suppression inside
        // iframes now, so this list only needs to police the TOP-LEVEL document.
        private val ALLOWED_MAIN_HOSTS = setOf(
            "9anime.or.at",
            "gogoanime.by",
            "anitaku.to",
            "gogoanime3.co",
            "sololatino.net",
            "animeflix.team",
            "9animes.me.uk",
            "animeyt.cc",
            "jkanime.net"
        )
    }

    private var isTv = false

    private lateinit var geckoView: GeckoView
    private lateinit var runtime: GeckoRuntime
    private lateinit var geckoSession: GeckoSession
    private lateinit var pageLoadingBar: ProgressBar
    private lateinit var virtualCursorView: VirtualCursorView
    private lateinit var osdTopBar: View
    private lateinit var osdControlsGuide: View
    private lateinit var txtNavModeBadge: TextView
    private lateinit var btnFullscreen: TextView

    // Hidden Sidebar UI elements
    private lateinit var sidebarDrawer: LinearLayout
    private lateinit var btnSidebarFullscreen: TextView
    private lateinit var btnSidebarMode: TextView
    private lateinit var btnSource9Anime: TextView
    private lateinit var btnSourceGogoAnime: TextView
    private lateinit var btnSourceSoloLatino: TextView
    private lateinit var btnSourceSoloLatinoHome: TextView
    private lateinit var btnSourceAnimeFlix: TextView
    private lateinit var btnSourceAnimeYT: TextView
    private lateinit var btnSourceJKAnime: TextView
    private lateinit var btnSidebarHome: TextView
    private lateinit var btnSidebarReload: TextView
    private lateinit var btnSidebarClose: TextView

    private var isSidebarOpen = false
    private var currentSource = SOURCE_9ANIME
    private var currentNavMode = MODE_SCROLL
    private var isPlayerFullscreen = false
    private var canGoBackFlag = false
    private var lastBackPressTime = 0L
    private var lastOkUpTime = 0L

    // Native <-> page bridge (video-play-detected, double-tap, player commands, fullscreen
    // toggle) - see app/src/main/assets/page_patches/. GeckoView has no evaluateJavascript()
    // equivalent; this WebExtension native-messaging port replaces it entirely.
    private var bridgePort: WebExtension.Port? = null

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

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        currentSource = prefs.getString(KEY_ACTIVE_SOURCE, SOURCE_9ANIME) ?: SOURCE_9ANIME

        val uiModeManager = getSystemService(android.content.Context.UI_MODE_SERVICE) as? android.app.UiModeManager
        isTv = (uiModeManager?.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION)
            || !packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN)

        requestedOrientation = if (isTv) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableImmersiveMode()

        setContentView(R.layout.activity_main)

        initViews()
        setupBackPressedHandler()

        if (isTv) {
            scheduleGuideDismiss()
        } else {
            // On phones with touch screens, hide the virtual D-Pad cursor and TV remote hints
            virtualCursorView.visibility = View.GONE
            osdControlsGuide.visibility = View.GONE
            txtNavModeBadge.visibility = View.GONE
        }

        bootGeckoViewEngine()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        intent.dataString?.let { newUrl ->
            if (::geckoSession.isInitialized) geckoSession.loadUri(newUrl)
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
        geckoView = findViewById(R.id.webView)
        pageLoadingBar = findViewById(R.id.pageLoadingBar)
        virtualCursorView = findViewById(R.id.virtualCursorView)
        virtualCursorView.targetView = geckoView
        virtualCursorView.isDirectScrollMode = (currentNavMode == MODE_SCROLL)
        osdTopBar = findViewById(R.id.osdTopBar)
        osdControlsGuide = findViewById(R.id.osdControlsGuide)
        txtNavModeBadge = findViewById(R.id.txtNavModeBadge)
        btnFullscreen = findViewById(R.id.btnFullscreen)

        // Sidebar references
        sidebarDrawer = findViewById(R.id.sidebarDrawer)
        btnSidebarFullscreen = findViewById(R.id.btnSidebarFullscreen)
        btnSidebarMode = findViewById(R.id.btnSidebarMode)
        btnSource9Anime = findViewById(R.id.btnSource9Anime)
        btnSourceGogoAnime = findViewById(R.id.btnSourceGogoAnime)
        btnSourceSoloLatino = findViewById(R.id.btnSourceSoloLatino)
        btnSourceSoloLatinoHome = findViewById(R.id.btnSourceSoloLatinoHome)
        btnSourceAnimeFlix = findViewById(R.id.btnSourceAnimeFlix)
        btnSourceAnimeYT = findViewById(R.id.btnSourceAnimeYT)
        btnSourceJKAnime = findViewById(R.id.btnSourceJKAnime)
        btnSidebarHome = findViewById(R.id.btnSidebarHome)
        btnSidebarReload = findViewById(R.id.btnSidebarReload)
        btnSidebarClose = findViewById(R.id.btnSidebarClose)

        btnFullscreen.setOnClickListener {
            togglePlayerFullscreen()
        }

        setupSidebar()
    }

    // ── GeckoView engine boot ────────────────────────────────────────────────

    @SuppressLint("SetTextI18n")
    private fun bootGeckoViewEngine() {
        // getDefault(), not create() - create() throws if a runtime already exists in this
        // process; getDefault() is the safe choice for an Activity that may be recreated.
        runtime = GeckoRuntime.getDefault(this)

        // Don't open the session/load the start URL until both extensions below have settled
        // (installed or failed) - otherwise the very first page load could race ahead of
        // uBlock Origin and land completely unprotected.
        var pendingInstalls = 2
        val onInstallSettled = {
            pendingInstalls--
            if (pendingInstalls == 0) openGeckoSession()
        }

        runtime.webExtensionController
            .ensureBuiltIn(UBLOCK_ASSET_PATH, UBLOCK_EXTENSION_ID)
            .accept({ onInstallSettled() }, { onInstallSettled() })

        runtime.webExtensionController
            .ensureBuiltIn(PATCHES_ASSET_PATH, PATCHES_EXTENSION_ID)
            .accept(
                { extension ->
                    extension?.setMessageDelegate(bridgeMessageDelegate, BRIDGE_NATIVE_APP_ID)
                    onInstallSettled()
                },
                { onInstallSettled() }
            )
    }

    private fun openGeckoSession() {
        val settingsBuilder = GeckoSessionSettings.Builder()
            .allowJavascript(true)
            .useTrackingProtection(true)
        if (isTv) {
            // Desktop/TV Chrome UA for a proper 16:9 widescreen layout instead of a mobile one.
            settingsBuilder.userAgentOverride(
                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36 TV/GoogleTV"
            )
        }

        geckoSession = GeckoSession(settingsBuilder.build())
        attachGeckoSessionDelegates(geckoSession)
        geckoSession.open(runtime)
        geckoView.setSession(geckoSession)
        virtualCursorView.geckoSession = geckoSession

        val startUrl = intent?.dataString ?: urlForSource(currentSource)
        geckoSession.loadUri(startUrl)
    }

    private fun attachGeckoSessionDelegates(session: GeckoSession) {
        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
                handleFullscreenChange(fullScreen)
            }
        }

        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                runOnUiThread { pageLoadingBar.visibility = View.GONE }
            }
            override fun onPageStop(session: GeckoSession, success: Boolean) {
                runOnUiThread { pageLoadingBar.visibility = View.GONE }
            }
        }

        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                canGoBackFlag = canGoBack
            }

            // Fires for top-level/main-frame navigations only (subframe/iframe navigation -
            // e.g. an embed provider's own internal redirects - goes through
            // onSubframeLoadRequest instead, which is deliberately left unhandled here: uBlock
            // Origin's own network-level filtering now polices unwanted iframe loads, so the
            // old WebView-era "allowed video host" allowlist gate for subframes is redundant).
            override fun onLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest
            ): GeckoResult<AllowOrDeny> {
                val uri = try { Uri.parse(request.uri) } catch (e: Exception) { null }
                val scheme = uri?.scheme?.lowercase() ?: ""
                if (scheme != "http" && scheme != "https") {
                    return GeckoResult.fromValue(AllowOrDeny.DENY)
                }
                val host = uri?.host?.lowercase() ?: ""
                val isAllowed = ALLOWED_MAIN_HOSTS.any { host == it || host.endsWith(".$it") }
                return GeckoResult.fromValue(if (isAllowed) AllowOrDeny.ALLOW else AllowOrDeny.DENY)
            }

            // Not overriding this returns null, which GeckoView treats as "deny the popup" -
            // window.open()/target="_blank" requests just fail silently. The page-patches
            // content script's own window.open() override (belt and suspenders) covers any
            // popup attempt that fires before this would even be consulted.
            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? = null
        }

        // GeckoView blocks audible autoplay by default unless the load had a genuine user
        // gesture. Safe to grant unconditionally here since this app only ever loads a small,
        // fixed set of first-party anime sites, never arbitrary third-party content.
        session.permissionDelegate = object : GeckoSession.PermissionDelegate {
            override fun onContentPermissionRequest(
                session: GeckoSession,
                perm: GeckoSession.PermissionDelegate.ContentPermission
            ): GeckoResult<Int> {
                return if (perm.permission == GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE ||
                    perm.permission == GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE
                ) {
                    GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW)
                } else {
                    GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_PROMPT)
                }
            }
        }

        // Unlike WebView, GeckoView shows nothing at all for a plain HTML <select> unless the
        // embedder implements this - onChoicePrompt is a `default` (no-op) method on the
        // interface, so the app compiled fine without it but every dropdown (e.g. this site's
        // season/"temporada" picker) silently did nothing when tapped.
        session.promptDelegate = object : GeckoSession.PromptDelegate {
            override fun onChoicePrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.ChoicePrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                val choices = prompt.choices
                val labels = choices.map { it.label }.toTypedArray()
                val isMultiple = prompt.type == GeckoSession.PromptDelegate.ChoicePrompt.Type.MULTIPLE

                val builder = android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle(prompt.title)
                    .setOnCancelListener { result.complete(prompt.dismiss()) }

                if (isMultiple) {
                    val checked = choices.map { it.selected }.toBooleanArray()
                    builder.setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
                        .setPositiveButton(android.R.string.ok) { d, _ ->
                            result.complete(prompt.confirm(choices.filterIndexed { i, _ -> checked[i] }.toTypedArray()))
                            d.dismiss()
                        }
                } else {
                    builder.setItems(labels) { d, which ->
                        result.complete(prompt.confirm(choices[which]))
                        d.dismiss()
                    }
                }

                val dialog = builder.create()
                // A standard AlertDialog's item list is a focusable, D-pad-navigable ListView by
                // default, but nothing is pre-focused on show - seed focus onto the first row so
                // a TV remote can immediately navigate it instead of appearing inert.
                dialog.setOnShowListener { dialog.listView?.requestFocus() }
                dialog.show()
                return result
            }
        }
    }

    // ── Native <-> page WebExtension bridge ──────────────────────────────────

    private val bridgeMessageDelegate = object : WebExtension.MessageDelegate {
        override fun onConnect(port: WebExtension.Port) {
            bridgePort = port
            port.setDelegate(bridgePortDelegate)
        }
    }

    private val bridgePortDelegate = object : WebExtension.PortDelegate {
        override fun onPortMessage(message: Any, port: WebExtension.Port) {
            val json = when (message) {
                is JSONObject -> message
                is String -> try { JSONObject(message) } catch (e: Exception) { null }
                else -> null
            } ?: return
            when (json.optString("type")) {
                "anime-video-play" -> runOnUiThread {
                    if (!isPlayerFullscreen) setPlayerFullscreen(true)
                }
                "anime-doubletap" -> runOnUiThread {
                    togglePlayerFullscreen()
                }
            }
        }
        override fun onDisconnect(port: WebExtension.Port) {
            if (bridgePort === port) bridgePort = null
        }
    }

    private fun sendPlayerCommand(action: String, seconds: Int = 0) {
        bridgePort?.postMessage(JSONObject().apply {
            put("type", "anime-player-command")
            put("payload", JSONObject().apply {
                put("action", action)
                put("seconds", seconds)
            })
        })
    }

    private fun toggleVideoPlayback() = sendPlayerCommand("toggle")
    private fun playVideo() = sendPlayerCommand("play")
    private fun pauseVideo() {
        sendPlayerCommand("pause")
        Toast.makeText(this, "⏸️ Paused", Toast.LENGTH_SHORT).show()
    }
    private fun seekVideo(seconds: Int) = sendPlayerCommand("seek", seconds)
    private fun triggerNextEpisode() {
        sendPlayerCommand("next-episode")
        Toast.makeText(this, "⏭️ Next Episode...", Toast.LENGTH_SHORT).show()
    }
    private fun triggerPreviousEpisode() {
        sendPlayerCommand("prev-episode")
        Toast.makeText(this, "⏮️ Previous Episode...", Toast.LENGTH_SHORT).show()
    }

    // ── Sidebar ──────────────────────────────────────────────────────────────

    private fun setupSidebar() {
        btnSidebarFullscreen.setOnClickListener {
            togglePlayerFullscreen()
            closeSidebar()
        }

        btnSidebarMode.setOnClickListener {
            toggleNavigationMode()
            updateSidebarUi()
        }

        btnSource9Anime.setOnClickListener { switchSource(SOURCE_9ANIME) }
        btnSourceGogoAnime.setOnClickListener { switchSource(SOURCE_GOGOANIME) }
        btnSourceSoloLatino.setOnClickListener { switchSource(SOURCE_SOLOLATINO) }
        btnSourceSoloLatinoHome.setOnClickListener { switchSource(SOURCE_SOLOLATINO_HOME) }
        btnSourceAnimeFlix.setOnClickListener { switchSource(SOURCE_ANIMEFLIX) }
        btnSourceAnimeYT.setOnClickListener { switchSource(SOURCE_ANIMEYT) }
        btnSourceJKAnime.setOnClickListener { switchSource(SOURCE_JKANIME) }

        btnSidebarHome.setOnClickListener {
            geckoSession.loadUri(urlForSource(currentSource))
            closeSidebar()
        }

        btnSidebarReload.setOnClickListener {
            geckoSession.reload()
            closeSidebar()
        }

        btnSidebarClose.setOnClickListener {
            closeSidebar()
        }

        // Virtual Cursor edge triggers - works identically in Pointer and Scroll mode (the
        // reticle moves in both, see VirtualCursorView).
        virtualCursorView.onLeftEdgeTrigger = {
            runOnUiThread {
                if (!isSidebarOpen && !isPlayerFullscreen) {
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

    // Sources first (this is what a user actually wants quick access to), settings/shortcuts
    // last - see activity_main.xml. Also the D-pad UP/DOWN focus-navigation order.
    private val sidebarFocusOrder: List<View> by lazy {
        listOf(
            btnSource9Anime, btnSourceGogoAnime, btnSourceSoloLatino, btnSourceSoloLatinoHome, btnSourceAnimeFlix, btnSourceAnimeYT, btnSourceJKAnime,
            btnSidebarFullscreen, btnSidebarMode, btnSidebarHome, btnSidebarReload, btnSidebarClose
        )
    }

    private fun moveSidebarFocus(forward: Boolean) {
        val order = sidebarFocusOrder
        val currentIndex = order.indexOfFirst { it.isFocused }
        val nextIndex = when {
            currentIndex == -1 -> 0
            forward -> (currentIndex + 1).coerceAtMost(order.size - 1)
            else -> (currentIndex - 1).coerceAtLeast(0)
        }
        order[nextIndex].requestFocus()
    }

    private fun openSidebar() {
        if (isSidebarOpen || isPlayerFullscreen) return
        isSidebarOpen = true
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

        virtualCursorView.isCursorVisible = false
        sidebarFocusOrder.firstOrNull()?.requestFocus()
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
                }
            }
            .start()

        geckoView.requestFocus()
        if (isTv && currentNavMode == MODE_POINTER) {
            virtualCursorView.isCursorVisible = true
        }
    }

    private fun toggleSidebar() {
        if (isSidebarOpen) closeSidebar() else openSidebar()
    }

    private fun updateSidebarUi() {
        btnSidebarFullscreen.text = if (isPlayerFullscreen) "⛶  Exit Fullscreen" else "⛶  Enter Fullscreen"
        btnSidebarFullscreen.setTextColor(
            if (isPlayerFullscreen) Color.parseColor("#FF5252") else Color.parseColor("#FFD600")
        )

        btnSidebarMode.text = if (currentNavMode == MODE_POINTER) "🖱️  Mode: Pointer" else "📜  Mode: Scroll"
        btnSidebarMode.setTextColor(
            if (currentNavMode == MODE_POINTER) Color.parseColor("#E0AAFF") else Color.parseColor("#00E676")
        )

        val sources = listOf(
            Triple(btnSource9Anime, SOURCE_9ANIME, "9Anime"),
            Triple(btnSourceGogoAnime, SOURCE_GOGOANIME, "GogoAnime"),
            Triple(btnSourceSoloLatino, SOURCE_SOLOLATINO, "SoloAnime ES"),
            Triple(btnSourceSoloLatinoHome, SOURCE_SOLOLATINO_HOME, "SoloStream ES"),
            Triple(btnSourceAnimeFlix, SOURCE_ANIMEFLIX, "AnimeFlix"),
            Triple(btnSourceAnimeYT, SOURCE_ANIMEYT, "AnimeYT (Español)"),
            Triple(btnSourceJKAnime, SOURCE_JKANIME, "JKAnime (Español)")
        )

        for ((btn, src, name) in sources) {
            if (currentSource == src) {
                btn.text = "●  $name (Active)"
                btn.setBackgroundResource(R.drawable.bg_sidebar_active_source)
                btn.setTextColor(Color.WHITE)
            } else {
                btn.text = "○  $name"
                btn.setBackgroundResource(R.drawable.bg_sidebar_item)
                btn.setTextColor(Color.parseColor("#F0F0FF"))
            }
        }
    }

    private fun switchSource(source: String) {
        currentSource = source
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit().putString(KEY_ACTIVE_SOURCE, source).apply()
        updateSidebarUi()

        val label = when (source) {
            SOURCE_GOGOANIME -> "GogoAnime"
            SOURCE_SOLOLATINO -> "SoloAnime ES"
            SOURCE_SOLOLATINO_HOME -> "SoloStream ES"
            SOURCE_ANIMEFLIX -> "AnimeFlix"
            SOURCE_ANIMEYT -> "AnimeYT"
            SOURCE_JKANIME -> "JKAnime"
            else -> "9Anime"
        }
        Toast.makeText(this, "🎌 Loading $label...", Toast.LENGTH_SHORT).show()
        geckoSession.loadUri(urlForSource(source))
        closeSidebar()
    }

    private fun setupBackPressedHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isSidebarOpen) {
                    closeSidebar()
                } else if (isPlayerFullscreen) {
                    geckoSession.exitFullScreen()
                } else if (canGoBackFlag) {
                    geckoSession.goBack()
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

    // ── Fullscreen ───────────────────────────────────────────────────────────

    private fun handleFullscreenChange(fullScreen: Boolean) {
        isPlayerFullscreen = fullScreen
        if (!isTv) {
            requestedOrientation = if (fullScreen) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
        if (fullScreen) {
            closeSidebar()
            virtualCursorView.visibility = View.GONE
            osdTopBar.visibility = View.GONE
            osdControlsGuide.visibility = View.GONE
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            btnFullscreen.text = "✖ Exit Fullscreen"
            btnFullscreen.setTextColor(Color.parseColor("#FF5252"))
        } else {
            virtualCursorView.visibility = if (currentNavMode == MODE_POINTER && isTv) View.VISIBLE else View.GONE
            btnFullscreen.text = "⛶ Fullscreen"
            btnFullscreen.setTextColor(Color.parseColor("#FFD600"))
        }
        enableImmersiveMode()
    }

    // Requests the page to enter/exit fullscreen via the bridge - the actual state change (and
    // all UI/orientation updates above) only happens once ContentDelegate.onFullScreen confirms
    // it really did, so this is a request, not an immediate state flip.
    fun setPlayerFullscreen(enabled: Boolean) {
        bridgePort?.postMessage(JSONObject().apply {
            put("type", "anime-set-fullscreen")
            put("enabled", enabled)
        })
        if (enabled) {
            Toast.makeText(this, "⛶ Fullscreen Active (Press BACK to exit)", Toast.LENGTH_SHORT).show()
            handler.postDelayed({ playVideo() }, 300L)
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
        // The reticle stays visible in both modes - Scroll Mode still needs it as a click target
        // (LEFT/RIGHT glide it sideways, OK clicks), UP/DOWN just also scrolls the page.
        virtualCursorView.isCursorVisible = true
        if (currentNavMode == MODE_POINTER) {
            txtNavModeBadge.text = "🖱️ Pointer Mode"
            txtNavModeBadge.setTextColor(Color.parseColor("#E0AAFF"))
            Toast.makeText(this, "Pointer Mode: D-Pad glides cursor, OK clicks", Toast.LENGTH_SHORT).show()
        } else {
            txtNavModeBadge.text = "📜 Scroll Mode"
            txtNavModeBadge.setTextColor(Color.parseColor("#80D8FF"))
            Toast.makeText(this, "Scroll Mode: UP/DOWN scrolls, LEFT/RIGHT moves the click reticle, OK clicks", Toast.LENGTH_SHORT).show()
        }
        scheduleGuideDismiss()
    }

    /**
     * Intercept and handle TV Remote D-Pad, Trackpad, and Media Keys.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val isDown = event.action == KeyEvent.ACTION_DOWN
        val isUp = event.action == KeyEvent.ACTION_UP

        // ── FULLSCREEN VIDEO REMOTE CONTROLS ────────────────────────────────
        if (isPlayerFullscreen) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BUTTON_B -> {
                    if (isUp) geckoSession.exitFullScreen()
                    return true
                }

                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    if (isUp) pauseVideo()
                    return true
                }

                KeyEvent.KEYCODE_MEDIA_PLAY -> {
                    if (isUp) playVideo()
                    return true
                }

                // Single OK toggles play/pause; a second OK within DOUBLE_OK_INTERVAL_MS instead
                // exits fullscreen (suppressing the toggle, so it doesn't also flicker play/pause).
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER,
                KeyEvent.KEYCODE_BUTTON_A,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    if (isUp) {
                        val now = System.currentTimeMillis()
                        val isDoubleOk = now - lastOkUpTime < DOUBLE_OK_INTERVAL_MS
                        lastOkUpTime = now
                        if (isDoubleOk) geckoSession.exitFullScreen() else toggleVideoPlayback()
                    }
                    return true
                }

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

                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (isUp) {
                        seekVideo(85)
                        Toast.makeText(this, "⏩ Skipped Intro (+85s)", Toast.LENGTH_SHORT).show()
                    }
                    return true
                }

                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (isUp) {
                        seekVideo(-30)
                        Toast.makeText(this, "⏪ -30s", Toast.LENGTH_SHORT).show()
                    }
                    return true
                }

                KeyEvent.KEYCODE_MEDIA_NEXT -> {
                    if (isUp) triggerNextEpisode()
                    return true
                }

                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                    if (isUp) triggerPreviousEpisode()
                    return true
                }
            }
            return super.dispatchKeyEvent(event)
        }

        // ── BROWSING MODE REMOTE CONTROLS ──────────────────────────────────
        when (event.keyCode) {
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_INFO,
            KeyEvent.KEYCODE_GUIDE,
            KeyEvent.KEYCODE_SETTINGS,
            KeyEvent.KEYCODE_BUTTON_Y -> {
                if (isUp) toggleSidebar()
                return true
            }

            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (isUp) toggleVideoPlayback()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                if (isUp) playVideo()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (isUp) pauseVideo()
                return true
            }

            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                // While the sidebar is open, UP/DOWN move real logical focus between its items
                // instead of gliding the cursor - see openSidebar(). Since the cursor never moves
                // in here, RIGHT directly closes the drawer, standing in for the "push it past
                // the right edge" gesture that closes it everywhere else.
                if (isSidebarOpen) {
                    if (isDown) {
                        when (event.keyCode) {
                            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN ->
                                moveSidebarFocus(forward = event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN)
                            KeyEvent.KEYCODE_DPAD_RIGHT -> closeSidebar()
                            else -> {}
                        }
                    }
                    return true
                }
                if (isDown) {
                    scheduleGuideDismiss()
                    virtualCursorView.visibility = View.VISIBLE
                    virtualCursorView.isCursorVisible = true
                }
                virtualCursorView.onDpadKey(event.keyCode, isDown)
                return true
            }

            // D-Pad OK / Center Click - single press selects/clicks at the reticle position; a
            // second press within DOUBLE_OK_INTERVAL_MS instead toggles fullscreen (suppressing
            // the click, so it doesn't also fire on whatever's under the reticle).
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BUTTON_A -> {
                if (isSidebarOpen) {
                    if (isUp) {
                        currentFocus?.takeIf { sidebarFocusOrder.contains(it) }?.performClick()
                    }
                    return true
                }
                if (isUp) {
                    val now = System.currentTimeMillis()
                    val isDoubleOk = now - lastOkUpTime < DOUBLE_OK_INTERVAL_MS
                    lastOkUpTime = now
                    if (isDoubleOk) {
                        togglePlayerFullscreen()
                    } else {
                        scheduleGuideDismiss()
                        virtualCursorView.dispatchClick(geckoView)
                    }
                }
                return true
            }

            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BUTTON_B -> {
                if (isUp) {
                    if (isSidebarOpen) {
                        closeSidebar()
                        return true
                    } else if (canGoBackFlag) {
                        geckoSession.goBack()
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

                    if (isSidebarOpen && ev.rawX > 310f * density) {
                        closeSidebar()
                        return true
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    // Trigger sidebar open on a rightward swipe starting right at the screen's
                    // left edge - this is the ONLY way to open it on touch; there is no
                    // persistent button.
                    if (!isSwipeConsumed && !isSidebarOpen && !isPlayerFullscreen) {
                        val deltaX = ev.rawX - touchStartX
                        val deltaY = Math.abs(ev.rawY - touchStartY)
                        if (touchStartX < 24f * density && deltaX > 20f * density && deltaY < 80f * density) {
                            isSwipeConsumed = true
                            openSidebar()
                            return true
                        }
                    }
                    // Close as soon as the finger drags off the panel, rather than requiring a
                    // separate tap outside afterwards.
                    if (isSidebarOpen && ev.rawX > 310f * density) {
                        closeSidebar()
                        return true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isSwipeConsumed = false
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onResume() {
        super.onResume()
        if (::geckoSession.isInitialized) geckoSession.setActive(true)
        enableImmersiveMode()
    }

    override fun onPause() {
        super.onPause()
        if (::geckoSession.isInitialized) geckoSession.setActive(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::geckoSession.isInitialized) geckoSession.close()
    }
}
