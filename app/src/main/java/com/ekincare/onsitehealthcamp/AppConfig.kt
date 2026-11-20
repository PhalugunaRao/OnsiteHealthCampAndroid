package com.ekincare.onsitehealthcamp

import android.net.Uri

/**
 * ┌───────────────────────────────────────────────────────────────┐
 * │  WEB APP URLS — the only place to change which site loads.    │
 * └───────────────────────────────────────────────────────────────┘
 *
 * Debug build  (Android Studio Run / ./gradlew assembleDebug)   -> TEST_URL
 * Release build (./gradlew assembleRelease / bundleRelease)     -> PROD_URL
 *
 * Rules:
 *  - HTTPS only. Location and camera are refused on http:// pages.
 *  - Host only, no trailing path. Camera, location and the permission bridge
 *    are granted only to this host; any other host opens outside the app.
 *  - Testing an OnsiteApt PR preview: change TEST_URL to that PR's Amplify URL.
 *  - Keep in sync with iOS: OnsiteHealthCamp/AppConfig.swift
 */
object AppConfig {
    private const val PROD_URL = "https://onsite-providers.ekincare.com"
    private const val TEST_URL = "https://pr-13.d1fle6jimql6gu.amplifyapp.com"

    val WEB_URL: String = if (BuildConfig.DEBUG) TEST_URL else PROD_URL

    // ---- Derived from WEB_URL, no need to edit below ----

    private val APP_HOST: String? = Uri.parse(WEB_URL).host

    /** Camera, location and the JS bridge are only granted to pages on the app's own host. */
    fun isAppUrl(uri: Uri): Boolean = uri.scheme == "https" && uri.host == APP_HOST
}
