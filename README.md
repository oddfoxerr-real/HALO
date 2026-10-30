# HALO — Samsung Notification Character Studio

**v0.7.0 floating-edge candidate · Android 16 / Galaxy A56 target · Kotlin · Jetpack Compose Material 3**

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

## Verified v0.6.0 prototype build

- **Successful CI run:** https://github.com/oddfoxerr-real/HALO/actions/runs/41081950468
- **APK artifact:** `HALO-debug-apk` (contains `app-debug.apk` and SHA-256 checksum)
- **CI checks passed:** unit tests, debug APK assembly, APK signing verification, and artifact upload.
- **Still unverified:** installation and background overlay behavior on the Samsung Galaxy A56.
- **Note:** a previous HALO v0.1 debug APK may use a different signing certificate. If Android rejects the update due to a certificate mismatch, retain the existing app data before replacing the old build.

## Build APK via GitHub Actions

The repository must be **separate from CYN and Gro-Ler**. Create `oddfoxerr-real/HALO` (if needed) and upload all project files to the repo root, preserving `.github/workflows/android-debug.yml`.

1. Push to `main` or any `prototype/**` branch, or select **Actions → Build HALO Android APK → Run workflow**.
2. The workflow installs JDK 17, Android SDK 36, Gradle 8.13, runs `:app:testDebugUnitTest :app:assembleDebug`, and verifies the APK signature.
3. Open the completed run → **Artifacts → HALO-debug-apk**. GitHub distributes the APK in a ZIP. Extract `app-debug.apk` to install it. On merge to `main`, `.github/workflows/android-release.yml` publishes a named APK to GitHub Releases for the current `versionName` if that version does not yet exist.

**The v0.6.0 prototype APK compiled and passed unit tests and APK signature verification on GitHub Actions.** It has not been physically tested on a Galaxy A56. The earlier [v0.5.0 GitHub Release](https://github.com/oddfoxerr-real/HALO/releases/tag/v0.5.0) exists. The v0.6.0 direct Release APK will publish after this branch is merged and its release workflow passes. Use the Actions artifact until then.

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

**Samsung-native Now Bar integration is not exposed through public Android APIs.** HALO v0.5 offers its own separately positioned unlocked-screen media outline.
 
## v0.5 — Pill Halo (opt-in media outline)

- Add **Settings → Pill Halo → Calibrate on phone** to size and position the outline on the actual display.
- Detect supported playing media sessions through Android's notification-listener-authorized `MediaSessionManager`; no microphone or audio capture.
- Color presets for Spotify, YouTube Music, SoundCloud and a user-configurable fallback color.
- Display the pill only when HALO is enabled, playback is active, overlays are permitted, the phone is interactive and unlocked, and quiet hours are not active.
- Disabled by default. A transparent non-touch overlay does not replace Samsung's Now Bar, AOD or other protected SystemUI surfaces.
- Samsung Galaxy A56 real-device verification remains necessary before claiming lock-screen support.

## v0.6 — Chainsaw music studio

Home → **Open music visualizer** launches a native Canvas music mode inspired by
Chainsaw Man's mechanical aesthetic, using original steel-tooth and resin artwork.
Teeth circulate around the rounded screen perimeter. Bass onsets kick the motor,
throw sparks and clear the jam; quiet sections make it decelerate, stutter and clog.
Sensitivity, chain speed, edge size and glow are saved independently of notification profiles.

Choose **Use device audio** while another player is running, or **Open music file**
to play a song through HALO. Android's Visualizer API supplies real FFT data: no
random or timed fake beats. Device-output analysis is hardware/player dependent;
if it is blocked or silent, HALO shows a no-signal message and the chain stays jammed.
The local-file path attaches directly to the MediaPlayer audio session.

Android requires `RECORD_AUDIO` for Visualizer analysis and `MODIFY_AUDIO_SETTINGS`
for output-mix access. Permission is requested only when starting this feature.
No microphone API is used, and no audio is recorded, uploaded, or stored. Files are
selected with Android's document picker, without broad storage permission.
Playback and analysis stop when leaving the music screen or losing audio focus.
This first music mode is an in-app full-display visualizer, **not a background,
lock-screen or always-on-display music overlay**. Existing notification effects are unchanged.

Validation: unit tests cover silence, bass restart, re-jamming, sustained tones,
FFT band selection and frame-rate independence. Physical Samsung playback and
rendering still require device testing. The prototype APK build passed in [GitHub Actions](https://github.com/oddfoxerr-real/HALO/actions/runs/41081950468). The release workflow runs on main app changes and creates a versioned APK Release only if it does not already exist (avoiding silent replacement of a differently signed debug APK).


## v0.7 — Optional floating chainsaw music edge (hardware-testing candidate)

- Home → Chainsaw Music → **Start floating edge over other apps** requests audio/overlay permission on use. The edge uses a dedicated foreground service, so it can remain visible when the user switches to a separate music player.
- Android requires `RECORD_AUDIO` for its low-quality FFT Visualizer interface, and a microphone-type foreground service for continued while-in-use access. HALO **does not create AudioRecord, use the microphone input, save recordings, use MediaProjection or upload audio**. Android may nevertheless show audio-access/foreground-service indicators.
- The notification has an explicit **Stop edge** action. HALO does not automatically start the floating mode after reboot. Stopping the service releases the audio effect and removes the window.
- Floating effects are **hidden on locked, sleeping and AOD screens**. HALO notification and Pill Halo overlays are suppressed while floating mode runs to avoid stacking semi-transparent windows.
- Output-mix FFT session 0 can be silent, unsupported or restricted for certain players/hardware. HALO does not simulate beats when audio is inaccessible. Local-file and in-app visualization remain available as alternatives.
- **Status:** source candidate, not yet tested on a real Galaxy A56. The v0.6.0 release remains the verified downloadable build until v0.7.0 CI and release complete. An in-place Android update from earlier builds may fail if the CI debug signing certificate differs.
