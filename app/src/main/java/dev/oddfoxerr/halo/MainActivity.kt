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
            val store = remember { ProfileStore(this).also { it.pillEnabled = false } }
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

private enum class HaloTab(val label: String) {
    HOME("Home"), MUSIC("Music"), APPS("Apps"), SETTINGS("Settings")
}

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
    var enabled by remember(revision) { mutableStateOf(store.masterEnabled) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("HALO", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.headlineMedium)
                        Text("EDGE EFFECT STUDIO", style = MaterialTheme.typography.labelSmall, color = Muted)
                    }
                },
                actions = { Text("v0.13.0", modifier = Modifier.padding(end = 18.dp),
                    color = MaterialTheme.colorScheme.primary) },
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
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = { Icon(image, contentDescription = null) },
                        label = { Text(item.label) }
                    )
                }
            }
        }
    ) { padding ->
        if (tab == HaloTab.MUSIC) {
            MusicStudio(Modifier.fillMaxSize().padding(padding))
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item { Spacer(Modifier.height(4.dp)) }
                when (tab) {
                    HaloTab.MUSIC -> Unit
                    HaloTab.HOME -> {
                        item {
                            StatusHero(enabled, listenerEnabled && overlayEnabled) { value ->
                                enabled = value
                                store.masterEnabled = value
                                if (!value) {
                                    NotificationEdgeOverlay.dismiss()
                                    HaloOverlay.dismiss()
                                    PillHaloOverlay.dismiss()
                                }
                                HaloNotificationListener.requestPillRefresh()
                            }
                        }
                        item {
                            SectionCard {
                                Text("One engine for all effects", style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold)
                                Text("Incoming notifications and music use the same GPU-designed edge patterns. No cartoon characters or separate border layer.",
                                    color = Muted)
                                Button(onClick = { tab = HaloTab.MUSIC },
                                    modifier = Modifier.fillMaxWidth()) { Text("Open Edge Studio") }
                            }
                        }
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                MetricCard("Notifications", "${NotificationActivityState.activeCount}", Modifier.weight(1f))
                                MetricCard("Listener", if (NotificationActivityState.listenerConnected) "Live" else "Offline",
                                    Modifier.weight(1f))
                            }
                        }
                        if (!listenerEnabled || !overlayEnabled) {
                            item {
                                SectionCard {
                                    Text("Complete setup", fontWeight = FontWeight.Bold)
                                    if (!listenerEnabled) Button(onClick = requestListener,
                                        modifier = Modifier.fillMaxWidth()) { Text("Enable notification access") }
                                    if (!overlayEnabled) OutlinedButton(onClick = requestOverlay,
                                        modifier = Modifier.fillMaxWidth()) { Text("Allow screen overlay") }
                                }
                            }
                        }
                        item { Text("Recent activity", style = MaterialTheme.typography.titleLarge) }
                        if (NotificationActivityState.recent.isEmpty()) {
                            item { SectionCard { Text("No notification events yet.", color = Muted) } }
                        } else {
                            items(NotificationActivityState.recent.take(8)) { event ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    ColorDot(store.readForPackage(event.packageName).color)
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(applicationLabel(context, event.packageName),
                                            fontWeight = FontWeight.SemiBold)
                                        Text(if (event.type == EventType.RECEIVED) "Notification received"
                                            else "Notification dismissed", color = Muted)
                                    }
                                    Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(event.timestamp)),
                                        color = Muted, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                    HaloTab.APPS -> {
                        item {
                            Text("Notification app rules", style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold)
                            Text("Use the global Music design for every notification. Choose which apps can trigger it and override their colors.",
                                color = Muted)
                        }
                        item { AppRulesEditor(store) }
                    }
                    HaloTab.SETTINGS -> {
                        item {
                            Text("Settings & permissions", style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold)
                        }
                        item { PermissionSection("Notification access", listenerEnabled, requestListener) }
                        item { PermissionSection("Display over apps", overlayEnabled, requestOverlay) }
                        item { SectionCard { QuietHoursEditor(store) } }
                        item {
                            SectionCard {
                                Text("Unified notification lighting", style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold)
                                Text("Every notification plays your selected Music design and uses its brightness, spread, glow, edge calibration and camera-cutout settings.",
                                    color = Muted)
                                var persistent by remember { mutableStateOf(store.notificationBorderPersistent) }
                                SwitchRow("Keep active notification lighting", "Continue gentle motion until the notification is dismissed.",
                                    persistent) {
                                    persistent = it
                                    store.notificationBorderPersistent = it
                                }
                                Button(onClick = {
                                    if (overlayEnabled) {
                                        val config = MusicConfiguration.read(context)
                                        NotificationEdgeOverlay.show(context, config.color.toLong(),
                                            4200L, false, store)
                                    } else requestOverlay()
                                }, modifier = Modifier.fillMaxWidth()) { Text("Test selected Music edge effect") }
                                OutlinedButton(onClick = { tab = HaloTab.MUSIC },
                                    modifier = Modifier.fillMaxWidth()) { Text("Customize effects in Music") }
                            }
                        }
                        item {
                            SectionCard {
                                Text("Notification behavior", style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold)
                                var silent by remember { mutableStateOf(store.showSilent) }
                                var screen by remember { mutableStateOf(store.onlyScreenOn) }
                                SwitchRow("Include silent notifications",
                                    "Normally only alerting notifications trigger an effect.", silent) {
                                    silent = it
                                    store.showSilent = it
                                }
                                SwitchRow("Screen-on only",
                                    "Suppress lighting while Android reports that the screen is off.", screen) {
                                    screen = it
                                    store.onlyScreenOn = it
                                }
                                SwitchRow("Material 3 system colors", "Use Samsung wallpaper-derived colors in the UI.",
                                    dynamicColors, onDynamicColorsChange)
                                OutlinedButton(onClick = {
                                    NotificationEdgeOverlay.dismiss()
                                    HaloOverlay.dismiss()
                                    PillHaloOverlay.dismiss()
                                }, modifier = Modifier.fillMaxWidth()) { Text("Clear overlay effects") }
                            }
                        }
                        item {
                            SectionCard {
                                Text("Permissions & privacy", style = MaterialTheme.typography.titleMedium)
                                Text("Notification events use local app identity and color. No notification message bodies are stored. Music capture remains optional and separate.",
                                    color = Muted)
                                Text("Android may restrict drawing over secure screens, Samsung's status bar, and AOD.",
                                    color = Muted)
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
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

    fun update(value: ApplicationRule) {
        store.writeRule(value)
        rule = value
        revision++
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(value = filter, onValueChange = { filter = it },
            label = { Text("Search installed apps") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        val visible = installed.filter { filter.isBlank() ||
            it.label.contains(filter, true) || it.packageName.contains(filter, true) }
        rule?.let { current ->
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(applicationLabel(context, current.packageName),
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(current.packageName, style = MaterialTheme.typography.labelSmall,
                            color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    OutlinedButton(onClick = { selectedPackage = null }) { Text("Close") }
                }
                SwitchRow("Enable notification lighting", "Trigger the Music edge design when this app sends a notification.",
                    current.enabled) { update(current.copy(enabled = it)) }
                val fallbackColor = CharacterApp.fromPackage(current.packageName).initialColor
                var customHex by remember(current.packageName) {
                    mutableStateOf("#%06X".format((current.colorOverride ?: fallbackColor) and 0xFFFFFF))
                }
                Text("Notification accent color", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(value = customHex, onValueChange = { typed ->
                    customHex = typed.take(7)
                    if (Regex("^#[0-9A-Fa-f]{6}$").matches(customHex)) {
                        update(current.copy(colorOverride =
                            customHex.substring(1).toLong(16) or 0xFF000000L))
                    }
                }, label = { Text("HEX color") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(selected = current.colorOverride == null,
                            onClick = { update(current.copy(colorOverride = null)) },
                            label = { Text("Auto") })
                    }
                    items(Swatches) { color ->
                        Box(Modifier.size(36.dp).background(Color(color), CircleShape)
                            .clickable { update(current.copy(colorOverride = color)) })
                    }
                }
                Text("The animation style, timing, and geometry are shared with your Music Studio settings.",
                    style = MaterialTheme.typography.bodySmall, color = Muted)
                OutlinedButton(onClick = {
                    store.clearRule(current.packageName)
                    rule = store.readRule(current.packageName)
                    revision++
                }, modifier = Modifier.fillMaxWidth()) { Text("Reset app rule") }
            }
        }
        Text("${visible.size} installed apps", style = MaterialTheme.typography.labelMedium,
            color = Muted)
        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)) {
            items(visible, key = { it.packageName }) { app ->
                val data = store.readRule(app.packageName)
                Card(modifier = Modifier.fillMaxWidth()
                    .clickable { selectedPackage = app.packageName },
                    shape = RoundedCornerShape(19.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        ColorDot(data.colorOverride ?: CharacterApp.fromPackage(data.packageName).initialColor)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(app.label, fontWeight = FontWeight.SemiBold, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            Text(if (data.enabled) "Uses Music edge" else "Disabled",
                                color = Muted, style = MaterialTheme.typography.bodySmall)
                        }
                        Text("Edit", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        Text("Only app names and notification metadata are used. HALO does not read or save message text.",
            color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}
