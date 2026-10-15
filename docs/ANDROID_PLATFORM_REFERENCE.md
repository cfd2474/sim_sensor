# Android Platform Reference

The Android contracts Sim Sensor depends on. Read this before changing code that touches
permissions, foreground services, location, BLE scanning, managed configurations, app
installation or background limits (`CLAUDE.md` §6).

Status markers:
- 📄 **documented**: taken from the cited source, not yet seen on our hardware
- ✅ **verified**: observed on our devices (record device model, Android version and date)
- ⚠️ **disagreement**: an observation contradicts the source. Keep both.

Target: minSdk 26 (Android 8.0), targetSdk 36.

All cited URLs resolved on 2026-10-14. The 📄 entries summarize those pages; re-read the page
before relying on a detail, and record the result here.

---

## 1. Foreground service (GPS + BLE + socket while in background)

- 📄 From Android 14 (API 34), a foreground service must declare `android:foregroundServiceType`
  in the manifest. Each type also needs its own permission: `location` needs
  `FOREGROUND_SERVICE_LOCATION`, `connectedDevice` needs `FOREGROUND_SERVICE_CONNECTED_DEVICE`,
  in addition to `FOREGROUND_SERVICE`. Starting a service without the matching type and
  permission throws.
  Source: https://developer.android.com/develop/background-work/services/fgs/service-types
- 📄 From Android 12 (API 31), an app generally **cannot start** a foreground service while in
  the background. There are listed exemptions. Start it while the app is visible.
  Source: https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- 📄 A `location`-type service gets location access in the background only if it was started
  while the app was in the foreground, or the app holds background location.
  Source: https://developer.android.com/develop/sensors-and-location/location/background
- 📄 While a foreground service runs, location use counts as **foreground** location: "Your app
  retains access when it's placed in the background, such as when the user presses the Home
  button on their device or turns their device's display off." (read 2026-10-14)
  Source: https://developer.android.com/develop/sensors-and-location/location/permissions
  **Decision:** Sim Sensor does not request `ACCESS_BACKGROUND_LOCATION`. The service is
  started only from the visible activity and is `START_NOT_STICKY` (a background restart
  couldn't regain location anyway); a trainee reopens the app after a reboot.

## 2. Location permissions

- 📄 `ACCESS_FINE_LOCATION` is needed for precise GPS. From Android 12 the user may grant only
  approximate location, so the app must handle that.
- 📄 `ACCESS_BACKGROUND_LOCATION` (Android 10+) must be requested **separately, after**
  foreground location. On Android 11+ the user grants it on the system settings page, not in
  a dialog. (Not used by Sim Sensor, see §1.)
  Source: https://developer.android.com/develop/sensors-and-location/location/permissions
- 📄 A device-owner MDM can pre-grant runtime permissions
  (`DevicePolicyManager.setPermissionGrantState`). Some sensor-related permissions, location
  among them, may be restricted from auto-grant on newer Android versions when the device is
  only work-profile managed. Verify on the agency's MDM setup.
  Source: https://developer.android.com/reference/android/app/admin/DevicePolicyManager#setPermissionGrantState(android.content.ComponentName,%20java.lang.String,%20java.lang.String,%20int)

## 3. Bluetooth LE scanning (beacons)

- 📄 Android 12+ (API 31): scanning requires the runtime permission `BLUETOOTH_SCAN`.
  **Do not** set `android:usesPermissionFlags="neverForLocation"`. The docs say some BLE
  beacons are filtered from scan results when that flag is set, and Sim Sensor uses beacon
  results to infer position. Android 11 and below: scanning requires location permission
  instead.
  Source: https://developer.android.com/develop/connectivity/bluetooth/bt-permissions
- 📄 Android 6–11: BLE scan results also require **location services switched on** in system
  settings, not just the permission.
- 📄 **Scan throttling:** starting scans more than 5 times in 30 seconds causes the system to
  stop delivering results for a while. Source: AOSP Bluetooth stack (`GattService`
  `NUM_SCAN_DURATIONS_KEPT`, 30 s window). This is not on developer.android.com. Keep one
  long-running scan instead of restarting it.
- 📄 Unfiltered scans are paused when the screen turns off (Android 8.1+). Use a `ScanFilter`
  (for example the iBeacon manufacturer data) so scanning continues with the screen off.
  Source: https://developer.android.com/develop/connectivity/bluetooth/ble/find-ble-devices

## 4. Notifications

- 📄 Android 13+ (API 33): `POST_NOTIFICATIONS` is a runtime permission. Without it the
  foreground-service notification is hidden from the shade (the service still runs), and alarm
  notifications don't show.
  Source: https://developer.android.com/develop/ui/compose/notifications/notification-permission

## 5. Managed configurations (MDM)

- 📄 The app declares its keys in `res/xml/app_restrictions.xml`, referenced from the manifest
  with `<meta-data android:name="android.content.APP_RESTRICTIONS" android:resource="@xml/app_restrictions"/>`.
- 📄 Read with `RestrictionsManager.getApplicationRestrictions()`. Changes broadcast
  `ACTION_APPLICATION_RESTRICTIONS_CHANGED`, which can only be received by a receiver
  **registered at runtime** (not in the manifest). Re-read on app start as well.
  Source: https://developer.android.com/work/managed-configurations
- Sim Sensor keys (contract with the agency MDM, see `docs/DESIGN.md` §11.2): `server_url`,
  `enrollment_token`, `device_name`, `settings_pin`.

## 6. Networking

- 📄 Cleartext HTTP/WS is blocked by default for targetSdk 28+. Release builds use HTTPS/WSS
  only. Debug builds allow cleartext through `src/debug/AndroidManifest.xml`.
  Source: https://developer.android.com/privacy-and-security/security-config

## 7. Installation and updates (sideload + MDM)

- 📄 An update installs only if it is **signed with the same certificate** as the installed
  app. Otherwise the install fails, and the user or MDM must uninstall first, which loses the
  app's data.
- 📄 An APK with a **lower** `versionCode` than the installed one is rejected as a downgrade.
  An equal `versionCode` may be treated by an MDM as "already installed". Sim Sensor derives
  `versionCode` from `VERSION` (MAJOR·10000 + MINOR·100 + PATCH).
  Source: https://developer.android.com/studio/publish/versioning

## 8. Background execution and battery

- 📄 Doze and App Standby can defer network and alarms for apps without a foreground service.
  `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` asks the user to exempt the app. An MDM may be able to
  exempt it by policy.
  Source: https://developer.android.com/training/monitoring-device-state/doze-standby
- Some manufacturers add their own app killers on top of AOSP. To be verified on the agency's
  phone model.

## 9. Google Play (closed testing; Play Protect recognition)

Sim Sensor is published to a Google Play **closed testing** track as well as through the MDM
and sideloading. Play reviews the app even for closed testing.

- 📄 **Target API:** new apps and updates must target Android 16 (API 36) or higher. Sim Sensor
  targets 36. Checked against the page on 2026-10-14.
  Source: https://developer.android.com/google/play/requirements/target-sdk
- 📄 **Upload format:** Play takes an Android App Bundle (`.aab`, `bundleRelease`). The MDM and
  sideloading keep using APKs (`assembleRelease`) from the same source and key.
  Source: https://developer.android.com/guide/app-bundle
- 📄 **Signing:** decided 2026-10-14 to use **our own app signing key, uploaded to Play App
  Signing**. Play, MDM and sideloaded installs then share one signature and can update each
  other. This must be chosen when the app is first created in Play Console.
  Source: https://support.google.com/googleplay/android-developer/answer/9842756
- 📄 **Background location** needs a permissions declaration in Play Console (core feature
  justification and a video) and a review. **Not needed:** Sim Sensor doesn't request
  `ACCESS_BACKGROUND_LOCATION` (§1).
  Source: https://support.google.com/googleplay/android-developer/answer/9799150
- 📄 **Foreground service types** must be declared in Play Console with a description of the
  user-facing task. Sim Sensor currently declares only `location`; `connectedDevice` comes with
  BLE beacons in Phase 3.
  Source: https://support.google.com/googleplay/android-developer/answer/13392821
- 📄 **Sensitive permissions** in general: only request what a core feature needs.
  `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is limited to accepted use cases, so Play review may
  reject it. Plan: don't request it in the Play build; the MDM grants the exemption by policy.
  Source: https://support.google.com/googleplay/android-developer/answer/9888170
- 📄 **New personal developer accounts** must run a closed test with at least 12 opted-in
  testers for 14 days before they can apply for production access. Closed testing itself is
  not blocked by this. Doesn't apply to organization accounts.
  Source: https://support.google.com/googleplay/android-developer/answer/14151465
- Also required in Play Console: a privacy policy URL (the app collects precise location) and
  the Data safety form. Both are operator tasks with content prepared in chunk 1.5.

---

## Verified on hardware

Emulator results count as verified for platform behavior, not for Samsung-specific behavior.

- ✅ Location foreground service started from the visible app keeps delivering GPS fixes after
  HOME, with only `ACCESS_FINE_LOCATION` granted (no background location): new emulator
  positions reached the server every second while backgrounded; process state 4 (foreground
  service). — Android emulator, Android 14 (API 34), 2026-10-14
- ✅ Alarm tone and heads-up notification while backgrounded (alarm channel, importance
  HIGH). — Android emulator, Android 14 (API 34), 2026-10-14
- ✅ `simsensor://enroll?...` link opens the app's confirmation screen (VIEW intent, BROWSABLE).
  Whether the Samsung camera app offers to open it is to be checked on the XCover6 Pro.
  — Android emulator, Android 14 (API 34), 2026-10-14
- ✅ Git Bash trap: `adb shell ... /sdcard/x` gets rewritten to a Windows path. Prefix adb
  commands with `MSYS_NO_PATHCONV=1`. — dev machine, 2026-10-14

**Samsung Galaxy XCover6 Pro (SM-G736U1), Android 16 (API 36), One UI 8, build
G736U1UESCGZH1, device owner = ATLAS MDM (`com.taksolutions.atlasmdm`), 2026-10-14:**
- ✅ ATLAS pre-grants `ACCESS_FINE_LOCATION` and `POST_NOTIFICATIONS` by policy (flags
  `POLICY_FIXED`): no prompts on a managed phone. `CAMERA` is not pre-granted, so the in-app QR
  scanner asks for it. Add CAMERA to the ATLAS policy for Sim Sensor to avoid the prompt.
- ✅ Deep-link enrollment (`simsensor://enroll?...` via VIEW intent) → confirmation → enrolled →
  online over the real network, real GPS fix.
- ✅ **Screen off for 5 minutes with an active alarm:** 305 telemetry samples in 5:09, largest gap
  1 s; the CO ramp kept running and LOW/HIGH alarms were raised while the screen was off. The
  app was in standby bucket 10 (active) and **not** on the Doze allowlist: the location
  foreground service alone was enough. (Phone on charger; repeat on battery for longer periods.)
- ✅ Alarm tone: `ToneGenerator` on `STREAM_ALARM` beeps every 0.5 s with frames delivered
  (logcat `ToneGenerator: startTone` / `AudioTrack: stop ... frames delivered`), with the phone
  in vibrate mode (ringer mode 1, alarm volume 11/15). Loudness to be judged by ear by the
  operator.
- ✅ Instructor SILENCE command plus ACK stopped the beeping (0 `startTone` in the next 5 s).
- ✅ Keep screen on (`FLAG_KEEP_SCREEN_ON` on the activity window): the window manager holds a
  `SCREEN_BRIGHT_WAKE_LOCK` attributed to Sim Sensor while it is in front, and releases it when
  the app goes to the background (`dumpsys power` wake-lock history).
- ⚠️ This test phone has **Stay awake while plugged in** on (`stay_on_while_plugged_in=15`), so it
  never sleeps while charging. Screen-timeout and battery tests must be done unplugged.
- ⏳ Still open: Samsung camera app opening the QR link; in-app QR scan with the real camera;
  long run on battery (Samsung "sleeping apps" after days unused); the programmable XCover key.
