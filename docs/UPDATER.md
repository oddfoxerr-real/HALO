# HALO automatic updater and stable signing

## Automatic operation

HALO schedules a network-constrained background check every six hours through WorkManager. On startup it schedules another check when the last successful check was more than 15 minutes ago. Android may defer a check due to Doze, networking or battery restrictions.

HALO examines all GitHub releases, including prereleases, but only downloads a strictly named HALO-vX.Y.Z-release.apk and corresponding SHA256SUMS.txt from oddfoxerr-real/HALO. Debug APKs cannot be auto-installed. The checksum is verified and Android's PackageManager confirms a newer version with the same package ID and signing certificate before installation.

The updater asks Android PackageInstaller to install without user action. When the operating system requires approval, HALO keeps the APK ready on the Settings page and never opens unsolicited permission screens over unrelated apps.

## Mandatory one-time GitHub signing secret

**Previous HALO 0.20.15-0.20.21 APKs were signed with DIFFERENT temporary debug keys, so updates between those APKs are rejected by Android.**

HALO's new release workflow uses one stable, private key and will fail closed if it is not configured. Keep this key private; do not commit it into the public GitHub repository.

1. Open oddfoxerr-real/HALO in GitHub, then Settings -> Secrets and variables -> Actions.
2. Create a new repository secret with the name HALO_SIGNING_MATERIAL_B64.
3. Paste the full one-line contents of the privately delivered file HALO-GitHub-signing-secret.txt into the secret value field.
4. Keep your private HALO-stable-release-key.p12 backup. Never lose this key; future updates need the same certificate.

The one GitHub secret contains a Base64-encoded JSON record with an encrypted PKCS12 keystore, a long random password, and the halo-release alias. GitHub Actions decodes it only in a temporary CI runner, builds a signed release variant, verifies its signature, and publishes the APK and SHA256SUMS.txt.

**One-time reinstall required:** Existing 0.20.15-0.20.21 installs cannot be upgraded in place to the new stable signing identity. Android requires uninstalling the old debug-signed app and installing the first stable-signed release. Uninstalling normally erases local app settings. Once the stable-signed version is installed, later releases can update in place using the same certificate.

## Android security constraints

REQUEST_INSTALL_PACKAGES may require one-time Install unknown apps access. UPDATE_PACKAGES_WITHOUT_USER_ACTION and PackageInstaller USER_ACTION_NOT_REQUIRED request a silent self-update only if Android allows it. The OS may return STATUS_PENDING_USER_ACTION; HALO does not bypass this requirement with root, accessibility automation, Shizuku or hidden shell commands.

No manual Update check is needed. The Settings -> Automatic updates card displays the last check, download status and an Install button only when Android requires user action.
