package com.ekincare.partner

import android.net.Uri
import android.webkit.GeolocationPermissions
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Answers the page's `navigator.geolocation` permission requests with the Android runtime
 * permission. Only the app's own host is ever granted.
 *
 * Several requests can arrive while the dialog is open (check-in + a tracking ping); they
 * all get the same answer from one dialog.
 *
 * Construct during activity initialisation: it registers an activity-result launcher.
 */
class LocationPrompt(
    activity: ComponentActivity,
    private val permissions: AppPermissions,
    private val onPermissionsChanged: () -> Unit,
) {
    private val pending = mutableListOf<Pair<String, GeolocationPermissions.Callback>>()

    private val request =
        activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val granted = result.values.any { it } // coarse-only still counts; the web copes with low accuracy
            if (granted) permissions.markGranted(AppPermissions.FINE_LOCATION)
            pending.forEach { (origin, callback) -> callback.invoke(origin, granted, false) }
            pending.clear()
            onPermissionsChanged()
        }

    fun onPrompt(origin: String, callback: GeolocationPermissions.Callback) {
        if (!AppConfig.isAppUrl(Uri.parse(origin))) {
            callback.invoke(origin, false, false)
            return
        }
        if (permissions.hasLocation) {
            permissions.markGranted(AppPermissions.FINE_LOCATION)
            callback.invoke(origin, true, false)
            return
        }
        pending += origin to callback
        if (pending.size > 1) return // dialog already open; answered together
        permissions.markAsked(AppPermissions.FINE_LOCATION)
        permissions.withPermanentChoiceHint(AppPermissions.FINE_LOCATION, R.string.permission_location) {
            request.launch(arrayOf(AppPermissions.FINE_LOCATION, AppPermissions.COARSE_LOCATION))
        }
    }
}
