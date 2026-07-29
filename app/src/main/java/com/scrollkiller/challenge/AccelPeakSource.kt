package com.scrollkiller.challenge

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import kotlin.math.sqrt

/**
 * [SensorStrategy.ACCEL_PEAKS] — counts jumps from `TYPE_ACCELEROMETER`.
 *
 * The thin half. It computes a magnitude, hands it to [JumpDetector], and increments a
 * [ChallengeProgress] when the detector says a jump happened; what a jump IS lives entirely in
 * that class, where it is unit-testable off-device.
 *
 * ## Why the accelerometer and not TYPE_LINEAR_ACCELERATION
 * Linear acceleration would hand us gravity-compensated values and save the magnitude maths, but it
 * is a FUSED sensor: not present on every device, and on some it is synthesised with a low-pass
 * filter whose lag smears exactly the sharp free-fall→landing transition the detector keys on.
 * `TYPE_ACCELEROMETER` is raw, universally present, and needs no runtime permission — unlike the
 * step sensors, which sit behind `ACTIVITY_RECOGNITION` (see [ChallengeAvailability]).
 *
 * ## Why SENSOR_DELAY_GAME rather than _UI
 * A landing spike is a few tens of milliseconds wide. `SENSOR_DELAY_UI` (~60ms) can sample either
 * side of one and miss the peak entirely, which reads to the user as a jump that did not count —
 * the single worst failure this challenge has, because they cannot tell it from the app being
 * broken. `SENSOR_DELAY_GAME` (~20ms) puts several samples inside every spike. It costs more power
 * than _UI for the seconds a challenge is on screen, which is the right trade for the only feedback
 * the mechanic has.
 *
 * Batching is off (`maxReportLatencyUs = 0`) for the same reason [StepSensorSource] switches it off:
 * a batched flush makes the ring sit at 0/10 and then jump to 4/10 in a lump, which the user reads
 * as broken.
 *
 * Main thread only; see [ChallengeSensorSource].
 */
class AccelPeakSource(private val context: Context) : ChallengeSensorSource {

    private val sensorManager: SensorManager? =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    /** Registered listener while running; null when stopped. Doubles as the "is running" flag. */
    private var listener: SensorEventListener? = null

    /** The decision-maker. Recreated per attempt via [JumpDetector.reset] in [stop]. */
    private val detector = JumpDetector()

    override fun hasSensor(): Boolean = resolveSensor() != null

    override fun start(progress: ChallengeProgress, onChange: () -> Unit): Boolean {
        stop()
        val manager = sensorManager ?: return false
        val sensor = resolveSensor() ?: return false

        val callback = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                val values = event?.values ?: return
                if (values.size < 3) return

                val magnitude = sqrt(
                    (values[0] * values[0] + values[1] * values[1] + values[2] * values[2]).toDouble(),
                )

                // elapsedRealtime rather than event.timestamp: the SensorEvent clock is
                // nanoseconds-since-boot on a well-behaved device but is a known lie on several
                // vendors (wall-clock nanos, or an uptime that stalls in suspend). The detector's
                // refractory and arm-expiry windows are the difference between one jump counting
                // once and counting four times, so they get a clock we can trust.
                if (!detector.onSample(magnitude, SystemClock.elapsedRealtime())) return

                progress.onIncrement()
                onChange()
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        val registered = try {
            manager.registerListener(callback, sensor, SensorManager.SENSOR_DELAY_GAME, 0)
        } catch (e: Exception) {
            Log.w(TAG, "accelerometer registration failed", e)
            false
        }
        if (!registered) return false

        listener = callback
        return true
    }

    /** Unregister and forget the baseline. Idempotent — safe from every dismissal path. */
    override fun stop() {
        val current = listener ?: return
        listener = null
        // Reset AFTER clearing the listener: a sample landing between the two would otherwise
        // re-baseline a detector nobody is reading any more.
        detector.reset()
        try {
            sensorManager?.unregisterListener(current)
        } catch (e: Exception) {
            Log.w(TAG, "accelerometer unregister failed", e)
        }
    }

    private fun resolveSensor(): Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private companion object {
        const val TAG = "ScrollKiller"
    }
}
