package dev.oddfoxerr.halo

import android.os.Bundle
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

private val previewInk = Color(0xFF050608)
private val previewPanel = Color(0xF020222B)
private val previewText = Color(0xFFB6B6C1)
private val previewAccent = Color(0xFFB6A8FF)
private val swatches = listOf(
    0xFF8790FBL, 0xFFA879FFL, 0xFF4BE49DL, 0xFFFF5365L, 0xFFF4B36FL, 0xFF7CB9FFL
)

/**
 * Real full-display preview, not a scaled-down phone illustration or touch-obscuring overlay.
 * All edits immediately update the very same native Canvas renderer used for notifications.
 * Does not need notification/overlay permissions: the preview is inside HALO's own activity.
 */
class PreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val initiallySelected = CharacterApp.entries.firstOrNull {
            it.name == intent.getStringExtra("app")
        } ?: CharacterApp.DISCORD
        setContent {
            MaterialTheme(colorScheme = if (ProfileStore(this).dynamicColors && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) dynamicDarkColorScheme(this) else darkColorScheme(
                primary = previewAccent,
                onPrimary = Color(0xFF201631),
                background = previewInk,
                onBackground = Color.White,
                surface = Color(0xFF1B1E27),
                onSurface = Color.White
            )) {
                PhonePreview(initiallySelected, onClose = { finish() })
            }
        }
    }
}

@Composable
private fun PhonePreview(initialApp: CharacterApp, onClose: () -> Unit) {
    val context = LocalContext.current
    val store = remember { ProfileStore(context) }
    var selected by remember { mutableStateOf(initialApp) }
    var profile by remember { mutableStateOf(store.read(initialApp)) }
    var isPlaying by remember { mutableStateOf(true) }
    var frame by remember { mutableStateOf(.41f) }
    var showControls by remember { mutableStateOf(true) }

    fun update(next: CharacterProfile) {
        profile = next
        store.write(next)
    }

    Box(Modifier.fillMaxSize().background(previewInk)) {
        // Native Canvas occupies the device's real viewport; animations use the actual dimensions.
        key(selected) {
            AndroidView(
                factory = { androidContext -> CharacterView(androidContext).apply { configureLoop(profile) } },
                update = { characterView ->
                    if (isPlaying) characterView.configureLoop(profile)
                    else characterView.previewAt(profile, frame)
                },
                modifier = Modifier.fillMaxSize()
            )
        }
        // A small camera cutout marker is UI-only and never changes the lighting coordinates.
        Box(
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 14.dp)
                .size(11.dp).background(Color(0xFF15191F), CircleShape)
        )
        if (!showControls) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("HALO", color = Color(0x70FFFFFF), style = MaterialTheme.typography.headlineLarge)
                Text("${selected.displayName} · Full screen preview", color = previewText,
                    style = MaterialTheme.typography.labelSmall)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter)
                .padding(top = 36.dp, start = 16.dp, end = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(onClick = onClose) { Text("✕  Close") }
            OutlinedButton(onClick = { showControls = !showControls }) {
                Text(if (showControls) "Hide controls" else "Edit character")
            }
        }
        if (showControls) {
            Card(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .padding(start = 10.dp, end = 10.dp, bottom = 12.dp),
                colors = CardDefaults.cardColors(containerColor = previewPanel),
                shape = RoundedCornerShape(25.dp),
                border = BorderStroke(1.dp, Color(0xFF3F414C))
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().height(332.dp).verticalScroll(rememberScrollState())
                        .padding(horizontal = 18.dp, vertical = 13.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text("Phone preview", style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold)
                            Text("Changes save automatically", style = MaterialTheme.typography.labelSmall,
                                color = previewText)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Loop", style = MaterialTheme.typography.labelMedium)
                            Spacer(Modifier.width(8.dp))
                            Switch(checked = isPlaying, onCheckedChange = { isPlaying = it })
                        }
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        items(CharacterApp.entries) { app ->
                            FilterChip(
                                selected = selected == app,
                                onClick = {
                                    selected = app
                                    profile = store.read(app)
                                    isPlaying = true
                                },
                                label = { Text(app.displayName) }
                            )
                        }
                    }
                    if (!isPlaying) {
                        Text("Scrub animation · ${(frame * 100f).toInt()}%", color = previewText,
                            style = MaterialTheme.typography.labelMedium)
                        Slider(value = frame, onValueChange = { frame = it }, valueRange = 0.01f..0.99f)
                    }
                    Text("Character size · ${"%.2f".format(profile.scale)}×")
                    Slider(value = profile.scale, onValueChange = {
                        update(profile.copy(scale = it))
                    }, valueRange = .60f..1.60f)
                    Text("Vertical position · ${(profile.anchorY * 100).toInt()}%")
                    Slider(value = profile.anchorY, onValueChange = {
                        update(profile.copy(anchorY = it))
                    }, valueRange = .20f..0.85f)
                    Text("Distance from edge · ${profile.edgeInsetDp.toInt()} dp")
                    Slider(value = profile.edgeInsetDp, onValueChange = {
                        update(profile.copy(edgeInsetDp = it))
                    }, valueRange = 0f..32f)
                    Text("Glow · ${(profile.glow * 100).toInt()}%")
                    Slider(value = profile.glow, onValueChange = {
                        update(profile.copy(glow = it))
                    }, valueRange = 0f..1f)
                    Text("Animation duration · ${"%.1f".format(profile.durationMs / 1000f)} seconds")
                    Slider(value = profile.durationMs.toFloat(), onValueChange = {
                        update(profile.copy(durationMs = it.toLong()))
                    }, valueRange = 1800f..10000f)
                    Text("Entry edge", color = previewText)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        EdgeSide.entries.forEach { side ->
                            FilterChip(selected = profile.side == side,
                                onClick = { update(profile.copy(side = side)) },
                                label = { Text(side.name.lowercase().replaceFirstChar { it.uppercase() }) })
                        }
                    }
                    Text("Character color", color = previewText)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(swatches) { tint ->
                            Box(
                                Modifier.size(34.dp).background(Color(tint), CircleShape)
                                    .clickable { update(profile.copy(color = tint)) },
                                contentAlignment = Alignment.Center
                            ) {
                                if (profile.color == tint) Text("✓", color = Color.Black,
                                    fontWeight = FontWeight.Black)
                            }
                        }
                    }
                    Text("This is an in-app full-screen preview. Real notification overlays require separate permission.",
                        style = MaterialTheme.typography.bodySmall, color = previewText)
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}
