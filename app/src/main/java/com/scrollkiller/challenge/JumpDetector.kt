package com.scrollkiller.challenge

/**
 * Counts jumps from accelerometer magnitude. The stateful, pure half of the jump challenge —
 * [AccelPeakSource] turns hardware into calls on this, and this decides what a jump IS.
 *
 * Pure Kotlin, no Android imports, so every edge below is unit-testable off-device. Same shape and
 * the same reasoning as [ChallengeProgress] beside [StepSensorSource], and as
 * [com.scrollkiller.service.SwipeDetector] beside the AccessibilityService.
 *
 * ## One jump = one count, and how shaking is rejected
 * A landing spike alone is not a jump. Shaking the phone, setting it down hard, or walking with it
 * in a pocket all produce spikes above any threshold worth using, and a detector that counted them
 * would let someone clear "jump 10 times" from the sofa — which makes the whole mechanic theatre.
 *
 * What distinguishes a real jump is the SEQUENCE: while you are airborne the accelerometer reads
 * near-zero (the sensor is falling with the phone — free fall), and only then does the landing
 * spike arrive. So free fall ARMS the detector and a spike only counts while armed. A shake never
 * produces the free-fall phase, so it never arms, so it never counts.
 *
 * Three more guards, each for a specific way the naive version miscounts:
 *  - [REFRACTORY_MS] — a landing does not produce one clean spike, it produces a spike and then a
 *    decaying oscillation as the body absorbs the impact. Without a refractory window a single jump
 *    counts three or four times and "jump 10" completes in two hops.
 *  - [ARM_EXPIRY_MS] — a free fall that armed the detector a second ago is not related to a spike
 *    arriving now. Airtime for a human jump is a few hundred milliseconds; anything longer means
 *    the phone was dropped, put down, or handed over, and the arm goes stale rather than waiting
 *    around to bless an unrelated bump.
 *  - Baselining — see [onSample].
 *
 * NOT thread-safe; main thread only, like everything the overlay touches.
 */
class JumpDetector {

    /** Running sum/count while baselining; see [onSample]. */
    private var baselineSum = 0.0
    private var baselineCount = 0

    /** Resting magnitude for THIS device, resolved once baselining completes. 0 until then. */
    private var baseline = 0.0

    /** Timestamp of the free fall that armed us, or null when not armed. */
    private var armedAtMs: Long? = null

    /** When the last jump was counted, so [REFRACTORY_MS] can suppress the landing ringing. */
    private var lastCountedMs: Long? = null

    /** True once [BASELINE_SAMPLES] readings have been seen and [baseline] is usable. */
    val isBaselined: Boolean get() = baselineCount >= BASELINE_SAMPLES

    /** Resting magnitude in use, for logs and tests. 0 until [isBaselined]. */
    val restingMagnitude: Double get() = baseline

    /**
     * Feed one accelerometer sample.
     *
     * @param magnitude sqrt(x² + y² + z²) in m/s². At rest this is gravity, whatever this
     *   particular sensor thinks gravity is.
     * @param timestampMs monotonic milliseconds. Must not go backwards.
     * @return true if a jump completed on THIS sample — at most one per call, so the caller can
     *   increment by one and never has to reason about bursts.
     *
     * ## Why gravity is measured rather than assumed
     * The first [BASELINE_SAMPLES] readings are averaged into [baseline] and score nothing. This is
     * the same "first observation baselines and fires nothing" rule [ChallengeProgress.onCumulative]
     * and [com.scrollkiller.guilt.GuiltFiring] already live by, and it is here because consumer
     * accelerometers are not calibrated to 9.81 — a device reading 9.4 or 10.2 at rest is
     * completely normal, and thresholds written as absolute m/s² would be systematically wrong on
     * it. Every threshold below is a FRACTION of this device's own resting magnitude instead.
     *
     * The average is then clamped to [PLAUSIBLE_GRAVITY_RANGE], which handles the one case the
     * averaging cannot: a user who starts jumping the instant the challenge appears baselines
     * against their own motion. An implausible baseline is discarded for [STANDARD_GRAVITY], which
     * on a moving device is the more accurate figure anyway.
     */
    fun onSample(magnitude: Double, timestampMs: Long): Boolean {
        if (!isBaselined) {
            baselineSum += magnitude
            baselineCount++
            if (isBaselined) {
                val measured = baselineSum / baselineCount
                baseline = if (measured in PLAUSIBLE_GRAVITY_RANGE) measured else STANDARD_GRAVITY
            }
            return false
        }

        // Let a stale arm expire before anything else, so an old free fall cannot bless a spike
        // that has nothing to do with it.
        armedAtMs?.let { armedAt ->
            if (timestampMs - armedAt > ARM_EXPIRY_MS) armedAtMs = null
        }

        if (magnitude < baseline * FREE_FALL_FRACTION) {
            // Airborne. Re-arming on each free-fall sample is intentional: the arm timestamp should
            // track the END of the fall (the moment before landing), not its start, or a long fall
            // would expire its own arm just as the landing arrives.
            armedAtMs = timestampMs
            return false
        }

        if (magnitude <= baseline * LANDING_FRACTION) return false   // ordinary handling, not a landing

        // A landing spike. It only counts if we were airborne first — this is the sequence check,
        // and it is the whole reason shaking the phone does not clear the challenge.
        if (armedAtMs == null) return false

        val last = lastCountedMs
        if (last != null && timestampMs - last < REFRACTORY_MS) {
            // The ringing after an impact we already counted. Disarm so the tail of this landing
            // cannot count once the refractory window lapses.
            armedAtMs = null
            return false
        }

        armedAtMs = null
        lastCountedMs = timestampMs
        return true
    }

    /**
     * Back to un-baselined, unarmed, nothing counted.
     *
     * Called when an attempt ends. The baseline is deliberately dropped with everything else: the
     * next attempt may be on a phone held differently, or after the user has walked to another
     * room, and re-measuring costs [BASELINE_SAMPLES] samples — about a third of a second.
     */
    fun reset() {
        baselineSum = 0.0
        baselineCount = 0
        baseline = 0.0
        armedAtMs = null
        lastCountedMs = null
    }

    companion object {
        /**
         * Readings averaged into the resting baseline before any jump can be counted. Ten samples
         * at `SENSOR_DELAY_UI` is roughly a third of a second — long enough to average out sensor
         * noise, short enough that the ring is live before the user has finished reading the prompt.
         */
        const val BASELINE_SAMPLES = 10

        /** Fallback resting magnitude when the measured one is implausible. Textbook gravity. */
        const val STANDARD_GRAVITY = 9.81

        /**
         * Resting magnitudes accepted as real. A calibrated-badly sensor sits inside this; a device
         * being jumped with during baselining does not.
         */
        val PLAUSIBLE_GRAVITY_RANGE = 7.0..13.0

        /**
         * Below this fraction of resting magnitude the phone is airborne. True free fall reads ~0,
         * but a real jump is never pure — arms swing, the phone is gripped — so this is generous.
         * Too low and short hops never arm; too high and ordinary walking arms constantly and the
         * sequence check stops meaning anything.
         */
        const val FREE_FALL_FRACTION = 0.45

        /**
         * Above this fraction of resting magnitude counts as a landing. A two-footed landing peaks
         * at several g; ordinary handling and walking stay well under 1.5. The gap between this and
         * [FREE_FALL_FRACTION] is deliberately wide — the band between them is "nothing is
         * happening", and a narrow band is how a detector starts counting fidgeting.
         */
        const val LANDING_FRACTION = 1.55

        /**
         * Minimum gap between counted jumps. Covers the decaying oscillation after an impact, which
         * is what makes one jump read as three. 400ms also caps the rate at 2.5 jumps/second, which
         * is faster than anyone actually jumps, so it costs a real jumper nothing.
         */
        const val REFRACTORY_MS = 400L

        /**
         * How long a free fall stays valid as an arm. Human airtime is ~200–500ms; past a second the
         * "fall" was the phone being lowered or dropped, and it should not bless the next bump.
         */
        const val ARM_EXPIRY_MS = 1_000L
    }
}
