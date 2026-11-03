package com.oddfinder.itemlens

import android.opengl.Matrix
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ar.core.Camera
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.TrackingState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.sqrt

enum class MeasureMode { OFF, LENGTH, AREA, ANGLE }
data class MeasureDot(val x: Float, val y: Float, val visible: Boolean)
data class MeasureViewState(
    val mode: MeasureMode = MeasureMode.OFF,
    val dots: List<MeasureDot> = emptyList(),
    val count: Int = 0,
    val result: String = "",
    val hint: String = "Aim at a detected surface",
    val handX: Float? = null,
    val handY: Float? = null,
    val handPinched: Boolean = false,
    val inchUnits: Boolean = false,
    val gesturesEnabled: Boolean = true
)
private sealed interface MeasureCommand {
    data class Place(val x: Float, val y: Float) : MeasureCommand
    data object Undo : MeasureCommand
    data object Clear : MeasureCommand
}
object MeasureToolkit {
    private val _state = MutableStateFlow(MeasureViewState())
    val state: StateFlow<MeasureViewState> = _state
    private val commands = ConcurrentLinkedQueue<MeasureCommand>()
    private val locations = mutableListOf<Pose>()
    @Volatile var mode = MeasureMode.OFF
        private set
    @Volatile var gesturesEnabled: Boolean = true
        private set
    @Volatile private var inches: Boolean = false
    @Volatile private var fingertip: Pair<Float, Float>? = null
    @Volatile private var pinched = false
    @Volatile private var lastHandAt = 0L
    private var note = "Aim at a surface and tap Add point"
    private var lastUpdate = 0L

    fun select(value: MeasureMode) {
        mode = value
        commands.clear()
        commands.offer(MeasureCommand.Clear)
        _state.value = MeasureViewState(mode = value, gesturesEnabled = gesturesEnabled, inchUnits = inches)
    }
    fun addAtCenter() { commands.offer(MeasureCommand.Place(Float.NaN, Float.NaN)) }
    fun addAt(x: Float, y: Float) { commands.offer(MeasureCommand.Place(x, y)) }
    fun undo() { commands.offer(MeasureCommand.Undo) }
    fun clear() { commands.offer(MeasureCommand.Clear) }
    fun toggleUnits() { inches = !inches; _state.value = _state.value.copy(inchUnits = inches) }
    fun toggleGestures() { gesturesEnabled = !gesturesEnabled; _state.value = _state.value.copy(gesturesEnabled = gesturesEnabled) }

    fun reportHand(x: Float, y: Float, touching: Boolean, stamp: Long) {
        fingertip = Pair(x, y)
        pinched = touching
        lastHandAt = stamp
    }
    fun hideHand() {
        fingertip = null
        pinched = false
    }
    fun resetTracking() {
        locations.clear()
        commands.clear()
        hideHand()
        _state.value = _state.value.copy(dots = emptyList(), count = 0, result = "", hint = "Move phone slowly to recover tracking")
    }

    fun onRenderFrame(frame: Frame, camera: Camera, width: Int, height: Int, tracking: Boolean, now: Long) {
        if (mode == MeasureMode.OFF) {
            locations.clear()
            commands.clear()
            return
        }
        while (true) {
            val command = commands.poll() ?: break
            when (command) {
                MeasureCommand.Clear -> { locations.clear(); note = "Aim at a surface and add a point" }
                MeasureCommand.Undo -> { if (locations.isNotEmpty()) locations.removeAt(locations.lastIndex) }
                is MeasureCommand.Place -> {
                    if (!tracking) {
                        note = "Tracking lost — move the phone slowly"
                        continue
                    }
                    val x = if (command.x.isNaN()) width / 2f else command.x
                    val y = if (command.y.isNaN()) height / 2f else command.y
                    val hit = runCatching {
                        frame.hitTest(x, y).firstOrNull { h ->
                            if (h.distance <= 0.08f || h.distance > 9f) false
                            else when (val surface = h.trackable) {
                                is Plane -> surface.trackingState == TrackingState.TRACKING && surface.isPoseInPolygon(h.hitPose)
                                is DepthPoint -> surface.trackingState == TrackingState.TRACKING
                                else -> false
                            }
                        }
                    }.getOrNull()
                    if (hit == null) {
                        note = "No real surface at that point — scan slowly or adjust aim"
                        continue
                    }
                    val maxCount = when (mode) {
                        MeasureMode.LENGTH -> 2
                        MeasureMode.ANGLE -> 3
                        MeasureMode.AREA -> 16
                        else -> 0
                    }
                    if (mode != MeasureMode.AREA && locations.size >= maxCount) locations.clear()
                    if (locations.size < maxCount) locations.add(hit.hitPose)
                    note = "Point added using ARCore surface tracking"
                }
            }
        }
        if (now - lastUpdate < 85L) return
        lastUpdate = now
        val dots = if (tracking) locations.map { project(camera, it, width, height) } else emptyList()
        val hand = if (now - lastHandAt < 550L) fingertip else null
        _state.value = MeasureViewState(
            mode = mode, dots = dots, count = locations.size,
            result = calculate(),
            hint = if (tracking) note else "Move the phone slowly to recover tracking",
            handX = hand?.first, handY = hand?.second,
            handPinched = hand != null && pinched,
            inchUnits = inches, gesturesEnabled = gesturesEnabled
        )
    }

    private fun project(camera: Camera, pose: Pose, width: Int, height: Int): MeasureDot {
        val v = FloatArray(16)
        val p = FloatArray(16)
        val eye = FloatArray(4)
        val clip = FloatArray(4)
        camera.getViewMatrix(v, 0)
        camera.getProjectionMatrix(p, 0, 0.05f, 30f)
        Matrix.multiplyMV(eye, 0, v, 0, floatArrayOf(pose.tx(), pose.ty(), pose.tz(), 1f), 0)
        Matrix.multiplyMV(clip, 0, p, 0, eye, 0)
        if (clip[3] <= 0.01f) return MeasureDot(0f, 0f, false)
        val nx = clip[0] / clip[3]
        val ny = clip[1] / clip[3]
        return MeasureDot((nx + 1f) * width * .5f, (1f - ny) * height * .5f, nx in -1f..1f && ny in -1f..1f)
    }
    private fun dist(a: Pose, b: Pose): Double {
        val x = (a.tx() - b.tx()).toDouble()
        val y = (a.ty() - b.ty()).toDouble()
        val z = (a.tz() - b.tz()).toDouble()
        return sqrt(x * x + y * y + z * z)
    }
    private fun linear(m: Double): String = if (inches) {
        val feet = m * 3.280839895
        if (feet < 2) String.format(Locale.US, "%.1f in", m * 39.37007874)
        else String.format(Locale.US, "%.2f ft", feet)
    } else if (m < 1) String.format(Locale.US, "%.1f cm", m * 100)
    else String.format(Locale.US, "%.2f m", m)

    private fun calculate(): String {
        val n = locations.size
        if (n == 0) return "No points yet"
        if (mode == MeasureMode.LENGTH) return if (n < 2) "Place a second point" else linear(dist(locations[0], locations[1]))
        if (mode == MeasureMode.ANGLE) {
            if (n < 3) return "Place " + (3 - n) + " more point(s)"
            val a = locations[0]; val b = locations[1]; val c = locations[2]
            val ux = (a.tx() - b.tx()).toDouble(); val uy = (a.ty() - b.ty()).toDouble(); val uz = (a.tz() - b.tz()).toDouble()
            val vx = (c.tx() - b.tx()).toDouble(); val vy = (c.ty() - b.ty()).toDouble(); val vz = (c.tz() - b.tz()).toDouble()
            val denom = sqrt((ux*ux+uy*uy+uz*uz)*(vx*vx+vy*vy+vz*vz))
            if (denom < 1e-7) return "Points too close"
            val degrees = Math.toDegrees(acos(((ux*vx+uy*vy+uz*vz)/denom).coerceIn(-1.0,1.0)))
            return String.format(Locale.US, "%.1f°", degrees)
        }
        if (mode == MeasureMode.AREA) {
            if (n < 3) return "Place at least three corners"
            var ax = 0.0; var ay = 0.0; var az = 0.0
            var perimeter = 0.0
            for (i in locations.indices) {
                val a = locations[i]; val b = locations[(i + 1) % n]
                ax += (a.ty() - b.ty()) * (a.tz() + b.tz())
                ay += (a.tz() - b.tz()) * (a.tx() + b.tx())
                az += (a.tx() - b.tx()) * (a.ty() + b.ty())
                perimeter += dist(a, b)
            }
            val area = .5 * sqrt(ax*ax+ay*ay+az*az)
            val areaText = if (inches) String.format(Locale.US, "%.2f ft²", area * 10.7639104)
              else String.format(Locale.US, "%.2f m²", area)
            return areaText + " · perimeter " + linear(perimeter)
        }
        return ""
    }
}

@Composable
fun MeasureToolkitUi() {
    val state by MeasureToolkit.state.collectAsState()
    val accent = Color(0xFFB0F7A0)
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val visible = state.dots.filter { it.visible }
            if (visible.size >= 2) {
                for (i in 0 until visible.lastIndex) {
                    drawLine(accent, Offset(visible[i].x, visible[i].y), Offset(visible[i+1].x, visible[i+1].y), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
                }
                if (state.mode == MeasureMode.AREA && visible.size > 2)
                    drawLine(accent.copy(alpha = .75f), Offset(visible.last().x, visible.last().y), Offset(visible.first().x, visible.first().y), strokeWidth = 2.dp.toPx())
            }
            for (point in visible) {
                drawCircle(Color.Black.copy(alpha=.75f), 12.dp.toPx(), Offset(point.x, point.y))
                drawCircle(accent, 7.dp.toPx(), Offset(point.x, point.y))
                drawCircle(Color.Black, 3.dp.toPx(), Offset(point.x, point.y))
            }
            if (state.handX != null && state.handY != null && state.mode != MeasureMode.OFF) {
                drawCircle(if (state.handPinched) accent else Color.White, if (state.handPinched) 17.dp.toPx() else 11.dp.toPx(), Offset(state.handX!!, state.handY!!), style = Stroke(2.dp.toPx()))
            }
        }
        if (state.mode == MeasureMode.OFF) {
            OutlinedButton(
                onClick = { MeasureToolkit.select(MeasureMode.LENGTH) },
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp),
                shape = RoundedCornerShape(20.dp)
            ) {
                Icon(Icons.Rounded.Straighten, null)
                Spacer(Modifier.width(5.dp))
                Text("Measure")
            }
        } else {
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).padding(start = 12.dp, end = 12.dp, bottom = 160.dp),
                color = Color(0xF0192721), shape = RoundedCornerShape(20.dp),
                tonalElevation = 4.dp
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        for (mode in listOf(MeasureMode.LENGTH, MeasureMode.AREA, MeasureMode.ANGLE)) {
                            FilterChip(
                                selected = state.mode == mode,
                                onClick = { MeasureToolkit.select(mode) },
                                label = { Text(when(mode) { MeasureMode.LENGTH -> "Ruler"; MeasureMode.AREA -> "Area"; else -> "Angle" }, fontSize = 12.sp) }
                            )
                        }
                        OutlinedButton(onClick = { MeasureToolkit.select(MeasureMode.OFF) }) { Text("×") }
                    }
                    Text(state.result, fontWeight = FontWeight.Bold, fontSize = 19.sp, color = accent)
                    Text(state.hint, fontSize = 11.sp, color = Color(0xFFCBD7CE))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = MeasureToolkit::addAtCenter, contentPadding = ButtonDefaults.ContentPadding) { Text("Add point") }
                        OutlinedButton(onClick = MeasureToolkit::undo) { Text("Undo") }
                        OutlinedButton(onClick = MeasureToolkit::clear) { Text("Clear") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = MeasureToolkit::toggleUnits) { Text(if (state.inchUnits) "Feet / in" else "Metres / cm") }
                        OutlinedButton(onClick = MeasureToolkit::toggleGestures) { Text(if (state.gesturesEnabled) "Pinch ON" else "Pinch OFF") }
                    }
                }
            }
        }
    }
}
