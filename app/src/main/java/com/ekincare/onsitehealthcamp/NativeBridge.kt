package com.ekincare.onsitehealthcamp

import android.app.Activity
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebView

/**
 * `window.OnsiteNative` for the web app (OnsiteApt `src/native.js`):
 *
 *  - `getPermissions()` returns the status JSON from [AppPermissions.statusJson]
 *  - `openSettings(target)` opens phone Settings
 *
 * The app also pushes an `onsite:permissions` event via [notifyPermissions] whenever the
 * status may have changed. Everything is refused unless the current page is the app's host.
 */
class NativeBridge(
    private val activity: Activity,
    private val permissions: AppPermissions,
) {
    private var webView: WebView? = null

    // JS bridge calls arrive on a background thread and WebView.url is UI-thread only,
    // so the current page's origin is tracked here instead.
    @Volatile
    private var onAppPage = false

    fun attach(webView: WebView) {
        this.webView = webView
        webView.addJavascriptInterface(this, JS_NAME)
    }

    /** Call from WebViewClient.onPageStarted. */
    fun onPageStarted(url: String?) {
        onAppPage = url != null && AppConfig.isAppUrl(Uri.parse(url))
    }

    fun notifyPermissions() {
        if (!onAppPage) return
        webView?.evaluateJavascript(
            "window.dispatchEvent(new CustomEvent('onsite:permissions',{detail:${permissions.statusJson()}}))",
            null,
        )
    }

    @JavascriptInterface
    fun getPermissions(): String = if (onAppPage) permissions.statusJson() else "null"

    @JavascriptInterface
    fun openSettings(target: String?) {
        if (onAppPage) activity.runOnUiThread { permissions.openSettings(target) }
    }

    private companion object {
        const val JS_NAME = "OnsiteNative"
    }
}
