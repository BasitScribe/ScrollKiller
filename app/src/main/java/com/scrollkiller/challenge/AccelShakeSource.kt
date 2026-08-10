package com.scrollkiller.challenge

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log

/**
 * [SensorStrategy.ACCEL_SHAKE] — the shake challenge, from `TYPE_ACCELEROMETER`.
 *
 * The thin half. It forwards raw axes and a monotonic timestamp to [ShakeDetector] and increments on
 * each true; what a shake IS lives there, where it is unit-testable off-device.
 *
 * ## Why the raw axes go through rather than a magnitude
 * [AccelPeakSource] hands [JumpDetector] a single magnitude, because a jump is a magnitude story —
 * free fall is small, a landing is large, and direction is irrelevant. A shake is the opposite: it
 * is entirely about direction changing, and magnitude discards exactly that. See [ShakeDetector] for
 * why a magnitude-based shake counter reads zero no matter how hard the phone is shaken.
 *
 * ## SENSOR_DELAY_GAME, like jump and unlike the holds
 * A shake reverses several times a second and the peak of each reversal is tens of milliseconds
 * wide, so `_UI` (~60ms) can sample either side of one and miss it — which reads to the user as a
 * shake that did not count, indistinguishable from the app being broken. `_NORMAL` (~200ms) would
 * miss most of them. Batching stays off for D50's reason: a flushed burst makes the ring sit still
 * and then jump.
 *
 * Main thread only; see [ChallengeSensorSource].
 */
class AccelShakeSource(private val context: Context) : ChallengeSensorSource {

    private val sensorManager: SensorManager? =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    /** Registered listener while running; null when stopped. Doubles as the "is running" flag. */
    private var listener: SensorEventListener? = null

    /** Counts the shakes. Cleared per attempt in [stop]. */
    private val detector = ShakeDetector()

    override fun hasSensor(): Boolean = resolveSensor() != null

    override fun start(progress: ChallengeProgress, onChange: () -> Unit): Boolean {
        stop()
        val manager = sensorManager ?: return false
        val sensor = resolveSensor() ?: return false

        val callback = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                val values = event?.values ?: return
                if (values.size < 3) return

                // elapsedRealtime, not event.timestamp — several vendors report a wall clock or an
                // uptime that stalls in suspend there, and this timestamp gates the refractory.
                val shook = detector.onSample(
                    x = values[0].toDouble(),
                    y = values[1].toDouble(),
                    z = values[2].toDouble(),
                    timestampMs = SystemClock.elapsedRealtime(),
                )
                if (!shook) return

                progress.onIncrement()
                onChange()
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        val registered = try {
            manager.registerListener(callback, sensor, SensorManager.SENSOR_DELAY_GAME, 0)
        } catch (e: Exception) {
            Log.w(TAG, "accelerometer registration failed (shake)", e)
            false
        }
        if (!registered) return false

        listener = callback
        return true
    }

    /** Unregister and forget the gravity estimate. Idempotent — safe from every dismissal path. */
    override fun stop() {
        val current = listener ?: return
        listener = null
        detector.reset()
        try {
            sensorManager?.unregisterListener(current)
        } catch (e: Exception) {
            Log.w(TAG, "accelerometer unregister failed (shake)", e)
        }
    }

    private fun resolveSensor(): Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private companion object {
        const val TAG = "ScrollKiller"
    }
}
