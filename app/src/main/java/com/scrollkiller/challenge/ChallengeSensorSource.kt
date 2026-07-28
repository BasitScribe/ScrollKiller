package com.scrollkiller.challenge

import android.content.Context

/**
 * The thin, untestable half of one challenge: turns some piece of sensor hardware into calls on a
 * [ChallengeProgress]. Every DECISION a source could make lives in a pure detector beside it
 * instead ([JumpDetector] and friends), which is the same split
 * [com.scrollkiller.service.SwipeDetector] and [com.scrollkiller.service.IdentityAdvanceDetector]
 * already use.
 *
 * ## The contract, and why each half is load-bearing
 *  - [start] and [stop] are SYMMETRIC and [stop] is IDEMPOTENT. A sensor left registered by a
 *    background AccessibilityService is a battery complaint nobody ever traces back to us, so
 *    every path that takes a challenge off screen calls [stop] — completion, cancel, block
 *    dismissal, service teardown — and calling it twice must be free.
 *  - [start] returns FALSE rather than throwing when the hardware is not there. A ring that can
 *    never move is worse than no challenge at all: the user stands in their kitchen jumping at a
 *    frozen 0/10 and concludes the app is broken, and they are not wrong.
 *  - [hasSensor] is the hardware half of availability, asked BEFORE a challenge is offered. See
 *    [ChallengeAvailability], which pairs it with whatever permission the strategy needs.
 *
 * Implementations are main-thread only. Sensor callbacks arrive on the thread of the handler the
 * manager was given; no source passes one, so they land on the main looper, which is where the
 * views are.
 */
interface ChallengeSensorSource {

    /** Does this device have the hardware this source needs? The hardware half of availability. */
    fun hasSensor(): Boolean

    /**
     * Begin feeding [progress], calling [onChange] after every update.
     *
     * @return false if the hardware is missing or registration was refused, in which case NOTHING
     *   should be shown — see the interface doc.
     */
    fun start(progress: ChallengeProgress, onChange: () -> Unit): Boolean

    /** Unregister. Idempotent — safe from every dismissal path, which is the entire point. */
    fun stop()
}

/**
 * Resolves a [SensorStrategy] to the source that implements it.
 *
 * The return type stays NULLABLE even though every strategy is currently built. That mirrors
 * [ChallengeRegistry.IMPLEMENTED] deliberately: the registry states the gap in data and a test
 * enforces it, and this states the same gap in code rather than pretending with a stub source that
 * would register nothing and report success. The next declared-before-built strategy needs no
 * signature change to be honest — which is the point of leaving it this way while the map is full.
 */
object ChallengeSensors {

    fun sourceFor(context: Context, strategy: SensorStrategy): ChallengeSensorSource? =
        when (strategy) {
            // Both step strategies are served by one source: which of them is actually in use is a
            // per-DEVICE question (detector or counter), so the source resolves it at start rather
            // than the factory guessing here. See StepSensorSource.
            SensorStrategy.STEP_EVENTS,
            SensorStrategy.STEP_CUMULATIVE,
            -> StepSensorSource(context)

            SensorStrategy.ACCEL_PEAKS -> AccelPeakSource(context)

            SensorStrategy.ORIENTATION_HOLD -> OrientationHoldSource(context)

            SensorStrategy.PROXIMITY_HOLD -> ProximityHoldSource(context)
        }
}
