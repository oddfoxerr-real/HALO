# HALO — Samsung Notification Character Studio

**v0.4.1 debug APK built in GitHub Actions · Android 16 / Galaxy A56 target · Kotlin · Jetpack Compose Material 3**

HALO is a real Android notification-listener app rather than a browser simulation. Its native Canvas renderer provides five character effects: Discord-inspired liquid mascot, WhatsApp bubble, YouTube play character, Gmail envelope, and a generic star. Character illustrations are original, not official brand assets.

## Features

- **Home:** master switch, permission setup, active notification count, local recent-event activity (app and time only), quick character preview.
- **Character Studio:** choose among five animation profiles; customize color/HEX, left/right entrance, scale, vertical placement, glow, duration, edge inset, and persistent indicator.
- **App Rules:** search launchable installed apps, choose their character (not limited to the app's default mascot), turn effects on/off per app, apply a per-app HEX or preset color, reset app rules.
- **Full-phone Preview:** genuinely full-screen native preview, auto-looping, true device-size rendering, live sizing/position, hideable controls, and manual frame scrubbing without repetitive replay taps.
- **Settings:** notification access/overlay permission links, overnight quiet hours, optional silent-notification inclusion, screen-on-only behavior, optional Samsung wallpaper-derived Material 3 dynamic colors.
- **Notification reliability:** screen-on restoration, screen-off overlay dismissal, quiet-hours auto-refresh, silent notification eligibility applied consistently to animation and unread indicators, and all tracking serialized on the main thread.
- **Notifications:** `NotificationListenerService` handles posted/removed events, suppresses ongoing, foreground-service and group-summary notifications, deduplicates notification keys, resumes known active notifications after reconnection, and tracks dismissals. No message body is retained.
- **Overlay:** narrow, non-touchable translucent edge window, with one-shot character animation and optional static unread dot until dismissal; screen-off/secure/AOD rendering is not promised.

### Setup on the Galaxy A56

1. Install a **built** `app-debug.apk`, not this source ZIP.
2. Open HALO → Settings; grant **Notification access** and **Display over other apps**.
3. Check the HALO master switch on Home.
4. On Studio choose a character and tap **Full phone preview** to tune it; changes persist on device.
5. On Apps select installed applications and assign character rules.
6. Receive a real notification. HALO will animate only when its notification rules, quiet hours, permissions and screen behavior allow it.

## Verified v0.4.1 GitHub Actions build

- **Successful CI run:** https://github.com/oddfoxerr-real/HALO/actions/runs/40759790614
- **APK artifact:** `HALO-v0.4.1-debug-apk` (contains `app-debug.apk` and SHA-256 checksum)
- **CI checks passed:** unit tests, debug APK assembly, APK signing verification, and artifact upload.
- **Still unverified:** installation and background overlay behavior on the Samsung Galaxy A56.
- **Note:** a previous HALO v0.1 debug APK may use a different signing certificate. If Android rejects the update due to a certificate mismatch, retain the existing app data before replacing the old build.

## Build APK via GitHub Actions

The repository must be **separate from CYN and Gro-Ler**. Create `oddfoxerr-real/HALO` (if needed) and upload all project files to the repo root, preserving `.github/workflows/android-debug.yml`.

1. Push to `main` or `prototype/v0.4.1`, or select **Actions → Build HALO Android APK → Run workflow**.
2. The workflow installs JDK 17, Android SDK 36, Gradle 8.13, runs `:app:testDebugUnitTest :app:assembleDebug`, and verifies the APK signature.
3. Open the completed run → **Artifacts → HALO-v0.4.1-debug-apk**. GitHub distributes the APK in a ZIP. Extract `app-debug.apk` to install it. For a direct Release asset, push a `v0.4.1` tag; `.github/workflows/android-release.yml` then publishes a named APK to GitHub Releases.

**The v0.4.1 debug APK has been compiled and signature-verified on GitHub Actions.** It has not been physically tested on a Galaxy A56 or published as a tagged GitHub Release. Download the successful run's APK artifact. Do not label the older v0.1 APK as v0.4.1.

### Local build

Use Android Studio with JDK 17, Android SDK 36 and Gradle 8.13. The preinstalled Gradle CLI can run:

```shell
gradle :app:testDebugUnitTest :app:assembleDebug
```

The project includes an optional wrapper bootstrapper; it requires network access to acquire and verify its missing wrapper JAR. If the wrapper is unavailable, use the installed Gradle 8.13 CLI, as in GitHub Actions. No SDK or Gradle is available in the current execution container.

### Offline Kotlin policy checks

```shell
kotlinc app/src/main/java/dev/oddfoxerr/halo/Profiles.kt app/src/main/java/dev/oddfoxerr/halo/NotificationRules.kt tests/PolicySmoke.kt -include-runtime -d halo-policy.jar
java -jar halo-policy.jar
```

This source checkpoint passed 44 Kotlin policy assertions. JUnit Android-project tests are included but not runnable here without the SDK/Gradle.

## Implementation structure

| File | Purpose |
|---|---|
| `MainActivity.kt` | AMOLED Material 3 Home, Studio, Apps, Settings |
| `PreviewActivity.kt` | Native full-screen preview/editor using real display size |
| `CharacterView.kt` | Five Canvas character animations and persistent LED dot |
| `HaloNotificationListener.kt` | Permissioned real notifications and ranking/quiet-hour filter |
| `HaloOverlay.kt` | Touch-transparent edge overlay lifecycle |
| `NotificationRules.kt` | Per-package rules, overnight quiet-hour policy |
| `NotificationActivityState.kt` | Memory-only recent events/active counts |
| `Profiles.kt`, `ProfileStore.kt` | Model, deduplication, persistence |
| `.github/workflows/android-debug.yml` | GitHub Actions APK build and artifact upload |

## Android limitations and design reference

HALO cannot reliably overlay Samsung's native Always On Display, light a powered-off panel, or override secure-screen overlays. It doesn't request Accessibility, root access, contacts, or network permission. The installed-app picker uses launchable-app visibility rather than Android's broad `QUERY_ALL_PACKAGES` permission.

Ideas researched from [aodNotify on Google Play](https://play.google.com/store/apps/details?id=com.jamworks.aod.notificationlightled) include per-app lighting, persistent visual indicators and night-time suppression. Official implementation references: [Material 3 in Compose](https://developer.android.com/develop/ui/compose/designsystems/material3) and [NotificationListenerService](https://developer.android.com/reference/android/service/notification/NotificationListenerService). HALO's app-specific cartoon animations are original to this project.

### Verification still needed

- Android lint on SDK 36 (CI compile and unit tests already passed).
- Test the overlay's touch pass-through and screen cutout handling on the Samsung A56.
- Confirm notification arrival, dismissal, background execution and battery saver behavior on One UI.
- Check performance and notification-rank handling across Samsung notification channels.

## Important release note

The previous Work-created v0.1 debug build may use a **different debug signing key** than GitHub Actions. Android cannot install an update signed by a different certificate. If installation fails with a signature conflict, do not assume the APK is corrupted; a consistently managed signing key is needed for seamless updates. Do not commit private signing keys to GitHub.

**Pill Halo / Samsung Now Bar integration is intentionally excluded from this release.** That feature is planned only after this native notification build is installed and verified.
