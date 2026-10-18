# Jellyfin for Android - Code Audit Report

## 1. Executive Summary

A comprehensive code audit was conducted on the **Jellyfin for Android** client codebase (`org.jellyfin.mobile`). The analysis focused on security posture, Android architectural patterns, concurrency, lifecycle stability, and target platform compliance (Android 14+ / targetSdk 36).

### Issue Summary Matrix

| ID | Title | Category | Severity | File Reference |
| :--- | :--- | :--- | :--- | :--- |
| **SEC-01** | Unrestricted WebView Navigation & JS Bridge Hijacking | Security | **High** | [JellyfinWebViewClient.kt](file:///home/syafiq/code/jellyfin-android/app/src/main/java/org/jellyfin/mobile/webapp/JellyfinWebViewClient.kt#L43-L88) |
| **BUG-01** | Logic Inversion in `StorageManager.changeStorageLocation` | Functional Bug | **High** | [StorageManager.kt](file:///home/syafiq/code/jellyfin-android/app/src/main/java/org/jellyfin/mobile/app/StorageManager.kt#L30-L44) |
| **LIF-01** | Fragment Lifecycle Violation in `PlayerFragment` | Stability / Crash | **High** | [PlayerFragment.kt](file:///home/syafiq/code/jellyfin-android/app/src/main/java/org/jellyfin/mobile/player/ui/PlayerFragment.kt#L103-L134) |
| **CON-01** | Synchronous `runBlocking` on Main Thread in `LibraryService` | Performance / ANR | **Medium** | [LibraryService.kt](file:///home/syafiq/code/jellyfin-android/app/src/main/java/org/jellyfin/mobile/sessionbrowser/LibraryService.kt#L45) |
| **SEC-02** | Plaintext Token Storage & Unencrypted Backup Exposure | Security | **Medium** | [UserEntity.kt](file:///home/syafiq/code/jellyfin-android/app/src/main/java/org/jellyfin/mobile/data/entity/UserEntity.kt#L31), [backup_content.xml](file:///home/syafiq/code/jellyfin-android/app/src/main/res/xml/backup_content.xml#L16-L24) |
| **API-01** | Missing Receiver Export Flags in `RemotePlayerService` | Android 14+ Compat | **Medium** | [RemotePlayerService.kt](file:///home/syafiq/code/jellyfin-android/app/src/main/java/org/jellyfin/mobile/webapp/RemotePlayerService.kt#L142) |
| **STA-01** | Potential Divide-by-Zero in Audio Volume Calculations | Stability / Crash | **Medium** | [MediaExtensions.kt](file:///home/syafiq/code/jellyfin-android/app/src/main/java/org/jellyfin/mobile/utils/MediaExtensions.kt#L77), [PlayerViewModel.kt](file:///home/syafiq/code/jellyfin-android/app/src/main/java/org/jellyfin/mobile/player/PlayerViewModel.kt#L464) |
| **SEC-03** | Permissive Network Security Base Configuration | Security | **Low** | [network_security_config.xml](file:///home/syafiq/code/jellyfin-android/app/src/main/res/xml/network_security_config.xml#L4-L15) |
| **STA-02** | Unhandled `ActivityNotFoundException` in `ExternalPlayer` | Crash Handling | **Low** | [ExternalPlayer.kt](file:///home/syafiq/code/jellyfin-android/app/src/main/java/org/jellyfin/mobile/bridge/ExternalPlayer.kt#L167) |
| **UX-01** | Legacy Picture-in-Picture Transition Trigger | User Experience | **Low** | [PlayerFragment.kt](file:///home/syafiq/code/jellyfin-android/app/src/main/java/org/jellyfin/mobile/player/ui/PlayerFragment.kt#L384-L409) |

---

## 2. High-Severity Findings

### SEC-01: Unrestricted WebView Navigation & JS Bridge Hijacking

- **Location:** [JellyfinWebViewClient.kt](file:///home/syafiq/code/jellyfin-android/app/src/main/java/org/jellyfin/mobile/webapp/JellyfinWebViewClient.kt#L43-L88), [WebViewFragment.kt](file:///home/syafiq/code/jellyfin-android/app/src/main/java/org/jellyfin/mobile/webapp/WebViewFragment.kt#L188-L192)
- **Problem:**
  [`WebViewFragment`](file:///home/syafiq/code/jellyfin-android/app/src/main/java/org/jellyfin/mobile/webapp/WebViewFragment.kt) exposes several sensitive JavaScript interfaces to the embedded WebView:
  ```kotlin
  addJavascriptInterface(NativeInterface(requireContext()), "NativeInterface")
  addJavascriptInterface(nativePlayer, "NativePlayer")
  addJavascriptInterface(externalPlayer, "ExternalPlayer")
  addJavascriptInterface(mediaSegments, "MediaSegments")
  ```
  However, [`JellyfinWebViewClient`](file:///home/syafiq/code/jellyfin-android/app/src/main/java/org/jellyfin/mobile/webapp/JellyfinWebViewClient.kt) **does not implement `shouldOverrideUrlLoading`**. While [`JellyfinWebChromeClient`](file:///home/syafiq/code/jellyfin-android/app/src/main/java/org/jellyfin/mobile/webapp/JellyfinWebChromeClient.kt#L40) handles new windows (`target="_blank"`), normal navigation in the main frame (such as markdown links, untrusted media description links, or server redirects) will navigate the primary WebView to external origins.
  
  In Android WebViews, JavaScript interfaces added via `addJavascriptInterface` remain active across top-level navigations. If an external webpage loads inside the WebView, that page can invoke native methods, gaining access to:
  - Device and app metadata (`NativeInterface.getDeviceInformation`)
  - Arbitrary file download queuing (`NativeInterface.downloadFiles`)
  - Intent launching (`NativeInterface.openUrl`, `ExternalPlayer.initPlayer`)
- **Remediation:**
  Implement `shouldOverrideUrlLoading` in [`JellyfinWebViewClient`](file:///home/syafiq/code/jellyfin-android/app/src/main/java/org/jellyfin/mobile/webapp/JellyfinWebViewClient.kt) to enforce strict host boundary checks:
  ```kotlin
  override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
      val targetUri = request.url
      val serverUri = Uri.parse(server.hostname)
      
      // Allow navigation only within the configured Jellyfin server domain
      if (targetUri.host.equals(serverUri.host, ignoreCase = true)) {
          return false
      }
      
      // Launch external URLs in system browser
      val intent = Intent(Intent.ACTION_VIEW, targetUri)
      view.context.startActivity(intent)
      return true
  }
  ```

---

### BUG-01: Logic Inversion in `StorageManager.changeStorageLocation`

- **Location:** [StorageManager.kt](file:///home/syafiq/code/jellyfin-android/app/src/main/java/org/jellyfin/mobile/app/StorageManager.kt#L30-L44)
- **Problem:**
  Line 43 evaluates `.isFailure`:
  ```kotlin
  fun changeStorageLocation(location: Uri): Boolean {
      if (appPreferences.storageLocation?.toUri() == location) return true

      return runCatching {
          context.contentResolver.takePersistableUriPermission(
              location,
              Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
          )

          appPreferences.storageLocation = location.toString()
          getStorageLocation()?.let(::ensureNoMedia)
      }.onFailure { err ->
          Timber.e(err, "Failed to change storage location to $location")
      }.isFailure // <--- Inverted boolean: returns true when it fails, false when it succeeds
  }
  ```
  In [SettingsFragment.kt](file:///home/syafiq/code/jellyfin-android/app/src/main/java/org/jellyfin/mobile/settings/SettingsFragment.kt#L45-L51):
  ```kotlin
  val changed = storageManager.changeStorageLocation(uri)
  if (changed && ::downloadLocationPreference.isInitialized) {
      downloadLocationPreference.summary = storageManager.getStorageLocation()?.name
      downloadLocationPreference.requestRebindAndHighlight()
  }
  ```
  Because `changeStorageLocation` returns `false` when it succeeds, the settings UI never updates the displayed storage location summary on success.
- **Remediation:**
  Change line 43 in [StorageManager.kt](file:///home/syafiq/code/jellyfin-android/app/src/main/java/org/jellyfin/mobile/app/StorageManager.kt#L43) from `.isFailure` to `.isSuccess`.

---

### LIF-01: Fragment Lifecycle Violation in `PlayerFragment`

- **Location:** [PlayerFragment.kt](file:///home/syafiq/code/jellyfin-android/app/src/main/java/org/jellyfin/mobile/player/ui/PlayerFragment.kt#L103-L134)
- **Problem:**
  Inside `PlayerFragment.onCreate()`, multiple `LiveData` observers are attached to `this` (the Fragment instance rather than `viewLifecycleOwner`):
  ```kotlin
  viewModel.player.observe(this) { player ->
      playerView.player = player
      ...
  }
  viewModel.playerState.observe(this) { playerState ->
      loadingIndicator.isVisible = playerState == Player.STATE_BUFFERING
      ...
  }
  ```
  `playerView`, `loadingIndicator`, and `toolbar` delegate to `_playerBinding!!`.
  When a Fragment's view is destroyed and recreated (for example, when navigating into child dialogs, PiP transitions, or backstack pops), observers registered to `this` persist across view lifecycles. When an emission occurs before `onCreateView` completes or after `onDestroyView` sets `_playerBinding = null`, accessing these properties triggers a fatal `NullPointerException`.
- **Remediation:**
  Move UI state observations out of `onCreate()` and into `onViewCreated(view, savedInstanceState)`, passing `viewLifecycleOwner`:
  ```kotlin
  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
      super.onViewCreated(view, savedInstanceState)
      viewModel.player.observe(viewLifecycleOwner) { player -> ... }
      viewModel.playerState.observe(viewLifecycleOwner) { playerState -> ... }
      ...
  }
  ```

---

## 3. Medium-Severity Findings

### CON-01: Synchronous `runBlocking` on Main Thread in `LibraryService`

- **Location:** [LibraryService.kt](file:///home/syafiq/code/jellyfin-android/app/src/main/java/org/jellyfin/mobile/sessionbrowser/LibraryService.kt#L45)
- **Problem:**
  [`LibraryService`](file:///home/syafiq/code/jellyfin-android/app/src/main/java/org/jellyfin/mobile/sessionbrowser/LibraryService.kt) provides Android Auto and MediaSession integration. Its lifecycle method `onCreate()` executes directly on the Android main thread:
  ```kotlin
  override fun onCreate() {
      super.onCreate()
      runBlocking { apiClientController.loadSavedServerUser() }
  }
  ```
  `apiClientController.loadSavedServerUser()` performs database queries across the Room SQLite database. Using `runBlocking` on the main thread causes UI thread blockage. If the database is under load or performing a migration during startup, this can produce an Application Not Responding (ANR) crash when a media controller or Android Auto binds to the service.
- **Remediation:**
  Initialize server/user state asynchronously using a background coroutine scope, or defer resolution lazily inside `onGetSession` / `SessionBrowserCallback` where asynchronous futures (`CoroutineScope(Dispatchers.IO).future`) are already utilized.

---

### SEC-02: Plaintext Token Storage & Unencrypted Backup Exposure

- **Location:** [UserEntity.kt](file:///home/syafiq/code/jellyfin-android/app/src/main/java/org/jellyfin/mobile/data/entity/UserEntity.kt#L31), [backup_content.xml](file:///home/syafiq/code/jellyfin-android/app/src/main/res/xml/backup_content.xml#L16-L24)
- **Problem:**
  [`UserEntity`](file:///home/syafiq/code/jellyfin-android/app/src/main/java/org/jellyfin/mobile/data/entity/UserEntity.kt) stores the Jellyfin server `accessToken` in plaintext in the Room database:
  ```kotlin
  @ColumnInfo(name = ACCESS_TOKEN)
  val accessToken: String?
  ```
  Furthermore, [`backup_content.xml`](file:///home/syafiq/code/jellyfin-android/app/src/main/res/xml/backup_content.xml#L16-L17) includes the entire SQLite database in Android device cloud and ADB backups:
  ```xml
  <include domain="database" path="." />
  ```
  The Android 12+ (API 31+) `data_extraction_rules.xml` configuration is missing. Plaintext tokens can be extracted via device backup archives or local adb backups.
- **Remediation:**
  - Encrypt access tokens using Android Keystore / `EncryptedSharedPreferences`.
  - Add `res/xml/data_extraction_rules.xml` and update `backup_content.xml` to exclude tables or database files containing sensitive tokens.

---

### API-01: Missing Receiver Export Flags in `RemotePlayerService`

- **Location:** [RemotePlayerService.kt](file:///home/syafiq/code/jellyfin-android/app/src/main/java/org/jellyfin/mobile/webapp/RemotePlayerService.kt#L142), [AndroidManifest.xml](file:///home/syafiq/code/jellyfin-android/app/src/main/AndroidManifest.xml#L104)
- **Problem:**
  The project targets SDK 36. Starting with Android 14 (API 34), dynamic broadcast receivers registered at runtime must explicitly declare whether they are exported or not exported via `RECEIVER_EXPORTED` or `RECEIVER_NOT_EXPORTED`.
  While [`PlayerNotificationHelper.kt`](file:///home/syafiq/code/jellyfin-android/app/src/main/java/org/jellyfin/mobile/player/interaction/PlayerNotificationHelper.kt#L139-L144) uses `ContextCompat.registerReceiver(..., RECEIVER_NOT_EXPORTED)`, [`RemotePlayerService.kt`](file:///home/syafiq/code/jellyfin-android/app/src/main/java/org/jellyfin/mobile/webapp/RemotePlayerService.kt#L142) calls `registerReceiver(receiver, filter)` directly without flags, triggering a `SecurityException` on Android 14+ devices.
  Additionally, [`DownloadBroadcastReceiver`](file:///home/syafiq/code/jellyfin-android/app/src/main/AndroidManifest.xml#L104) in `AndroidManifest.xml` lacks an explicit `android:exported="false"` attribute.
- **Remediation:**
  Update [`RemotePlayerService.kt`](file:///home/syafiq/code/jellyfin-android/app/src/main/java/org/jellyfin/mobile/webapp/RemotePlayerService.kt#L142):
  ```kotlin
  ContextCompat.registerReceiver(
      this,
      receiver,
      filter,
      ContextCompat.RECEIVER_NOT_EXPORTED,
  )
  ```
  Add `android:exported="false"` to [`DownloadBroadcastReceiver`](file:///home/syafiq/code/jellyfin-android/app/src/main/AndroidManifest.xml#L104).

---

### STA-01: Potential Divide-by-Zero in Audio Volume Calculations

- **Location:** [MediaExtensions.kt](file:///home/syafiq/code/jellyfin-android/app/src/main/java/org/jellyfin/mobile/utils/MediaExtensions.kt#L77), [PlayerViewModel.kt](file:///home/syafiq/code/jellyfin-android/app/src/main/java/org/jellyfin/mobile/player/PlayerViewModel.kt#L464)
- **Problem:**
  Volume percentage is calculated by:
  ```kotlin
  (currentVolume - volumeRange.first) * Constants.PERCENT_MAX / volumeRange.width
  ```
  Where `IntRange.width = endInclusive - start`.
  On Android TV devices, HDMI passthrough systems, or fixed-volume audio endpoints, `getStreamMinVolume` equals `getStreamMaxVolume`, making `volumeRange.width == 0`. This causes an immediate unhandled `ArithmeticException: / by zero`.
- **Remediation:**
  Guard the division:
  ```kotlin
  val width = volumeRange.width.coerceAtLeast(1)
  return (currentVolume - volumeRange.first) * Constants.PERCENT_MAX / width
  ```

---

## 4. Low-Severity Findings

### SEC-03: Permissive Network Security Base Configuration

- **Location:** [network_security_config.xml](file:///home/syafiq/code/jellyfin-android/app/src/main/res/xml/network_security_config.xml#L4-L15)
- **Problem:**
  `cleartextTrafficPermitted="true"` and user CA certificates (`<certificates src="user" />`) are trusted globally across the application. While common for local Jellyfin homelab servers, global cleartext allows insecure HTTP traffic across public networks.
- **Remediation:**
  Warn users in the setup UI when connecting to plaintext HTTP endpoints, and restrict user-installed CA certificates to debug build types where possible.

---

### STA-02: Unhandled `ActivityNotFoundException` in `ExternalPlayer`

- **Location:** [ExternalPlayer.kt](file:///home/syafiq/code/jellyfin-android/app/src/main/java/org/jellyfin/mobile/bridge/ExternalPlayer.kt#L167)
- **Problem:**
  `playerContract.launch(playerIntent)` launches an external video player application via intent. If the selected app is uninstalled, disabled, or cannot handle the video MIME type, an uncaught `ActivityNotFoundException` crashes the app.
- **Remediation:**
  Wrap the launch call in a `try/catch (e: ActivityNotFoundException)` block:
  ```kotlin
  try {
      playerContract.launch(playerIntent)
  } catch (e: ActivityNotFoundException) {
      Timber.e(e, "External player activity not found")
      notifyEvent(Constants.EVENT_CANCELED)
      context.toast(R.string.external_player_not_supported_yet, Toast.LENGTH_LONG)
  }
  ```

---

### UX-01: Legacy Picture-in-Picture Transition Trigger

- **Location:** [PlayerFragment.kt](file:///home/syafiq/code/jellyfin-android/app/src/main/java/org/jellyfin/mobile/player/ui/PlayerFragment.kt#L384-L409)
- **Problem:**
  PiP mode relies on `onUserLeaveHint()`. In Android 12+ (API 31+), entering PiP from `onUserLeaveHint` causes a visible stutter during swipe-to-home gesture navigation.
- **Remediation:**
  On Android 12+, set `setAutoEnterEnabled(true)` on `PictureInPictureParams.Builder` when playback starts to enable smooth, gesture-integrated PiP transitions.
