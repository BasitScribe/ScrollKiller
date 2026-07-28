package com.scrollkiller.challenge

/**
 * How far through a challenge the user is. The stateful, pure half of the challenge engine —
 * [StepSensorSource] turns hardware into calls on this, and this decides what the ring shows and
 * when the block comes down.
 *
 * Pure Kotlin, no Android imports, so every edge below is unit-testable off-device. Same shape and
 * the same reasoning as [com.scrollkiller.service.SwipeDetector] and
 * [com.scrollkiller.service.IdentityAdvanceDetector]: the sensor plumbing is thin and untestable,
 * the DECISION is neither, so they live apart.
 *
 * NOT thread-safe; main thread only, like everything the overlay touches.
 *
 * @param target the spec's [ChallengeSpec.target]. Progress clamps here and never exceeds it.
 */
class ChallengeProgress(private val target: Int) {

    /** Steps counted so far, clamped to [target]. Never negative. */
    var progress: Int = 0
        private set

    /**
     * Cumulative reading this run started from, for [SensorStrategy.STEP_CUMULATIVE]. Null until
     * the first reading arrives — which is exactly what makes that first reading a BASELINE rather
     * than a score of several thousand.
     */
    private var baseline: Long? = null

    /**
     * Done.
     *
     * ## Not sticky for holds, and that is deliberate
     * Under the counting inputs ([onIncrement], [onCumulative]) progress only ever rises, so once
     * this is true it stays true and a late event cannot un-complete it. [onHoldElapsed] BREAKS that
     * monotonicity on purpose — a broken hold sends progress back to zero — so this can read true and
     * then false again.
     *
     * Nothing downstream depends on the stickiness: [ChallengeController] latches completion in its
     * own flag, so `onComplete` fires exactly once even if this flips back afterwards, and by then
     * the block has already come down. Do NOT "fix" the non-monotonicity by clamping this — the reset
     * IS the anti-cheat (see [onHoldElapsed]).
     */
    val isComplete: Boolean get() = progress >= target

    /** How far along, 0f..1f. What the ring draws. */
    val fraction: Float get() = if (target <= 0) 1f else progress.toFloat() / target

    /**
     * One step happened ([SensorStrategy.STEP_EVENTS]).
     *
     * Clamped at [target] rather than allowed to run over. Step events CAN arrive in a burst — a
     * batched sensor flushes several at once — and a ring drawing 140% of a circle is a bug the
     * user sees. Completion is decided here, not by the arithmetic at the call site.
     */
    fun onIncrement() {
        if (progress < target) progress++
    }

    /**
     * A cumulative step reading ([SensorStrategy.STEP_CUMULATIVE]).
     *
     * ## The two edges that make this worth its own class
     * `TYPE_STEP_COUNTER` reports steps since BOOT, and it is shared with every other app on the
     * device — so the first reading of a challenge is a number in the thousands that has nothing
     * to do with this challenge. It is recorded as a baseline and scores ZERO, which is the same
     * "first observation baselines and fires nothing" rule [com.scrollkiller.guilt.GuiltFiring]
     * already lives by.
     *
     * And that counter RESETS on reboot. A device restarted mid-challenge sends a [total] smaller
     * than the baseline, and the naive subtraction yields negative progress — a ring drawn
     * backwards. Going backwards means the counter restarted, not that the user un-walked, so it
     * re-baselines from the new value; again the same handling GuiltFiring applies to a count that
     * drops within a day.
     */
    fun onCumulative(total: Long) {
        val base = baseline
        if (base == null || total < base) {
            // First reading of this run, or the counter restarted underneath us. Either way this
            // value is the new zero and scores nothing.
            baseline = total
            return
        }
        progress = (total - base).coerceAtMost(target.toLong()).toInt()
    }

    /**
     * Seconds a hold has been sustained ([SensorStrategy.ORIENTATION_HOLD],
     * [SensorStrategy.PROXIMITY_HOLD]).
     *
     * ABSOLUTE and clamped, like [onCumulative] and unlike [onIncrement] — [HoldDetector] reports how
     * long the condition has been continuously true, not a delta. That choice is what makes the reset
     * free: a broken hold reports **0**, progress becomes 0, and no separate "the hold broke" signal
     * is needed.
     *
     * ## Why going backwards is correct here
     * This is the ONE input that can lower progress, and it is the anti-cheat. If a break paused the
     * timer instead of resetting it, a 30-second face-down hold would be satisfiable as six
     * five-second flips with a peek at Instagram between each. The user is told this in the prompt and
     * feels it as a double buzz, so it is a rule rather than a surprise.
     *
     * Negative input is coerced to zero rather than trusted: it can only come from a clock that went
     * backwards, and a negative progress would draw the ring in reverse over someone else's app.
     */
    fun onHoldElapsed(seconds: Int) {
        progress = seconds.coerceIn(0, target)
    }

    /**
     * Back to zero, baseline forgotten.
     *
     * Called when the user cancels. Progress is deliberately NOT carried between attempts: a
     * challenge you can chip away at across five separate blocks — two steps here, three there —
     * is not a challenge, it is a slow tap.
     */
    fun reset() {
        progress = 0
        baseline = null
    }
}
