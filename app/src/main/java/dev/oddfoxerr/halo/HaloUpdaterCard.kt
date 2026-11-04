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
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("Automatic updates", style = MaterialTheme.typography.titleMedium)
            Text(
                "HALO checks for signed GitHub releases every 6 hours and downloads " +
                "verified updates automatically. You never need to check manually.",
                style = MaterialTheme.typography.bodyMedium
            )
            val statusText = when (snapshot.state) {
                "current" -> "Up to date"
                "downloading" -> "Downloading"
                "ready", "approval" -> "Ready to install"
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
            if (snapshot.ready) {
                Button(onClick = { HaloUpdates.installPrepared(context) },
                    modifier = Modifier.fillMaxWidth()) {
                    Text("Install downloaded " + snapshot.version)
                }
                if (!context.packageManager.canRequestPackageInstalls()) {
                    Text("Android may require one-time authorization for sideloaded APK installs. " +
                        "HALO never opens that permission screen automatically.",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + context.packageName))
                        )
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text("Allow installs from HALO")
                    }
                }
            }
        }
    }
}
