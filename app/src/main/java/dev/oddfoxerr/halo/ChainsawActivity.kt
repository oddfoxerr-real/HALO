package dev.oddfoxerr.halo

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.audiofx.Visualizer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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

/** Foreground music studio: visualizes output mix or an explicitly selected local song. */
class ChainsawActivity : ComponentActivity() {
    private lateinit var chain: ChainsawView
    private val handler = Handler(Looper.getMainLooper())
    private var visualizer: Visualizer? = null
    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null
    private var pendingAction: (() -> Unit)? = null
    private var selectedUri: Uri? = null
    private var fft = ByteArray(0)
    private var previousFrame = 0L
    private var silentSince = 0L
    private var running by mutableStateOf(false)
    private var status by mutableStateOf("Choose device audio or open a song.")
    private var sensitivity by mutableFloatStateOf(1.5f)
    private var rate by mutableFloatStateOf(1f)
    private var thickness by mutableFloatStateOf(1f)
    private var glow by mutableFloatStateOf(.6f)
    private var immersive by mutableStateOf(false)
    private val preferences by lazy { getSharedPreferences("chainsaw_music", MODE_PRIVATE) }
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pendingAction
        pendingAction = null
        if (granted) action?.invoke() else status = "Audio permission denied. Enable it to analyze music; HALO does not record your microphone."
    }
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { selectedUri = uri; openSong(uri) }
    }
    private val frame = object : Runnable {
        override fun run() {
            val source = visualizer ?: return
            try {
                if (source.getFft(fft) != Visualizer.SUCCESS) {
                    stop("Audio data unavailable. Try a local song or restart device audio.")
                    return
                }
                val now = SystemClock.elapsedRealtime()
                val bass = ChainDynamics.bassEnergy(fft, source.samplingRate)
                chain.motor.step(bass, (now - previousFrame) / 1000f, sensitivity, rate)
                previousFrame = now
                if (bass > .004f) silentSince = now
                status = if (now - silentSince > 3500) {
                    "No bass signal — playback may be paused or Android may block this source."
                } else if (chain.motor.jam > .65f) "Jammed · waiting for the next hit" else "Live · chain driven by bass"
                chain.invalidate()
                handler.postDelayed(this, 33)
            } catch (_: RuntimeException) {
                stop("Audio source disconnected. Start again to reconnect.")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        sensitivity = preferences.getFloat("sensitivity", 1.5f).coerceIn(.5f, 5f)
        rate = preferences.getFloat("rate", 1f).coerceIn(.25f, 3f)
        thickness = preferences.getFloat("thickness", 1f).coerceIn(.6f, 1.6f)
        glow = preferences.getFloat("glow", .6f).coerceIn(0f, 1f)
        chain = ChainsawView(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFFF8054), background = Color.Black)) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    AndroidView(factory = { chain }, modifier = Modifier.fillMaxSize(), update = {
                        it.thickness = thickness; it.glow = glow; it.invalidate()
                    })
                    if (immersive) {
                        TextButton(onClick = { immersive = false }, modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding()) {
                            Text("Show controls")
                        }
                    } else {
                        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 38.dp, vertical = 16.dp)
                            .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("CHAINSAW", style = MaterialTheme.typography.headlineLarge, color = Color(0xFFFF8054))
                            Text("Music edge visualizer", style = MaterialTheme.typography.titleMedium, color = Color.White)
                            Text("Steel teeth. Rust-red resin. Beat-triggered restarts.", color = Color.LightGray)
                            Text(status, color = Color(0xFFFFC1A8), style = MaterialTheme.typography.bodyMedium)
                            Button(onClick = { withPermission { startDeviceAudio() } }, modifier = Modifier.fillMaxWidth()) { Text("Use device audio") }
                            OutlinedButton(onClick = { withPermission { picker.launch(arrayOf("audio/*")) } }, modifier = Modifier.fillMaxWidth()) { Text("Open music file") }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { stop("Stopped. Choose a source to restart.") }, enabled = running,
                                    modifier = Modifier.weight(1f)) { Text("Stop") }
                                OutlinedButton(onClick = { selectedUri?.let { uri -> withPermission { openSong(uri) } } },
                                    enabled = selectedUri != null, modifier = Modifier.weight(1f)) { Text("Replay file") }
                            }
                            Control("Beat sensitivity", sensitivity, .5f..5f) { sensitivity = it; save() }
                            Control("Chain speed", rate, .25f..3f) { rate = it; save() }
                            Control("Edge size", thickness, .6f..1.6f) { thickness = it; save() }
                            Control("Glow", glow, 0f..1f) { glow = it; save() }
                            Button(onClick = { immersive = true }, modifier = Modifier.fillMaxWidth()) { Text("Hide controls") }
                            Text("Runs while this screen is open. Device audio availability depends on Android and the player. No microphone recording or audio files are uploaded or saved.",
                                color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { finish() }) { Text("Back to HALO") }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Control(label: String, value: Float, range: ClosedFloatingPointRange<Float>, change: (Float) -> Unit) {
        Text("$label · %.2f".format(value), color = Color.White)
        Slider(value = value, onValueChange = change, valueRange = range)
    }

    private fun save() {
        preferences.edit().putFloat("sensitivity", sensitivity).putFloat("rate", rate)
            .putFloat("thickness", thickness).putFloat("glow", glow).apply()
    }

    private fun withPermission(action: () -> Unit) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) action()
        else {
            pendingAction = action
            status = "Android requires audio permission for its Visualizer API. HALO uses playback data, not the microphone."
            permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startDeviceAudio() {
        stop("Connecting to device audio…")
        attach(0)
    }

    private fun openSong(uri: Uri) {
        stop("Loading music…")
        try {
            val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            val next = MediaPlayer()
            player = next
            next.setAudioAttributes(attributes)
            next.setDataSource(this, uri)
            next.setOnPreparedListener {
                if (player !== it) return@setOnPreparedListener
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attributes)
                    .setOnAudioFocusChangeListener { change ->
                        if (change < 0) stop("Playback stopped because another app needs audio.")
                    }.build()
                focus = request
                if (audioManager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    stop("Audio is busy. Try again when the other audio session ends.")
                } else if (attach(it.audioSessionId)) it.start()
                else stop("Visualizer unavailable for this file or device.")
            }
            next.setOnCompletionListener { stop("Song finished. Replay it or open another file.") }
            next.setOnErrorListener { _, _, _ -> stop("This audio file could not be played. Choose another file."); true }
            next.prepareAsync()
        } catch (_: Exception) { stop("Cannot open this music file. Select a supported audio file.") }
    }

    private fun attach(session: Int): Boolean {
        return try {
            val source = Visualizer(session)
            visualizer = source
            source.captureSize = Visualizer.getCaptureSizeRange().last()
            source.scalingMode = Visualizer.SCALING_MODE_AS_PLAYED
            fft = ByteArray(source.captureSize)
            if (source.setEnabled(true) != Visualizer.SUCCESS) error("Cannot enable audio analysis")
            chain.motor.reset()
            previousFrame = SystemClock.elapsedRealtime()
            silentSince = previousFrame
            running = true
            status = "Listening for bass…"
            handler.post(frame)
            true
        } catch (_: RuntimeException) {
            visualizer?.release(); visualizer = null
            status = "Android could not expose this audio source. Try opening a local music file."
            false
        }
    }

    private fun stop(message: String) {
        handler.removeCallbacks(frame)
        visualizer?.release(); visualizer = null
        player?.release(); player = null
        focus?.let { audioManager.abandonAudioFocusRequest(it) }; focus = null
        running = false
        if (::chain.isInitialized) { chain.motor.reset(); chain.invalidate() }
        status = message
    }

    override fun onStop() {
        super.onStop()
        stop("Stopped while HALO was in the background. Choose a source to resume.")
    }

    override fun onDestroy() {
        pendingAction = null
        stop("Closed")
        super.onDestroy()
    }
}
