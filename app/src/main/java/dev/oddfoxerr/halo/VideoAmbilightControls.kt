package dev.oddfoxerr.halo

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
    var profile by remember(context) { mutableStateOf(VideoLightingPreferences.read(context)) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var advanced by remember { mutableStateOf(false) }
    val videoActive = VideoAmbilightService.isRunning && !VideoAmbilightService.isPainted
    val paintedActive = VideoAmbilightService.isRunning && VideoAmbilightService.isPainted

    fun save(value: VideoLightingPreferences) {
        profile = value
        value.save(context)
        if (videoActive) {
            context.startService(
                Intent(context, VideoAmbilightService::class.java)
                    .setAction(VideoAmbilightService.ACTION_REFRESH)
            )
        }
    }

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
                feedback = "Android could not start screen lighting: ${failure.message.orEmpty()}"
            }
        } else feedback = "Screen sharing was cancelled. No frame was captured."
    }
    val overlayPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        feedback = if (Settings.canDrawOverlays(context))
            "Overlay enabled. Tap Start video lighting again."
        else "Enable display over other apps first."
    }

    Text("Video lighting", style = MaterialTheme.typography.titleLarge)
    Text(
        "Cinematic lighting is now separate from music effects. It follows the actual picture " +
            "with a soft, narrow border and avoids covering the video.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Text("Appearance", style = MaterialTheme.typography.titleMedium)
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        VideoLightStyle.entries.forEach { style ->
            FilterChip(
                selected = profile.style == style,
                onClick = { save(profile.copy(style = style)) },
                label = { Text(style.label) }
            )
        }
    }

    Text(
        when (profile.style) {
            VideoLightStyle.CINEMATIC ->
                "Recommended · still, accurate scene colors with no chasing dots or particles."
            VideoLightStyle.SOFT_PULSE ->
                "Same narrow cinematic glow, with only a very subtle breathing motion."
            VideoLightStyle.SELECTED ->
                "Advanced · use the music animation with a strict narrow-edge visibility limit."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Text("Brightness · ${(profile.intensity * 100).toInt()}%",
        style = MaterialTheme.typography.labelLarge)
    Slider(
        value = profile.intensity,
        onValueChange = { save(profile.copy(intensity = it)) },
        valueRange = .12f..1f
    )

    Text("Glow width · ${profile.widthDp.toInt()} dp",
        style = MaterialTheme.typography.labelLarge)
    Slider(
        value = profile.widthDp,
        onValueChange = { save(profile.copy(widthDp = it)) },
        valueRange = 6f..36f
    )
    Text(
        "A narrow border keeps the picture clear. This width only affects video lighting.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Text("Scene response · ${(profile.response * 100).toInt()}%",
        style = MaterialTheme.typography.labelLarge)
    Slider(
        value = profile.response,
        onValueChange = { save(profile.copy(response = it)) },
        valueRange = .12f..1f
    )
    Text(
        "Higher response follows scene cuts more closely; lower response softens transitions.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    TextButton(onClick = { advanced = !advanced }) {
        Text(if (advanced) "Hide sampling alignment" else "Advanced · sampling alignment")
    }
    if (advanced) {
        Text("Capture depth · ${(profile.sampleInset * 100f).toInt()}%",
            style = MaterialTheme.typography.labelLarge)
        Slider(
            value = profile.sampleInset,
            onValueChange = { save(profile.copy(sampleInset = it)) },
            valueRange = 0.03f..0.13f
        )
        Text(
            "Choose how far into the image HALO samples. Closer sampling tracks border colors " +
                "more precisely; use a deeper sample if playback controls obscure the edges.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    HorizontalDivider()
    Text(if (paintedActive) "Painted Screen currently owns the screen capture" else status,
        style = MaterialTheme.typography.bodyMedium,
        color = if (videoActive) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant)

    Button(
        onClick = {
            if (videoActive || paintedActive) {
                context.stopService(Intent(context, VideoAmbilightService::class.java))
                feedback = if (paintedActive) "Painted Screen stopped. Tap Start again to grant Video Ambilight access."
                    else "Stopping screen sharing…"
            } else if (!Settings.canDrawOverlays(context)) {
                overlayPermission.launch(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}"))
                )
            } else {
                val manager = context.getSystemService(MediaProjectionManager::class.java)
                consent.launch(manager.createScreenCaptureIntent())
            }
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(if (videoActive) "Stop video lighting" else if (paintedActive)
            "Switch from Painted Screen" else "Start cinematic lighting")
    }

    feedback?.let {
        Text(it, color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall)
    }
    Text("How to use", style = MaterialTheme.typography.titleSmall)
    Text(
        "1. Keep Cinematic glow selected for video.\n" +
            "2. Tap Start and approve Entire screen sharing.\n" +
            "3. Watch your video. Only the frame edges glow with their matching scene colors.\n" +
            "4. Stop from HALO, the notification or Android's screen-sharing control.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Text(
        "Protected or DRM-restricted video may appear black in Android screen capture. " +
            "HALO never stores or uploads captured frames.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
