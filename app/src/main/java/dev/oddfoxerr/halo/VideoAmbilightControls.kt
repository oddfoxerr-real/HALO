package dev.oddfoxerr.halo

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.provider.Settings
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

@Composable
fun VideoAmbilightControls() {
    val context = LocalContext.current
    val status by VideoAmbilightService.status.collectAsState()
    val preferences = remember(context) {
        context.getSharedPreferences("halo_video_settings", android.content.Context.MODE_PRIVATE)
    }
    var response by remember { mutableFloatStateOf(preferences.getFloat("response", .78f)) }
    var feedback by remember { mutableStateOf<String?>(null) }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val start = Intent(context, VideoAmbilightService::class.java)
                .setAction(VideoAmbilightService.ACTION_START)
                .putExtra(VideoAmbilightService.EXTRA_RESULT_CODE, result.resultCode)
                .putExtra(VideoAmbilightService.EXTRA_RESULT_DATA, result.data)
            try {
                ContextCompat.startForegroundService(context, start)
                feedback = null
            } catch (failure: RuntimeException) {
                feedback = "Android could not start screen-color lighting: ${failure.message.orEmpty()}"
            }
        } else feedback = "Screen sharing was cancelled; HALO did not capture your display."
    }
    val overlayPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        feedback = if (Settings.canDrawOverlays(context)) "Overlay enabled. Tap Start video lighting again."
        else "Allow display over other apps first."
    }

    Text("Video Ambilight", style = MaterialTheme.typography.titleLarge)
    Text("Your selected HALO animation keeps moving while each edge and camera effect adopts the colors of the corresponding video area. The scene changes the colors, not the animation. No audio, screenshots, or video files are stored.",
        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)

    Text(status, color = if (VideoAmbilightService.isRunning) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant)
    Button(
        onClick = {
            if (VideoAmbilightService.isRunning) {
                context.stopService(Intent(context, VideoAmbilightService::class.java))
                feedback = "Stopping video lighting…"
            } else if (!Settings.canDrawOverlays(context)) {
                overlayPermission.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")))
            } else {
                val manager = context.getSystemService(MediaProjectionManager::class.java)
                consent.launch(manager.createScreenCaptureIntent())
            }
        },
        modifier = Modifier.fillMaxWidth()
    ) { Text(if (VideoAmbilightService.isRunning) "Stop video lighting" else "Start video lighting") }

    feedback?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

    Text("Scene response · ${(response * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
    Slider(value = response, onValueChange = {
        response = it
        preferences.edit().putFloat("response", it).apply()
    }, valueRange = .12f..1f)
    Text("Higher values follow scene colors faster. Lower values soften color changes while the selected animation continues at its own speed.",
        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)

    HorizontalDivider()
    Text("How to use", style = MaterialTheme.typography.titleSmall)
    Text("1. Choose any effect under Designs and tune its animation speed.\n2. Tap Start, approve screen sharing and select Entire screen.\n3. Switch to your video app. The chosen animation continues with 16 live scene-color zones.\n4. Stop from HALO, its notification or the system screen-sharing chip.",
        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    Text("Protected video streams and secure screens can appear black in screen capture. HALO respects those restrictions. Screensharing stops when Android revokes the session, including device lock on supported Android versions.",
        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
}
