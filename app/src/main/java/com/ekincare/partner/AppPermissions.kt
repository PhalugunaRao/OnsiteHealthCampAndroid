package com.ekincare.partner

import android.Manifest
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.location.LocationManagerCompat
import org.json.JSONObject

/**
 * Camera / location runtime-permission state, reported in the shape the web app expects
 * (OnsiteApt `src/native.js`):
 *
 *  - `prompt`  never asked
 *  - `denied`  asked and refused, Android will still show its dialog
 *  - `blocked` only phone Settings can fix it
 *  - `granted` (location also `approximate` when only coarse is allowed)
 */
class AppPermissions(private val activity: ComponentActivity) {

    private val prefs by lazy { activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    fun has(permission: String): Boolean =
        ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED

    val hasLocation: Boolean get() = has(FINE_LOCATION) || has(COARSE_LOCATION)

    fun markAsked(permission: String) = prefs.edit { putBoolean(permission, true) }

    fun markGranted(permission: String) = prefs.edit { putBoolean(grantedKey(permission), true) }

    // ponytail: Android can't tell "never asked" from "blocked" (both: no rationale), hence
    // the asked flag. Dismissing the very first dialog with Back also reads as blocked.
    fun status(permission: String): String = when {
        has(permission) -> "granted"
        !prefs.getBoolean(permission, false) -> "prompt"
        activity.shouldShowRequestPermissionRationale(permission) -> "denied"
        else -> "blocked"
    }

    fun statusJson(): String {
        val location = when {
            has(FINE_LOCATION) -> "granted"
            has(COARSE_LOCATION) -> "approximate"
            else -> status(FINE_LOCATION)
        }
        val locationManager = activity.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return JSONObject()
            .put("platform", "android")
            .put("location", location)
            .put("locationServices", LocationManagerCompat.isLocationEnabled(locationManager))
            .put("camera", status(CAMERA))
            .toString()
    }

    /**
     * A permission granted before but missing now was almost always "Only this time", which
     * Android drops when the app closes, so it asks on every launch. Apps cannot remove that
     * option; say which one stops the repeat before the system dialog appears.
     */
    fun withPermanentChoiceHint(permission: String, labelRes: Int, launch: () -> Unit) {
        if (!prefs.getBoolean(grantedKey(permission), false)) {
            launch()
            return
        }
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.permission_hint_title, activity.getString(labelRes)))
            .setMessage(R.string.permission_hint_message)
            .setPositiveButton(R.string.action_continue) { _, _ -> launch() }
            .setCancelable(false) // the pending page request must always get an answer
            .show()
    }

    /** `location` opens the phone-wide location switch; anything else the app's own settings page. */
    fun openSettings(target: String?) {
        val intent = if (target == "location") {
            Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.packageName, null))
        }
        try {
            activity.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            activity.startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun grantedKey(permission: String) = "granted:$permission"

    companion object {
        const val CAMERA = Manifest.permission.CAMERA
        const val FINE_LOCATION = Manifest.permission.ACCESS_FINE_LOCATION
        const val COARSE_LOCATION = Manifest.permission.ACCESS_COARSE_LOCATION
        private const val PREFS = "permissions"
    }
}
