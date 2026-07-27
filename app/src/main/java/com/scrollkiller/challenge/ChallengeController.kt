package com.scrollkiller.challenge

import android.content.Context

/**
 * Runs one challenge attempt: holds the [ChallengeProgress], drives the [StepSensorSource], and
 * calls back on every step and once on completion.
 *
 * A sibling of [com.scrollkiller.service.BlockScreenController] rather than more code inside
 * [com.scrollkiller.service.OverlayController], per CLAUDE.md's no-God-classes rule. The overlay
 * decides WHETHER to offer a challenge and what completing one is worth; this decides nothing —
 * it wires a sensor to a counter and reports.
 *
 * ## Lifecycle is the whole job
 * [stop] unregisters the sensor and is idempotent, and every path that takes the challenge off
 * screen calls it — completion, cancel, the block being dismissed, the service being destroyed.
 * A step sensor left registered by a background AccessibilityService is a battery complaint nobody
 * ever traces back to us, so this class has exactly one way in and one way out.
 *
 * Main thread only, like the rest of the overlay.
 */
class ChallengeController(context: Context) {

    private val sensor = StepSensorSource(context)

    /** Progress for the attempt in flight; null when nothing is running. */
    private var progress: ChallengeProgress? = null

    /** The spec being attempted, so the view can be re-rendered without the caller holding it. */
    var active: ChallengeSpec? = null
        private set

    /** Is an attempt running right now? */
    val isRunning: Boolean get() = active != null

    /** Steps so far in the running attempt, or 0. */
    val currentProgress: Int get() = progress?.progress ?: 0

    /** Does this device have step hardware at all? See [com.scrollkiller.ui.onboarding.MotionStatus]. */
    fun hasStepSensor(): Boolean = sensor.hasStepSensor()

    /**
     * Begin [spec]. Returns false if the sensor could not be started, in which case NOTHING is
     * shown — a ring that can never move is worse than no challenge, because the user stands there
     * walking and concludes the app is broken.
     *
     * @param onProgress fired after every counted step, for the ring.
     * @param onComplete fired ONCE, when the target is reached. The caller grants the reprieve;
     *   this does not know what a challenge is worth.
     */
    fun start(
        spec: ChallengeSpec,
        onProgress: () -> Unit,
        onComplete: () -> Unit,
    ): Boolean {
        stop()
        val tracker = ChallengeProgress(spec.target)
        // Completion is latched here rather than read from isComplete on every event: the sensor
        // can deliver a burst, and firing onComplete twice would grant two reprieves and try to
        // dismiss an already-dismissed block.
        var completed = false
        val started = sensor.start(tracker) {
            onProgress()
            if (!completed && tracker.isComplete) {
                completed = true
                onComplete()
            }
        }
        if (!started) return false
        progress = tracker
        active = spec
        return true
    }

    /**
     * End the attempt and release the sensor. Idempotent.
     *
     * Progress is dropped, not banked. A challenge you can chip away at across five separate
     * blocks — two steps here, three there — is a slow tap, not a challenge, and the honest place
     * to enforce that is here, where the attempt ends.
     */
    fun stop() {
        sensor.stop()
        progress?.reset()
        progress = null
        active = null
    }
}
