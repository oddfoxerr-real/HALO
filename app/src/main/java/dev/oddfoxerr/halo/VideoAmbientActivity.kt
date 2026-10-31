package dev.oddfoxerr.halo

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

/** Only an explicit tap may launch Android's MediaProjection consent dialog. */
class VideoAmbientActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFFBEABFF), background = Color.Black,
                surface = Color(0xFF16171D))) {
                VideoAmbientStudio()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VideoAmbientStudio() {
    val context = LocalContext.current
    var settings by remember { mutableStateOf(VideoAmbientSettings.read(context)) }
    val state by VideoAmbientRuntime.state.collectAsState()
    var error by remember { mutableStateOf<String?>(null) }
    val projection = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val intent = Intent(context, VideoAmbientService::class.java)
                .setAction(VideoAmbientService.ACTION_START)
                .putExtra(VideoAmbientService.EXTRA_RESULT, result.resultCode)
                .putExtra(VideoAmbientService.EXTRA_DATA, result.data)
            try {
                ContextCompat.startForegroundService(context, intent)
                error = null
            } catch (failure: RuntimeException) {
                error = "Android refused to start screen capture: ${failure.message}"
            }
        } else {
            error = "Screen sharing wasn't granted. HALO doesn't capture without permission."
        }
    }
    val overlayPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (!Settings.canDrawOverlays(context))
            error = "Enable Display over other apps to show Ambilight beside your video."
    }
    fun begin() {
        if (!Settings.canDrawOverlays(context)) {
            overlayPermission.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")))
            return
        }
        try {
            val manager = context.getSystemService(MediaProjectionManager::class.java)
            projection.launch(manager.createScreenCaptureIntent())
        } catch (failure: RuntimeException) {
            error = "Android could not request screen sharing: ${failure.message}"
        }
    }
    fun update(value: VideoAmbientSettings) {
        settings = value.normalized()
        settings.save(context)
        if (VideoAmbientService.isRunning)
            context.startService(Intent(context, VideoAmbientService::class.java)
                .setAction(VideoAmbientService.ACTION_REFRESH))
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Video Ambilight") }) },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Screen colors → edge lighting",
                style = MaterialTheme.typography.headlineSmall)
            Text("Like TV bias lighting: HALO reads the visible colors near each edge of the video and matches them around your display. No music beat is required.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (state.active) "Screen sharing active" else "Video capture off",
                        style = MaterialTheme.typography.titleMedium)
                    Text(state.message, style = MaterialTheme.typography.bodyMedium)
                    if (state.active) {
                        Text("${state.frames} frames sampled locally",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(
                            onClick = {
                                context.startService(Intent(context, VideoAmbientService::class.java)
                                    .setAction(VideoAmbientService.ACTION_STOP))
                            }, modifier = Modifier.fillMaxWidth()
                        ) { Text("Stop screen capture") }
                    } else {
                        Button(onClick = ::begin, modifier = Modifier.fillMaxWidth()) {
                            Text("Start Video Ambilight")
                        }
                    }
                    error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            VideoControl("Brightness", settings.brightness, .2f..1f) {
                update(settings.copy(brightness = it))
            }
            VideoControl("Bloom", settings.glow, 0f..1f) {
                update(settings.copy(glow = it))
            }
            VideoControl("Light spread", settings.spread, .5f..2f) {
                update(settings.copy(spread = it))
            }
            VideoControl("Color smoothing", settings.smoothing, .05f..1f) {
                update(settings.copy(smoothing = it))
            }

            HorizontalDivider()
            Text("How it behaves", style = MaterialTheme.typography.titleMedium)
            Text("Start HALO, allow Android's screen-sharing prompt, then switch to YouTube or a video player. Colors are sampled locally, about 12 times per second, and blended smoothly between 16 screen-edge regions.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Protected videos may appear black to Android screen sharing. In that case HALO cannot read their colors. Recording stops when you tap Stop, Android ends sharing, or the service is closed.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun VideoControl(
    name: String, value: Float, range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(name, style = MaterialTheme.typography.titleSmall)
            Text("${(value * 100f).roundToInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}
