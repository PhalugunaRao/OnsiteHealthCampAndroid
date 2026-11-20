package com.ekincare.onsitehealthcamp

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.location.LocationManager
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.GeolocationPermissions
import android.webkit.MimeTypeMap
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.location.LocationManagerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONObject
import java.io.File

/**
 * One WebView loading the OnsiteApt web app. Native work is limited to what
 * WebView does not do on its own:
 *  - <input type="file"> (camera capture, image/PDF picker) via onShowFileChooser
 *  - navigator.geolocation runtime permission via onGeolocationPermissionsShowPrompt
 *  - window.OnsiteNative bridge: getPermissions() / openSettings(target), plus an
 *    'onsite:permissions' event pushed to the page whenever status may have changed
 *    (see OnsiteApt src/native.js for the shape).
 * Permissions are only asked when the page needs them, never at launch.
 */
class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private val appHost: String? = Uri.parse(BuildConfig.WEB_URL).host

    // Pending <input type="file"> request. Must ALWAYS be answered (null on cancel),
    // otherwise WebView never opens a file chooser again until the page reloads.
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var cameraFile: File? = null
    private var cameraUri: Uri? = null
    private var afterCameraPermission: ((Boolean) -> Unit)? = null
    // Page requests waiting on the location dialog. Several can arrive while it is open
    // (check-in + a tracking ping); all get the same answer, one dialog only.
    private val pendingGeo = mutableListOf<Pair<String, GeolocationPermissions.Callback>>()

    // JS bridge calls arrive on a background thread; WebView.url is UI-thread only,
    // so track whether the current page is ours here.
    @Volatile private var onAppPage = false
    private val prefs by lazy { getSharedPreferences("permissions", MODE_PRIVATE) }

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val after = afterCameraPermission
            afterCameraPermission = null
            if (granted) markGranted(Manifest.permission.CAMERA)
            after?.invoke(granted)
            notifyPermissions()
        }

    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val granted = result.values.any { it } // coarse-only still counts; web code copes with low accuracy
            if (granted) markGranted(Manifest.permission.ACCESS_FINE_LOCATION)
            pendingGeo.forEach { (origin, callback) -> callback.invoke(origin, granted, false) }
            pendingGeo.clear()
            notifyPermissions()
        }

    private val takePicture =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
            finishChooser(if (saved) capturedPhoto() else null)
        }

    private val pickFile =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val picked = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            // Camera chosen from the chooser returns RESULT_OK with no data; the photo is in cameraFile.
            val uris = picked?.takeIf { it.isNotEmpty() }
                ?: if (result.resultCode == RESULT_OK) capturedPhoto() else null
            finishChooser(uris)
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Dark status/nav icons on the web app's light header, regardless of system dark mode.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(BAR_COLOR, BAR_COLOR),
            navigationBarStyle = SystemBarStyle.light(BAR_COLOR, BAR_COLOR),
        )
        cameraDir().deleteRecursively() // drop photos left by earlier sessions

        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG) // chrome://inspect
        webView = WebView(this)
        // targetSdk 35 is always edge-to-edge: keep content clear of bars and keyboard.
        // Pad a container, not the WebView — WebView ignores its own padding and keeps
        // drawing under the status bar. The container's colour fills the bar areas.
        val root = FrameLayout(this).apply {
            setBackgroundColor(BAR_COLOR)
            addView(webView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true // localStorage holds X-AGENT-KEY; without it login is lost
            setGeolocationEnabled(true)
            allowFileAccess = false
            allowContentAccess = true // content:// URIs from camera / picker
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            // WebView ignores the phone's font-size setting by default. Follow it, but never
            // shrink below the web design and cap growth so fixed-size tiles don't overflow.
            // Changing the setting recreates the activity, so this re-runs.
            textZoom = (resources.configuration.fontScale * 100).toInt().coerceIn(100, MAX_TEXT_ZOOM)
            userAgentString = "$userAgentString OnsiteHealthCamp-Android/${BuildConfig.VERSION_NAME}"
        }
        webView.webViewClient = AppWebViewClient()
        webView.webChromeClient = AppChromeClient()
        webView.addJavascriptInterface(Bridge(), "OnsiteNative")

        onBackPressedDispatcher.addCallback(this) {
            if (webView.canGoBack()) webView.goBack() else finish()
        }

        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) {
            webView.loadUrl(BuildConfig.WEB_URL)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        notifyPermissions() // user may be back from Settings
    }

    override fun onPause() {
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        finishChooser(null)
        webView.destroy()
        super.onDestroy()
    }

    // ---------- file input ----------

    private fun showFileChooser(params: WebChromeClient.FileChooserParams) {
        val mimeTypes = params.acceptTypes
            .flatMap { it.split(",") }
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .map { if (it.startsWith(".")) mimeForExtension(it) else it }
            .distinct()
        val wantsImage = mimeTypes.isEmpty() || mimeTypes.any { it.startsWith("image/") || it == "*/*" }

        when {
            // <input capture="environment">: straight to camera (CaptureTile).
            params.isCaptureEnabled && wantsImage -> withCameraPermission(explainDenial = true) { granted ->
                if (granted) launchCamera() else finishChooser(null)
            }
            // Image or PDF picker (Aadhaar, module report): files + camera option.
            // Camera is optional here, so a denial just drops the camera option without a dialog.
            wantsImage -> withCameraPermission(explainDenial = false) { granted -> launchPicker(mimeTypes, includeCamera = granted) }
            else -> launchPicker(mimeTypes, includeCamera = false)
        }
    }

    private fun withCameraPermission(explainDenial: Boolean, block: (Boolean) -> Unit) {
        if (hasPermission(Manifest.permission.CAMERA)) {
            markGranted(Manifest.permission.CAMERA)
            block(true)
            return
        }
        afterCameraPermission = { granted ->
            block(granted)
            if (!granted && explainDenial) explainCameraDenied()
        }
        askCamera()
    }

    private fun askCamera() {
        markAsked(Manifest.permission.CAMERA)
        withPermanentChoiceHint(Manifest.permission.CAMERA, "Camera") {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    // A permission granted before but missing now was almost always "Only this time", which
    // Android drops when the app closes, so it asks on every launch. Apps cannot remove that
    // option; tell the user which one stops the repeat before the system dialog appears.
    private fun withPermanentChoiceHint(permission: String, what: String, launch: () -> Unit) {
        if (!prefs.getBoolean("granted:$permission", false)) {
            launch()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("$what permission")
            .setMessage("To stop being asked every time you open the app, choose \"While using the app\" on the next screen.")
            .setPositiveButton("Continue") { _, _ -> launch() }
            .setCancelable(false) // the pending page request must always get an answer
            .show()
    }

    private fun markGranted(permission: String) = prefs.edit().putBoolean("granted:$permission", true).apply()

    // The page never learns a camera denial (the file input just gets nothing back),
    // so the app explains it here. Location denials are explained by the page instead.
    private fun explainCameraDenied() {
        if (permissionStatus(Manifest.permission.CAMERA) == "denied") {
            AlertDialog.Builder(this)
                .setTitle("Camera needed")
                .setMessage("OnsiteHealthCamp needs the camera to take camp photos. Allow it, then tap the photo again.")
                .setPositiveButton("Allow") { _, _ -> askCamera() }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            AlertDialog.Builder(this)
                .setTitle("Camera is turned off")
                .setMessage("Open Settings → Permissions → Camera → Allow, then come back and tap the photo again.")
                .setPositiveButton("Open Settings") { _, _ -> openSettings("app") }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun launchCamera() {
        try {
            takePicture.launch(newCameraUri())
        } catch (e: ActivityNotFoundException) {
            finishChooser(null)
        }
    }

    private fun launchPicker(mimeTypes: List<String>, includeCamera: Boolean) {
        val getContent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeTypes.singleOrNull() ?: "*/*"
            if (mimeTypes.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toTypedArray())
        }
        val chooser = Intent.createChooser(getContent, null)
        if (includeCamera) {
            val camera = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                .putExtra(MediaStore.EXTRA_OUTPUT, newCameraUri())
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(camera))
        } else {
            cameraFile = null
            cameraUri = null
        }
        try {
            pickFile.launch(chooser)
        } catch (e: ActivityNotFoundException) {
            finishChooser(null)
        }
    }

    private fun cameraDir() = File(cacheDir, "camera")

    private fun newCameraUri(): Uri {
        val dir = cameraDir().apply { mkdirs() }
        val file = File.createTempFile("IMG_", ".jpg", dir)
        cameraFile = file
        return FileProvider.getUriForFile(this, "$packageName.fileprovider", file).also { cameraUri = it }
    }

    private fun capturedPhoto(): Array<Uri>? {
        val file = cameraFile ?: return null
        val uri = cameraUri ?: return null
        return if (file.length() > 0) arrayOf(uri) else null
    }

    private fun finishChooser(uris: Array<Uri>?) {
        fileCallback?.onReceiveValue(uris)
        fileCallback = null
        cameraFile = null
        cameraUri = null
    }

    private fun mimeForExtension(ext: String) =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.removePrefix(".")) ?: "*/*"

    // ---------- permission status bridge ----------

    private fun markAsked(permission: String) = prefs.edit().putBoolean(permission, true).apply()

    // prompt  = never asked; denied = may ask again; blocked = only Settings can fix it.
    // ponytail: Android can't tell "never asked" from "blocked" (both: no rationale), hence
    // the asked flag. Dismissing the very first dialog with Back also reads as blocked.
    private fun permissionStatus(permission: String) = when {
        hasPermission(permission) -> "granted"
        !prefs.getBoolean(permission, false) -> "prompt"
        shouldShowRequestPermissionRationale(permission) -> "denied"
        else -> "blocked"
    }

    private fun permissionsJson(): String {
        val location = when {
            hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) -> "granted"
            hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION) -> "approximate"
            else -> permissionStatus(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        return JSONObject()
            .put("platform", "android")
            .put("location", location)
            .put("locationServices", LocationManagerCompat.isLocationEnabled(locationManager))
            .put("camera", permissionStatus(Manifest.permission.CAMERA))
            .toString()
    }

    private fun notifyPermissions() {
        if (!onAppPage) return
        webView.evaluateJavascript(
            "window.dispatchEvent(new CustomEvent('onsite:permissions',{detail:${permissionsJson()}}))",
            null,
        )
    }

    private fun openSettings(target: String?) {
        val intent = if (target == "location") {
            Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private inner class Bridge {
        @JavascriptInterface
        fun getPermissions(): String = if (onAppPage) permissionsJson() else "null"

        @JavascriptInterface
        fun openSettings(target: String?) {
            if (onAppPage) runOnUiThread { this@MainActivity.openSettings(target) }
        }
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun isAppUrl(uri: Uri) = uri.scheme == "https" && uri.host == appHost

    // ---------- clients ----------

    private inner class AppChromeClient : WebChromeClient() {
        override fun onShowFileChooser(
            view: WebView,
            callback: ValueCallback<Array<Uri>>,
            params: FileChooserParams,
        ): Boolean {
            fileCallback?.onReceiveValue(null) // answer any stale request first
            fileCallback = callback
            showFileChooser(params)
            return true
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
            if (!isAppUrl(Uri.parse(origin))) {
                callback.invoke(origin, false, false)
                return
            }
            if (hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
                hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
            ) {
                markGranted(Manifest.permission.ACCESS_FINE_LOCATION)
                callback.invoke(origin, true, false)
                return
            }
            pendingGeo += origin to callback
            if (pendingGeo.size > 1) return // dialog already open; answered together
            markAsked(Manifest.permission.ACCESS_FINE_LOCATION)
            withPermanentChoiceHint(Manifest.permission.ACCESS_FINE_LOCATION, "Location") {
                locationPermission.launch(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                )
            }
        }
    }

    private inner class AppWebViewClient : WebViewClient() {
        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
            onAppPage = url != null && isAppUrl(Uri.parse(url))
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            if (isAppUrl(uri)) return false
            // tel:, mailto:, other hosts: hand to the system.
            try {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            } catch (e: ActivityNotFoundException) {
                // nothing can open it; ignore
            }
            return true
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame) return
            // ponytail: plain offline page; the web app's own ping queue covers flaky network once loaded.
            val html = """
                <html><head><meta name="viewport" content="width=device-width,initial-scale=1"></head>
                <body style="font-family:sans-serif;text-align:center;padding:48px 24px;color:#333">
                <h3>No connection</h3><p>Check internet and try again.</p>
                <a href="${BuildConfig.WEB_URL}" style="display:inline-block;padding:12px 24px;background:#0266f9;color:#fff;border-radius:8px;text-decoration:none">Retry</a>
                </body></html>
            """.trimIndent()
            view.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
        }
    }

    private companion object {
        // Matches the web app header / page background (--n-50).
        val BAR_COLOR = Color.parseColor("#F9FAFB")

        // ponytail: 130% is where 10-11px labels in fixed tiles start to wrap; raise after a layout pass.
        const val MAX_TEXT_ZOOM = 130
    }
}
