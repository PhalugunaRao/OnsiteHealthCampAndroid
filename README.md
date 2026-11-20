# OnsiteHealthCamp — Android

Android wrapper app for the OnsiteApt web app (onsite camp operations for phlebotomists).
One Kotlin activity hosts a `WebView` that loads the web app; native code only covers what
a WebView cannot do on its own: camera/file upload, location permission, and a small
permission bridge to the page.

## Requirements

- Android Studio (or JDK 17+ and the Android SDK)
- compileSdk / targetSdk 35, minSdk 26
- Gradle wrapper included (Gradle 8.11.1, AGP 8.9.3, Kotlin 2.0.21)

## Build and run

```bash
./gradlew assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug       # install on a connected device / emulator
```

`local.properties` (SDK path) is created by Android Studio and is not committed.

## Which URL loads

**Only edit `app/src/main/java/com/ekincare/onsitehealthcamp/AppConfig.kt`:**

| Build | URL |
|---|---|
| `debug` | OnsiteApt PR preview (`https://pr-13.d1fle6jimql6gu.amplifyapp.com`) — change per PR being tested |
| `release` | Production `https://onsite-providers.ekincare.com` |

Release builds always load production, so a test URL cannot ship by accident.

## What the native side does (`MainActivity.kt`)

- **Camera / file upload** — WebView ignores `<input type="file">` by default.
  `onShowFileChooser` handles it:
  - `capture` inputs (camp photos) open the camera directly (FileProvider URI in cache).
  - image/PDF inputs (Aadhaar, reports) open the system picker with a camera option.
  - Every request is always answered (`null` on cancel/deny) so the input never locks up.
- **Location** — `navigator.geolocation` is granted only to the app's own host, after the
  Android runtime permission. Several page requests share one dialog.
- **Permissions are asked only when the page needs them**, never at launch.
  - Camera denied on a photo tile → dialog with *Allow* or *Open Settings*.
  - Granted before but missing now ("Only this time") → a short note to pick
    *While using the app* before the system dialog.
- **JS bridge `window.OnsiteNative`** (only on the app's host):
  - `getPermissions()` → JSON `{ platform, location, locationServices, camera }`
  - `openSettings('app' | 'location')`
  - The app also dispatches an `onsite:permissions` event to the page after a permission
    dialog and on return to the foreground. Web side: `src/native.js` in OnsiteApt.
- **Layout** — edge-to-edge (targetSdk 35): a container is padded by the system bar and
  keyboard insets (WebView ignores its own padding); bar colour matches the web header.
- **Text size** — `textZoom` follows the phone font-size setting, clamped 100–130%.
- Back key walks WebView history; external links, `tel:` and `mailto:` open outside the app;
  plain offline page with Retry; user agent gets `OnsiteHealthCamp-Android/<version>`.

## Debugging

Debug builds enable WebView remote debugging: open `chrome://inspect` in desktop Chrome
with the device connected.

## Release

Create a release keystore (never commit it), add a `signingConfig` to the `release`
build type, then `./gradlew assembleRelease` (or `bundleRelease` for Play).
