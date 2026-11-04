package dev.oddfoxerr.halo

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/** Standalone notification designer. Music preferences are never read or changed. */
@Composable
internal fun NotificationStudio(store: ProfileStore, overlayEnabled: Boolean, requestOverlay: () -> Unit) {
    val context = LocalContext.current
    var design by remember { mutableStateOf(store.notificationEdgeDesign) }
    var family by remember { mutableStateOf<EdgeGroup?>(store.notificationEdgeDesign.group) }
    var sampleColor by remember { mutableStateOf(store.notificationPreviewColor) }
    var persistence by remember { mutableStateOf(store.notificationBorderPersistent) }
    var duration by remember { mutableFloatStateOf(store.notificationDurationMs.toFloat()) }
    var glow by remember { mutableFloatStateOf(store.notificationBorderGlow) }
    var thickness by remember { mutableFloatStateOf(store.notificationBorderThickness) }
    val preview = remember(context) { ChainsawView(context) }

    LaunchedEffect(design, sampleColor, glow, thickness) {
        preview.configure(NotificationLighting.config(store, sampleColor.toInt(), design))
        preview.playNotification(sampleColor.toInt(), 4200L, true)
    }
    DisposableEffect(preview) {
        onDispose { preview.stopNotification() }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Live notification preview", style = MaterialTheme.typography.titleMedium)
        Text("Select any style below. This is a separate preview from Music.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Box(Modifier.width(198.dp).height(340.dp)
                .background(Color.Black, RoundedCornerShape(24.dp))) {
                AndroidView(factory = { preview }, modifier = Modifier.fillMaxSize(),
                    update = {
                        it.configure(NotificationLighting.config(store, sampleColor.toInt(), design))
                    })
                Column(Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("NOTIFICATION", color = Color(0xFFB3ADC3),
                        style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.height(5.dp))
                    Text(design.title, color = Color(0xFFF1EDFF),
                        style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(5.dp))
                    Text("LIVE PREVIEW", color = Color(0xFF908AA0),
                        style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Text("Notification style: " + design.title, style = MaterialTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            FilterChip(
                selected = family == null,
                onClick = { family = null },
                label = { Text("All · 59") }
            )
            EdgeGroup.entries.forEach { category ->
                FilterChip(selected = family == category, onClick = { family = category },
                    label = { Text("${category.title} · ${EdgeCatalogue.count(category)}") })
            }
        }
        Text("${EdgeCatalogue.visible(family).size} effects in this view",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        // Same catalogue and order as Music. A bounded lazy list avoids
        // creating dozens of shader previews inside the Alerts screen.
        key(family) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().height(350.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(EdgeCatalogue.visible(family), key = { it.name }) { option ->
                    Card(onClick = {
                        design = option
                        store.notificationEdgeDesign = option
                    }, modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (design == option)
                                MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surface
                        )) {
                        Row(Modifier.fillMaxWidth().padding(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            AndroidView(factory = { EdgeDesignPreview(it) },
                                update = {
                                    val previewSettings = NotificationLighting.config(
                                        store, sampleColor.toInt(), option)
                                    it.configure(option, sampleColor.toInt(), previewSettings.accent)
                                }, modifier = Modifier.width(42.dp).height(76.dp)
                                    .background(Color.Black, RoundedCornerShape(10.dp)))
                            Column(Modifier.weight(1f)) {
                                Text(option.title, style = MaterialTheme.typography.titleSmall)
                                Text(option.description, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (design == option) Text("Selected",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
        Text("Preview color", style = MaterialTheme.typography.titleSmall)
        Text("Real alerts use each app's assigned color; these swatches affect only the preview.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            listOf(0xFF8790FBL, 0xFF4BE49DL, 0xFFA879FFL, 0xFFFF5365L, 0xFF7CB9FFL)
                .forEach { color ->
                    FilterChip(selected = sampleColor == color,
                        onClick = {
                            sampleColor = color
                            store.notificationPreviewColor = color
                        }, label = {
                            Box(Modifier.size(17.dp)
                                .background(Color(color), RoundedCornerShape(9.dp)))
                        })
                }
        }
        Text("Thickness: " + String.format("%.1f", thickness),
            style = MaterialTheme.typography.labelLarge)
        Slider(value = thickness, onValueChange = {
            thickness = it
            store.notificationBorderThickness = it
        }, valueRange = 1f..3f)
        Text("Glow: " + (glow * 100).toInt() + "%",
            style = MaterialTheme.typography.labelLarge)
        Slider(value = glow, onValueChange = {
            glow = it
            store.notificationBorderGlow = it
        }, valueRange = 0f..1f)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Keep light until dismissed", style = MaterialTheme.typography.titleSmall)
                Text("Continue a gentle light while that notification is active.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = persistence, onCheckedChange = {
                persistence = it
                store.notificationBorderPersistent = it
            })
        }
        Text("Duration: " + String.format("%.1f", duration / 1000f) + " seconds",
            style = MaterialTheme.typography.labelLarge)
        Slider(value = duration, onValueChange = {
            duration = it
            store.notificationDurationMs = it.toLong()
        }, valueRange = 800f..10_000f)
        Button(onClick = {
            if (!overlayEnabled) requestOverlay()
            else NotificationEdgeOverlay.show(
                context, sampleColor, duration.toLong(), false, store, design)
        }, modifier = Modifier.fillMaxWidth()) { Text("Test effect on my screen") }
        Text("Per-app notification design and color overrides are below.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
