package com.scrollkiller.challenge

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Decides whether the phone is being BALANCED right now: held flat and level, in a hand rather than
 * resting on something. The pure half of the balance challenge — [TiltBalanceSource] turns hardware
 * into calls on this, and [HoldDetector] times the boolean it returns.
 *
 * Pure Kotlin, no Android imports, so every threshold below is unit-testable off-device.
 *
 * ## Three classes, one challenge, and why that is the right split
 * This returns a BOOLEAN, exactly like the `faceDown` expression inside [OrientationHoldSource] —
 * it is more complicated than that expression, which is precisely why it is a class instead of a
 * line. Timing it, and resetting on a break, stays [HoldDetector]'s job, unchanged and reused whole.
 * The balance challenge therefore inherits reset-on-break, the seconds ring, the haptics and
 * `KEEP_SCREEN_ON` for free, which is the payoff D55 already collected once for the forehead hold.
 *
 * ## LEVEL is measured laterally, not from Z, and that is not a style choice
 * The obvious test is `z > 9.0`. It is wrong across devices: consumer accelerometers are not
 * calibrated to 9.81 and a resting magnitude of 9.4 or 10.2 is ordinary (see
 * [JumpDetector.PLAUSIBLE_GRAVITY_RANGE]), so a fixed Z floor silently means 17° of tolerance on one
 * phone and 28° on another. The LATERAL component `sqrt(x² + y²)` is `g·sin(tilt)`, so the same
 * number means very nearly the same ANGLE on every device — about 15° at [MAX_LATERAL]. Z is still
 * read, but only for its SIGN, to require screen-up rather than a phone level and upside down.
 *
 * ## The anti-cheat is a tremor floor, and it is the SOFTEST in the suite — say so
 * Level alone is trivially defeated: a table is more level than any hand. So the phone must also be
 * ALIVE — a held phone always carries a small tremor, and a resting one carries only sensor noise.
 * [MIN_TREMOR] sits between them.
 *
 * **This is deliberately the weakest guard of the four sensor challenges and is documented as such.**
 * Jump needs a free-fall phase that shaking cannot fake (D53); forehead needs covered AND upright, so
 * a thumb on a table fails (D55); a flip needs the phone physically turned over. A tremor floor is a
 * threshold between two continuous quantities, and on a very quiet sensor a table could clear it,
 * while a very steady hand resting on a knee could fail it. It is set LOW, favouring the false pass
 * over the false fail, because a user doing exactly what was asked and watching the ring refuse to
 * move concludes the app is broken (D50's standing rule) — whereas the false pass costs a reprieve
 * to somebody who at least had to put their phone down flat and stop scrolling to get it.
 *
 * If a device run shows a resting phone completing this, the fix is [MIN_TREMOR] — one constant.
 *
 * NOT thread-safe; main thread only, like everything the overlay touches.
 */
class BalanceDetector {

    /** Smoothed magnitude, so the tremor estimate measures deviation from a level rather than noise. */
    private var smoothedMagnitude = 0.0

    /** Smoothed absolute deviation from [smoothedMagnitude] — the "is it alive" signal. */
    private var tremor = 0.0

    /** Samples seen. The first primes the filters; [WARMUP_SAMPLES] more let them settle. */
    private var samples = 0

    /** True once the filters are usable and balance can be reported. */
    val isWarmedUp: Boolean get() = samples > WARMUP_SAMPLES

    /** Current tremor estimate, for logs and tests. */
    val currentTremor: Double get() = tremor

    /**
     * Feed one accelerometer sample, in m/s², including gravity.
     *
     * @return true when the phone is level, screen-up, and being held — the condition
     *   [HoldDetector] should be timing. False while warming up, so an attempt can never bank
     *   seconds it has not measured.
     */
    fun onSample(x: Double, y: Double, z: Double): Boolean {
        val magnitude = sqrt(x * x + y * y + z * z)

        if (samples == 0) {
            // Prime from the first reading rather than from zero, or the first second of every
            // attempt reads as one enormous deviation and the phone looks alive on a table.
            smoothedMagnitude = magnitude
            samples = 1
            return false
        }

        val deviation = abs(magnitude - smoothedMagnitude)
        smoothedMagnitude = MAGNITUDE_ALPHA * smoothedMagnitude + (1 - MAGNITUDE_ALPHA) * magnitude
        tremor = TREMOR_ALPHA * tremor + (1 - TREMOR_ALPHA) * deviation
        samples++
        if (!isWarmedUp) return false

        // Screen-up by SIGN only; how level it is comes from the lateral component. See class doc.
        val screenUp = z > 0
        val lateral = sqrt(x * x + y * y)
        return screenUp && lateral < MAX_LATERAL && tremor >= MIN_TREMOR
    }

    /** Back to un-warmed with no tremor history. Called when an attempt ends. */
    fun reset() {
        smoothedMagnitude = 0.0
        tremor = 0.0
        samples = 0
    }

    companion object {

        /**
         * Lateral acceleration, in m/s², still counted as level. `g·sin(15°)` ≈ 2.5, so this is
         * about fifteen degrees of tilt on any device — which is the point of measuring laterally
         * rather than from Z. Tight enough that the phone must be deliberately flat; loose enough
         * that a steady palm is not fighting the threshold.
         */
        const val MAX_LATERAL = 2.5

        /**
         * Smoothed deviation, in m/s², below which the phone is judged to be resting on something
         * rather than held. Sensor noise on a still surface sits well under this; an ordinary hand
         * tremor sits comfortably above it.
         *
         * **The softest threshold in the challenge suite** — see the class doc, which says why it is
         * set low on purpose and what to change if a device run proves a table can clear it.
         */
        const val MIN_TREMOR = 0.06

        /** Low-pass weight for the magnitude the tremor is measured against. */
        const val MAGNITUDE_ALPHA = 0.90

        /** Low-pass weight for the tremor estimate itself. Smooths over roughly ten samples. */
        const val TREMOR_ALPHA = 0.90

        /**
         * Samples discarded after the first while both filters settle. Twenty at `SENSOR_DELAY_UI`
         * (~60ms) is a little over a second — long enough for the tremor estimate to be meaningful,
         * and it costs the user nothing because [HoldDetector] has not started timing yet either.
         */
        const val WARMUP_SAMPLES = 20
    }
}
