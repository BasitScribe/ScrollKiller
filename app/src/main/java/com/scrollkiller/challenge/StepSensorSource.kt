package com.scrollkiller.challenge

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log

/**
 * Turns the device's step hardware into calls on a [ChallengeProgress]. The thin, untestable half
 * of the challenge engine — every decision it could make lives in that class instead.
 *
 * ## Detector first, counter as the fallback
 * Two sensors can count steps and they behave differently enough to matter:
 *  - `TYPE_STEP_DETECTOR` fires ONE event per step, with no state of its own. It is inherently
 *    incremental and low-latency, which is precisely what a live progress ring wants.
 *  - `TYPE_STEP_COUNTER` reports steps since boot. It needs baselining, it is shared with every
 *    other app, and it resets on reboot — all handled in [ChallengeProgress.onCumulative], but all
 *    of it avoidable when a detector exists.
 *
 * So the detector is preferred and the counter is the fallback, and the [SensorStrategy] a spec
 * declares is a PREFERENCE rather than a promise: the spec cannot know what hardware it will run
 * on, so the real strategy is resolved here, per device, at start.
 *
 * ## Why batching is switched off
 * [SensorManager.registerListener] is called with `maxReportLatencyUs = 0`. This is not a
 * micro-optimisation. Step sensors are exactly the kind the platform likes to BATCH — buffering
 * events in hardware and flushing them in a lump to save power — and a batched flush makes the ring
 * sit at 0/20 while the user walks and then jump to 12/20 all at once. The user reads that as
 * broken, gives up, and taps "5 more minutes" instead. Zero latency costs a little power for the
 * seconds a challenge is on screen, and buys the only feedback the mechanic has.
 *
 * ## Registration is scoped to the challenge being on screen
 * [start] and [stop] are symmetric and [stop] is idempotent. A step sensor left registered by a
 * background AccessibilityService is a battery complaint that never gets diagnosed, so every path
 * that takes the challenge down calls [stop] — completion, cancel, block dismissal, service
 * teardown.
 *
 * Main thread only. Sensor callbacks arrive on the thread of the handler the manager was given;
 * we pass none, so they land on the main looper, which is where the views are.
 */
class StepSensorSource(private val context: Context) : ChallengeSensorSource {

    private val sensorManager: SensorManager? =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    /** Registered listener while running; null when stopped. Doubles as the "is running" flag. */
    private var listener: SensorEventListener? = null

    /** The strategy actually in use this run, resolved from the hardware. Null when stopped. */
    var activeStrategy: SensorStrategy? = null
        private set

    /** Does this device have any step sensor at all? The hardware half of availability. */
    override fun hasSensor(): Boolean = resolveSensor() != null

    /**
     * Begin feeding [progress], calling [onChange] after every update.
     *
     * @return true if a sensor was found and registration succeeded. FALSE means the challenge
     *   cannot run and the caller must not show a ring that will never move — the block screen
     *   checks availability before offering the challenge at all, so this is the second line of
     *   defence rather than the first.
     */
    override fun start(progress: ChallengeProgress, onChange: () -> Unit): Boolean {
        stop()
        val manager = sensorManager ?: return false
        val sensor = resolveSensor() ?: return false

        val strategy = strategyFor(sensor) ?: return false
        val callback = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                val value = event?.values?.firstOrNull() ?: return
                when (strategy) {
                    // The detector reports 1.0 per event. Trust the EVENT, not the value: a
                    // device reporting something else here still means "one step happened".
                    SensorStrategy.STEP_EVENTS -> progress.onIncrement()
                    SensorStrategy.STEP_CUMULATIVE -> progress.onCumulative(value.toLong())
                    else -> return
                }
                onChange()
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        val registered = try {
            // maxReportLatencyUs = 0 disables batching — see the class doc; this is the argument
            // that makes the ring move step by step instead of in lumps.
            manager.registerListener(callback, sensor, SensorManager.SENSOR_DELAY_UI, 0)
        } catch (e: Exception) {
            Log.w(TAG, "step sensor registration failed", e)
            false
        }
        if (!registered) return false

        listener = callback
        activeStrategy = strategy
        return true
    }

    /** Unregister. Idempotent — safe to call from every dismissal path, which is the point. */
    override fun stop() {
        val current = listener ?: return
        listener = null
        activeStrategy = null
        try {
            sensorManager?.unregisterListener(current)
        } catch (e: Exception) {
            Log.w(TAG, "step sensor unregister failed", e)
        }
    }

    /** The best available step sensor: detector if present, else counter, else null. */
    private fun resolveSensor(): Sensor? {
        val manager = sensorManager ?: return null
        return manager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
            ?: manager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    }

    private fun strategyFor(sensor: Sensor): SensorStrategy? = when (sensor.type) {
        Sensor.TYPE_STEP_DETECTOR -> SensorStrategy.STEP_EVENTS
        Sensor.TYPE_STEP_COUNTER -> SensorStrategy.STEP_CUMULATIVE
        else -> null
    }

    private companion object {
        const val TAG = "ScrollKiller"
    }
}
