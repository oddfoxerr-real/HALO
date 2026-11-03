package com.oddfinder.itemlens

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Detects a phone resting screen-up or screen-down on a horizontal surface.
 * Gravity provides level orientation, not an absolute room coordinate.
 */
class FlatStartingPoint(context: Context) : SensorEventListener {
    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gravity = sensors.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    @Volatile private var lastSample = 0L
    @Volatile private var lastFlat = 0L
    @Volatile private var stableFrom = 0L
    @Volatile var level = false
        private set

    fun start() {
        stableFrom = 0L
        level = false
        gravity?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }
    fun stop() {
        sensors.unregisterListener(this)
        stableFrom = 0L
        level = false
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    override fun onSensorChanged(event: SensorEvent) {
        val v = event.values
        val mag = sqrt(v[0]*v[0] + v[1]*v[1] + v[2]*v[2])
        val now = SystemClock.uptimeMillis()
        lastSample = now
        if (mag in 7f..13f && abs(v[2])/mag > 0.94f) {
            if (stableFrom == 0L) stableFrom = now
            if (now - stableFrom > 450L) {
                level = true
                lastFlat = now
            }
        } else {
            stableFrom = 0L
            level = false
        }
    }
    fun canCalibrate(): Boolean {
        val now = SystemClock.uptimeMillis()
        return now - lastSample < 1500L && now - lastFlat < 1300L
    }
    fun lifted(): Boolean = !level && SystemClock.uptimeMillis() - lastFlat > 350L
}
