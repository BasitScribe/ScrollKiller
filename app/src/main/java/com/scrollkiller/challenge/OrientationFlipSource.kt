package com.scrollkiller.challenge

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log

/**
 * [SensorStrategy.ORIENTATION_FLIPS] — the flip challenge, from `TYPE_ACCELEROMETER`.
 *
 * The thin half. It forwards the Z axis to [FlipDetector] and increments on each true; what counts
 * as a flip, and the dead band that stops one slow turn reading as a dozen, live there.
 *
 * ## SENSOR_DELAY_UI, which is neither of the rates already in use
 * The holds sample at `_NORMAL` (~200ms) because orientation is a sustained state with no transient
 * to catch, and jump and shake sample at `_GAME` (~20ms) because their peaks are tens of
 * milliseconds wide. A flip sits between: it is a STATE change, so there is no spike to miss, but
 * both of its states have to be observed and a brisk wrist flip can pass through one in a couple of
 * hundred milliseconds. At `_NORMAL` a fast flip-and-back could be sampled only in transit and never
 * counted; ~60ms samples every settled orientation several times over while costing a fraction of
 * `_GAME` across a sixty-second attempt.
 *
 * Batching stays off for D50's reason: a flushed burst makes the ring sit still and then jump.
 *
 * Main thread only; see [ChallengeSensorSource].
 */
class OrientationFlipSource(private val context: Context) : ChallengeSensorSource {

    private val sensorManager: SensorManager? =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    /** Registered listener while running; null when stopped. Doubles as the "is running" flag. */
    private var listener: SensorEventListener? = null

    /** Tracks the settled orientation. Cleared per attempt in [stop]. */
    private val detector = FlipDetector()

    override fun hasSensor(): Boolean = resolveSensor() != null

    override fun start(progress: ChallengeProgress, onChange: () -> Unit): Boolean {
        stop()
        val manager = sensorManager ?: return false
        val sensor = resolveSensor() ?: return false

        val callback = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                val values = event?.values ?: return
                if (values.size < 3) return

                // No timestamp: the dead band between the two settled orientations is the whole
                // debouncing strategy, so unlike jump and shake there is no refractory window to
                // gate and nothing here needs a clock. See FlipDetector.
                if (!detector.onSample(values[2].toDouble())) return

                progress.onIncrement()
                onChange()
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        val registered = try {
            manager.registerListener(callback, sensor, SensorManager.SENSOR_DELAY_UI, 0)
        } catch (e: Exception) {
            Log.w(TAG, "accelerometer registration failed (flips)", e)
            false
        }
        if (!registered) return false

        listener = callback
        return true
    }

    /** Unregister and forget the settled orientation. Idempotent — safe from every dismissal path. */
    override fun stop() {
        val current = listener ?: return
        listener = null
        detector.reset()
        try {
            sensorManager?.unregisterListener(current)
        } catch (e: Exception) {
            Log.w(TAG, "accelerometer unregister failed (flips)", e)
        }
    }

    private fun resolveSensor(): Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private companion object {
        const val TAG = "ScrollKiller"
    }
}
