package com.ekincare.partner

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.GeolocationPermissions
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/** Page-level hooks: file inputs and location permission. */
class AppChromeClient(
    private val fileChooser: FileChooser,
    private val locationPrompt: LocationPrompt,
) : WebChromeClient() {

    override fun onShowFileChooser(
        view: WebView,
        callback: ValueCallback<Array<Uri>>,
        params: FileChooserParams,
    ): Boolean {
        fileChooser.show(callback, params)
        return true
    }

    override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
        locationPrompt.onPrompt(origin, callback)
    }
}

/**
 * Navigation: keep the app's host inside, hand everything else to the system.
 * [onPageReady] fires when a page (or the offline page) has finished loading.
 */
class AppWebViewClient(
    private val bridge: NativeBridge,
    private val onPageReady: () -> Unit,
) : WebViewClient() {

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        bridge.onPageStarted(url)
    }

    override fun onPageFinished(view: WebView, url: String?) {
        onPageReady()
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val uri = request.url
        if (AppConfig.isAppUrl(uri)) return false
        // tel:, mailto:, other hosts.
        try {
            view.context.startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: ActivityNotFoundException) {
            // nothing can open it; ignore
        }
        return true
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (!request.isForMainFrame) return
        view.loadDataWithBaseURL(null, OFFLINE_HTML, "text/html", "utf-8", null)
    }

    private companion object {
        // ponytail: plain offline page; the web app's own ping queue covers flaky network once loaded.
        val OFFLINE_HTML = """
            <html><head><meta name="viewport" content="width=device-width,initial-scale=1"></head>
            <body style="font-family:sans-serif;text-align:center;padding:48px 24px;color:#333">
            <h3>No connection</h3><p>Check internet and try again.</p>
            <a href="${AppConfig.WEB_URL}" style="display:inline-block;padding:12px 24px;background:#0266f9;color:#fff;border-radius:8px;text-decoration:none">Retry</a>
            </body></html>
        """.trimIndent()
    }
}
