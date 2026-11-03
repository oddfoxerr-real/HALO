# ItemLens — Samsung A56 native Android app

This isolated **itemlens-apk-build** branch builds ItemLens without modifying the HALO main branch.

The source is packaged in `itemlens-source.b64` as a compressed Android Kotlin/Compose/ARCore project. The build workflow extracts the source, deterministically generates the calibration marker, compiles the app, and uploads the **installable APK**.

## Android install

Open **Actions → Build ItemLens APK → latest successful run → Artifacts → ItemLens-0.1.0-debug-apk**. Download and extract the artifact ZIP, then tap `app-debug.apk` on your Galaxy A56 to install it.

## Tracking limits

Saved item locations are projected through walls **only as saved coordinates**. This app does not see or detect physical objects through walls. It needs ARCore and a stable 16 cm printed reference marker.

The debug APK is for prototype testing, not a production release.
