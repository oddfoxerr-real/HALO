package dev.oddfoxerr.halo

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.NotificationManagerCompat
import java.text.DateFormat
import java.util.Date

private val AMOLED = Color(0xFF050608)
private val Surface = Color(0xFF171A20)
private val Lavender = Color(0xFFC4B5FD)
private val Muted = Color(0xFFB0B4BF)
private val Swatches = listOf(0xFF8790FBL, 0xFFA879FFL, 0xFF4BE49DL, 0xFFFF5365L, 0xFFF4B36FL, 0xFF7CB9FFL)

class MainActivity : ComponentActivity() {
    private var permissionRevision by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val store = remember { ProfileStore(this) }
            var dynamic by remember { mutableStateOf(store.dynamicColors) }
            val scheme = if (dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) dynamicDarkColorScheme(this) else darkColorScheme(
                primary = Lavender, onPrimary = Color(0xFF241537), background = AMOLED,
                onBackground = Color(0xFFF5F2FA), surface = Surface,
                onSurface = Color(0xFFF5F2FA), surfaceVariant = Color(0xFF282B34),
                onSurfaceVariant = Color(0xFFCED0DB), secondary = Color(0xFF98DFB8)
            )
            val revision = permissionRevision
            val listener = remember(revision) {
                NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
            }
            val overlay = remember(revision) { Settings.canDrawOverlays(this) }
            MaterialTheme(colorScheme = scheme) {
                HaloApp(
                    store = store, revision = revision, listenerEnabled = listener, overlayEnabled = overlay,
                    dynamicColors = dynamic, onDynamicColorsChange = { value ->
                        dynamic = value
                        store.dynamicColors = value
                    },
                    requestListener = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                    requestOverlay = {
                        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        permissionRevision++
    }
}

private enum class HaloTab(val label: String) { HOME("Home"), MUSIC("Visuals"), APPS("Apps"), SETTINGS("Settings") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HaloApp(
    store: ProfileStore,
    revision: Int,
    listenerEnabled: Boolean,
    overlayEnabled: Boolean,
    dynamicColors: Boolean,
    onDynamicColorsChange: (Boolean) -> Unit,
    requestListener: () -> Unit,
    requestOverlay: () -> Unit
) {
    val context = LocalContext.current
    var tab by remember { mutableStateOf(HaloTab.HOME) }
    var enabled by remember { mutableStateOf(store.masterEnabled) }
    LaunchedEffect(revision) { enabled = store.masterEnabled }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("HALO", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.headlineMedium)
                        Text("EDGE VISUALIZER", style = MaterialTheme.typography.labelSmall, color = Muted)
                    }
                },
                actions = { Text("v0.13", modifier = Modifier.padding(end = 18.dp), color = MaterialTheme.colorScheme.primary) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                HaloTab.entries.forEach { item ->
                    val image = when (item) {
                        HaloTab.HOME -> Icons.Default.Home
                        HaloTab.MUSIC -> Icons.Default.MusicNote
                        HaloTab.APPS -> Icons.Default.Apps
                        HaloTab.SETTINGS -> Icons.Default.Settings
                    }
                    NavigationBarItem(
                        selected = tab == item, onClick = { tab = item },
                        icon = { Icon(image, contentDescription = null) },
                        label = { Text(item.label) }
                    )
                }
            }
        }
    ) { contentPadding ->
        if (tab == HaloTab.MUSIC) {
            MusicStudio(Modifier.fillMaxSize().padding(contentPadding))
        } else LazyColumn(
            Modifier.fillMaxSize().padding(contentPadding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item { Spacer(Modifier.height(3.dp)) }
            when (tab) {
                HaloTab.MUSIC -> Unit
                HaloTab.HOME -> {
                    item {
                        StatusHero(enabled, listenerEnabled && overlayEnabled) { value ->
                            enabled = value
                            store.masterEnabled = value
                            if (!value) { HaloOverlay.dismiss(); PillHaloOverlay.dismiss(); NotificationEdgeOverlay.dismiss() }
                            HaloNotificationListener.requestPillRefresh()
                        }
                    }
                    item {
                        Text("Your notifications", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            MetricCard("Active", "${NotificationActivityState.activeCount}", Modifier.weight(1f))
                            MetricCard("Listener", if (NotificationActivityState.listenerConnected) "Live" else "Offline", Modifier.weight(1f))
                        }
                    }
                    if (!listenerEnabled || !overlayEnabled) item {
                        SectionCard {
                            Text("Complete setup", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("Grant both system permissions for lighting outside HALO.", color = Muted)
                            if (!listenerEnabled) Button(onClick = requestListener, modifier = Modifier.fillMaxWidth()) { Text("Enable notification access") }
                            if (!overlayEnabled) OutlinedButton(onClick = requestOverlay, modifier = Modifier.fillMaxWidth()) { Text("Allow screen overlay") }
                        }
                    }
                    item {
                        SectionCard {
                            Text("Notification visualizer", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("Notifications run the same GPU-driven effects as music, with the selected animation and per-app colors.", color = Muted)
                            Button(onClick = {
                                if (overlayEnabled) NotificationEdgeOverlay.show(
                                    context, MusicConfiguration.read(context).color.toLong() and 0xFFFFFFFFL,
                                    4200L, false, store
                                ) else requestOverlay()
                            }, modifier = Modifier.fillMaxWidth()) { Text("Preview notification edge") }
                        }
                    }
                    item {
                        SectionCard {
                            Text("Music edge studio", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("Muviz-style audio-reactive waves, spectrum bars, neon trails and optional mechanical lighting.", color = Muted)
                            Button(onClick = { tab = HaloTab.MUSIC },
                                modifier = Modifier.fillMaxWidth()) { Text("Customize music edge") }
                        }
                    }
                    item { Text("Recent activity", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
                    if (NotificationActivityState.recent.isEmpty()) item {
                        SectionCard { Text("No notification events yet. New notifications will appear here after access is granted.", color = Muted) }
                    } else items(NotificationActivityState.recent.take(8)) { event ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            ColorDot(store.readForPackage(event.packageName).color)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(applicationLabel(context, event.packageName), fontWeight = FontWeight.SemiBold)
                                Text(if (event.type == EventType.RECEIVED) "Notification received" else "Notification dismissed", color = Muted)
                            }
                            Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(event.timestamp)), color = Muted, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                HaloTab.APPS -> {
                    item {
                        Text("App rules", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("Decide which apps trigger the shared edge visualizer and customize their colors.", color = Muted)
                    }
                    item { AppRulesEditor(store) }
                }
                HaloTab.SETTINGS -> {
                    item {
                        Text("Settings & permissions", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("Manage notification filtering, quiet hours, and appearance.", color = Muted)
                    }
                    item { PermissionSection("Notification access", listenerEnabled, requestListener) }
                    item { PermissionSection("Display over apps", overlayEnabled, requestOverlay) }
                    item { SectionCard { QuietHoursEditor(store) } }
                    item {
                        SectionCard {
                            Text("Notification visualizer", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("Notifications use the selected Music Studio animation and its edge placement. App rules can override the highlight color.", color = Muted)
                            OutlinedButton(onClick = { tab = HaloTab.MUSIC },
                                modifier = Modifier.fillMaxWidth()) { Text("Choose animation in Visuals") }
                            var persistent by remember { mutableStateOf(store.notificationBorderPersistent) }
                            SwitchRow("Keep animation while unread",
                                "Retain a quieter animated edge until the notification is dismissed.",
                                persistent) {
                                persistent = it; store.notificationBorderPersistent = it
                            }
                            var borderGlow by remember { mutableFloatStateOf(store.notificationBorderGlow) }
                            SettingSlider("Notification glow", "${(borderGlow * 100).toInt()}%",
                                borderGlow, 0f..1f) {
                                borderGlow = it; store.notificationBorderGlow = it
                            }
                            var borderThickness by remember { mutableFloatStateOf(store.notificationBorderThickness) }
                            SettingSlider("Notification intensity", "%.1f×".format(borderThickness),
                                borderThickness, 1f..8f) {
                                borderThickness = it; store.notificationBorderThickness = it
                            }
                            Text("Camera cutout / notch effect", style = MaterialTheme.typography.titleSmall)
                            var notchSelection by remember { mutableStateOf(store.notchSettings().mode) }
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(NotchMode.entries) { option ->
                                    FilterChip(selected = notchSelection == option,
                                        onClick = {
                                            notchSelection = option
                                            store.saveNotch(store.notchSettings().copy(mode = option))
                                            HaloNotificationListener.requestPillRefresh()
                                        }, label = { Text(option.title) })
                                }
                            }
                            Text("Uses the same camera halo as Music. Detailed camera width, height, offset and gap controls are under Music → Tune.",
                                color = Muted, style = MaterialTheme.typography.bodySmall)
                            Button(onClick = {
                                if (overlayEnabled) NotificationEdgeOverlay.show(context,
                                    MusicConfiguration.read(context).color.toLong() and 0xFFFFFFFFL,
                                    4200L, false, store) else requestOverlay()
                            }, modifier = Modifier.fillMaxWidth()) { Text("Test notification animation") }
                            Text("The border follows your screen, not the protected bounds of Samsung's actual heads-up notification cards.", color = Muted,
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    item {
                        SectionCard {
                            Text("Pill Halo · Samsung-style", fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium)
                            Text("An optional media-reactive outline on unlocked screens. Samsung's protected Now Bar itself cannot be recolored.",
                                color = Muted)
                            var pill by remember { mutableStateOf(store.pillEnabled) }
                            SwitchRow("Show media pill", "Only while an Android media session is playing.", pill) {
                                pill = it; store.pillEnabled = it
                                HaloNotificationListener.requestPillRefresh()
                            }
                            Button(onClick = {
                                context.startActivity(Intent(context, PillPreviewActivity::class.java))
                            }, modifier = Modifier.fillMaxWidth()) { Text("Calibrate on phone") }
                        }
                    }
                    item {
                        SectionCard {
                            Text("Notification behavior", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            var silent by remember { mutableStateOf(store.showSilent) }
                            var screen by remember { mutableStateOf(store.onlyScreenOn) }
                            SwitchRow("Include silent notifications", "Normally HALO only reacts to alerting notifications.", silent) {
                                silent = it; store.showSilent = it
                            }
                            HorizontalDivider()
                            SwitchRow("Screen-on effects only", "Safer for battery and Samsung lock-screen restrictions.", screen) {
                                screen = it; store.onlyScreenOn = it
                            }
                            HorizontalDivider()
                            SwitchRow("Wallpaper-based Material 3 color", "Keep the AMOLED dark layout, use Samsung's dynamic colors.", dynamicColors, onDynamicColorsChange)
                        }
                    }
                    item {
                        SectionCard {
                            Text("Diagnostics", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            Text("Listener: ${if (listenerEnabled) "Authorized" else "Permission needed"} · Overlay: ${if (overlayEnabled) "Authorized" else "Permission needed"}", color = Muted)
                            OutlinedButton(onClick = { HaloOverlay.dismiss(); PillHaloOverlay.dismiss(); NotificationEdgeOverlay.dismiss() }, modifier = Modifier.fillMaxWidth()) { Text("Clear HALO overlay") }
                            Text("HALO monitors notification metadata locally. Message bodies are not stored. Android may prevent third-party overlays on secure screens or Samsung's AOD.", color = Muted, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}

@Composable
private fun StatusHero(enabled: Boolean, connected: Boolean, onToggle: (Boolean) -> Unit) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("HALO is ${if (enabled) "enabled" else "paused"}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(if (connected) "Ready for incoming notifications" else "Finish permission setup to activate effects", color = Muted)
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun SectionCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) { Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content) }
}

@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier, shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp)) {
            Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(label, color = Muted, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun ColorDot(value: Long, size: Int = 37) {
    Box(Modifier.size(size.dp).background(Color(value), CircleShape), contentAlignment = Alignment.Center) {
        Text("✦", color = Color.Black, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun MiniPreview(profile: CharacterProfile) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(Modifier.width(205.dp).height(340.dp).background(Color.Black, RoundedCornerShape(27.dp))) {
            key(profile.app) {
                AndroidView(factory = { CharacterView(it).apply { configureLoop(profile) } },
                    update = { it.configureLoop(profile) }, modifier = Modifier.fillMaxSize())
            }
            Column(Modifier.align(Alignment.TopCenter).padding(top = 29.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("HALO", color = Color(0xFFDDD8EB), style = MaterialTheme.typography.headlineSmall)
                Text(profile.app.displayName, color = Muted, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        Switch(checked = checked, onCheckedChange = onToggle)
    }
}

@Composable
private fun ProfileEditor(profile: CharacterProfile, onUpdate: (CharacterProfile) -> Unit) {
    var hex by remember(profile.app) { mutableStateOf("#%06X".format(profile.color and 0xFFFFFF)) }
    Text("${profile.app.displayName} appearance", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    SwitchRow("Enable character", "Disable just this character profile.", profile.enabled) { onUpdate(profile.copy(enabled = it)) }
    Text("Entrance motion", style = MaterialTheme.typography.labelLarge)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(CharacterMotion.entries) { motion ->
            FilterChip(selected = profile.motion == motion,
                onClick = { onUpdate(profile.copy(motion = motion)) },
                label = { Text(motion.title) })
        }
    }
    SwitchRow("Unread notification dot", "Retain a small indicator until dismissal.", profile.persistent) { onUpdate(profile.copy(persistent = it)) }
    Text("Lighting color", color = Muted)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(Swatches) { color ->
            Box(Modifier.size(38.dp).background(Color(color), CircleShape).clickable {
                hex = "#%06X".format(color and 0xFFFFFF)
                onUpdate(profile.copy(color = color))
            }, contentAlignment = Alignment.Center) {
                if (profile.color == color) Text("✓", color = Color.Black)
            }
        }
    }
    OutlinedTextField(
        value = hex, onValueChange = { value ->
            hex = value.take(7)
            if (Regex("^#[0-9A-Fa-f]{6}$").matches(hex)) onUpdate(profile.copy(color = hex.drop(1).toLong(16) or 0xFF000000L))
        }, singleLine = true, label = { Text("Custom HEX") }, modifier = Modifier.fillMaxWidth()
    )
    Text("Entry edge", color = Muted)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        EdgeSide.entries.forEach { side -> FilterChip(
            selected = profile.side == side, onClick = { onUpdate(profile.copy(side = side)) }, label = { Text(side.name.lowercase().replaceFirstChar { it.uppercase() }) }
        ) }
    }
    SettingSlider("Duration", "%.1f seconds".format(profile.durationMs / 1000f), profile.durationMs.toFloat(), 1800f..10000f) { onUpdate(profile.copy(durationMs = it.toLong())) }
    SettingSlider("Size", "%.2f×".format(profile.scale), profile.scale, .60f..1.60f) { onUpdate(profile.copy(scale = it)) }
    SettingSlider("Vertical position", "${(profile.anchorY * 100).toInt()}%", profile.anchorY, .20f.. .85f) { onUpdate(profile.copy(anchorY = it)) }
    SettingSlider("Edge inset", "${profile.edgeInsetDp.toInt()} dp", profile.edgeInsetDp, 0f..32f) { onUpdate(profile.copy(edgeInsetDp = it)) }
    SettingSlider("Glow", "${(profile.glow * 100).toInt()}%", profile.glow, 0f..1f) { onUpdate(profile.copy(glow = it)) }
}

@Composable
private fun SettingSlider(label: String, amount: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label); Text(amount, color = Muted)
    }
    Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range)
}

@Composable
private fun PermissionSection(title: String, granted: Boolean, action: () -> Unit) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(if (granted) "Granted" else "Required for system effects", color = if (granted) Color(0xFF92DEB5) else Color(0xFFFFBD91))
            }
            if (!granted) OutlinedButton(onClick = action) { Text("Allow") }
        }
    }
}

@Composable
private fun QuietHoursEditor(store: ProfileStore) {
    var enabled by remember { mutableStateOf(store.quietEnabled) }
    var start by remember { mutableStateOf(toClock(store.quietStart)) }
    var end by remember { mutableStateOf(toClock(store.quietEnd)) }
    var valid by remember { mutableStateOf(true) }
    Text("Quiet hours", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    SwitchRow("Enable quiet hours", "Suppress effects during your sleep schedule.", enabled) {
        enabled = it; store.quietEnabled = it
        if (it && store.isQuietNow()) { HaloOverlay.dismiss(); NotificationEdgeOverlay.dismiss() }
        HaloNotificationListener.requestPillRefresh()
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(value = start, onValueChange = { start = it.take(5) }, label = { Text("From HH:mm") }, singleLine = true, modifier = Modifier.weight(1f))
        OutlinedTextField(value = end, onValueChange = { end = it.take(5) }, label = { Text("To HH:mm") }, singleLine = true, modifier = Modifier.weight(1f))
    }
    if (!valid) Text("Use 24-hour time, for example 22:30.", color = MaterialTheme.colorScheme.error)
    OutlinedButton(onClick = {
        val a = parseClock(start)
        val b = parseClock(end)
        valid = a != null && b != null
        if (valid) {
            store.quietStart = a!!; store.quietEnd = b!!
            if (store.isQuietNow()) { HaloOverlay.dismiss(); NotificationEdgeOverlay.dismiss() }
            HaloNotificationListener.requestPillRefresh()
        }
    }, modifier = Modifier.fillMaxWidth()) { Text("Save quiet hours") }
}

private fun toClock(minutes: Int) = "%02d:%02d".format(minutes / 60, minutes % 60)
private fun parseClock(text: String): Int? {
    if (!Regex("^([01]\\d|2[0-3]):[0-5]\\d$").matches(text)) return null
    val values = text.split(":")
    return values[0].toInt() * 60 + values[1].toInt()
}

private data class InstalledApp(val packageName: String, val label: String)

private fun applicationLabel(context: android.content.Context, packageName: String): String = try {
    val pm = context.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
} catch (_: Exception) { packageName }

/** Queries launchable apps only, without broad QUERY_ALL_PACKAGES access. */
private fun launchableApps(context: android.content.Context): List<InstalledApp> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val resolved = pm.queryIntentActivities(intent, 0).map { info ->
        InstalledApp(info.activityInfo.packageName, info.loadLabel(pm).toString())
    }
    // Only display actual launchable applications. Do not insert phantom entries
    // for Discord/Gmail/etc when those apps aren't installed on this device.
    return resolved.distinctBy { it.packageName }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
}

@Composable
private fun AppRulesEditor(store: ProfileStore) {
    val context = LocalContext.current
    val installed = remember { launchableApps(context) }
    var filter by remember { mutableStateOf("") }
    var selectedPackage by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    var rule by remember(selectedPackage, revision) { mutableStateOf(selectedPackage?.let(store::readRule)) }
    fun change(value: ApplicationRule) { store.writeRule(value); rule = value; revision++ }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(value = filter, onValueChange = { filter = it }, label = { Text("Search installed apps") },
            modifier = Modifier.fillMaxWidth(), singleLine = true)
        val visible = installed.filter { filter.isBlank() || it.label.contains(filter, true) || it.packageName.contains(filter, true) }
        if (selectedPackage != null && rule != null) {
            val current = rule!!
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(applicationLabel(context, current.packageName), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(current.packageName, style = MaterialTheme.typography.labelSmall, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    OutlinedButton(onClick = { selectedPackage = null }) { Text("Close") }
                }
                SwitchRow("Enable edge notifications for this app",
                    "Use the current Music Studio animation when this app notifies.",
                    current.enabled) { change(current.copy(enabled = it)) }
                Text("Per-app color override", style = MaterialTheme.typography.labelLarge)
                var customHex by remember(current.packageName) {
                    mutableStateOf("#%06X".format((current.colorOverride ?: store.read(current.character).color) and 0xFFFFFF))
                }
                OutlinedTextField(
                    value = customHex,
                    onValueChange = { value ->
                        customHex = value.take(7)
                        if (Regex("^#[0-9a-fA-F]{6}$").matches(customHex)) {
                            change(current.copy(colorOverride = customHex.drop(1).toLong(16) or 0xFF000000L))
                        }
                    },
                    label = { Text("Custom per-app HEX color") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    item { FilterChip(selected = current.colorOverride == null, onClick = { change(current.copy(colorOverride = null)) }, label = { Text("Default") }) }
                    items(Swatches) { color -> Box(Modifier.size(34.dp).background(Color(color), CircleShape).clickable { change(current.copy(colorOverride = color)) }) }
                }
                OutlinedButton(onClick = {
                    store.clearRule(current.packageName); rule = store.readRule(current.packageName); revision++
                }, modifier = Modifier.fillMaxWidth()) { Text("Reset app rule") }
            }
        }
        Text("${visible.size} apps available", style = MaterialTheme.typography.labelMedium, color = Muted)
        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)) {
            items(visible, key = { it.packageName }) { app ->
                val data = store.readRule(app.packageName)
                Card(modifier = Modifier.fillMaxWidth().clickable { selectedPackage = app.packageName },
                    shape = RoundedCornerShape(19.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        ColorDot(data.colorOverride ?: store.read(data.character).color)
                        Spacer(Modifier.width(11.dp))
                        Column(Modifier.weight(1f)) {
                            Text(app.label, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (data.enabled) "Edge notification enabled" else "Notifications off", color = Muted,
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Text("Edit", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        Text("Apps not shown here still use the Other apps fallback character. HALO never needs access to your contacts or message contents.", color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}
