# HALO background updater

HALO v0.21 schedules Wi-Fi-only, battery-aware GitHub release checks approximately every six hours using Android WorkManager. Work remains scheduled across device restarts. A newer release is downloaded into private app cache without a user opening the app.

Before presenting installation, HALO checks:
- The release tag is a higher three-part version.
- The APK and SHA256SUMS assets belong to the official GitHub repository.
- The APK digest matches the release checksum.
- The APK contains the HALO application ID, a higher Android version code, and exactly the same signing certificate as the installed HALO app.
- The download fits a bounded size and uses HTTPS only.

On ordinary Android phones, **installation still requires Android system approval**. HALO raises an update-ready notification, or opens the system installer when HALO is next foregrounded. It does not circumvent Android protections or launch arbitrary installers from a background worker.

## Required one-time GitHub signing setup

The pre-v0.21 GitHub debug APKs were signed by short-lived CI debug keys. Those certificates are not automatically shared across GitHub Actions runners. Without a stable key, Android will reject updates as incompatible.

The private repository administrator must create a long-lived Android signing keystore with alias `halo`, using the same password for keystore and key. Do not commit this keystore to GitHub.

On Windows, with a Java JDK on PATH:

```powershell
keytool -genkeypair -v -keystore halo-updates.jks -alias halo -keyalg RSA -keysize 3072 -validity 10000 -dname "CN=HALO Updates,O=HALO,C=ZA"
[Convert]::ToBase64String([IO.File]::ReadAllBytes((Resolve-Path .\halo-updates.jks))) | Set-Clipboard
```

Set these repository Actions secrets in GitHub Settings → Secrets and variables → Actions:
- `HALO_SIGNING_KEYSTORE_B64`: paste the Base64 keystore string from the clipboard.
- `HALO_SIGNING_PASSWORD`: the keystore/key password entered in `keytool`.

Store a private offline backup of the key and password. All later HALO releases must use these **same** secrets. The release workflow refuses to publish an updater-enabled APK when either is missing.

### Migration note

An older HALO installation signed with an unrelated CI debug key cannot be updated in place with the new stable key. Android will require uninstalling the old installation once, which removes app-local settings because HALO currently disables app data backup. After installing the first stable-key APK, later releases using that same key can update normally without uninstalling. Do not assume that a signing mismatch can be bypassed.

On Samsung phones, allow HALO's **Install unknown apps** permission when Android asks the first time. Allow notifications to receive background update-ready alerts; otherwise the next time HALO opens it presents the verified install handoff.

This updater is intended for the GitHub-distributed development APK and is not a replacement for Google Play's in-app update infrastructure. No self-install is performed silently.
