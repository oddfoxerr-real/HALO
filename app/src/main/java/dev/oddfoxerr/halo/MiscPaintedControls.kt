package dev.oddfoxerr.halo

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
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
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Safe image-painter preview. Android forbids third-party apps from injecting
 * shaders into unrelated application windows. The full-screen screen-copy
 * overlay blocked touches and altered brightness on Samsung devices.
 *
 * This component deliberately NEVER creates a system overlay or starts
 * MediaProjection. The image-guided brush engine remains inside HALO's UI.
 */
@Composable
fun MiscPaintedControls() {
    val context = LocalContext.current
    val captureStatus by VideoAmbilightService.status.collectAsState()
    val unsafeSession = VideoAmbilightService.isRunning && VideoAmbilightService.isPainted
    var settings by remember(context) {
        mutableStateOf(PaintedScreenPreferences.read(context))
    }
    var photo by remember { mutableStateOf<Uri?>(null) }
    var artwork by remember { mutableStateOf<PaintedStrokeFrame?>(null) }
    var message by remember {
        mutableStateOf("Choose a photo to preview real textured brush strokes.")
    }
    val paintedView = remember(context) { PaintedScreenView(context) }
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
    }

    LaunchedEffect(photo, settings) {
        val selected = photo ?: return@LaunchedEffect
        message = "Painting your selected image…"
        try {
            val frame = withContext(Dispatchers.Default) {
                val image = readPreviewBitmap(context, selected)
                try {
                    PaintedStrokePlanner.plan(image, settings)
                } catch (error: Exception) {
                    if (!image.isRecycled) image.recycle()
                    throw error
                }
            }
            artwork = frame
            message = "Preview ready · original colours, textured brush layers"
        } catch (error: Exception) {
            message = "Could not paint image: " + (error.message ?: "unsupported picture")
        }
    }

    DisposableEffect(paintedView) {
        onDispose { paintedView.clear() }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Painted Screen", style = MaterialTheme.typography.titleLarge)
            Text(
                "System-wide painting is disabled for safety: its overlay blocked touches and darkened the display on Samsung. The real brush engine remains available here on your own photos, without affecting other apps.",
                style = MaterialTheme.typography.bodyMedium
            )

            if (unsafeSession) {
                Text(
                    "An older painting session is active. Stop it immediately.",
                    color = MaterialTheme.colorScheme.error
                )
                Button(onClick = {
                    context.stopService(Intent(context, VideoAmbilightService::class.java))
                }, modifier = Modifier.fillMaxWidth()) {
                    Text("Stop full-screen painting")
                }
            }

            Button(
                onClick = { choosePhoto.launch("image/*") },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (photo == null) "Choose a photo" else "Change photo") }

            if (photo != null) {
                AndroidView(
                    modifier = Modifier.fillMaxWidth()
                        .height(340.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    factory = { paintedView },
                    update = { view ->
                        view.configure(settings)
                        view.setFrame(artwork)
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

            if (VideoAmbilightService.isRunning && !unsafeSession) {
                Text("Video Ambilight is running independently. " + captureStatus,
                    style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = {
                paintedView.clear()
                artwork = null
                photo = null
                message = "Choose a photo to preview real textured brush strokes."
            }) { Text("Clear preview") }
            Text(
                "This preview uses your selected image locally. It never requests screen sharing or display-over-other-apps permission, and it never uploads the photo.",
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
