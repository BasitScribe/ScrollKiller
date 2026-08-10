package com.scrollkiller.challenge

import kotlin.math.sqrt

/**
 * Counts shakes from raw accelerometer axes. The stateful, pure half of the shake challenge —
 * [AccelShakeSource] turns hardware into calls on this, and this decides what a shake IS.
 *
 * Pure Kotlin, no Android imports, so every edge below is unit-testable off-device. Same split as
 * [JumpDetector] beside [AccelPeakSource].
 *
 * ## Why this cannot reuse [JumpDetector]'s magnitude, and the trap that looks like it can
 * The obvious implementation counts oscillations of `sqrt(x² + y² + z²)`. It does not work, and it
 * fails in a way that looks like the detector is merely badly tuned. Shake a phone back and forth
 * along X and the reading is `sqrt(a² + g²)` — which is **always at or above g and never below it**,
 * because magnitude has no sign. There is nothing to cross, so a zero-crossing counter reads zero
 * shakes no matter how hard the phone is shaken.
 *
 * So gravity is ESTIMATED and SUBTRACTED instead. A slow low-pass over each axis converges on the
 * gravity vector, and what is left (`linear`) is the motion the user is actually adding. At rest
 * that is ~0; during a shake it swings hard. [GRAVITY_ALPHA] is deliberately slow — a filter fast
 * enough to track a 2–5 Hz shake would subtract the shake along with gravity and report nothing,
 * which is the same bug in a second costume.
 *
 * `TYPE_LINEAR_ACCELERATION` would do this in hardware and is deliberately NOT used, for D53's
 * reason: it is absent on some devices and synthesised on others with a filter whose lag smears
 * exactly the transitions being counted.
 *
 * ## Thresholds are ABSOLUTE here, where [JumpDetector]'s are fractions of gravity
 * Not an inconsistency. JumpDetector reads a signal that still CONTAINS gravity, so a device whose
 * resting magnitude is 9.4 or 10.2 shifts every one of its thresholds and they have to be relative
 * to what that device calls gravity. Gravity is already removed here, so what is measured is the
 * user's own motion in m/s² and an absolute number is the honest way to say "this hard".
 *
 * ## What it rejects
 * [SHAKE_ON] at 6 m/s² is about 0.6 g of added motion. Carrying the phone, walking with it in hand
 * and setting it down all stay well below that; a real shake clears it several times over. The gap
 * down to [SHAKE_OFF] is hysteresis — a single threshold chatters at its own boundary and turns one
 * shake into five — and [REFRACTORY_MS] catches what hysteresis does not, the sensor ringing after a
 * hard direction change.
 *
 * A jump WILL register shakes, and that asymmetry is intended: jumping is strictly harder than
 * shaking, so there is no exploit in it. The reverse — shaking clearing the jump challenge — is what
 * D53's free-fall arming prevents, and still does.
 *
 * NOT thread-safe; main thread only, like everything the overlay touches.
 */
class ShakeDetector {

    /** Running gravity estimate per axis. Primed from the first sample, then low-passed. */
    private var gravityX = 0.0
    private var gravityY = 0.0
    private var gravityZ = 0.0

    /** Samples seen. The first primes the filter; [WARMUP_SAMPLES] more let it settle. */
    private var samples = 0

    /** True while linear magnitude is above [SHAKE_ON] — the hysteresis latch. */
    private var above = false

    /** When the last shake was counted, so [REFRACTORY_MS] can suppress the ringing. */
    private var lastCountedMs: Long? = null

    /** True once the gravity estimate is usable and shakes can count. */
    val isWarmedUp: Boolean get() = samples > WARMUP_SAMPLES

    /**
     * Feed one accelerometer sample, in m/s², including gravity.
     *
     * @param timestampMs monotonic milliseconds. Must not go backwards.
     * @return true if a shake completed on THIS sample — at most one per call, so the caller can
     *   increment by one and never has to reason about bursts.
     *
     * The first sample PRIMES the gravity estimate rather than being averaged into a zero, which
     * matters more than it looks: starting the filter at (0,0,0) would make the first second of
     * every attempt read as one enormous linear acceleration and score several free shakes. Same
     * "first observation baselines and fires nothing" rule [ChallengeProgress.onCumulative],
     * [JumpDetector] and [com.scrollkiller.guilt.GuiltFiring] all live by.
     */
    fun onSample(x: Double, y: Double, z: Double, timestampMs: Long): Boolean {
        if (samples == 0) {
            gravityX = x
            gravityY = y
            gravityZ = z
            samples = 1
            return false
        }

        gravityX = GRAVITY_ALPHA * gravityX + (1 - GRAVITY_ALPHA) * x
        gravityY = GRAVITY_ALPHA * gravityY + (1 - GRAVITY_ALPHA) * y
        gravityZ = GRAVITY_ALPHA * gravityZ + (1 - GRAVITY_ALPHA) * z
        samples++
        if (!isWarmedUp) return false

        val lx = x - gravityX
        val ly = y - gravityY
        val lz = z - gravityZ
        val linear = sqrt(lx * lx + ly * ly + lz * lz)

        if (above) {
            // Wait for the motion to fall well back before another shake can be counted. This is
            // the hysteresis: releasing at the same level it triggered would count the noise
            // straddling that one threshold as a burst of shakes.
            if (linear <= SHAKE_OFF) above = false
            return false
        }

        if (linear < SHAKE_ON) return false
        above = true

        val last = lastCountedMs
        if (last != null && timestampMs - last < REFRACTORY_MS) return false

        lastCountedMs = timestampMs
        return true
    }

    /**
     * Back to un-warmed, unlatched, nothing counted.
     *
     * The gravity estimate is dropped with everything else: the next attempt may hold the phone in a
     * completely different orientation, and re-priming costs [WARMUP_SAMPLES] samples — about a fifth
     * of a second.
     */
    fun reset() {
        gravityX = 0.0
        gravityY = 0.0
        gravityZ = 0.0
        samples = 0
        above = false
        lastCountedMs = null
    }

    companion object {

        /**
         * Low-pass weight for the gravity estimate. At `SENSOR_DELAY_GAME` (~20ms) this is a time
         * constant of roughly 400ms — far slower than the 2–5 Hz a person shakes at, which is the
         * whole requirement. Raising it toward 1 makes the filter ignore real tilt; lowering it lets
         * the filter chase the shake and cancel it. See the class doc.
         */
        const val GRAVITY_ALPHA = 0.95

        /**
         * Samples discarded after the first while the filter settles. Twenty at ~20ms is about
         * 0.4s — one time constant, and short enough that the ring is live before the user has
         * finished reading the prompt.
         */
        const val WARMUP_SAMPLES = 20

        /**
         * Linear acceleration, in m/s², that counts as a shake. ~0.6 g of ADDED motion — see the
         * class doc for what that clears and what it rejects.
         */
        const val SHAKE_ON = 6.0

        /** Motion must fall back below this before the next shake can count. Hysteresis. */
        const val SHAKE_OFF = 2.5

        /**
         * Minimum gap between counted shakes. Caps the rate at ~8/second, faster than anyone shakes,
         * so it costs a real shaker nothing while absorbing the ringing after a hard reversal.
         */
        const val REFRACTORY_MS = 120L
    }
}
