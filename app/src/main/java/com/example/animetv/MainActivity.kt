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
        const val TARGET_URL = "https://9anime.or.at/"
        const val MODE_POINTER = 0
        const val MODE_SCROLL = 1
        private const val BACK_PRESS_INTERVAL = 2000L
    }

    private var isTv = false

    private lateinit var webView: WebView
    private lateinit var videoContainer: FrameLayout
    private lateinit var pageLoadingBar: ProgressBar
    private lateinit var virtualCursorView: VirtualCursorView
    private lateinit var osdTopBar: View
    private lateinit var osdControlsGuide: View
    private lateinit var txtAdBlockBadge: TextView
    private lateinit var txtNavModeBadge: TextView
    private lateinit var btnFullscreen: TextView

    private lateinit var webChromeClient: AnimeWebChromeClient
    private lateinit var webViewClient: AnimeWebViewClient

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

        // Load starting URL
        val startUrl = intent?.dataString ?: TARGET_URL
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

        btnFullscreen.setOnClickListener {
            togglePlayerFullscreen()
        }
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
                if (webChromeClient.isFullscreen) {
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
            virtualCursorView.visibility = View.GONE
            osdTopBar.visibility = View.GONE
            osdControlsGuide.visibility = View.GONE
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            enableImmersiveMode()
        } else {
            virtualCursorView.visibility = if (currentNavMode == MODE_POINTER && isTv) View.VISIBLE else View.GONE
            osdTopBar.visibility = View.VISIBLE
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

                // Play / Pause toggle
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER,
                KeyEvent.KEYCODE_BUTTON_A,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_MEDIA_PLAY,
                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
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
            // Mode toggle button (Menu, Info, Guide, Settings, or Gamepad Y)
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_INFO,
            KeyEvent.KEYCODE_GUIDE,
            KeyEvent.KEYCODE_SETTINGS,
            KeyEvent.KEYCODE_BUTTON_Y -> {
                if (isUp) {
                    toggleNavigationMode()
                }
                return true
            }

            // Quick Play/Pause key when browsing
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (isUp) {
                    toggleVideoPlayback()
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
                    if (!virtualCursorView.isCursorVisible && currentNavMode == MODE_POINTER) {
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
                if (currentNavMode == MODE_POINTER && (isTv || virtualCursorView.isCursorVisible)) {
                    if (isUp) {
                        scheduleGuideDismiss()
                        virtualCursorView.dispatchClick(webView)
                    }
                    return true
                }
                // In Scroll Mode, pass enter through to web elements
            }

            // Back button
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BUTTON_B -> {
                if (isUp) {
                    if (isPlayerFullscreen) {
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


    private fun sendPlayerCommand(action: String, seconds: Int = 0) {
        val js = """
            (function() {
                var payload = { action: '$action', seconds: $seconds };
                var strPayload = JSON.stringify(payload);
                
                // 1. Broadcast to all iframes in the document (cross-origin embed players)
                var iframes = document.querySelectorAll('iframe');
                for (var i = 0; i < iframes.length; i++) {
                    try {
                        iframes[i].contentWindow.postMessage(payload, '*');
                        iframes[i].contentWindow.postMessage(strPayload, '*');
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
