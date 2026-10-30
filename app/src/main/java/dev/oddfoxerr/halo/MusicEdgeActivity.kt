package dev.oddfoxerr.halo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.sin

/** Dedicated audio-lighting studio; independent of notification characters and themes. */
class MusicEdgeActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("chainsaw_music", MODE_PRIVATE) }
    private var status by mutableStateOf("Choose a style, then start music lighting.")
    private var active by mutableStateOf(false)
    private var selectedMode by mutableStateOf(EdgeMode.WAVE)
    private var selectedColor by mutableIntStateOf(AndroidColor.rgb(170, 91, 255))
    private var sensitivity by mutableFloatStateOf(1.5f)
    private var size by mutableFloatStateOf(1f)
    private var glow by mutableFloatStateOf(.65f)
    private var speed by mutableFloatStateOf(1f)
    private var colorSource by mutableStateOf(EdgeColorSource.MANUAL)
    private var onlyWhilePlaying by mutableStateOf(false)
    private var allowedSources by mutableStateOf<Set<String>>(emptySet())
    private var sourcePickerOpen by mutableStateOf(false)

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) requestOverlayPermission() else status = "Android audio visualization permission was denied."
    }
    private val overlayPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Settings.canDrawOverlays(this)) startMusic() else status = "Display-over-other-apps permission is required."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedMode = EdgeSpectrum.mode(prefs.getString("edge_mode", "WAVE"))
        selectedColor = prefs.getInt("edge_color", AndroidColor.rgb(170, 91, 255))
        sensitivity = prefs.getFloat("sensitivity", 1.5f).coerceIn(.5f, 5f)
        size = prefs.getFloat("thickness", 1f).coerceIn(.6f, 1.6f)
        glow = prefs.getFloat("glow", .65f).coerceIn(0f, 1f)
        speed = prefs.getFloat("rate", 1f).coerceIn(.25f, 3f)
        colorSource = MediaEdgePolicy.colorSource(prefs.getString("color_source", "MANUAL"))
        onlyWhilePlaying = prefs.getBoolean("only_while_playing", false)
        allowedSources = prefs.getStringSet("allowed_sources", emptySet())?.toSet().orEmpty()

        setContent {
            val preview = remember { ChainsawView(this) }
            var previewIteration by remember { mutableIntStateOf(0) }
            LaunchedEffect(previewIteration) {
                if (previewIteration == 0) return@LaunchedEffect
                for (tick in 0 until 52) {
                    val envelope = abs(sin(tick / 7f)) * (1f - (tick / 52f) * .3f)
                    val signal = FloatArray(EdgeSpectrum.BAND_COUNT) { band ->
                        envelope * (0.15f + .45f * abs(sin(tick / 6f + band / 4f)))
                    }
                    preview.updateSpectrum(signal, envelope * .35f, .033f, sensitivity, speed)
                    delay(33)
                }
                preview.updateSpectrum(FloatArray(EdgeSpectrum.BAND_COUNT), 0f, .033f, sensitivity, speed)
            }

            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFFBA97FF),
                background = Color(0xFF08090C),
                surface = Color(0xFF171923),
                onSurface = Color.White
            )) {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                    .safeDrawingPadding().verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Music Edge", style = MaterialTheme.typography.headlineMedium)
                    Text("Muviz-style effects for your whole screen", color = Color.LightGray)
                    Box(Modifier.fillMaxWidth().height(230.dp).background(
                        MaterialTheme.colorScheme.surface, androidx.compose.foundation.shape.RoundedCornerShape(28.dp))) {
                        AndroidView(factory = { preview }, modifier = Modifier.fillMaxSize().padding(5.dp), update = {
                            it.mode = selectedMode
                            it.edgeColor = selectedColor
                            it.thickness = size
                            it.glow = glow
                        })
                        Text("STUDIO PREVIEW", modifier = Modifier.align(Alignment.Center),
                            style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                    }
                    OutlinedButton(
                        onClick = { previewIteration++ }, modifier = Modifier.fillMaxWidth()
                    ) { Text("Preview animation (demo signal)") }

                    Text("Visual styles", style = MaterialTheme.typography.titleMedium)
                    EdgeMode.entries.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            pair.forEach { mode ->
                                FilterChip(
                                    selected = selectedMode == mode,
                                    onClick = { selectedMode = mode; save() },
                                    label = { Text(mode.title) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                    Text("Color source", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        EdgeColorSource.entries.forEach { source ->
                            FilterChip(
                                selected = colorSource == source,
                                onClick = { colorSource = source; save() },
                                label = { Text(source.label) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    Text(
                        if (colorSource == EdgeColorSource.ALBUM_ART)
                            "HALO samples the current album cover when Android exposes media metadata. Requires Notification Access; falls back to your saved color."
                        else "Pick a fixed color for all music.",
                        color = Color.LightGray, style = MaterialTheme.typography.bodySmall
                    )
                    Text("Colors", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0xFFAA5BFF, 0xFF4CC8FF, 0xFF45E0B5, 0xFFFF785E).forEach { color ->
                            FilterChip(
                                selected = selectedColor == color.toInt(),
                                onClick = { selectedColor = color.toInt(); save() },
                                label = { Text("●", color = Color(color)) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    ControlSlider("Sensitivity", sensitivity, .5f..5f) { sensitivity = it; save() }
                    ControlSlider("Size", size, .6f..1.6f) { size = it; save() }
                    ControlSlider("Glow", glow, 0f..1f) { glow = it; save() }
                    ControlSlider("Movement speed", speed, .25f..3f) { speed = it; save() }

                    HorizontalDivider()
                    Text("Playback rules", style = MaterialTheme.typography.titleMedium)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Only while music is playing")
                            Text("Hide when no active Android media session is detected. Requires HALO Notification Access.",
                                style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                        Switch(checked = onlyWhilePlaying, onCheckedChange = {
                            onlyWhilePlaying = it; save()
                        })
                    }
                    OutlinedButton(
                        onClick = { sourcePickerOpen = true }, modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (allowedSources.isEmpty()) "Music sources: All supported apps"
                            else "Music sources: ${allowedSources.size} selected apps"
                        )
                    }
                    Text("This filters music sources, not the foreground app. Protected or unsupported audio cannot be bypassed.",
                        color = Color.Gray, style = MaterialTheme.typography.bodySmall)

                    HorizontalDivider()
                    Text("Live on your phone", style = MaterialTheme.typography.titleMedium)
                    Text(status, style = MaterialTheme.typography.bodyMedium, color = Color.LightGray)
                    Button(onClick = ::requestPermissions, enabled = !active,
                        modifier = Modifier.fillMaxWidth()) { Text("Start live music edge") }
                    OutlinedButton(onClick = {
                        stopService(Intent(this@MusicEdgeActivity, MusicEdgeService::class.java))
                        active = false
                        status = "Music edge stopped."
                    }, enabled = active, modifier = Modifier.fillMaxWidth()) { Text("Stop live music edge") }
                    Text("After starting, open your music player. HALO uses Android's low-resolution playback visualizer; some apps may block it. The notification provides a Stop action. HALO does not record microphone audio.",
                        style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    TextButton(onClick = { finish() }) { Text("Back to HALO") }
                    if (sourcePickerOpen) {
                        val candidates = remember { installedSourceApps() }
                        var search by remember { mutableStateOf("") }
                        AlertDialog(
                            onDismissRequest = { sourcePickerOpen = false },
                            title = { Text("Choose music sources") },
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = search, onValueChange = { search = it },
                                        singleLine = true, label = { Text("Search apps") }
                                    )
                                    Text("No selections = allow all media apps", style = MaterialTheme.typography.bodySmall)
                                    Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
                                        candidates.filter {
                                            it.second.contains(search, ignoreCase = true) ||
                                                it.first.contains(search, ignoreCase = true)
                                        }.forEach { (pkg, name) ->
                                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                                Text(name, modifier = Modifier.weight(1f))
                                                Checkbox(checked = pkg in allowedSources, onCheckedChange = { checked ->
                                                    allowedSources = if (checked) allowedSources + pkg else allowedSources - pkg
                                                    save()
                                                })
                                            }
                                        }
                                    }
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = { sourcePickerOpen = false }) { Text("Done") }
                            },
                            dismissButton = {
                                TextButton(onClick = { allowedSources = emptySet(); save() }) { Text("Allow all") }
                            }
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun ControlSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
        Text("$label · %.2f".format(value), color = Color.White)
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }

    private fun save() {
        prefs.edit()
            .putString("edge_mode", selectedMode.name)
            .putInt("edge_color", selectedColor)
            .putFloat("sensitivity", sensitivity)
            .putFloat("thickness", size)
            .putFloat("glow", glow)
            .putFloat("rate", speed)
            .putString("color_source", colorSource.name)
            .putBoolean("only_while_playing", onlyWhilePlaying)
            .putStringSet("allowed_sources", allowedSources.toSet())
            .apply()
        if (MusicEdgeService.isRunning) {
            startService(Intent(this, MusicEdgeService::class.java).setAction(MusicEdgeService.ACTION_REFRESH))
        }
    }

    private fun requestPermissions() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.RECORD_AUDIO)
        } else requestOverlayPermission()
    }

    private fun requestOverlayPermission() {
        if (Settings.canDrawOverlays(this)) startMusic()
        else overlayPermission.launch(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        )
    }

    private fun startMusic() {
        if (MusicEdgeService.isRunning) {
            active = true
            status = "Music edge is already active."
            return
        }
        try {
            // Ensure the legacy music overlay doesn't remain stacked over the new version.
            stopService(Intent(this, ChainsawOverlayService::class.java))
            startForegroundService(
                Intent(this, MusicEdgeService::class.java).setAction(MusicEdgeService.ACTION_START))
            status = "Starting. Switch to your music app and use the notification to stop."
        } catch (_: Exception) {
            status = "Android blocked the foreground visualizer. Check permissions and retry."
        }
    }

    /** Launcher-visible apps only; does not request QUERY_ALL_PACKAGES. */
    private fun installedSourceApps(): List<Pair<String, String>> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName to it.loadLabel(packageManager).toString() }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
    }

    override fun onResume() {
        super.onResume()
        active = MusicEdgeService.isRunning
    }
}
