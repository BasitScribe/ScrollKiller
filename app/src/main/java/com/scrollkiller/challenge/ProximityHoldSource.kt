package com.scrollkiller.challenge

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import kotlin.math.abs

/**
 * [SensorStrategy.PROXIMITY_HOLD] — the forehead hold, from `TYPE_PROXIMITY` **and**
 * `TYPE_ACCELEROMETER`.
 *
 * The thin half, and the first source to need two sensors. It decides whether the phone is currently
 * held against the user's forehead and hands that boolean to [HoldDetector] — which is reused
 * completely unchanged from the face-down challenge, timing, reset-on-break and all. That reuse is
 * the whole payoff of having kept it pure.
 *
 * ## Why proximity ALONE is not enough
 * A covered proximity sensor means "something is close", not "this phone is at your head". A thumb
 * over the sensor while the phone lies on a table satisfies it perfectly, and that is a two-second
 * cheat on a thirty-second challenge. So the condition is proximity NEAR **and** the device roughly
 * UPRIGHT — on edge rather than flat — which a phone on a table cannot be. Neither half is
 * sufficient and the pairing is the point:
 *  - upright alone is just holding your phone normally, which is what the user was already doing;
 *  - near alone is a thumb.
 *
 * It is still not uncheatable — nothing available to an accessibility service is — but it costs more
 * effort to fake than to simply do, which is the honest bar for every challenge here.
 *
 * ## NEAR is measured against the sensor's own range
 * `values[0]` is nominally centimetres, but a great many devices are BINARY: they report `0` when
 * covered and [Sensor.getMaximumRange] when not, and nothing in between. A hard-coded `< 5f` is
 * therefore wrong on any device whose maximum range is below 5, and needlessly strict on one that
 * reports real distances. Comparing against the sensor's declared maximum is the only portable
 * reading of "something is close".
 *
 * ## One listener, two sensors
 * Both sensors are registered to the SAME [SensorEventListener] object, which matters for teardown:
 * `unregisterListener(listener)` with no sensor argument releases that listener from every sensor it
 * is registered to, so [stop] stays a single call and cannot half-release. The condition is
 * re-evaluated on either sensor's event using the latest value of each, because the two arrive
 * independently and at different rates.
 *
 * Sampling is `SENSOR_DELAY_NORMAL` for the same reason as [OrientationHoldSource]: a hold is a
 * sustained state with no transient to catch, so ~200ms keeps a per-second label honest at a
 * fraction of the power. Batching stays off so the ring cannot jump in lumps.
 *
 * Main thread only; see [ChallengeSensorSource].
 */
class ProximityHoldSource(private val context: Context) : ChallengeSensorSource {

    private val sensorManager: SensorManager? =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    /** Registered listener while running; null when stopped. Doubles as the "is running" flag. */
    private var listener: SensorEventListener? = null

    /** Times the hold and forgets it on a break. Reused verbatim from the face-down challenge. */
    private val detector = HoldDetector()

    /**
     * Latest reading from each sensor, or null until the first event from it arrives.
     *
     * Both must be present before the condition can be judged: starting from "not covered, not
     * upright" would be a guess, and starting from "covered and upright" would credit time the user
     * had not yet earned. Null therefore means "unknown", and unknown is not a hold.
     */
    private var near: Boolean? = null
    private var upright: Boolean? = null

    /** Needs BOTH sensors. Proximity is near-universal but genuinely absent on some devices. */
    override fun hasSensor(): Boolean = proximity() != null && accelerometer() != null

    override fun start(progress: ChallengeProgress, onChange: () -> Unit): Boolean {
        stop()
        val manager = sensorManager ?: return false
        val proximitySensor = proximity() ?: return false
        val accelerometerSensor = accelerometer() ?: return false

        // Cached because getMaximumRange is a lookup and this is read on every proximity event.
        val maxRange = proximitySensor.maximumRange

        val callback = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                val sensor = event?.sensor ?: return
                val values = event.values ?: return

                when (sensor.type) {
                    Sensor.TYPE_PROXIMITY -> {
                        if (values.isEmpty()) return
                        // See the class doc: compare against the sensor's own maximum, not a
                        // hard-coded centimetre figure, because many devices only report 0 or max.
                        near = values[0] < maxRange
                    }
                    Sensor.TYPE_ACCELEROMETER -> {
                        if (values.size < 3) return
                        // Upright = on edge, not flat. Small |z| means gravity is acting across the
                        // screen plane rather than through it, whichever way the phone is turned.
                        upright = abs(values[2]) < UPRIGHT_MAX_Z
                    }
                    else -> return
                }

                // Unknown is not a hold — both readings must have arrived at least once.
                val held = (near == true) && (upright == true)
                val heldSeconds = detector.onSample(held, SystemClock.elapsedRealtime())
                progress.onHoldElapsed(heldSeconds)
                onChange()
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        val registered = try {
            // Both to the SAME listener, so one unregister releases both. If the second fails we
            // must not leave the first registered — stop() below handles that.
            manager.registerListener(callback, proximitySensor, SensorManager.SENSOR_DELAY_NORMAL, 0) &&
                manager.registerListener(
                    callback,
                    accelerometerSensor,
                    SensorManager.SENSOR_DELAY_NORMAL,
                    0,
                )
        } catch (e: Exception) {
            Log.w(TAG, "proximity/accelerometer registration failed", e)
            false
        }
        if (!registered) {
            // A partial registration is the one leak this source can produce: the proximity listener
            // may be live while the accelerometer one never took. Release whatever landed.
            try {
                manager.unregisterListener(callback)
            } catch (e: Exception) {
                Log.w(TAG, "cleanup after partial registration failed", e)
            }
            return false
        }

        listener = callback
        return true
    }

    /**
     * Unregister BOTH sensors and forget any hold in flight. Idempotent.
     *
     * One `unregisterListener(listener)` call covers both, because both were registered to the same
     * listener object — do not "improve" this into two per-sensor calls, which is how one of them
     * ends up missed on a future edit.
     */
    override fun stop() {
        val current = listener ?: return
        listener = null
        detector.reset()
        near = null
        upright = null
        try {
            sensorManager?.unregisterListener(current)
        } catch (e: Exception) {
            Log.w(TAG, "proximity/accelerometer unregister failed", e)
        }
    }

    private fun proximity(): Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)

    private fun accelerometer(): Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private companion object {
        const val TAG = "ScrollKiller"

        /**
         * |z| below this counts as upright — the phone on edge rather than face up or face down.
         * At 4 m/s² that is roughly within 65° of vertical, loose enough that nobody has to hold the
         * phone at a precise angle against their head, tight enough that a phone lying on a table
         * (|z| ≈ 9.8) can never satisfy it.
         */
        const val UPRIGHT_MAX_Z = 4.0f
    }
}
