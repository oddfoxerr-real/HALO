package dev.oddfoxerr.halo

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.media.projection.MediaProjectionManager
import android.provider.Settings
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Genuine GPU paint shader for photos and explicitly shared app previews. */
@Composable
fun MiscPaintedControls() {
    val context = LocalContext.current
    val captureStatus by VideoAmbilightService.status.collectAsState()
    val paintedSession = VideoAmbilightService.isRunning && VideoAmbilightService.isPainted
    val fullScreenActive = paintedSession && VideoAmbilightService.isScreenOverlay
    val gpuPreview = paintedSession && VideoAmbilightService.isPreview &&
        !VideoAmbilightService.isScreenOverlay
    var requestedFullScreen by remember { mutableStateOf(false) }
    var settings by remember(context) {
        mutableStateOf(PaintedScreenPreferences.read(context))
    }
    var photo by remember { mutableStateOf<Uri?>(null) }
    var liveFeedback by remember { mutableStateOf<String?>(null) }
    val otherVideo = VideoAmbilightService.isRunning &&
        !VideoAmbilightService.isPainted
    val screenPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val intent = Intent(context, VideoAmbilightService::class.java)
                .setAction(VideoAmbilightService.ACTION_START)
                .putExtra(VideoAmbilightService.EXTRA_PAINTED, true)
                .putExtra(VideoAmbilightService.EXTRA_SAFE_LIVE, true)
                .putExtra(VideoAmbilightService.EXTRA_PAINT_PREVIEW, true)
                .putExtra(VideoAmbilightService.EXTRA_PAINT_OVERLAY, requestedFullScreen)
                .putExtra(VideoAmbilightService.EXTRA_RESULT_CODE, result.resultCode)
                .putExtra(VideoAmbilightService.EXTRA_RESULT_DATA, result.data)
            try {
                ContextCompat.startForegroundService(context, intent)
                liveFeedback = null
            } catch (error: RuntimeException) {
                liveFeedback = "GPU painting could not start: " +
                    (error.message ?: "Android restricted screen sharing")
            }
        } else {
            liveFeedback = "Screen sharing permission was not granted."
        }
    }
    val overlayPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        liveFeedback = if (Settings.canDrawOverlays(context))
            "Overlay permission granted. Tap Paint current screen again."
        else "Allow display over other apps for full-screen painting."
    }
    var photoBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var message by remember { mutableStateOf("Choose a photo to run the GPU paint shader.") }
    val photoView = remember(context) { PaintShaderView(context) }
    val liveView = remember(context) { PaintShaderView(context) }
    val choosePhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            photo = uri
            message = "Painting your selected image…"
        }
    }

    fun save(next: PaintedScreenPreferences) {
        settings = next
        next.save(context)
        if (paintedSession) context.startService(
            Intent(context, VideoAmbilightService::class.java)
                .setAction(VideoAmbilightService.ACTION_REFRESH)
        )
    }

    LaunchedEffect(photo) {
        val selected = photo ?: return@LaunchedEffect
        message = "Decoding selected picture…"
        try {
            val bitmap = withContext(Dispatchers.IO) {
                readPreviewBitmap(context, selected)
            }
            photoBitmap = bitmap
            message = "Photo ready · AGSL GPU shader"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            message = "Unable to open picture: " + (error.message ?: "unsupported format")
        }
    }

    DisposableEffect(photoView) {
        onDispose { photoView.clearSource() }
    }
    DisposableEffect(liveView, gpuPreview) {
        if (gpuPreview) PaintPreviewFrameHub.attach(liveView)
        onDispose { PaintPreviewFrameHub.detach(liveView) }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Paint Shader · GPU", style = MaterialTheme.typography.titleLarge)
            Text(
                "Real AGSL brush shader, not a translucent filter or pixel mosaic. " +
                    "Paint the selected app across your whole display, or use the small preview inside HALO.",
                style = MaterialTheme.typography.bodyMedium
            )

            Text("Live GPU painting", style = MaterialTheme.typography.titleMedium)
            Text(
                if (paintedSession) captureStatus else
                    "Full-screen: select SINGLE APP (not entire screen) in Android's capture picker, " +
                    "then open that app. The touch-through painting follows it across the display. " +
                    "Other apps and protected screens remain unmodified.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = {
                    when {
                        paintedSession -> context.startService(
                            Intent(context, VideoAmbilightService::class.java)
                                .setAction(VideoAmbilightService.ACTION_STOP)
                        )
                        otherVideo -> {
                            context.startService(
                                Intent(context, VideoAmbilightService::class.java)
                                    .setAction(VideoAmbilightService.ACTION_STOP)
                            )
                            liveFeedback = "Video Ambilight stopped. Tap Start again."
                        }
                        Build.VERSION.SDK_INT < 33 ->
                            liveFeedback = "Live painting requires Android 13 or newer."
                        else -> {
                            requestedFullScreen = false
                            val manager = context.getSystemService(
                                MediaProjectionManager::class.java)
                            screenPermission.launch(manager.createScreenCaptureIntent())
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (paintedSession) "Stop GPU painting" else if (otherVideo)
                    "Stop Video Ambilight" else "Start small GPU preview")
            }
            if (!paintedSession && !otherVideo) {
                Button(
                    onClick = {
                        when {
                            Build.VERSION.SDK_INT < 34 ->
                                liveFeedback = "Full-screen single-app painting requires Android 14 or newer."
                            !Settings.canDrawOverlays(context) ->
                                overlayPermission.launch(
                                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:" + context.packageName))
                                )
                            else -> {
                                requestedFullScreen = true
                                val manager = context.getSystemService(
                                    MediaProjectionManager::class.java)
                                screenPermission.launch(manager.createScreenCaptureIntent())
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Paint current screen · full display") }
            }
            if (fullScreenActive) {
                Text(
                    "Full-screen GPU paint is running. Switch to the selected app. " +
                        "Use HALO's Stop screen capture notification to turn it off.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            liveFeedback?.let {
                Text(it, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
            if (gpuPreview) {
                AndroidView(
                    modifier = Modifier.fillMaxWidth().height(460.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    factory = { liveView },
                    update = { it.configure(settings) }
                )
            }
            Text("Photo GPU shader", style = MaterialTheme.typography.titleMedium)

            Button(
                onClick = { choosePhoto.launch("image/*") },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (photo == null) "Choose a photo" else "Change photo") }

            if (photo != null && photoBitmap != null) {
                AndroidView(
                    modifier = Modifier.fillMaxWidth()
                        .height(340.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    factory = { photoView },
                    update = { view ->
                        view.configure(settings)
                        view.show(photoBitmap)
                    }
                )
            }
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text("Paint style", style = MaterialTheme.typography.titleMedium)
            androidx.compose.foundation.layout.Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PaintedStyle.entries.forEach { style ->
                    FilterChip(
                        selected = style == settings.style,
                        onClick = { save(settings.copy(style = style)) },
                        label = { Text(style.label, maxLines = 1) }
                    )
                }
            }
            Text("Painting coverage · " + (settings.strength * 100).toInt() + "%",
                style = MaterialTheme.typography.labelLarge)
            Slider(
                value = settings.strength,
                onValueChange = { save(settings.copy(strength = it)) },
                valueRange = .35f..1f
            )
            Text("Brush size · " + (settings.brushSize * 100).toInt() + "%",
                style = MaterialTheme.typography.labelLarge)
            Slider(
                value = settings.brushSize,
                onValueChange = { save(settings.copy(brushSize = it)) },
                valueRange = .65f..2.3f
            )
            Text("Paint relief · " + (settings.relief * 100).toInt() + "%",
                style = MaterialTheme.typography.labelLarge)
            Slider(
                value = settings.relief,
                onValueChange = { save(settings.copy(relief = it)) },
                valueRange = .15f..1.6f
            )

            if (VideoAmbilightService.isRunning && !paintedSession) {
                Text("Video Ambilight is running independently. " + captureStatus,
                    style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = {
                photoView.clearSource()
                photoBitmap = null
                photo = null
                message = "Choose a photo to run the GPU paint shader."
            }) { Text("Clear preview") }
            Text(
                "Everything is processed locally. Live preview requires screen-sharing consent; protected content may be black. The Stop notification ends capture. No frames are uploaded.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Decode a bounded local image to prevent a selected photo exhausting RAM. */
private fun readPreviewBitmap(context: Context, uri: Uri): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, bounds)
    } ?: throw IllegalArgumentException("Cannot open selected image")

    val imageWidth = bounds.outWidth
    val imageHeight = bounds.outHeight
    require(imageWidth > 0 && imageHeight > 0) {
        "Selected image has no readable pixels"
    }
    var sampling = 1
    while (imageWidth / sampling > 640 || imageHeight / sampling > 960)
        sampling *= 2

    val options = BitmapFactory.Options().apply {
        inSampleSize = sampling
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, options)
    } ?: throw IllegalArgumentException("Image decoding failed")
}
