# Slate clipboard maintenance

This directory patches the independent Slate v0.3 source restored by
`.github/workflows/build-slate-v03.yml`; it does not modify the HALO application.

The original image import writes directly to a `.data` file before adding it to
history. Any text capture or history edit meanwhile removes that file as an
unreferenced image. Different IME, photo-picker and settings store instances also
use different locks. A pending import can repopulate history after the user clears
it or switches history off.

The patch stages bytes in `.pending`, publishes the completed file and history
under one shared lock, and invalidates pending imports when history is cleared.
It checks the history setting again before publishing. Stores share one worker
and retain only the application context. The existing 8 MB limit, 35-item history,
pin controls, package identity, keyboard behavior and signing configuration stay
in place. The build is version 0.3.1 (4).

CI uses the real ClipboardStore with Robolectric Android services and controlled
input streams. It first requires the original implementation to fail the image
retention regression, then runs all six tests against the patch and builds the
APK. This is JVM Android simulation, not Galaxy A56 testing. Image insertion in
third-party editors and Samsung keyboard feel remain device checks.

No runtime dependency is added; JUnit and Robolectric are test-only.
