package com.scrollkiller.challenge

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log

/**
 * [SensorStrategy.ORIENTATION_HOLD] — the face-down hold, from `TYPE_ACCELEROMETER`.
 *
 * The thin half. It reads one axis, decides whether the screen is currently pointing at the floor,
 * and hands that boolean to [HoldDetector]; how long counts as held and what a break costs live
 * there, where they are unit-testable off-device.
 *
 * ## How face-down is read
 * The accelerometer's Z axis points out of the screen, so gravity puts Z at about **+9.8 when the
 * screen faces up** and about **−9.8 when it faces the floor**. Face-down is therefore
 * `z < FACE_DOWN_MAX_Z`, and that threshold is deliberately loose (about −7, roughly 45° of tilt):
 * phones rest on cushions, uneven tables, and their own camera bumps, and a strict −9 would fail a
 * user who did exactly what they were asked. The opposite error — counting a phone merely tilted
 * away — is prevented by the sign, not the magnitude: Z cannot be meaningfully negative unless the
 * screen is genuinely pointed downward.
 *
 * ## Why SENSOR_DELAY_NORMAL, where jump needed _GAME
 * [AccelPeakSource] samples at ~20ms because a landing spike is tens of milliseconds wide and can be
 * missed between samples entirely. A hold has no transient to catch — orientation is a sustained
 * state — so ~200ms is plenty to keep a per-second label honest, and it is far cheaper across a
 * thirty-second hold. The ring's own idempotent `setProgress` absorbs the four-in-five samples that
 * do not change the displayed second.
 *
 * Batching stays off for the same reason as every other source: a flush would make the ring jump.
 *
 * Main thread only; see [ChallengeSensorSource].
 */
class OrientationHoldSource(private val context: Context) : ChallengeSensorSource {

    private val sensorManager: SensorManager? =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    /** Registered listener while running; null when stopped. Doubles as the "is running" flag. */
    private var listener: SensorEventListener? = null

    /** Times the hold and forgets it on a break. Cleared per attempt in [stop]. */
    private val detector = HoldDetector()

    override fun hasSensor(): Boolean = resolveSensor() != null

    override fun start(progress: ChallengeProgress, onChange: () -> Unit): Boolean {
        stop()
        val manager = sensorManager ?: return false
        val sensor = resolveSensor() ?: return false

        val callback = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                val values = event?.values ?: return
                if (values.size < 3) return

                val faceDown = values[2] < FACE_DOWN_MAX_Z
                // elapsedRealtime, not event.timestamp — several vendors report a wall clock or an
                // uptime that stalls in suspend there, and this timestamp IS the user's progress.
                val heldSeconds = detector.onSample(faceDown, SystemClock.elapsedRealtime())

                // Pushed unconditionally: 0 is meaningful (the hold broke, reset the ring), and
                // onHoldElapsed is absolute so an unchanged second is a no-op assignment.
                progress.onHoldElapsed(heldSeconds)
                onChange()
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        val registered = try {
            manager.registerListener(callback, sensor, SensorManager.SENSOR_DELAY_NORMAL, 0)
        } catch (e: Exception) {
            Log.w(TAG, "accelerometer registration failed (orientation hold)", e)
            false
        }
        if (!registered) return false

        listener = callback
        return true
    }

    /** Unregister and forget any hold in flight. Idempotent — safe from every dismissal path. */
    override fun stop() {
        val current = listener ?: return
        listener = null
        detector.reset()
        try {
            sensorManager?.unregisterListener(current)
        } catch (e: Exception) {
            Log.w(TAG, "accelerometer unregister failed (orientation hold)", e)
        }
    }

    private fun resolveSensor(): Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private companion object {
        const val TAG = "ScrollKiller"

        /**
         * Z below this means the screen is pointed at the floor. About 45° of tolerance — see the
         * class doc for why loose is the safe direction to err here.
         */
        const val FACE_DOWN_MAX_Z = -7.0f
    }
}
