package com.scrollkiller.challenge

/**
 * Times how long a condition has been continuously true. The stateful, pure half of both hold
 * challenges — [OrientationHoldSource] and [ProximityHoldSource] decide WHAT is being held (screen
 * down; screen down and covered), and this decides how long it has been held and when that stops
 * counting.
 *
 * Pure Kotlin, no Android imports, so every edge below is unit-testable off-device. Same split as
 * [JumpDetector] beside [AccelPeakSource].
 *
 * ## Breaking RESETS, it does not pause
 * The single most important property here, and the anti-cheat the whole mechanic rests on. If a
 * break merely paused the timer, "hold your phone face down for 30 seconds" would be satisfiable as
 * six lazy five-second flips with a glance at Instagram between each — which is not a break from
 * scrolling, it is scrolling with extra steps. So a break forgets the hold-start timestamp
 * completely and the next hold starts from zero.
 *
 * This is why [onSample] returns an ABSOLUTE elapsed figure rather than an increment: absolute means
 * the reset needs no separate signal, it is just a 0, exactly the way
 * [ChallengeProgress.onCumulative] handles a step counter restarting under it.
 *
 * ## Why whole seconds
 * The ring shows seconds, so fractional precision would only produce redraws that look identical.
 * Truncating also means the label never shows a target the user has not actually earned — at 29.9s
 * held this reports 29, not 30.
 *
 * NOT thread-safe; main thread only, like everything the overlay touches.
 */
class HoldDetector {

    /** When the current hold began, or null when the condition is not currently met. */
    private var holdStartMs: Long? = null

    /** True while the condition is being held. Exposed for logs and tests, not for progress. */
    val isHolding: Boolean get() = holdStartMs != null

    /**
     * Feed one sample.
     *
     * @param conditionMet is the thing being held true RIGHT NOW (screen face down, or face down and
     *   covered). The source computes this; this class does not care what it means.
     * @param timestampMs monotonic milliseconds. Must not go backwards.
     * @return whole seconds the condition has been continuously true, or **0** the moment it is not.
     *   A caller passing this straight to [ChallengeProgress.onHoldElapsed] therefore gets the
     *   reset-on-break behaviour for free.
     */
    fun onSample(conditionMet: Boolean, timestampMs: Long): Int {
        if (!conditionMet) {
            // Forget the hold entirely rather than remembering how far they got. See the class doc:
            // a pause would make the challenge a game of flip-and-peek.
            holdStartMs = null
            return 0
        }

        val start = holdStartMs
        if (start == null) {
            // First sample of a new hold. It scores zero — the same "first observation baselines and
            // fires nothing" rule ChallengeProgress and GuiltFiring already live by — because no time
            // has yet passed with the condition true.
            holdStartMs = timestampMs
            return 0
        }

        // Guard a clock that went backwards. elapsedRealtime should not, but a negative here would
        // become a negative progress and a ring drawn backwards, and this is cheaper than trusting it.
        val elapsedMs = (timestampMs - start).coerceAtLeast(0L)
        return (elapsedMs / MS_PER_SECOND).toInt()
    }

    /**
     * Back to not holding.
     *
     * Called when an attempt ends. Identical in effect to a broken hold, but named separately because
     * the CALLER's intent differs — this is teardown, not the user flipping their phone over — and a
     * source that could only reset by faking a `conditionMet = false` sample would be lying about
     * what it observed.
     */
    fun reset() {
        holdStartMs = null
    }

    private companion object {
        const val MS_PER_SECOND = 1_000L
    }
}
