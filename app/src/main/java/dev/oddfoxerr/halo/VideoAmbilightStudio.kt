package dev.oddfoxerr.halo

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

/** TV-style scene color matching for video players, separate from music FFT effects. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoAmbilightStudio(modifier: Modifier = Modifier) {
    val context=LocalContext.current
    val runtime by VideoAmbientRuntime.state.collectAsState()
    var options by remember { mutableStateOf(VideoAmbilightSettings.read(context)) }
    var feedback by remember { mutableStateOf<String?>(null) }
    fun save(value: VideoAmbilightSettings) {
        options=value.normalized()
        options.save(context)
        if (VideoAmbilightService.isRunning) context.startService(
            Intent(context,VideoAmbilightService::class.java)
                .setAction(VideoAmbilightService.ACTION_REFRESH))
    }

    val permissionLauncher=rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { response ->
        if (response.resultCode == Activity.RESULT_OK && response.data != null) {
            try {
                val intent=Intent(context,VideoAmbilightService::class.java)
                    .setAction(VideoAmbilightService.ACTION_START)
                    .putExtra(VideoAmbilightService.EXTRA_RESULT,response.resultCode)
                    .putExtra(VideoAmbilightService.EXTRA_DATA,response.data)
                ContextCompat.startForegroundService(context,intent)
                feedback=null
            } catch (error: RuntimeException) {
                feedback="Could not start screen color capture: ${error.message}"
            }
        } else feedback="Screen sharing was not approved. No capture was started."
    }

    val overlayLauncher=rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(context)) {
            feedback="Overlay access granted. Tap Start Video Ambilight to approve capture."
        } else feedback="Screen overlay permission is needed to display lighting over videos."
    }

    fun start() {
        if (!Settings.canDrawOverlays(context)) {
            overlayLauncher.launch(Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
            return
        }
        try {
            val manager=context.getSystemService(MediaProjectionManager::class.java)
            permissionLauncher.launch(manager.createScreenCaptureIntent())
        } catch (error: RuntimeException) {
            feedback="Android cannot open the screen sharing dialog: ${error.message}"
        }
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(horizontal=18.dp, vertical=14.dp),
        verticalArrangement=Arrangement.spacedBy(16.dp)) {

        Text("Video Ambilight",style=MaterialTheme.typography.headlineLarge)
        Text("Make the edges of your screen match the colors of the video at that exact moment.",
            style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)

        Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface),
            shape=RoundedCornerShape(24.dp)) {
            Column(Modifier.fillMaxWidth().padding(18.dp),
                verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Box(Modifier.fillMaxWidth().height(82.dp).background(
                    Brush.horizontalGradient(listOf(
                        Color(0xFF3D5BFF),Color(0xFFCB52DE),Color(0xFFFF8C39),Color(0xFF56DABF)
                    )),RoundedCornerShape(17.dp)),contentAlignment=Alignment.Center) {
                    Text("LIVE SCREEN-COLOR LIGHTING",color=Color.White,
                        style=MaterialTheme.typography.labelLarge)
                }
                Row(verticalAlignment=Alignment.CenterVertically,
                    horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(9.dp).background(
                        if(runtime.active) Color(0xFF75E6B0) else Color.Gray,RoundedCornerShape(10.dp)))
                    Text(runtime.message,modifier=Modifier.weight(1f),
                        style=MaterialTheme.typography.bodyMedium)
                }
                if(runtime.active) {
                    Button(onClick={
                        context.stopService(Intent(context,VideoAmbilightService::class.java))
                        feedback=null
                    },modifier=Modifier.fillMaxWidth()) { Text("Stop Video Ambilight") }
                } else {
                    Button(onClick={start()},modifier=Modifier.fillMaxWidth()) {
                        Text("Start Video Ambilight")
                    }
                }
                feedback?.let { Text(it,color=MaterialTheme.colorScheme.error,
                    style=MaterialTheme.typography.bodySmall) }
                Text("When Android asks what to share, choose Entire screen for full-screen edge mapping. Then switch to YouTube or your video app.",
                    style=MaterialTheme.typography.bodySmall,
                    color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Text("Scene response",style=MaterialTheme.typography.titleMedium)
        VideoSlider("Light brightness",options.brightness,.10f..1f) {
            save(options.copy(brightness=it))
        }
        VideoSlider("Scene transition smoothing",options.smoothing,0f..0.94f) {
            save(options.copy(smoothing=it))
        }
        VideoSlider("Color intensity",options.saturation,0f..1.8f) {
            save(options.copy(saturation=it))
        }
        VideoSlider("Sample distance from screen edge",options.sampleDepth,.025f..0.19f) {
            save(options.copy(sampleDepth=it))
        }

        Text("Edge lighting",style=MaterialTheme.typography.titleMedium)
        VideoSlider("Light band thickness",options.thicknessDp,3f..34f,"dp") {
            save(options.copy(thicknessDp=it))
        }
        VideoSlider("Soft diffusion",options.diffusionDp,2f..40f,"dp") {
            save(options.copy(diffusionDp=it))
        }

        Text("Performance",style=MaterialTheme.typography.titleMedium)
        SingleChoiceSegmentedButtonRow(modifier=Modifier.fillMaxWidth()) {
            listOf(10,15,20,30).forEachIndexed { index, fps ->
                SegmentedButton(selected=options.frameRate==fps,
                    onClick={save(options.copy(frameRate=fps))},
                    shape=SegmentedButtonDefaults.itemShape(index,4)) {
                    Text("${fps} fps")
                }
            }
        }
        Text("HALO analyzes only sixteen zones around the visible screen boundary. It does not save screenshots, record videos, or upload the pixels. A persistent Android capture notification lets you stop sharing.",
            style=MaterialTheme.typography.bodySmall,
            color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Video may appear black in protected apps because Android blocks capture of DRM-secured frames. YouTube and other apps may also restrict capture. Android requires fresh approval for each session.",
            style=MaterialTheme.typography.bodySmall,
            color=MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun VideoSlider(
    title: String,value: Float,range: ClosedFloatingPointRange<Float>,unit: String = "",
    onChange: (Float)->Unit
) {
    Column(verticalArrangement=Arrangement.spacedBy(1.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            Text(title,modifier=Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
            Text("${(value*100f).roundToInt()/100f} $unit",
                color=MaterialTheme.colorScheme.primary,
                style=MaterialTheme.typography.labelMedium)
        }
        Slider(value=value.coerceIn(range.start,range.endInclusive),
            onValueChange=onChange,valueRange=range)
    }
}
