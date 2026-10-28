package dev.oddfoxerr.halo

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class PillPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent {
            val scheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && ProfileStore(this).dynamicColors)
                dynamicDarkColorScheme(this) else darkColorScheme(
                    primary = Color(0xFFB8A6FF),
                    background = Color(0xFF050608),
                    surface = Color(0xFF1B1E27),
                    onSurface = Color.White
                )
            MaterialTheme(colorScheme = scheme) { PillCalibration(onClose = { finish() }) }
        }
    }
}

@Composable
private fun PillCalibration(onClose: () -> Unit) {
    val context = LocalContext.current
    val store = remember { ProfileStore(context) }
    var enabled by remember { mutableStateOf(store.pillEnabled) }
    var serviceColors by remember { mutableStateOf(store.pillMediaColors) }
    var pillWidth by remember { mutableStateOf(store.pillWidthDp) }
    var pillHeight by remember { mutableStateOf(store.pillHeightDp) }
    var bottom by remember { mutableStateOf(store.pillBottomDp) }
    var glow by remember { mutableStateOf(store.pillGlow) }
    var thickness by remember { mutableStateOf(store.pillThicknessDp) }
    var showControls by remember { mutableStateOf(true) }
    val color = if (serviceColors) PillHaloPolicy.mediaColor("com.spotify.music", store.pillColor) else store.pillColor
    fun refresh() { HaloNotificationListener.requestPillRefresh() }

    Box(Modifier.fillMaxSize().background(Color(0xFF050608))) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            AndroidView(
                factory = { PillHaloView(it) },
                update = { it.setStyle(color.toInt(), glow, thickness) },
                modifier = Modifier.padding(bottom = bottom.dp)
                    .width((pillWidth + 24).dp).height((pillHeight + 24).dp)
            )
        }
        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .padding(top = 32.dp, start = 16.dp, end = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            OutlinedButton(onClick = onClose) { Text("✕ Close") }
            OutlinedButton(onClick = { showControls = !showControls }) {
                Text(if (showControls) "Hide controls" else "Edit pill")
            }
        }
        if (showControls) {
            Card(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xF020232B)),
                shape = RoundedCornerShape(24.dp)
            ) {
                Column(
                    Modifier.height(305.dp).verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("Pill Halo preview", style = MaterialTheme.typography.titleLarge)
                    Text("Adjust the outline using your actual screen. Settings save automatically.",
                        style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Enable during active media", Modifier.weight(1f))
                        Switch(checked = enabled, onCheckedChange = {
                            enabled = it; store.pillEnabled = it; refresh()
                        })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Service colors", Modifier.weight(1f))
                        Switch(checked = serviceColors, onCheckedChange = {
                            serviceColors = it; store.pillMediaColors = it; refresh()
                        })
                    }
                    Text("Width · ${pillWidth.toInt()} dp")
                    Slider(value = pillWidth, onValueChange = {
                        pillWidth = it; store.pillWidthDp = it; refresh()
                    }, valueRange = 120f..320f)
                    Text("Height · ${pillHeight.toInt()} dp")
                    Slider(value = pillHeight, onValueChange = {
                        pillHeight = it; store.pillHeightDp = it; refresh()
                    }, valueRange = 32f..84f)
                    Text("Bottom offset · ${bottom.toInt()} dp")
                    Slider(value = bottom, onValueChange = {
                        bottom = it; store.pillBottomDp = it; refresh()
                    }, valueRange = 8f..280f)
                    Text("Glow · ${(glow * 100).toInt()}%")
                    Slider(value = glow, onValueChange = {
                        glow = it; store.pillGlow = it; refresh()
                    }, valueRange = 0f..1f)
                    Text("Stroke · ${"%.1f".format(thickness)} dp")
                    Slider(value = thickness, onValueChange = {
                        thickness = it; store.pillThicknessDp = it; refresh()
                    }, valueRange = 1f..6f)
                    Text("Unlocked screens only. This does not modify the native Samsung Now Bar or Always On Display.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
