package dev.kaff.app.data.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.sqrt

/**
 * Detects a deliberate shake: two strong jolts (over [THRESHOLD_G]) within [WINDOW_MS]. Uses the
 * accelerometer at the normal (lowest useful) rate to keep power use minimal.
 */
class ShakeDetector(context: Context, private val onShake: () -> Unit) : SensorEventListener {

    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private var firstJoltAt = 0L
    private var lastShakeAt = 0L

    fun start(): Boolean {
        val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return false
        return sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val (x, y, z) = event.values
        val g = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
        if (g < THRESHOLD_G) return
        val now = SystemClock.elapsedRealtime()
        when {
            now - lastShakeAt < COOLDOWN_MS -> Unit
            firstJoltAt != 0L && now - firstJoltAt in MIN_GAP_MS..WINDOW_MS -> {
                firstJoltAt = 0L
                lastShakeAt = now
                onShake()
            }
            now - firstJoltAt > WINDOW_MS || firstJoltAt == 0L -> firstJoltAt = now
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val THRESHOLD_G = 2.6f
        const val MIN_GAP_MS = 120L
        const val WINDOW_MS = 700L
        const val COOLDOWN_MS = 2_000L
    }
}
