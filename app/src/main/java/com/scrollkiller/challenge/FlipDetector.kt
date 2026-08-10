package com.scrollkiller.challenge

/**
 * Counts full face-up ↔ face-down turns of the phone. The stateful, pure half of the flip challenge —
 * [OrientationFlipSource] turns hardware into calls on this, and this decides what a flip IS.
 *
 * Pure Kotlin, no Android imports, so every edge below is unit-testable off-device. Same split as
 * [JumpDetector] beside [AccelPeakSource] and [HoldDetector] beside the two holds.
 *
 * ## The same axis as the face-down hold, measured as a COUNT
 * [OrientationHoldSource] reads accelerometer Z to ask "is the screen pointing at the floor RIGHT
 * NOW" and hands that to [HoldDetector], which times it. This reads the same axis to ask "has the
 * screen CHANGED which way it points", which is a different question with a different answer shape —
 * discrete events rather than elapsed time. Worth stating plainly because the two look like they
 * could share code and must not: a hold is defined by nothing happening and a flip by something
 * happening, so merging them would mean one class whose meaning depended on which caller it had.
 *
 * ## The dead band IS the debouncing
 * Only Z beyond ±[SETTLED_Z] counts as an orientation at all; everything between is "in transit" and
 * is ignored entirely. A phone standing on edge, being carried, or resting against something
 * therefore reports nothing rather than flickering between two verdicts — which is what a single
 * threshold at zero would do, turning one slow turn into a dozen counted flips. No refractory window
 * is needed on top: the phone physically cannot re-cross the band without being turned over again.
 *
 * ±[SETTLED_Z] is about 45° of tolerance either way, matching [OrientationHoldSource]'s reasoning:
 * phones are turned over onto cushions and camera bumps, and demanding a near-perfect ±9 would fail
 * a user who did exactly what was asked.
 *
 * ## Why the first settled reading scores nothing
 * The challenge starts with the phone in SOME orientation — screen up, almost always, because the
 * user just tapped a button on it. Counting that first observation would hand out a free flip on
 * every attempt. It baselines instead, the same rule [ChallengeProgress.onCumulative],
 * [JumpDetector] and [com.scrollkiller.guilt.GuiltFiring] already live by.
 *
 * NOT thread-safe; main thread only, like everything the overlay touches.
 */
class FlipDetector {

    /**
     * The last SETTLED orientation: true = screen up, false = screen down, null = nothing settled
     * yet. In-transit samples never write here, which is what makes the dead band work.
     */
    private var faceUp: Boolean? = null

    /** The last settled orientation, for logs and tests. Null before the first one. */
    val settledFaceUp: Boolean? get() = faceUp

    /**
     * Feed one accelerometer Z reading, in m/s².
     *
     * Z points out of the screen, so gravity puts it near **+9.8 with the screen up** and near
     * **−9.8 with the screen down** — the sign is the orientation and the magnitude only says how
     * far from flat.
     *
     * @return true if the phone completed a turn on THIS sample — at most one per call. "Flip 10
     *   times" therefore means ten CHANGES of orientation, i.e. five there-and-back cycles.
     */
    fun onSample(z: Double): Boolean {
        val settled = when {
            z > SETTLED_Z -> true
            z < -SETTLED_Z -> false
            else -> return false   // in transit — deliberately not an observation at all
        }

        val previous = faceUp
        faceUp = settled
        // Null means this is the first orientation we have seen; see the class doc for why it is
        // free. Equal means the phone moved within its own dead band and came back.
        return previous != null && previous != settled
    }

    /** Back to no known orientation, so the next attempt's first reading baselines again. */
    fun reset() {
        faceUp = null
    }

    companion object {

        /**
         * How far past flat counts as a settled orientation, in m/s². About 45° of tolerance, and
         * everything inside ±this is in transit. See the class doc — this band is the entire
         * debouncing strategy.
         */
        const val SETTLED_Z = 7.0
    }
}
