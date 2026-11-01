package dev.oddfoxerr.halo

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

private val HUE_STOPS = listOf(
    0xFFFF4B4B, 0xFFFFCF45, 0xFF62E67B, 0xFF46D7EE,
    0xFF4877FF, 0xFFC45DFF, 0xFFFF4B4B
).map { Color(it) }

private val SWATCHES = listOf(
    0xFFFFFFFF, 0xFFFF5B5B, 0xFFFF944B, 0xFFFFDA57,
    0xFF70E388, 0xFF35D3C4, 0xFF4ACFFF, 0xFF4F93FF,
    0xFF9C7CFF, 0xFFD583FF, 0xFFFF6DAC, 0xFFF7D5B3,
    0xFFCAD5E5, 0xFF8B9BAE, 0xFF2FDEB2, 0xFFFC7474,
    0xFFFFB020, 0xFFE8F675, 0xFF49A9E9, 0xFFB2A1F8,
    0xFFEF70D9, 0xFFB6FFEF, 0xFF9BFFC8, 0xFFFFF0CD
).map(Long::toInt)

private fun hsv(value: Int): FloatArray = FloatArray(3).also {
    AndroidColor.colorToHSV(value, it)
}

private fun toOpaqueColor(hue: Float, saturation: Float, value: Float): Int =
    AndroidColor.HSVToColor(floatArrayOf(
        hue.coerceIn(0f, 359.999f),
        saturation.coerceIn(0f, 1f),
        value.coerceIn(0f, 1f)
    ))

/** 24 quick swatches plus continuous HSV and exact hex input; saved by the caller. */
@Composable
fun ColorStudio(primary: Int, accent: Int, change: (Int, Int) -> Unit) {
    var editing by remember { mutableIntStateOf(0) }
    val current = if (editing == 0) primary else accent
    val channel = if (editing == 0) "Primary" else "Accent"
    val components = remember(current) { hsv(current) }
    var hex by remember(editing, current) {
        mutableStateOf("#%06X".format(current and 0xFFFFFF))
    }

    fun apply(color: Int) {
        val opaque = color or (0xFF shl 24)
        if (editing == 0) change(opaque, accent) else change(primary, opaque)
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier.fillMaxWidth().height(64.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Brush.horizontalGradient(listOf(Color(primary), Color(accent))))
                .semantics { contentDescription = "Current primary to accent color gradient" }
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilterChip(
                selected = editing == 0, onClick = { editing = 0 },
                label = { Text("Primary") },
                leadingIcon = {
                    Box(Modifier.size(18.dp).clip(RoundedCornerShape(5.dp))
                        .background(Color(primary)))
                }
            )
            FilterChip(
                selected = editing == 1, onClick = { editing = 1 },
                label = { Text("Accent") },
                leadingIcon = {
                    Box(Modifier.size(18.dp).clip(RoundedCornerShape(5.dp))
                        .background(Color(accent)))
                }
            )
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = { change(accent, primary) }) { Text("Swap") }
        }

        Text("$channel color", style = MaterialTheme.typography.titleSmall)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SWATCHES.forEachIndexed { index, color ->
                OutlinedButton(
                    onClick = { apply(color) },
                    modifier = Modifier.size(45.dp)
                        .semantics { contentDescription = "Set $channel to swatch ${index + 1}" },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)
                ) {
                    Box(Modifier.size(26.dp).clip(RoundedCornerShape(8.dp))
                        .background(Color(color)))
                }
            }
        }

        Text("Hue · ${components[0].toInt()}°", style = MaterialTheme.typography.labelLarge)
        Box(Modifier.fillMaxWidth().height(12.dp)
            .clip(RoundedCornerShape(12.dp)).background(Brush.horizontalGradient(HUE_STOPS)))
        Slider(
            value = components[0], onValueChange = {
                apply(toOpaqueColor(it, components[1], components[2]))
            }, valueRange = 0f..359.999f,
            modifier = Modifier.semantics { contentDescription = "$channel hue" }
        )

        Text("Saturation · ${(components[1] * 100).toInt()}%",
            style = MaterialTheme.typography.labelLarge)
        Box(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(12.dp))
            .background(Brush.horizontalGradient(listOf(
                Color(toOpaqueColor(components[0], 0f, components[2])),
                Color(toOpaqueColor(components[0], 1f, components[2]))
            ))))
        Slider(value = components[1], onValueChange = {
            apply(toOpaqueColor(components[0], it, components[2]))
        }, modifier = Modifier.semantics { contentDescription = "$channel saturation" })

        Text("Brightness · ${(components[2] * 100).toInt()}%",
            style = MaterialTheme.typography.labelLarge)
        Box(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(12.dp))
            .background(Brush.horizontalGradient(listOf(
                Color.Black, Color(toOpaqueColor(components[0], components[1], 1f))
            ))))
        Slider(value = components[2], onValueChange = {
            apply(toOpaqueColor(components[0], components[1], it))
        }, modifier = Modifier.semantics { contentDescription = "$channel color brightness" })

        OutlinedTextField(
            value = hex,
            onValueChange = { typed ->
                hex = typed.uppercase().take(7)
                if (hex.matches(Regex("#[0-9A-F]{6}"))) {
                    apply((hex.substring(1).toLong(16) or 0xFF000000L).toInt())
                }
            },
            label = { Text("$channel HEX") },
            supportingText = { Text("Exact RGB value, e.g. #9A7BFF") },
            singleLine = true,
            isError = hex.length == 7 && !hex.matches(Regex("#[0-9A-F]{6}")),
            modifier = Modifier.fillMaxWidth()
        )
    }
}
