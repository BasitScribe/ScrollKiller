package com.scrollkiller.challenge

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log

/**
 * [SensorStrategy.TILT_BALANCE] — the balance hold, from `TYPE_ACCELEROMETER`.
 *
 * The thin half, and the thinnest of the set: it asks [BalanceDetector] whether the phone is
 * currently being held flat and level, and hands that boolean to [HoldDetector] exactly as
 * [OrientationHoldSource] hands over its own. Two pure classes, both already tested, composed here.
 *
 * ## Reusing HoldDetector unchanged is the whole point
 * This challenge gets reset-on-break, the counting-down seconds ring, the completion and break
 * haptics and `FLAG_KEEP_SCREEN_ON` without a line of new code, because D54 kept the timing logic
 * pure and separate from what was being timed. That is the same payoff D55 collected when the
 * forehead hold reused it, and it is the reason a third hold cost one small class rather than a
 * subsystem.
 *
 * ## SENSOR_DELAY_UI, faster than the other holds
 * [OrientationHoldSource] samples at `_NORMAL` (~200ms) because face-down is a coarse, sustained
 * state. This one is not coarse: [BalanceDetector] estimates a TREMOR, and a tremor measured at five
 * samples a second is mostly aliasing. ~60ms gives the filter something real to work with while
 * staying far cheaper than `_GAME` across an eighty-second attempt at the top of the ladder.
 *
 * Batching stays off for D50's reason.
 *
 * Main thread only; see [ChallengeSensorSource].
 */
class TiltBalanceSource(private val context: Context) : ChallengeSensorSource {

    private val sensorManager: SensorManager? =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    /** Registered listener while running; null when stopped. Doubles as the "is running" flag. */
    private var listener: SensorEventListener? = null

    /** Decides WHAT is being held: level, screen-up, and alive. */
    private val balance = BalanceDetector()

    /** Times it, and forgets it on a break. The same instance type both other holds use. */
    private val hold = HoldDetector()

    override fun hasSensor(): Boolean = resolveSensor() != null

    override fun start(progress: ChallengeProgress, onChange: () -> Unit): Boolean {
        stop()
        val manager = sensorManager ?: return false
        val sensor = resolveSensor() ?: return false

        val callback = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                val values = event?.values ?: return
                if (values.size < 3) return

                val balanced = balance.onSample(
                    x = values[0].toDouble(),
                    y = values[1].toDouble(),
                    z = values[2].toDouble(),
                )
                // elapsedRealtime, not event.timestamp — several vendors report a wall clock or an
                // uptime that stalls in suspend there, and this timestamp IS the user's progress.
                val heldSeconds = hold.onSample(balanced, SystemClock.elapsedRealtime())

                // Pushed unconditionally: 0 is meaningful (the balance broke, reset the ring), and
                // onHoldElapsed is absolute so an unchanged second is a no-op assignment.
                progress.onHoldElapsed(heldSeconds)
                onChange()
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        val registered = try {
            manager.registerListener(callback, sensor, SensorManager.SENSOR_DELAY_UI, 0)
        } catch (e: Exception) {
            Log.w(TAG, "accelerometer registration failed (tilt balance)", e)
            false
        }
        if (!registered) return false

        listener = callback
        return true
    }

    /**
     * Unregister and forget both the tremor history and any hold in flight. Idempotent — safe from
     * every dismissal path, and it resets BOTH detectors, because a stale tremor estimate would let
     * the next attempt bank seconds against the last attempt's motion.
     */
    override fun stop() {
        val current = listener ?: return
        listener = null
        balance.reset()
        hold.reset()
        try {
            sensorManager?.unregisterListener(current)
        } catch (e: Exception) {
            Log.w(TAG, "accelerometer unregister failed (tilt balance)", e)
        }
    }

    private fun resolveSensor(): Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private companion object {
        const val TAG = "ScrollKiller"
    }
}
