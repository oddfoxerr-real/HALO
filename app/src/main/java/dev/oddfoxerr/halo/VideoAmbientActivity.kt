package dev.oddfoxerr.halo

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class VideoAmbientActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFFBEABFF), background = Color.Black, surface = Color(0xFF16171D)
            )) {
                VideoAmbilightScreen()
            }
        }
    }
}

@Composable
private fun VideoAmbilightScreen() {
    val context = LocalContext.current
    val status by VideoAmbientRuntime.state.collectAsState()
    var message by remember { mutableStateOf<String?>(null) }
    val consent = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { response ->
        if (response.resultCode != Activity.RESULT_OK || response.data == null) {
            message = "Screen capture was not approved."
        } else {
            val service = Intent(context, VideoAmbientService::class.java).apply {
                action = VideoAmbientService.ACTION_START
                putExtra(VideoAmbientService.EXTRA_RESULT_CODE, response.resultCode)
                putExtra(VideoAmbientService.EXTRA_CONSENT, response.data)
            }
            try {
                ContextCompat.startForegroundService(context, service)
                message = "Screen color sampling requested. Open YouTube or a video app."
            } catch (error: RuntimeException) {
                message = "Could not start capture: ${error.message}"
            }
        }
    }
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(15.dp)
    ) {
        Text("Video Ambilight", style = MaterialTheme.typography.headlineMedium)
        Text("TV-style colors around your screen, matched to the video frame.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (status.active) "Live color sampling" else "Ready for video",
                    style = MaterialTheme.typography.titleMedium)
                Text(status.message, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (status.frameCount > 0L) {
                    Text("Analyzed frames: ${status.frameCount}", style = MaterialTheme.typography.labelSmall)
                }
                status.zones.chunked(8).forEachIndexed { index, segment ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(listOf("Top", "Right", "Bottom", "Left")[index],
                            modifier = Modifier.width(58.dp), style = MaterialTheme.typography.labelSmall)
                        segment.forEach { argb ->
                            Box(Modifier.weight(1f).height(20.dp)
                                .background(Color(argb), RoundedCornerShape(4.dp)))
                        }
                    }
                }
            }
        }
        if (status.active || VideoAmbientService.isRunning) {
            Button(onClick = {
                context.startService(Intent(context, VideoAmbientService::class.java)
                    .setAction(VideoAmbientService.ACTION_STOP))
                message = "Stopping capture"
            }, modifier = Modifier.fillMaxWidth()) { Text("Stop Video Ambilight") }
        } else {
            Button(onClick = {
                if (!Settings.canDrawOverlays(context)) {
                    message = "Allow HALO to draw over apps, then return and tap Start."
                    context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")))
                } else {
                    val manager = context.getSystemService(MediaProjectionManager::class.java)
                    val captureIntent = if (Build.VERSION.SDK_INT >= 34) {
                        manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                    } else manager.createScreenCaptureIntent()
                    consent.launch(captureIntent)
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("Start matching screen colors") }
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        HorizontalDivider()
        Text("How it works", style = MaterialTheme.typography.titleMedium)
        Text("1. Approve Android's screen sharing prompt.\n2. Open a video in YouTube, Gallery or another app.\n3. HALO matches 32 zones around the screen while the video plays.\n4. Stop from this page or the persistent notification.",
            style = MaterialTheme.typography.bodyMedium)
        Text("Screen frames are processed locally and discarded. No recording or upload. Android requires consent each time. Protected videos may display black, and some apps block capture.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
