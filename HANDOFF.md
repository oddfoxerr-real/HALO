# HALO PC continuation — 31 October 2026

Branch: `prototype/music-studio`. Based on main `7c0f26c` (v0.9.0).
Repository: https://github.com/oddfoxerr-real/HALO
Version prepared: **0.10.0**, versionCode **11**.

## User direction

HALO remains a notification studio with independent, optional music edge lighting.
Use Muviz Edge as a behavioral reference: screen-edge designs, colors, selected music
sources and playback rules. Give HALO its own Music section and appearance. Chainsaw
is one optional design, not the entire visualizer. Keep full-perimeter notification
choices and existing characters. Do not modify CYN or Gro-Ler.

## Implemented in this branch

- Dedicated **Music** bottom-navigation tab, sharing its screen with the notification shortcut.
- Designs / Tune / Playback segmented controls, five selectable designs: Silk, Spectrum,
  Orbit, Pulse and Chainsaw. Labeled animated demo preview; it does not claim to be live audio.
- Two-color gradients, four palettes, custom HEX colors and album-cover main color.
- Saved sensitivity, speed, thickness, glow, display inset and corner-radius controls.
- One music service: worker-thread Visualizer FFT at up to 25 Hz, 1024-sample capture,
  bounded latest-frame exchange and display-driven rendering with stale-data decay.
- Independent screen/playback/quiet-hours/silence visibility checks. Audio capture is
  disabled while the screen is locked/off or the media/quiet-hours rule blocks it.
- Observable service status, including no-signal and permission failures; explicit Stop.
- Notifications take priority over music temporarily, then music resumes. Persistent
  notification indicators are suppressed while music mode is active.
- Removed the obsolete separate Chainsaw activity/service and its local file player.
  Normal use is music from the user's existing player, visualized over other apps.
- Existing `chainsaw_music` preferences are retained for upgrade compatibility.

## Validation and next checks

- `git diff --check` passes.
- GitHub Actions is configured to run unit tests, assemble a debug APK, verify its
  signature and upload it for this branch. Check the latest branch run before using
  an APK; a source push alone is not a successful Android build.
- New tests cover stale capture decay, invalid samples, clearing animation state,
  visibility gates, notification priority and quiet hours. Existing FFT/motor tests remain.
- No Galaxy A56/One UI device validation has been performed for this branch.
- On a phone: test starting/stopping repeatedly, denying/revoking permissions, switching
  players, silence, lock/unlock, rotation, rapid slider updates, quiet hours and a real
  notification while music is active. Check touch-through behavior and frame pacing.
- Visually inspect all five designs at portrait/landscape sizes and large font settings.
- Android's output-mix Visualizer can be blocked by some devices/players. Source selection
  gates visibility by media session; it does not isolate concurrent apps' mixed audio.
- Samsung native AOD/lock-screen replacement, foreground-app exclusion, fullscreen-game
  detection and camera-cutout music routing are NOT implemented.
- Existing CI generates debug signing keys on each runner. Do not assume an APK can
  update an installed v0.9 build without verifying signing-certificate compatibility.

## Build / continuation

```sh
git clone https://github.com/oddfoxerr-real/HALO.git
cd HALO
git switch prototype/music-studio
```

Use JDK 17, Android SDK 36 and Gradle 8.13, then run:

```sh
gradle :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Main has an automatic release workflow. Keep development on this branch until review;
merging version 0.10.0 into main will trigger publication. Do not replace the existing
v0.9.0 release asset. See `docs/MUVIZ_EDGE_STUDY.md` for public reference links. No Muviz
APK decompilation or copying of its source/assets was performed.
