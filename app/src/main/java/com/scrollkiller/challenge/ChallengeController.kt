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
class ChallengeController(private val context: Context) {

    /**
     * The source for the attempt in flight; null when nothing is running.
     *
     * Resolved PER ATTEMPT from the spec's strategy ([ChallengeSensors.sourceFor]) rather than being
     * one fixed field, because the strategy is now a property of the challenge the user chose. It
     * used to be a `StepSensorSource` held for the controller's lifetime, which was the shape of
     * "there is exactly one challenge and it counts steps".
     */
    private var sensor: ChallengeSensorSource? = null

    /** Progress for the attempt in flight; null when nothing is running. */
    private var progress: ChallengeProgress? = null

    /** The spec being attempted, so the view can be re-rendered without the caller holding it. */
    var active: ChallengeSpec? = null
        private set

    /** Is an attempt running right now? */
    val isRunning: Boolean get() = active != null

    /** Steps so far in the running attempt, or 0. */
    val currentProgress: Int get() = progress?.progress ?: 0

    /**
     * Begin [spec]. Returns false if the sensor could not be started, in which case NOTHING is
     * shown — a ring that can never move is worse than no challenge, because the user stands there
     * walking and concludes the app is broken.
     *
     * @param onProgress fired after every counted step/jump, and after every hold sample, for the ring.
     * @param onComplete fired ONCE, when the target is reached. The caller grants the reprieve;
     *   this does not know what a challenge is worth.
     * @param onBroken fired when progress DROPS — which only a hold can do, and means the user let go
     *   (see [ChallengeProgress.onHoldElapsed]). Detected here rather than in the source because this
     *   is already the class watching progress transitions for [onComplete], and a source would have
     *   to grow a second observer to notice the same thing. What to DO about it (a buzz) is the
     *   caller's call — this class still decides nothing.
     */
    fun start(
        spec: ChallengeSpec,
        onProgress: () -> Unit,
        onComplete: () -> Unit,
        onBroken: () -> Unit = {},
    ): Boolean {
        stop()
        // Null means the strategy is declared but unbuilt. ChallengeRegistry.IMPLEMENTED should have
        // kept such a spec out of `enabled` already; failing soft here rather than throwing keeps a
        // registry mistake off a screen covering someone else's app.
        val source = ChallengeSensors.sourceFor(context, spec.sensorStrategy) ?: return false
        val tracker = ChallengeProgress(spec.target)
        // Completion is latched here rather than read from isComplete on every event: the sensor
        // can deliver a burst, and firing onComplete twice would grant two reprieves and try to
        // dismiss an already-dismissed block.
        var completed = false
        // Last progress we saw, so a DROP can be told from a rise. Only holds ever drop.
        var seen = 0
        val started = source.start(tracker) {
            onProgress()
            val now = tracker.progress
            // Order matters: report the break BEFORE updating `seen`, and only when something was
            // actually lost — a hold sitting at 0 samples repeatedly and must not buzz every 200ms.
            if (now < seen) onBroken()
            seen = now
            if (!completed && tracker.isComplete) {
                completed = true
                onComplete()
            }
        }
        if (!started) return false
        sensor = source
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
        sensor?.stop()
        sensor = null
        progress?.reset()
        progress = null
        active = null
    }
}
