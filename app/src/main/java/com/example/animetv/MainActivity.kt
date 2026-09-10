package com.example.animetv

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.example.animetv.tv.VirtualCursorView
import org.json.JSONObject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
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
        // How long a just-pressed top-bar tab stays focused (glowing) after switchSource() fires,
        // before focus hands off to the page. Without this, moveFocusToPage() ran in the same
        // frame as the OK press, so a source switch that visibly worked on the first press looked
        // like it needed a second one - the first press's own visual confirmation had already
        // vanished before the user could see it, and a hasty second OK landed on the page instead
        // of the bar (see dispatchKeyEvent's topBar.hasFocus() branch).
        private const val SOURCE_SWITCH_FOCUS_DELAY_MS = 200L

        // Reverse-engineering kill switches used to isolate a playback-breaking layer (see
        // bootGeckoViewEngine/openGeckoSession) - both OFF here confirmed neither uBlock Origin
        // nor useTrackingProtection was the cause. The real culprit was GeckoView's cookie
        // partitioning (Total Cookie Protection), fixed separately below via cookieBehavior.
        // Left in place, defaulted to false, in case another site needs the same isolation test.
        private const val DEBUG_DISABLE_UBLOCK = false
        private const val DEBUG_DISABLE_TRACKING_PROTECTION = false
        // Also tested and ruled out: disabling our OWN page_patches content script entirely (the
        // "Prueba con Servidor 1" symptom on SoloLatino's Premium tab persisted identically with
        // it off) - see commit history for the fuller isolation-test writeup.
        private const val DEBUG_DISABLE_PAGE_PATCHES = false
        // Isolation test: with both extensions above ruled out (identical failure with neither
        // active), the next candidate is GeckoView's default UA - it reports as a mobile Android
        // Gecko browser (isTv's desktop-Chrome override doesn't apply on a phone), which some
        // embed providers may serve a different/less-tested code path for versus the desktop
        // Firefox UA the user's working comparison browsers all sent.
        private const val DEBUG_FORCE_DESKTOP_UA = false

        private const val UBLOCK_EXTENSION_ID = "uBlock0@raymondhill.net"
        private const val UBLOCK_ASSET_PATH = "resource://android/assets/ublock_origin/"
        private const val PATCHES_EXTENSION_ID = "patches@animetv.app"
        private const val PATCHES_ASSET_PATH = "resource://android/assets/page_patches/"
        private const val BRIDGE_NATIVE_APP_ID = "anime_tv_bridge"

        // GeckoRuntime.getDefault() can't take a GeckoRuntimeSettings (it always builds one with
        // no options), and GeckoRuntime.create(context, settings) throws "Failed to initialize
        // GeckoRuntime" if called a second time in the same process (there's only ever one real
        // native Gecko runtime per process) - so this process-wide cache lets bootGeckoViewEngine()
        // call create() exactly once (with remoteDebuggingEnabled, for `about:debugging` USB
        // inspection of the live session) and safely reuse that same instance across any Activity
        // recreation, the same way getDefault()'s own internal null-check does.
        @Volatile private var cachedRuntime: GeckoRuntime? = null

        // Main-frame navigation lock: the viewport must stay on a recognized anime provider.
        // uBlock Origin + the page-patches content script handle ad/overlay suppression inside
        // iframes now, so this list only needs to police the TOP-LEVEL document.
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
            "morencius.com",
            "audinifer.com",
            "cloudwindow-route.com",
            "minochinos.com",
            "ghbrisk.com",
            "bysedikamoum.com",
            "voe.sx",
            "gofile.io",
            "embed69.org",
            "xupalace.org",
            "mega.nz",
            "mega.co.nz",
            "mega.io",
            "ok.ru",
            "vk.com",
            "snapcdn.top",
            "f7hyg4q.org",
            "desu.sh",
            "mytsumi.com",
            "bysesukior.com"
        )
    }

    private var isTv = false

    private lateinit var geckoView: GeckoView
    private lateinit var runtime: GeckoRuntime
    private lateinit var geckoSession: GeckoSession
    private lateinit var pageLoadingBar: ProgressBar
    private lateinit var virtualCursorView: VirtualCursorView
    private lateinit var osdControlsGuide: View

    // Top Bar (sources + settings gear) - a PERSISTENT fixed header, always visible during
    // normal browsing (see the activity_main.xml LinearLayout wrapper: the bar takes its own
    // fixed row, GeckoView fills the rest). It has no open/close state of its own - the old
    // slide-down-overlay design got replaced after real testing showed the same "can't get back
    // out" problem the original left sidebar had. Only the settings gear's own popup
    // (settingsPanel) is still a real modal with open/close semantics.
    private lateinit var topBar: LinearLayout
    private lateinit var btnSource9Anime: TextView
    private lateinit var btnSourceGogoAnime: TextView
    private lateinit var btnSourceSoloLatino: TextView
    private lateinit var btnSourceSoloLatinoHome: TextView
    private lateinit var btnSourceAnimeFlix: TextView
    private lateinit var btnSourceAnimeYT: TextView
    private lateinit var btnSourceJKAnime: TextView
    private lateinit var btnTopBarSettings: TextView
    private lateinit var settingsPanel: LinearLayout
    private lateinit var btnSettingsFullscreen: TextView
    private lateinit var btnSettingsMode: TextView
    private lateinit var btnSettingsHome: TextView
    private lateinit var btnSettingsReload: TextView

    private var isSettingsPanelOpen = false
    // Real GeckoView scroll position (GeckoSession.ScrollDelegate, see attachGeckoSessionDelegates)
    // - not a JS/content-script round trip. Used to decide whether pressing UP at the top of the
    // page should move focus into the top bar instead of trying to scroll further.
    private var pageScrollY = 0
    private val pageIsAtTop: Boolean get() = pageScrollY <= 4
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
        osdControlsGuide = findViewById(R.id.osdControlsGuide)

        // Top bar + settings panel references
        topBar = findViewById(R.id.topBar)
        btnSource9Anime = findViewById(R.id.btnSource9Anime)
        btnSourceGogoAnime = findViewById(R.id.btnSourceGogoAnime)
        btnSourceSoloLatino = findViewById(R.id.btnSourceSoloLatino)
        btnSourceSoloLatinoHome = findViewById(R.id.btnSourceSoloLatinoHome)
        btnSourceAnimeFlix = findViewById(R.id.btnSourceAnimeFlix)
        btnSourceAnimeYT = findViewById(R.id.btnSourceAnimeYT)
        btnSourceJKAnime = findViewById(R.id.btnSourceJKAnime)
        btnTopBarSettings = findViewById(R.id.btnTopBarSettings)
        settingsPanel = findViewById(R.id.settingsPanel)
        btnSettingsFullscreen = findViewById(R.id.btnSettingsFullscreen)
        btnSettingsMode = findViewById(R.id.btnSettingsMode)
        btnSettingsHome = findViewById(R.id.btnSettingsHome)
        btnSettingsReload = findViewById(R.id.btnSettingsReload)

        // settingsPanel's XML marginTop is only a same-frame fallback (see layout comment) - keep
        // it pinned to topBar's real measured height so a future topBar padding/text-size change
        // (e.g. for TV legibility) can't leave a gap or overlap.
        topBar.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            val newHeight = bottom - top
            if (newHeight > 0 && newHeight != oldBottom - oldTop) {
                val gapPx = (8 * resources.displayMetrics.density).toInt()
                (settingsPanel.layoutParams as FrameLayout.LayoutParams).topMargin = newHeight + gapPx
                settingsPanel.requestLayout()
            }
        }

        setupTopBar()
    }

    // ── GeckoView engine boot ────────────────────────────────────────────────

    @SuppressLint("SetTextI18n")
    private fun bootGeckoViewEngine() {
        // See cachedRuntime's own comment: create() (not getDefault(), which can't accept custom
        // settings) exactly once per process, cached for any later Activity recreation.
        runtime = cachedRuntime ?: GeckoRuntime.create(
            this,
            GeckoRuntimeSettings.Builder()
                .remoteDebuggingEnabled(true)
                .build()
        ).also { cachedRuntime = it }

        // GeckoView defaults to cookieBehavior ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS ("Total
        // Cookie Protection" / dynamic First-Party Isolation) - it gives every third-party origin
        // a separate cookie/storage jar PER top-level site it's embedded in, instead of one
        // shared jar. Confirmed on-device (via a controlled test with uBlock Origin AND
        // useTrackingProtection both fully disabled, which made no difference) that this - not
        // either adblock layer - is what breaks playback on providers whose embed depends on a
        // normal, unpartitioned third-party session (logcat showed the exact
        // "Partitioned cookie or storage access was provided to ... third-party context" line for
        // player.pelisserieshoy.com right where it fails). This is a single-purpose media wrapper
        // around a small, fixed set of anime-site embeds, not a general browser, so there's no
        // privacy upside to isolating them from themselves - ACCEPT_ALL matches how a real
        // desktop browser with no special third-party cookie restrictions behaves for these sites.
        runtime.settings.contentBlocking.cookieBehavior = ContentBlocking.CookieBehavior.ACCEPT_ALL

        // Don't open the session/load the start URL until both extensions below have settled
        // (installed/uninstalled or failed) - otherwise the very first page load could race ahead
        // of uBlock Origin and land completely unprotected.
        var pendingInstalls = 2
        val onInstallSettled = {
            pendingInstalls--
            if (pendingInstalls == 0) openGeckoSession()
        }

        // ensureBuiltIn() installs into the profile's *persistent* extension storage - once
        // installed, it stays installed (and active) across app restarts, since adb install -r
        // preserves app data. Simply not calling ensureBuiltIn() on a later run when a DEBUG_*
        // flag flips to true does NOT disable an extension installed by an earlier run - this was
        // discovered when live network traces kept showing "Blocked By uBlock Origin" with
        // DEBUG_DISABLE_UBLOCK = true. An isolation test needs an ACTUAL uninstall.
        fun settleExtension(
            disabled: Boolean,
            assetPath: String,
            extensionId: String,
            onReady: ((WebExtension) -> Unit)? = null
        ) {
            if (disabled) {
                runtime.webExtensionController.list().accept({ extensions ->
                    val existing = extensions?.find { it.id == extensionId }
                    if (existing != null) {
                        runtime.webExtensionController.uninstall(existing)
                            .accept({ onInstallSettled() }, { onInstallSettled() })
                    } else {
                        onInstallSettled()
                    }
                }, { onInstallSettled() })
            } else {
                runtime.webExtensionController.ensureBuiltIn(assetPath, extensionId)
                    .accept(
                        { extension -> extension?.let(onReady ?: {}); onInstallSettled() },
                        { onInstallSettled() }
                    )
            }
        }

        settleExtension(DEBUG_DISABLE_UBLOCK, UBLOCK_ASSET_PATH, UBLOCK_EXTENSION_ID)
        settleExtension(DEBUG_DISABLE_PAGE_PATCHES, PATCHES_ASSET_PATH, PATCHES_EXTENSION_ID) { extension ->
            extension.setMessageDelegate(bridgeMessageDelegate, BRIDGE_NATIVE_APP_ID)
        }
    }

    private fun openGeckoSession() {
        val settingsBuilder = GeckoSessionSettings.Builder()
            .allowJavascript(true)
            .useTrackingProtection(!DEBUG_DISABLE_TRACKING_PROTECTION)
        if (isTv) {
            // Desktop/TV Chrome UA for a proper 16:9 widescreen layout instead of a mobile one -
            // the UA string alone isn't enough, GeckoView still defaults to a mobile CSS viewport
            // and "request desktop site"-style content adaptations unless these two are also set,
            // which left sites seeing a "desktop Chrome" UA on a narrow mobile-width viewport.
            settingsBuilder.userAgentOverride(
                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36 TV/GoogleTV"
            )
            settingsBuilder.userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_DESKTOP)
            settingsBuilder.viewportMode(GeckoSessionSettings.VIEWPORT_MODE_DESKTOP)
        } else if (DEBUG_FORCE_DESKTOP_UA) {
            settingsBuilder.userAgentOverride(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:153.0) Gecko/20100101 Firefox/153.0"
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
                runOnUiThread {
                    pageLoadingBar.visibility = View.VISIBLE
                    pageScrollY = 0
                }
            }
            override fun onPageStop(session: GeckoSession, success: Boolean) {
                runOnUiThread { pageLoadingBar.visibility = View.GONE }
            }
        }

        // Real scroll position from the compositor - used to decide whether UP at the page's
        // content should move focus into the persistent top bar (see pageIsAtTop, dispatchKeyEvent).
        session.scrollDelegate = object : GeckoSession.ScrollDelegate {
            override fun onScrollChanged(session: GeckoSession, scrollX: Int, scrollY: Int) {
                runOnUiThread { pageScrollY = scrollY }
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
                if (scheme == "about" || scheme == "data" || scheme == "blob" || scheme == "resource" || scheme == "moz-extension") {
                    return GeckoResult.fromValue(AllowOrDeny.ALLOW)
                }
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
                    if (!isPlayerFullscreen && currentSource != SOURCE_SOLOLATINO && currentSource != SOURCE_SOLOLATINO_HOME) {
                        setPlayerFullscreen(true)
                    }
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

    // ── Top Bar ──────────────────────────────────────────────────────────────
    // A PERSISTENT fixed header (see activity_main.xml's LinearLayout wrapper: the bar has its
    // own permanent row, GeckoView fills the rest) - it has no open/close state at all. The
    // original design was a slide-down overlay toggled by MENU, but on-device use surfaced the
    // exact same "focus gets in and can't get back out" problem the old left sidebar had, so it
    // got replaced with something that behaves like an ordinary TV app header (YouTube TV/
    // Netflix-style): always there, reached by moving focus UP out of the page content once it's
    // scrolled to the top (see pageIsAtTop, dispatchKeyEvent), and left by moving DOWN back into
    // the page. It's hidden only during actual fullscreen video playback (see
    // handleFullscreenChange) to give the video the full screen.
    //
    // The one thing about the old sidebar that actually worked reliably - its D-pad focus-list
    // idiom (indexOfFirst{it.isFocused} + requestFocus() + performClick(), see moveFocusList()
    // below) - carries over unchanged, just parameterized over two different lists (the bar's
    // tabs, and the settings popup's items) instead of one.

    private fun setupTopBar() {
        btnSource9Anime.setOnClickListener { switchSource(SOURCE_9ANIME) }
        btnSourceGogoAnime.setOnClickListener { switchSource(SOURCE_GOGOANIME) }
        btnSourceSoloLatino.setOnClickListener { switchSource(SOURCE_SOLOLATINO) }
        btnSourceSoloLatinoHome.setOnClickListener { switchSource(SOURCE_SOLOLATINO_HOME) }
        btnSourceAnimeFlix.setOnClickListener { switchSource(SOURCE_ANIMEFLIX) }
        btnSourceAnimeYT.setOnClickListener { switchSource(SOURCE_ANIMEYT) }
        btnSourceJKAnime.setOnClickListener { switchSource(SOURCE_JKANIME) }

        btnTopBarSettings.setOnClickListener { openSettingsPanel() }

        btnSettingsFullscreen.setOnClickListener {
            togglePlayerFullscreen()
            closeSettingsPanel()
        }

        btnSettingsMode.setOnClickListener {
            toggleNavigationMode()
            updateSettingsPanelUi()
        }

        btnSettingsHome.setOnClickListener {
            geckoSession.loadUri(urlForSource(currentSource))
            closeSettingsPanel()
            moveFocusToPage()
        }

        btnSettingsReload.setOnClickListener {
            geckoSession.reload()
            closeSettingsPanel()
            moveFocusToPage()
        }

        updateTopBarUi()
        updateSettingsPanelUi()
    }

    // Sources in traversal order, left to right, then the settings gear - the D-pad LEFT/RIGHT
    // focus-navigation order while focus is in the bar (see moveFocusList()).
    private val topBarFocusOrder: List<View> by lazy {
        listOf(
            btnSource9Anime, btnSourceGogoAnime, btnSourceSoloLatino, btnSourceSoloLatinoHome,
            btnSourceAnimeFlix, btnSourceAnimeYT, btnSourceJKAnime, btnTopBarSettings
        )
    }

    // The settings popup's own D-pad UP/DOWN focus-navigation order.
    private val settingsPanelFocusOrder: List<View> by lazy {
        listOf(btnSettingsFullscreen, btnSettingsMode, btnSettingsHome, btnSettingsReload)
    }

    // Used by moveFocusToTopBar()'s fallback so landing in the bar with nothing already focused
    // (the common case - UP-at-top or MENU from the page) lands on the CURRENT source's tab
    // (matching the ● highlight updateTopBarUi() already renders) instead of always the first tab.
    private val sourceButtonMap: Map<String, View> by lazy {
        mapOf(
            SOURCE_9ANIME to btnSource9Anime,
            SOURCE_GOGOANIME to btnSourceGogoAnime,
            SOURCE_SOLOLATINO to btnSourceSoloLatino,
            SOURCE_SOLOLATINO_HOME to btnSourceSoloLatinoHome,
            SOURCE_ANIMEFLIX to btnSourceAnimeFlix,
            SOURCE_ANIMEYT to btnSourceAnimeYT,
            SOURCE_JKANIME to btnSourceJKAnime
        )
    }

    private fun moveFocusList(order: List<View>, forward: Boolean) {
        val currentIndex = order.indexOfFirst { it.isFocused }
        val nextIndex = when {
            currentIndex == -1 -> 0
            forward -> (currentIndex + 1).coerceAtMost(order.size - 1)
            else -> (currentIndex - 1).coerceAtLeast(0)
        }
        order[nextIndex].requestFocus()
    }

    // Moves real Android focus into the persistent bar - from the page (UP at the top of scroll,
    // or MENU as a direct shortcut from anywhere) or back from the settings popup.
    private fun moveFocusToTopBar() {
        if (isPlayerFullscreen) return
        // A stale timestamp left over from before this transition must never let an unrelated
        // later OK press in the bar coincidentally read as a "double OK" and toggle fullscreen.
        lastOkUpTime = 0L
        virtualCursorView.isCursorVisible = false
        // A direction key held down at the moment focus jumps to the bar (e.g. UP, right as
        // pageIsAtTop trips) can have its eventual key-up swallowed by this same transition -
        // onDpadKey(..., false) is the only other place that clears held state, so force it here
        // too, or the page could keep silently auto-scrolling/gliding after focus has moved on.
        virtualCursorView.clearHeldKeys()
        val target = topBarFocusOrder.firstOrNull { it.isFocused }
            ?: sourceButtonMap[currentSource]
            ?: topBarFocusOrder.firstOrNull()
        target?.requestFocus()
    }

    // Moves real Android focus back to the page content - the bar itself is never hidden by this,
    // it just stops holding focus (see the class-level comment above).
    private fun moveFocusToPage() {
        lastOkUpTime = 0L
        closeSettingsPanel()
        geckoView.requestFocus()
        if (isTv) {
            virtualCursorView.isCursorVisible = true
        }
    }

    // Opened only from the bar's gear, and only ever closes back to the gear (never straight to
    // the page) - a single unambiguous nesting order, so dispatchKeyEvent never has to guess which
    // layer a BACK/DOWN/LEFT press should unwind first.
    private fun openSettingsPanel() {
        if (isSettingsPanelOpen) return
        isSettingsPanelOpen = true
        settingsPanel.visibility = View.VISIBLE
        settingsPanelFocusOrder.firstOrNull()?.requestFocus()
    }

    private fun closeSettingsPanel() {
        if (!isSettingsPanelOpen) return
        isSettingsPanelOpen = false
        settingsPanel.visibility = View.GONE
        btnTopBarSettings.requestFocus()
    }

    private fun updateTopBarUi() {
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
                btn.text = "●  $name"
                btn.setBackgroundResource(R.drawable.bg_topbar_active_source)
                btn.setTextColor(Color.WHITE)
            } else {
                btn.text = "○  $name"
                btn.setBackgroundResource(R.drawable.bg_topbar_item)
                btn.setTextColor(Color.parseColor("#F0F0FF"))
            }
        }
    }

    private fun updateSettingsPanelUi() {
        btnSettingsFullscreen.text = if (isPlayerFullscreen) "⛶  Exit Fullscreen" else "⛶  Enter Fullscreen"
        btnSettingsFullscreen.setTextColor(
            if (isPlayerFullscreen) Color.parseColor("#FF5252") else Color.parseColor("#FFD600")
        )

        btnSettingsMode.text = if (currentNavMode == MODE_POINTER) "🖱️  Mode: Pointer" else "📜  Mode: Scroll"
        btnSettingsMode.setTextColor(
            if (currentNavMode == MODE_POINTER) Color.parseColor("#E0AAFF") else Color.parseColor("#00E676")
        )
    }

    private fun switchSource(source: String) {
        if (source == currentSource) return
        currentSource = source
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit().putString(KEY_ACTIVE_SOURCE, source).apply()
        updateTopBarUi()

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
        pageLoadingBar.visibility = View.VISIBLE
        geckoSession.loadUri(urlForSource(source))
        // Keep the just-pressed tab focused (glowing) for a beat so the press has visible
        // confirmation before focus - and the reticle - jump away to the page; onPageStart/
        // onPageStop (attachGeckoSessionDelegates) flip pageLoadingBar back off once the new page
        // actually starts/finishes loading, independent of this timer.
        topBar.postDelayed({ moveFocusToPage() }, SOURCE_SWITCH_FOCUS_DELAY_MS)
    }

    private fun setupBackPressedHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isSettingsPanelOpen) {
                    closeSettingsPanel()
                } else if (topBar.hasFocus()) {
                    moveFocusToPage()
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
            moveFocusToPage()
            // The bar is a persistent header during normal browsing, but fullscreen video should
            // get the whole screen - this is the one case it actually gets hidden.
            topBar.visibility = View.GONE
            virtualCursorView.visibility = View.GONE
            osdControlsGuide.visibility = View.GONE
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            topBar.visibility = View.VISIBLE
            virtualCursorView.visibility = if (currentNavMode == MODE_POINTER && isTv) View.VISIBLE else View.GONE
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
        // Apply our own chrome (topBar/cursor visibility, orientation) immediately rather than
        // waiting for ContentDelegate.onFullScreen to confirm the page's real Fullscreen API
        // request succeeded. That confirmation depends on the browser granting "transient user
        // activation" to a call that only reaches the page asynchronously (native key event ->
        // bridgePort -> content script), which some sources' cross-origin player iframes don't
        // reliably get - on those, the message still arrives and CAN toggle the CSS-based
        // expansion, but the native Fullscreen API call can silently fail, and our own UI was
        // gated on that same confirmation, so double-OK looked like it did nothing at all.
        // handleFullscreenChange is idempotent, so a later onFullScreen callback re-confirming
        // (or correcting) this is harmless.
        handleFullscreenChange(enabled)
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
            Toast.makeText(this, "Pointer Mode: D-Pad glides cursor, OK clicks", Toast.LENGTH_SHORT).show()
        } else {
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
            // The bar has no open/close state (see the class comment above setupTopBar()) - MENU
            // is just a direct shortcut into/out of it from anywhere, on top of the UP-at-top-of-
            // page route below.
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_INFO,
            KeyEvent.KEYCODE_GUIDE,
            KeyEvent.KEYCODE_SETTINGS,
            KeyEvent.KEYCODE_BUTTON_Y -> {
                if (isUp) {
                    if (topBar.hasFocus()) moveFocusToPage() else moveFocusToTopBar()
                }
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
                // Settings popup open: UP/DOWN move focus within it, LEFT backs out to the gear -
                // this inner layer never routes straight to the page (see openSettingsPanel()).
                if (isSettingsPanelOpen) {
                    if (isDown) {
                        when (event.keyCode) {
                            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN ->
                                moveFocusList(settingsPanelFocusOrder, forward = event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN)
                            KeyEvent.KEYCODE_DPAD_LEFT -> closeSettingsPanel()
                            else -> {}
                        }
                    }
                    return true
                }
                // Focus is in the persistent bar (no popup): LEFT/RIGHT move focus across
                // tabs+gear, DOWN moves focus back into the page - the bar itself is never hidden
                // by this, it just stops holding focus (see the class comment above setupTopBar()).
                if (topBar.hasFocus()) {
                    if (isDown) {
                        when (event.keyCode) {
                            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT ->
                                moveFocusList(topBarFocusOrder, forward = event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
                            KeyEvent.KEYCODE_DPAD_DOWN -> moveFocusToPage()
                            else -> {}
                        }
                    }
                    return true
                }
                // Focus is on the page: UP normally scrolls/glides the reticle like any other
                // direction, EXCEPT once the page is already scrolled to the top (pageIsAtTop,
                // tracked via GeckoSession.ScrollDelegate - a real scroll position, not a guess) -
                // at that point there's nowhere further up to scroll, so UP instead moves focus
                // into the bar sitting right above the content, the same way it would on any
                // ordinary TV app header.
                if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP && isDown && pageIsAtTop) {
                    moveFocusToTopBar()
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
                if (isSettingsPanelOpen) {
                    if (isUp) {
                        currentFocus?.takeIf { settingsPanelFocusOrder.contains(it) }?.performClick()
                    }
                    return true
                }
                if (topBar.hasFocus()) {
                    if (isUp) {
                        currentFocus?.takeIf { topBarFocusOrder.contains(it) }?.performClick()
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
                    if (isSettingsPanelOpen) {
                        closeSettingsPanel()
                        return true
                    } else if (topBar.hasFocus()) {
                        moveFocusToPage()
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
