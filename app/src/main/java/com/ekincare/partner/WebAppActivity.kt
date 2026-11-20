package com.ekincare.partner

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Hosts one WebView loading the OnsiteApt web app ([AppConfig.WEB_URL]).
 *
 * Native work is limited to what WebView does not do on its own, one class each:
 *  - [FileChooser]     `<input type="file">`: camera capture, image / PDF picker
 *  - [LocationPrompt]  `navigator.geolocation` runtime permission
 *  - [NativeBridge]    `window.OnsiteNative` permission status / settings bridge
 *  - [AppPermissions]  shared permission state and dialogs
 *  - [AppWebViewClient], [AppChromeClient]  WebView callbacks routed to the above
 *
 * Permissions are only asked when the page needs them, never at launch.
 */
class WebAppActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private var pageReady = false

    // Created at construction: FileChooser and LocationPrompt register activity-result launchers.
    private val permissions = AppPermissions(this)
    private val bridge = NativeBridge(this, permissions)
    private val fileChooser = FileChooser(this, permissions, bridge::notifyPermissions)
    private val locationPrompt = LocationPrompt(this, permissions, bridge::notifyPermissions)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Dark status/nav icons on the web app's light header, regardless of system dark mode.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(BAR_COLOR, BAR_COLOR),
            navigationBarStyle = SystemBarStyle.light(BAR_COLOR, BAR_COLOR),
        )
        fileChooser.clearCache()

        webView = WebView(this)
        setContentView(edgeToEdgeContainer(webView))
        keepSplashUntilPageReady()
        configureWebView()

        onBackPressedDispatcher.addCallback(this) {
            if (webView.canGoBack()) webView.goBack() else finish()
        }

        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) {
            webView.loadUrl(AppConfig.WEB_URL)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        bridge.notifyPermissions() // user may be back from Settings
    }

    override fun onPause() {
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        fileChooser.cancel()
        webView.destroy()
        super.onDestroy()
    }

    /**
     * targetSdk 35 is always edge-to-edge: keep content clear of bars and keyboard.
     * Pad a container, not the WebView — WebView ignores its own padding and keeps drawing
     * under the status bar. The container's colour fills the bar areas.
     */
    private fun edgeToEdgeContainer(content: WebView) = FrameLayout(this).apply {
        setBackgroundColor(BAR_COLOR)
        addView(content, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    /**
     * The splash (Android 12+ system splash, or the logo window background on 8-11) only
     * stays until the activity draws its first frame, which happens in a few hundred ms —
     * long before the web page has loaded, so a kill-and-reopen showed a blank white
     * WebView instead of the logo. Hold the first frame until the page has loaded, capped
     * so a slow or offline start never hangs on the logo.
     */
    private fun keepSplashUntilPageReady() {
        val content = findViewById<View>(android.R.id.content)
        content.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (!pageReady) return false
                content.viewTreeObserver.removeOnPreDrawListener(this)
                return true
            }
        })
        content.postDelayed(::showPage, SPLASH_MAX_MS)
    }

    private fun showPage() {
        if (pageReady) return
        pageReady = true
        findViewById<View>(android.R.id.content).invalidate()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG) // chrome://inspect
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true // localStorage holds X-AGENT-KEY; without it login is lost
            setGeolocationEnabled(true)
            allowFileAccess = false
            allowContentAccess = true // content:// URIs from camera / picker
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            // WebView ignores the phone's font-size setting by default. Follow it, never below the
            // web design, capped so fixed-size tiles don't overflow. A change recreates the activity.
            textZoom = (resources.configuration.fontScale * 100).toInt().coerceIn(100, MAX_TEXT_ZOOM)
            userAgentString = "$userAgentString OnsiteHealthCamp-Android/${BuildConfig.VERSION_NAME}"
        }
        webView.webViewClient = AppWebViewClient(bridge, onPageReady = ::showPage)
        webView.webChromeClient = AppChromeClient(fileChooser, locationPrompt)
        bridge.attach(webView)
    }

    private companion object {
        // Matches the web app header / page background (--n-50).
        val BAR_COLOR = Color.parseColor("#F9FAFB")

        // ponytail: 130% is where 10-11px labels in fixed tiles start to wrap; raise after a layout pass.
        const val MAX_TEXT_ZOOM = 130

        // Longest the splash waits for the page before showing whatever WebView has.
        const val SPLASH_MAX_MS = 6_000L
    }
}
