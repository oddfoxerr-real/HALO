# HALO — Samsung Notification Character Studio

**Development: v0.11.0 Animation Studio** — 15 original audio-reactive music designs and nine notification-border effects. This is a developer preview; Samsung One UI hardware validation is still pending. Earlier version sections below are historical.

## v0.11.0 — Animation Studio

- **Music (15):** Silk, Spectrum, Orbit, Pulse, Chainsaw, Aurora, Comet, Twin Streams, Sparks, Pixel Rush, Neon Circuit, Rainfall, Helix, Bloom, and Prism.
- **Notification borders (9):** Pulse, Outline Sweep, Soft Glow, Comet Trail, Chasing Dashes, Electric Sparks, Ripple, Aurora, and Beacon.
- **Refined existing effects:** spectrum smoothing, Silk motion, Chainsaw energy, and continuously animated retained notification borders.
- **Performance:** bounded Canvas draw counts, cached geometry and shaders where applicable, no bitmaps or WebViews, and the existing frame-pacing/audio-signal lifecycle.
- **Customization:** existing palettes, independent four-side offsets and visibility, brightness, glow, thickness and playback rules remain available. Every music effect can be previewed using the built-in demo signal.
- **Limitations:** Audio visualization varies by player and Android permissions. Overlays cannot guarantee drawing over Samsung-protected status icons, native AOD, or lock screen. Device testing is required before a stable release.



**v0.9.0 clean-room Muviz feature study · Android 16 / Galaxy A56 target · Kotlin · Jetpack Compose Material 3**

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

1. Push to a `prototype/**`, `feature/**` or `fix/**` branch, or select **Actions → Build HALO Android APK → Run workflow**. Main also builds, and its release workflow publishes new versions.
2. The workflow installs JDK 17 and Android SDK 36, uses the checked-in Gradle 8.13 wrapper, runs `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug`, and verifies the APK signature. Test and lint reports are uploaded as `HALO-validation-reports`.
3. Open the completed run → **Artifacts → HALO-debug-apk**. GitHub distributes the APK in a ZIP. Extract `app-debug.apk` to install it. On merge to `main`, `.github/workflows/android-release.yml` publishes a named APK to GitHub Releases for the current `versionName` if that version does not yet exist.

**The v0.6.0 debug APK passed GitHub Actions unit tests and signature verification and was published in [GitHub Releases](https://github.com/oddfoxerr-real/HALO/releases/tag/v0.6.0).** The newer [v0.7.0 APK](https://github.com/oddfoxerr-real/HALO/releases/tag/v0.7.0) adds optional floating mode. Neither version has been validated on a physical Galaxy A56.

### Local build

Use JDK 17 and Android SDK platform 36. Set `JAVA_HOME` and `ANDROID_HOME` (or configure the SDK through Android Studio), then run:

```shell
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

On Windows PowerShell, use `.\gradlew.bat` with the same tasks. The standard wrapper JAR is included in the repository and downloads Gradle 8.13 with a pinned SHA-256 checksum. The first build needs network access for Gradle, Android build tools and dependencies; a separate Gradle installation is unnecessary.

### Offline Kotlin policy checks

```shell
kotlinc app/src/main/java/dev/oddfoxerr/halo/Profiles.kt app/src/main/java/dev/oddfoxerr/halo/NotificationRules.kt tests/PolicySmoke.kt -include-runtime -d halo-policy.jar
java -jar halo-policy.jar
```

This historical source checkpoint passed 44 Kotlin policy assertions. The Gradle build above runs the complete JUnit suite, including music visibility, signal decay and frame-pacing checks.

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

- Android unit tests, lint, APK assembly and signing passed for the Music studio continuation in [this CI run](https://github.com/oddfoxerr-real/HALO/actions/runs/41236195628). The remaining checks require a device.
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
- **Status:** [v0.7.0 debug APK released](https://github.com/oddfoxerr-real/HALO/releases/tag/v0.7.0) following GitHub Actions unit tests, APK build and signature verification. The floating-overlay behavior is **not yet tested on a real Galaxy A56**. An in-place Android update from earlier builds may fail if the CI debug signing certificate differs.


## v0.8 — Music edge studio and full-screen notification lighting

**Major correction:** the Chainsaw Man-inspired chain is no longer the entire music visualization experience. The Home music action opens a separate Material 3 `MusicEdgeActivity`. The default is a neon **Wave** profile, with **Spectrum**, **Neon trail** and optional **Mechanical** styles; colors, sensitivity, size, glow and speed are saved. A clearly labeled **demo-signal preview** lets you inspect animations without requesting access to audio. The existing local-file mechanical prototype remains in source but is no longer the primary UI.

The new `MusicEdgeService` uses the Android `Visualizer.setDataCaptureListener` FFT callback rather than synchronous `getFft` polling every 33 ms. The new renderer draws a bounded 112-point wave or at most 88 bars, instead of repeatedly painting hundreds of tiny teeth around a full-screen translucent window. Audio access is opt-in, an ongoing notification contains **Stop visualizer**, and locked/sleeping screens hide the overlay. The visualizer does not use the microphone input, network or audio uploads. Android/Samsung may still block some media session data. The system-wide overlay remains experimental until tested on a physical Galaxy A56.

**Notifications** now have **Character / Full border / Both** modes, selectable in Settings and overridable per installed app. The full display perimeter has **Pulse**, **Outline sweep** and **Soft glow** styles, adjustable thickness/glow, a test button and a persistent-until-dismissed option. Per-app HEX colors also drive the perimeter. This follows the display border, **not** the protected screen coordinates of Samsung's native heads-up cards, Now Bar, AOD or notification shade.

**Performance work:** character preview waveform geometry is bounded to roughly 64 line segments and refreshed at about 30 fps rather than drawing hundreds of nodes every screen refresh. The music overlay removes unnecessary full-window hardware layer caching, limits animation to new FFT callbacks, and suppresses other overlapping HALO overlays to reduce occlusion and touch glitches.

The v0.8 debug release must only be considered ready after GitHub Actions successfully compiles/tests/signs it and a GitHub Release is published. CI checks cannot substitute for checking actual Samsung freezing or playback capture with a device.

## v0.9 — Public-feature audit, album artwork and smoother rendering

This update is **not** a decompilation of Muviz Edge. The clean-room comparison and optional local APK inspection instructions are in [docs/MUVIZ_EDGE_STUDY.md](docs/MUVIZ_EDGE_STUDY.md). HALO uses its own implementation based on published behavior and Android's public APIs.

- In Music Edge Studio choose **My colors** or **Album artwork**. Album cover colors use sampled Android media-session metadata and fall back to a saved color if missing; no images are stored or uploaded.
- **Only while music is playing** optionally hides the edge when Android exposes no active playing media session.
- **Music sources** lets you limit lighting to selected installed player apps; no selection allows all. App filtering requires Notification Access and operates on music **source** packages, not foreground screen detection.
- FFT data is coalesced before UI delivery and the edge renderer interpolates smoothly between Android audio callbacks. Rendering rests after the signal decays.
- Existing Character / Full border / Both notification modes remain unchanged.
- Some apps/DRM content do not expose audio data to Android Visualizer. HALO cannot bypass those restrictions. No direct Muviz binary analysis or Samsung hardware validation has been performed.

## v0.10 Music studio

Open the **Music** tab. Pick Silk, Spectrum, Orbit, Pulse or Chainsaw, choose a two-color
palette and use Tune to fit the edge to your display. The preview button is explicitly
a demo signal. Start music lighting, then play audio from your normal music app.
Lighting runs over other apps, yields to notifications, and hides on lock or silence.
Its own Playback settings control media sources and quiet hours. These settings are
independent of notification characters. One music service replaces the earlier two
competing services; the old separate local-file music player has been removed.

HALO uses Android's [Visualizer](https://developer.android.com/reference/android/media/audiofx/Visualizer)
for low-resolution playback analysis. `RECORD_AUDIO` is requested only when music starts;
no microphone audio is recorded. Not all players/devices expose the output mix.
Background overlays require Display over other apps; playing-session rules and album
colors need Notification access. No native Samsung AOD replacement is claimed.
