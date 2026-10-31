package dev.oddfoxerr.halo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.sin

/** Notification shortcut opens the same music studio that lives in HALO's Music tab. */
class MusicEdgeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFBEABFF), background = Color.Black,
                surface = Color(0xFF16171D))) {
                MusicStudio(Modifier.fillMaxSize().safeDrawingPadding())
            }
        }
    }
}

private enum class MusicPanel(val label: String) { DESIGNS("Designs"), TUNE("Tune"), RULES("Playback") }
private data class MusicPalette(val name: String, val first: Long, val second: Long)
private val palettes = listOf(
    MusicPalette("Aurora", 0xFF9A7BFF, 0xFF52E0CB), MusicPalette("Ember", 0xFFFF684B, 0xFFFFCC65),
    MusicPalette("Ice", 0xFF4CAEFF, 0xFFB7F5FF), MusicPalette("Rose", 0xFFED73A7, 0xFFC6A0FF)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicStudio(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var settings by remember { mutableStateOf(MusicConfiguration.read(context)) }
    val latest by rememberUpdatedState(settings)
    val runtime by MusicRuntime.state.collectAsState()
    var panel by remember { mutableStateOf(MusicPanel.DESIGNS) }
    var demo by remember { mutableStateOf(false) }
    var resumed by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var revision by remember { mutableIntStateOf(0) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var chooseSources by remember { mutableStateOf(false) }
    val preview = remember { ChainsawView(context) }
    fun save(value: MusicConfiguration) { settings = value; value.save(context) }
    fun start() {
        try {
            context.startForegroundService(Intent(context, MusicEdgeService::class.java).setAction(MusicEdgeService.ACTION_START))
            feedback = null
        } catch (_: RuntimeException) { feedback = "Android could not start lighting. Keep HALO open and check its permissions." }
    }
    val overlayLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        revision++
        if (Settings.canDrawOverlays(context)) start() else feedback = "Allow display over other apps to light the screen edges."
    }
    fun afterAudioPermission() {
        if (Settings.canDrawOverlays(context)) start()
        else overlayLauncher.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
    }
    val audioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        revision++
        if (granted) afterAudioPermission() else feedback = "Audio access is required to analyze playback. No microphone audio is recorded."
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) { resumed = true; revision++ }
            if (event == Lifecycle.Event.ON_PAUSE) resumed = false
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); preview.clearSignal() }
    }
    val listenerAccess = remember(revision) { NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName) }
    // Debounce service refresh. Slider dragging never queues a service start for every pointer event.
    LaunchedEffect(settings) {
        delay(180)
        if (MusicEdgeService.isRunning) context.startService(Intent(context, MusicEdgeService::class.java).setAction(MusicEdgeService.ACTION_REFRESH))
    }
    LaunchedEffect(demo, resumed) {
        preview.clearSignal()
        if (demo && resumed) {
            var frame = 0
            while (true) {
                val beat = if (frame % 100 < 70) kotlin.math.exp(-(frame % 15) * .32f) else 0f
                val signal = FloatArray(EdgeSpectrum.BAND_COUNT) { index ->
                    beat * (.25f + .45f * abs(sin(frame * .07f + index * .32f)))
                }
                preview.updateSpectrum(signal, beat * .32f, latest.sensitivity, latest.speed)
                frame++; delay(33)
            }
        }
    }
    Column(modifier.background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState())
        .padding(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Music", style = MaterialTheme.typography.headlineLarge)
        Text("Your soundtrack, around your screen.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(9.dp).background(if (runtime.active) Color(0xFF74DDB4) else Color.Gray, RoundedCornerShape(9.dp)))
                    Column(Modifier.weight(1f)) {
                        Text(runtime.message, style = MaterialTheme.typography.titleSmall)
                        if (runtime.source.isNotBlank()) Text(runtime.source, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Button(onClick = {
                    if (runtime.active) {
                        context.stopService(Intent(context, MusicEdgeService::class.java)); feedback = null
                    } else if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        audioLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    } else afterAudioPermission()
                }, modifier = Modifier.fillMaxWidth()) { Text(if (runtime.active) "Stop music lighting" else "Start music lighting") }
                feedback?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Box(Modifier.width(204.dp).height(370.dp).background(Color.Black, RoundedCornerShape(26.dp))) {
                AndroidView(factory = { preview }, update = { it.configure(settings) }, modifier = Modifier.fillMaxSize())
                Column(Modifier.align(Alignment.Center).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("HALO", style = MaterialTheme.typography.titleLarge, color = Color(0xFFEEE9FA))
                    Text(settings.mode.title, style = MaterialTheme.typography.labelMedium, color = Color(0xFFB4ACCA))
                    Spacer(Modifier.height(8.dp))
                    Text(if (demo) "DEMO SIGNAL" else "PREVIEW", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                }
            }
        }
        OutlinedButton(onClick = { demo = !demo }, modifier = Modifier.fillMaxWidth()) {
            Text(if (demo) "Stop design preview" else "Play design preview")
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            MusicPanel.entries.forEachIndexed { index, value ->
                SegmentedButton(selected = panel == value, onClick = { panel = value },
                    shape = SegmentedButtonDefaults.itemShape(index, MusicPanel.entries.size)) { Text(value.label) }
            }
        }
        when (panel) {
            MusicPanel.DESIGNS -> {
                Text("15 audio-reactive designs", style = MaterialTheme.typography.titleMedium)
                Text("Five refined classics and ten new original effects. Tap a style to play its live preview.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                EdgeMode.entries.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        pair.forEach { mode ->
                            DesignTile(mode, settings.mode == mode, settings.color, settings.accent,
                                Modifier.weight(1f)) { save(settings.copy(mode = mode)); demo = true }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                Text("Palette", style = MaterialTheme.typography.titleMedium)
                palettes.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        pair.forEach { palette ->
                            FilterChip(selected = settings.color == palette.first.toInt() && settings.accent == palette.second.toInt(),
                                onClick = { save(settings.copy(color = palette.first.toInt(), accent = palette.second.toInt(), colorSource = EdgeColorSource.MANUAL)) },
                                label = { Text(palette.name) }, leadingIcon = {
                                    Box(Modifier.size(18.dp).background(Brush.linearGradient(listOf(Color(palette.first), Color(palette.second))), RoundedCornerShape(9.dp)))
                                }, modifier = Modifier.weight(1f))
                        }
                    }
                }
                MusicToggle("Use album colors", "Use the current cover as the main color; keep your accent as the second color.",
                    settings.colorSource == EdgeColorSource.ALBUM_ART) {
                    save(settings.copy(colorSource = if (it) EdgeColorSource.ALBUM_ART else EdgeColorSource.MANUAL))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HexColor("Main color", settings.color, Modifier.weight(1f)) { save(settings.copy(color = it, colorSource = EdgeColorSource.MANUAL)) }
                    HexColor("Accent", settings.accent, Modifier.weight(1f)) { save(settings.copy(accent = it)) }
                }
            }
            MusicPanel.TUNE -> {
                MusicSlider("Beat sensitivity", settings.sensitivity, .5f..5f, "×") { save(settings.copy(sensitivity = it)) }
                MusicSlider("Motion speed", settings.speed, .25f..3f, "×") { save(settings.copy(speed = it)) }
                MusicSlider("Thickness", settings.thickness, .6f..3f, "×") { save(settings.copy(thickness = it)) }
                MusicSlider("Brightness", settings.brightness, .2f..1f, "") { save(settings.copy(brightness = it)) }
                MusicSlider("Glow", settings.glow, 0f..1f, "") { save(settings.copy(glow = it)) }
                HorizontalDivider()
                Text("Choose screen edges", style = MaterialTheme.typography.titleMedium)
                Text("Use any combination. All four sides are enabled by default.", style = MaterialTheme.typography.bodySmall)
                MusicToggle("Top edge", "Light the top of your display.", settings.drawTop) { save(settings.copy(drawTop = it)) }
                MusicToggle("Bottom edge", "Light the bottom of your display.", settings.drawBottom) { save(settings.copy(drawBottom = it)) }
                MusicToggle("Left edge", "Light the left side.", settings.drawLeft) { save(settings.copy(drawLeft = it)) }
                MusicToggle("Right edge", "Light the right side.", settings.drawRight) { save(settings.copy(drawRight = it)) }
                HorizontalDivider()
                Text("Fit your display", style = MaterialTheme.typography.titleMedium)
                Text("Move each edge independently. Negative inset values push the frame outward; Android may clip protected system UI.", style = MaterialTheme.typography.bodySmall)
                MusicSlider("Top position", settings.topInset, -24f..120f, "dp") { save(settings.copy(topInset = it)) }
                MusicSlider("Bottom position", settings.bottomInset, -24f..120f, "dp") { save(settings.copy(bottomInset = it)) }
                MusicSlider("Left position", settings.leftInset, -24f..120f, "dp") { save(settings.copy(leftInset = it)) }
                MusicSlider("Right position", settings.rightInset, -24f..120f, "dp") { save(settings.copy(rightInset = it)) }
                MusicSlider("Corner radius", settings.corner, 0f..100f, "dp") { save(settings.copy(corner = it)) }
                OutlinedButton(onClick = { save(settings.copy(
                    topInset = 4f, bottomInset = 4f, leftInset = 4f, rightInset = 4f,
                    drawTop = true, drawBottom = true, drawLeft = true, drawRight = true,
                    corner = 32f, thickness = 1f, brightness = 1f, glow = .75f)) },
                    modifier = Modifier.fillMaxWidth()) { Text("Reset edge calibration") }
            }
            MusicPanel.RULES -> {
                MusicToggle("Follow music playback", "Hide when your music player is paused. Uses notification access to detect media sessions.", settings.onlyPlaying) {
                    save(settings.copy(onlyPlaying = it))
                }
                MusicToggle("Respect quiet hours", "Use HALO's quiet-hours schedule for music lighting too.", settings.respectQuiet) {
                    save(settings.copy(respectQuiet = it))
                }
                OutlinedButton(onClick = { chooseSources = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (settings.allowedSources.isEmpty()) "Music sources · All apps" else "Music sources · ${settings.allowedSources.size} selected")
                }
                Text("Sources control when lighting is shown. Android provides a device-wide mix, so simultaneous apps cannot be isolated.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Notifications take priority", style = MaterialTheme.typography.titleMedium)
                Text("HALO pauses music lighting for an incoming notification, then resumes it. Configure character, full border or both under Settings and App rules.",
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (!listenerAccess && (settings.onlyPlaying || settings.colorSource == EdgeColorSource.ALBUM_ART || settings.allowedSources.isNotEmpty())) {
            OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }, modifier = Modifier.fillMaxWidth()) {
                Text("Enable notification access")
            }
        }
        Text("Start music lighting to run the foreground overlay beyond HALO's interface. The design preview stops when you leave this page. Music lighting hides when playback is silent or Android blocks capture. Samsung's protected status icons, lock screen and AOD cannot be replaced.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
    }
    if (chooseSources) SourcePicker(settings.allowedSources, { save(settings.copy(allowedSources = it)) }, { chooseSources = false })
}

@Composable
private fun DesignTile(mode: EdgeMode, selected: Boolean, first: Int, second: Int, modifier: Modifier, choose: () -> Unit) {
    Card(onClick = choose, modifier = modifier.semantics { contentDescription = "${mode.title} design${if (selected) ", selected" else ""}" },
        colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Canvas(Modifier.fillMaxWidth().height(52.dp)) {
                val brush = Brush.linearGradient(listOf(Color(first), Color(second)))
                drawRoundRect(brush, cornerRadius = CornerRadius(12.dp.toPx()), style = Stroke(2.dp.toPx()), alpha = .35f)
                when (mode) {
                    EdgeMode.BARS -> for (i in 0..14) {
                        val x = 6.dp.toPx() + (size.width - 12.dp.toPx()) * i / 14
                        val height = (4 + 18 * abs(sin(i * .7))).toFloat().dp.toPx()
                        drawLine(brush, Offset(x, size.height - 5.dp.toPx()), Offset(x, size.height - height), 2.dp.toPx())
                    }
                    EdgeMode.TRAIL -> drawLine(brush, Offset(size.width * .2f, 0f), Offset(size.width * .8f, 0f), 3.dp.toPx())
                    EdgeMode.CHAIN -> for (i in 1..12) {
                        val x = size.width * i / 13
                        drawLine(Color.LightGray, Offset(x - 3.dp.toPx(), size.height - 2.dp.toPx()), Offset(x, size.height - 9.dp.toPx()), 2.dp.toPx())
                    }
                    EdgeMode.COMET, EdgeMode.TWIN, EdgeMode.NEON, EdgeMode.PRISM -> {
                        for (i in 0..3) {
                            val x = size.width * i / 4f
                            drawLine(brush, Offset(x, size.height * .45f),
                                Offset((x + size.width * .15f).coerceAtMost(size.width), size.height * .45f),
                                (2f + i % 2 * 2f).dp.toPx(), alpha = 1f - i * .17f)
                        }
                    }
                    EdgeMode.PIXELS, EdgeMode.SPARKS, EdgeMode.RAIN -> for (i in 0..19) {
                        val x = (i + 1) * size.width / 22f
                        val y = size.height / 2f + (kotlin.math.sin(i * 1.7) * size.height * .22).toFloat()
                        if (mode == EdgeMode.RAIN) drawLine(brush, Offset(x, y), Offset(x, y + (i % 4 + 2).dp.toPx()), 2.dp.toPx())
                        else drawCircle(brush, (if (mode == EdgeMode.PIXELS) 2.3f else 1.3f).dp.toPx(), Offset(x, y))
                    }
                    EdgeMode.AURORA, EdgeMode.HELIX -> for (i in 0..18) {
                        val x = size.width * i / 18f
                        val y = size.height * (.5f + .17f * kotlin.math.sin(i * .62 + if (mode == EdgeMode.HELIX) 2.1 else 0.0).toFloat())
                        drawCircle(brush, 1.7.dp.toPx(), Offset(x, y))
                    }
                    EdgeMode.BLOOM -> for (i in 1..4) drawCircle(brush, (i * 5).dp.toPx(),
                        Offset(size.width / 2f, size.height / 2f), style = Stroke(1.6.dp.toPx()))
                    EdgeMode.WAVE, EdgeMode.PULSE -> drawRoundRect(brush, Offset(5.dp.toPx(), 5.dp.toPx()),
                        androidx.compose.ui.geometry.Size(size.width - 10.dp.toPx(), size.height - 10.dp.toPx()), CornerRadius(9.dp.toPx()), style = Stroke(2.dp.toPx()))
                }
            }
            Text(mode.title, style = MaterialTheme.typography.titleSmall)
        }
    }
}
@Composable
private fun MusicToggle(title: String, detail: String, value: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(value, change, modifier = Modifier.semantics { contentDescription = title })
    }
}
@Composable
private fun MusicSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, unit: String, change: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label); Text("%.2f %s".format(value, unit), color = MaterialTheme.colorScheme.primary)
        }
        Slider(value, change, valueRange = range, modifier = Modifier.semantics { contentDescription = label })
    }
}
@Composable
private fun HexColor(label: String, color: Int, modifier: Modifier, change: (Int) -> Unit) {
    var hex by remember(color) { mutableStateOf("#%06X".format(color and 0xFFFFFF)) }
    OutlinedTextField(hex, { input ->
        hex = input.take(7)
        if (hex.matches(Regex("#[0-9a-fA-F]{6}"))) change((hex.drop(1).toLong(16) or 0xFF000000L).toInt())
    }, modifier = modifier, label = { Text(label) }, singleLine = true, isError = hex.length == 7 && !hex.matches(Regex("#[0-9a-fA-F]{6}")))
}
@Composable
private fun SourcePicker(selected: Set<String>, change: (Set<String>) -> Unit, close: () -> Unit) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var search by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { it.activityInfo.packageName to it.loadLabel(context.packageManager).toString() }
                .distinctBy { it.first }.sortedBy { it.second.lowercase() }
        }
    }
    AlertDialog(onDismissRequest = close, title = { Text("Music sources") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(search, { search = it }, label = { Text("Search apps") }, singleLine = true)
            Text("Nothing selected means all music apps.", style = MaterialTheme.typography.bodySmall)
            if (apps == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            else androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 340.dp)) {
                val visible = apps.orEmpty().filter { it.second.contains(search, true) || it.first.contains(search, true) }
                items(visible.size, key = { visible[it].first }) { index ->
                    val (pkg, name) = visible[index]
                    Row(Modifier.fillMaxWidth().clickable { change(if (pkg in selected) selected - pkg else selected + pkg) }, verticalAlignment = Alignment.CenterVertically) {
                        Text(name, Modifier.weight(1f)); Checkbox(pkg in selected, null)
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = close) { Text("Done") } },
        dismissButton = { TextButton(onClick = { change(emptySet()) }) { Text("Allow all") } })
}
