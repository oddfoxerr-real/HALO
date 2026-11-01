package dev.oddfoxerr.halo

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.media.projection.MediaProjectionConfig
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

/**
 * Misc is deliberately separate from Video Ambilight. Starting one stops the
 * other only after the user explicitly asks to switch capture modes.
 */
@Composable
fun MiscPaintedControls() {
    val context = LocalContext.current
    val status by VideoAmbilightService.status.collectAsState()
    var settings by remember(context) { mutableStateOf(PaintedScreenPreferences.read(context)) }
    var feedback by remember { mutableStateOf<String?>(null) }
    val active = VideoAmbilightService.isRunning && VideoAmbilightService.isPainted
    val otherCapture = VideoAmbilightService.isRunning && !VideoAmbilightService.isPainted

    fun save(value: PaintedScreenPreferences) {
        settings = value
        value.save(context)
        if (active) {
            context.startService(
                Intent(context, VideoAmbilightService::class.java)
                    .setAction(VideoAmbilightService.ACTION_REFRESH)
            )
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val intent = Intent(context, VideoAmbilightService::class.java)
                .setAction(VideoAmbilightService.ACTION_START)
                .putExtra(VideoAmbilightService.EXTRA_PAINTED, true)
                .putExtra(VideoAmbilightService.EXTRA_RESULT_CODE, result.resultCode)
                .putExtra(VideoAmbilightService.EXTRA_RESULT_DATA, result.data)
            try {
                ContextCompat.startForegroundService(context, intent)
                feedback = null
            } catch (error: RuntimeException) {
                feedback = "Android could not start the painting overlay: ${error.message.orEmpty()}"
            }
        } else {
            feedback = "Screen-sharing permission was not granted."
        }
    }

    val overlayPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        feedback = if (Settings.canDrawOverlays(context))
            "Overlay enabled. Tap Start Painted Screen again."
        else "Display-over-apps permission is required."
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Painted Screen", style = MaterialTheme.typography.titleLarge)
            Text(
                "Live brush strokes follow shapes and colors across your screen, with textured pigment instead of a flat color filter. The original apps remain visible and touchable underneath.",
                style = MaterialTheme.typography.bodyMedium
            )
            Text("Paint style", style = MaterialTheme.typography.titleMedium)
            androidx.compose.foundation.layout.Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PaintedStyle.entries.forEach { style ->
                    FilterChip(
                        selected = settings.style == style,
                        onClick = { save(settings.copy(style = style)) },
                        label = { Text(style.label, maxLines = 1) }
                    )
                }
            }
            Text("Paint strength · ${(settings.strength * 100).toInt()}%",
                style = MaterialTheme.typography.labelLarge)
            Slider(
                value = settings.strength,
                onValueChange = { save(settings.copy(strength = it)) },
                valueRange = .35f..1f
            )
            Text(
                when (settings.style) {
                    PaintedStyle.OIL -> "Thick pigment, small brush strokes and gently emphasized edges."
                    PaintedStyle.WATERCOLOR -> "Softer washed-out pigments and blended brush marks."
                    PaintedStyle.INK -> "Posterized colors with stronger illustrated contours."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                if (active) status else if (otherCapture)
                    "Video Ambilight is currently using screen capture."
                else "Off · start it whenever you want the painted look.",
                color = if (active) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = {
                    when {
                        active -> context.stopService(Intent(context, VideoAmbilightService::class.java))
                        otherCapture -> {
                            context.stopService(Intent(context, VideoAmbilightService::class.java))
                            feedback = "Video lighting stopped. Tap Start again to grant Painted Screen access."
                        }
                        Build.VERSION.SDK_INT < 33 -> feedback = "Painted Screen requires Android 13 or newer."
                        !Settings.canDrawOverlays(context) ->
                            overlayPermission.launch(
                                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")))
                        else -> {
                            val manager = context.getSystemService(MediaProjectionManager::class.java)
                            permission.launch(if (Build.VERSION.SDK_INT >= 34)
                                manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                            else manager.createScreenCaptureIntent())
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (active) "Stop Painted Screen" else if (otherCapture)
                    "Switch from Video lighting" else "Start Painted Screen")
            }
            feedback?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text(
                "HALO requests full-display capture so painting keeps matching the screen when changing apps. Android or Samsung can restrict sharing and protected content. Frames stay on-device in memory and are never recorded or uploaded. Stop from HALO or Android's screen-sharing indicator.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
