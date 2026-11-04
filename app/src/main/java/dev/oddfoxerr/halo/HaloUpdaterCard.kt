package dev.oddfoxerr.halo

import android.content.Intent
import android.content.SharedPreferences
import android.provider.Settings
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

/** Read-only update status by default. No manual Check button or permission popup. */
@Composable
internal fun HaloUpdaterCard() {
    val context = LocalContext.current
    val prefs = remember(context) { HaloUpdates.prefs(context) }
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            revision++
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val snapshot = remember(revision) { HaloUpdates.snapshot(context) }
    val canInstall = context.packageManager.canRequestPackageInstalls()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("Automatic updates", style = MaterialTheme.typography.titleMedium)
            Text(
                "HALO checks for updates in the background and automatically downloads " +
                "verified releases. Android may request one-time install permission " +
                "and confirmation when needed.",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                if (canInstall) "App installation access: allowed"
                else "App installation access: not yet allowed",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!canInstall) {
                OutlinedButton(onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + context.packageName))
                    )
                }, modifier = Modifier.fillMaxWidth()) {
                    Text("Allow automatic updates")
                }
            }
            val statusText = when (snapshot.state) {
                "current" -> "Up to date"
                "downloading" -> "Downloading"
                "ready" -> "Ready to install"
                "approval" -> "Android installation approval required"
                "permission" -> "One-time installation access needed"
                "installing" -> "Installing"
                "incompatible" -> "Old installation has a different signing key"
                "error" -> "Will retry automatically"
                else -> "Automatic checking enabled"
            }
            Text(statusText, style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary)
            Text(snapshot.message, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (snapshot.checkedAt > 0L) {
                Text("Last checked: " + DateFormat.getDateTimeInstance(
                    DateFormat.SHORT, DateFormat.SHORT
                ).format(Date(snapshot.checkedAt)),
                    style = MaterialTheme.typography.labelSmall)
            }
            if (snapshot.ready && canInstall) {
                Button(onClick = { HaloUpdates.installPrepared(context) },
                    modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (snapshot.state == "approval") "Approve Android installation"
                        else "Install downloaded " + snapshot.version
                    )
                }
            }
        }
    }
}
