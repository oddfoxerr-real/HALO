# HALO animation engine research — 0.13.0

The previous animation engine drew mostly independent paths and then applied glow.
Version 0.13.0 changes that architecture: AGSL computes animated distance-field geometry,
translucency, refraction-like highlights and audio modulation in one GPU shader program
(Android 13 and later). The prior Canvas renderer remains only as an API 29–32 /
unsupported-GPU fallback, not as the main renderer on supported Samsung phones.

## Permissively licensed references

- [Android-AGSL-Shader-Playground](https://github.com/mejdi14/Android-AGSL-Shader-Playground) — MIT.
  Native Android AGSL demonstrations including a neon edge, liquid chrome, vortex,
  waves and ripples. Techniques and API interaction are useful; no example code is bundled.
- [Shady](https://github.com/drinkthestars/shady) — MIT for the repository.
  The project also flags some Shadertoy-derived effects as **CC BY-NC-SA**, which we
  must not redistribute as permissively licensed production code. No third-party
  shader implementation from this project is bundled.
- [CAYADEV SoundVisualizer](https://github.com/CaYatur/SoundVisualizer) — MIT desktop
  GPU effects and audio-reactive compositor. Its Windows/macOS/Linux implementation
  cannot be directly imported into a native Android transparent overlay.
- [ProjectM Android](https://github.com/johnneerdael/ProjectM-Android) — LGPL-2.1 with
  native rendering and separately licensed presets. It is a full-frame MilkDrop engine,
  not a drop-in transparent Android music-edge renderer. No assets or library included.

## What actually changed

- GPU color and opacity follow the **same time-varying distance field** as the visual
  silhouette. This replaces applying extra blur behind an unmodified 2D effect.
- Music uses FFT energy; notifications drive the same shader with an artificial pulse
  envelope **without** starting audio capture or requiring RECORD_AUDIO permission.
- Transparent center, device-safe display cutout geometry, independent edge visibility,
  colors and sizing remain adjustable.
- Galaxy A56 / One UI hardware fidelity remains unverified. RuntimeShader is API 33+,
  so the existing original Canvas fallback remains for older phones or GPU failures.

HALO's AGSL shader source is written specifically for this project; no Muviz Edge
APK assets, proprietary shaders, Shadertoy licensed sketches, or third-party preset
packs are copied into the repository.
