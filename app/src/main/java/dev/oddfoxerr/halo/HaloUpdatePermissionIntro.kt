package dev.oddfoxerr.halo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * The only upfront updater permission experience. Android owns the actual
 * "Install unknown apps" grant and any final package installation consent.
 * Dismissing the intro never disables background checks/downloads.
 */
@Composable
internal fun HaloUpdatePermissionIntro(resumeRevision: Int) {
    val context = LocalContext.current
    val prefs = remember(context) { HaloUpdates.prefs(context) }
    var dismissed by remember { mutableStateOf(false) }
    val installGranted = remember(resumeRevision) {
        context.packageManager.canRequestPackageInstalls()
    }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Notifications are optional. The updater continues through in-app
        // status even if permission is denied.
    }
    val prompt = !installGranted &&
        !prefs.getBoolean("install_intro_handled", false) && !dismissed
    if (prompt) {
        AlertDialog(
            onDismissRequest = {
                dismissed = true
                prefs.edit().putBoolean("install_intro_handled", true).apply()
            },
            title = { Text("Enable automatic HALO updates") },
            text = {
                Text("HALO checks and downloads verified updates automatically. " +
                    "Allow HALO to install apps once in Android settings. " +
                    "Android may still ask you to confirm individual installations.")
            },
            confirmButton = {
                TextButton(onClick = {
                    dismissed = true
                    prefs.edit().putBoolean("install_intro_handled", true).apply()
                    try {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + context.packageName)
                            )
                        )
                    } catch (_: Exception) {
                        // The button in Settings remains available if the OEM
                        // lacks the expected per-source permission screen.
                    }
                }) { Text("Allow updates") }
            },
            dismissButton = {
                TextButton(onClick = {
                    dismissed = true
                    prefs.edit().putBoolean("install_intro_handled", true).apply()
                }) { Text("Later") }
            }
        )
    } else if (installGranted &&
        Build.VERSION.SDK_INT >= 33 &&
        !prefs.getBoolean("update_notifications_asked", false) &&
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) {
        // Only request notification access if the user has already opted in to
        // updates. It permits the OS install-confirmation notification when
        // HALO is not on screen, but is never a requirement to download.
        AlertDialog(
            onDismissRequest = {
                prefs.edit().putBoolean("update_notifications_asked", true).apply()
            },
            title = { Text("Update notifications") },
            text = {
                Text("Allow an update-ready notification if Android needs your " +
                    "confirmation while HALO is in the background?")
            },
            confirmButton = {
                TextButton(onClick = {
                    prefs.edit().putBoolean("update_notifications_asked", true).apply()
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }) { Text("Allow notifications") }
            },
            dismissButton = {
                TextButton(onClick = {
                    prefs.edit().putBoolean("update_notifications_asked", true).apply()
                }) { Text("Not now") }
            }
        )
    }
}
