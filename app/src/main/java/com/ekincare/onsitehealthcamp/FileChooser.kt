package com.ekincare.onsitehealthcamp

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import java.io.File

/**
 * Answers the page's `<input type="file">`, which WebView ignores on its own.
 *
 *  - `capture` inputs (camp photos) open the camera directly.
 *  - image / PDF inputs (Aadhaar, reports) open the system picker, with a camera option
 *    when camera permission is granted.
 *
 * Every request MUST be answered (null on cancel / deny), otherwise WebView never opens a
 * file chooser again until the page reloads.
 *
 * Construct during activity initialisation: it registers activity-result launchers.
 */
class FileChooser(
    private val activity: ComponentActivity,
    private val permissions: AppPermissions,
    private val onPermissionsChanged: () -> Unit,
) {
    private var callback: ValueCallback<Array<Uri>>? = null
    private var cameraFile: File? = null
    private var cameraUri: Uri? = null
    private var afterCameraPermission: ((Boolean) -> Unit)? = null

    private val cameraPermission =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val after = afterCameraPermission
            afterCameraPermission = null
            if (granted) permissions.markGranted(AppPermissions.CAMERA)
            after?.invoke(granted)
            onPermissionsChanged()
        }

    private val takePicture =
        activity.registerForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
            finish(if (saved) capturedPhoto() else null)
        }

    private val pickFile =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val picked = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            // Camera chosen from the chooser returns RESULT_OK with no data; the photo is in cameraFile.
            val uris = picked?.takeIf { it.isNotEmpty() }
                ?: if (result.resultCode == Activity.RESULT_OK) capturedPhoto() else null
            finish(uris)
        }

    fun show(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams) {
        this.callback?.onReceiveValue(null) // answer any stale request first
        this.callback = callback

        val mimeTypes = acceptedMimeTypes(params)
        val wantsImage = mimeTypes.isEmpty() || mimeTypes.any { it.startsWith("image/") || it == "*/*" }
        when {
            // <input capture="environment">: straight to camera.
            params.isCaptureEnabled && wantsImage -> withCameraPermission(explainDenial = true) { granted ->
                if (granted) launchCamera() else finish(null)
            }
            // Camera is optional here, so a denial just drops the camera option without a dialog.
            wantsImage -> withCameraPermission(explainDenial = false) { granted ->
                launchPicker(mimeTypes, includeCamera = granted)
            }
            else -> launchPicker(mimeTypes, includeCamera = false)
        }
    }

    /** Answer a pending request, e.g. when the activity is destroyed. */
    fun cancel() = finish(null)

    /** Drop photos left by earlier sessions. */
    fun clearCache() {
        cameraDir().deleteRecursively()
    }

    private fun acceptedMimeTypes(params: WebChromeClient.FileChooserParams): List<String> =
        params.acceptTypes
            .flatMap { it.split(",") }
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .map { if (it.startsWith(".")) mimeForExtension(it) else it }
            .distinct()

    private fun withCameraPermission(explainDenial: Boolean, block: (Boolean) -> Unit) {
        if (permissions.has(AppPermissions.CAMERA)) {
            permissions.markGranted(AppPermissions.CAMERA)
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
        permissions.markAsked(AppPermissions.CAMERA)
        permissions.withPermanentChoiceHint(AppPermissions.CAMERA, R.string.permission_camera) {
            cameraPermission.launch(AppPermissions.CAMERA)
        }
    }

    // The page never learns a camera denial (the file input just gets nothing back), so the
    // app explains it. Location denials are explained by the page instead.
    private fun explainCameraDenied() {
        val dialog = AlertDialog.Builder(activity)
        if (permissions.status(AppPermissions.CAMERA) == "denied") {
            dialog.setTitle(R.string.camera_needed_title)
                .setMessage(R.string.camera_needed_message)
                .setPositiveButton(R.string.action_allow) { _, _ -> askCamera() }
        } else {
            dialog.setTitle(R.string.camera_off_title)
                .setMessage(R.string.camera_off_message)
                .setPositiveButton(R.string.action_open_settings) { _, _ -> permissions.openSettings("app") }
        }
        dialog.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun launchCamera() {
        try {
            takePicture.launch(newCameraUri())
        } catch (e: ActivityNotFoundException) {
            finish(null)
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
            finish(null)
        }
    }

    private fun cameraDir() = File(activity.cacheDir, "camera")

    private fun newCameraUri(): Uri {
        val dir = cameraDir().apply { mkdirs() }
        val file = File.createTempFile("IMG_", ".jpg", dir)
        cameraFile = file
        return FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
            .also { cameraUri = it }
    }

    private fun capturedPhoto(): Array<Uri>? {
        val file = cameraFile ?: return null
        val uri = cameraUri ?: return null
        return if (file.length() > 0) arrayOf(uri) else null
    }

    private fun finish(uris: Array<Uri>?) {
        callback?.onReceiveValue(uris)
        callback = null
        cameraFile = null
        cameraUri = null
    }

    private fun mimeForExtension(ext: String) =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.removePrefix(".")) ?: "*/*"
}
