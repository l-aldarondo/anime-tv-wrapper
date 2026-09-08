package com.example.animetv.webview

import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ProgressBar
import com.example.animetv.adblock.AdBlocker

class AnimeWebChromeClient(
    private val webView: WebView,
    private val videoContainer: FrameLayout,
    private val progressBar: ProgressBar,
    private val onFullscreenChanged: (Boolean) -> Unit
) : WebChromeClient() {

    private var customView: View? = null
    private var customViewCallback: CustomViewCallback? = null

    val isFullscreen: Boolean
        get() = customView != null

    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
        if (customView != null) {
            onHideCustomView()
            return
        }

        customView = view
        customViewCallback = callback

        // Hide primary WebView and show fullscreen video container
        webView.visibility = View.GONE
        videoContainer.visibility = View.VISIBLE
        videoContainer.removeAllViews()
        videoContainer.addView(
            view,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        onFullscreenChanged(true)
    }

    override fun onHideCustomView() {
        if (customView == null) return

        videoContainer.visibility = View.GONE
        videoContainer.removeAllViews()
        webView.visibility = View.VISIBLE

        customViewCallback?.onCustomViewHidden()
        customView = null
        customViewCallback = null

        onFullscreenChanged(false)
    }

    override fun onCreateWindow(
        view: WebView?,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: android.os.Message?
    ): Boolean {
        // Prevent all pop-ups and new window creation
        resultMsg?.obj?.let {
            if (it is WebView.WebViewTransport) {
                it.webView = null
            }
        }
        return false
    }

    override fun onProgressChanged(view: WebView?, newProgress: Int) {
        super.onProgressChanged(view, newProgress)

        // Keep progress bar hidden per user request
        progressBar.visibility = View.GONE

        // Early script injection during DOM building
        if (newProgress in 40..60) {
            view?.evaluateJavascript(AdBlocker.getAntiAdJs(), null)
        }
    }
}
