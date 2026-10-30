# HALO clean-room Muviz Edge behavior study

This file is a **behavioral comparison**, not a decompilation of Muviz Edge. We do not possess or redistribute its APK, extracted assets, proprietary code, keys, subscriptions, or premium material. HALO's code is independently implemented.

## Sources and evidence

- [Muviz Edge — Sparkine Labs](https://www.sparkine.com/muviz-edge/) — official product.
- [Muviz Edge — Google Play](https://play.google.com/store/apps/details?id=com.sparkine.muvizedge) — public description of design packs, palettes from album art, media app selection, fullscreen hiding, and custom AOD.
- [Android Visualizer documentation](https://developer.android.com/reference/android/media/audiofx/Visualizer) — FFT sample callbacks and platform capture restrictions.
- [Android MediaSessionManager](https://developer.android.com/reference/android/media/session/MediaSessionManager#getActiveSessions(android.content.ComponentName)) — permitted active sessions via enabled notification listener.
- [JADX](https://github.com/skylot/jadx), [Apktool](https://github.com/iBotPeaches/Apktool), [ReaDroid](https://github.com/Mcsd7/ReaDroid) — independently maintained inspection tools.
- [Flux AudioVisualizer](https://github.com/SRE-0/Audio-Visualizer) (MIT) and [NextGenVisualizer](https://github.com/jeffshee/NextGenVisualizer) (Apache 2.0) — open-source implementation references; HALO does not vendor their source.

## Observed Muviz feature → independent HALO equivalent

| Publicly described behavior | HALO implementation / status |
| --- | --- |
| Music-reactive screen-edge animation | FFT callback → logarithmic bands → independent frame interpolation |
| Multiple visualizer design packs | HALO wave, bars, trail, optional mechanical (designs are HALO originals) |
| Manual color palettes | HALO manual swatches |
| Color derived from album artwork | MediaSession metadata palette sampling with saved-color fallback |
| Select which music sources produce edge lighting | Allowlist of launchable app package names, applied to active Android media sessions |
| Run only while music is playing | Optional playing-session gate |
| Don't display over fullscreen games/videos | Not implemented; knowing the foreground application reliably may require additional user-granted Android usage access |
| Third-party custom AOD | **Not implemented**. Samsung protected AOD/Now Bar cannot be replaced from an ordinary overlay |
| Notification full-perimeter lighting | Separate HALO feature: character, full border or both, per-app choices |
| Fluid independent frame animation | Screen-refresh interpolation between FFT callbacks; input backlog coalescing |

## Reproducible local inspection (only for an APK you are authorized to examine)

This can identify an installed application's file location without root on many Android builds. The APK or split APKs are **not** stored in the HALO repository.

```sh
adb shell pm path com.sparkine.muvizedge
```

If the device permits pulling a listed path, use `adb pull "<path from pm path>" ./local-apks/` for **each split**, not merely the base file. For a locally held APK:

```sh
sha256sum ./local-apks/base.apk
apkanalyzer manifest print ./local-apks/base.apk
jadx -d ./local-analysis/jadx ./local-apks/base.apk
apktool d -f -o ./local-analysis/resources ./local-apks/base.apk
```

Treat decompiler output as **evidence**, not as a source-code dependency. Do not commit APKs, raw decompiled code, proprietary assets or unapproved captures. Check platform APIs, window types, permission boundaries, and lifecycle/state management, then implement and test original HALO components.

The analysis above is limited to *public documentation and independent HALO code inspection*. A real binary audit requires a lawfully acquired APK and separate analysis; it has not been performed here. Some features can only be verified on a real Galaxy device.
