package com.scrollkiller.guilt

import kotlin.random.Random

/**
 * Weighted-random line selection with no-repeat-within-session, for ONE surface.
 *
 * The two properties, and why each exists:
 *
 *  - WEIGHTED: a line's [GuiltLine.weight] is its relative odds inside the eligible pool.
 *    This is how the pack gets tuned (a line that lands well gets a 4, a filler gets a 2)
 *    without editing code — and, once the pack is served, without shipping an app update.
 *
 *  - NO REPEAT WITHIN SESSION: a line is not offered again until every other eligible line
 *    has been shown, then the cycle restarts. This is stronger than "don't repeat the last
 *    one" and it's the point of the feature — the guilt only works while it still surprises
 *    you. Naive weighted sampling would replay a heavy line three times in a row and the
 *    whole thing would read as a canned string array again, which is exactly what this
 *    replaces.
 *
 * Crossing a cycle boundary is where a naive implementation leaks a visible repeat: the pool
 * empties, resets, and can immediately re-draw the line that just emptied it. [pick] carries
 * the just-shown line across the reset as an exclusion so that can't happen.
 *
 * Pure Kotlin and [Random]-injectable so the distribution and the no-repeat guarantee are
 * both unit-testable off-device. NOT thread-safe: all callers are on the service main thread.
 */
class GuiltRotation(private val random: Random = Random.Default) {

    /** Ids shown in the current cycle. Cleared when the eligible pool is exhausted. */
    private val shownThisCycle = mutableSetOf<String>()

    /** The last id this rotation returned; excluded across a cycle reset. */
    private var lastId: String? = null

    /**
     * Pick a line from [candidates], or null if there are none.
     *
     * @param avoid an id to exclude — used to stop two DIFFERENT surfaces (block screen and
     *   bubble keep separate rotations) from showing the same line back-to-back. Applied
     *   BEFORE the cycle filter, not after: not repeating a line the user just read is a
     *   visible quality property, whereas finishing a cycle in order is bookkeeping. Applying
     *   it afterwards meant that when the avoided line was the last one unshown in a cycle it
     *   got returned anyway — the one case the guard exists for. Yielded to only by a pool
     *   with no other option at all (a one-line pack legitimately repeats).
     */
    fun pick(candidates: List<GuiltLine>, avoid: String? = null): GuiltLine? {
        if (candidates.isEmpty()) return null

        // What may be shown at all on this draw. Repeating beats rendering nothing, so a pool
        // that is entirely excluded falls back to the raw candidates.
        val eligible = candidates.filterNot { it.id == avoid }.ifEmpty { candidates }

        var pool = eligible.filterNot { it.id in shownThisCycle }
        if (pool.isEmpty()) {
            // Cycle exhausted — start a new one. The line that just ended the cycle stays
            // excluded so the reset itself can't produce a visible back-to-back repeat.
            shownThisCycle.clear()
            pool = eligible.filterNot { it.id == lastId }.ifEmpty { eligible }
        }

        val chosen = weightedPick(pool)
        shownThisCycle += chosen.id
        lastId = chosen.id
        return chosen
    }

    /** Forget session history — used when a new pack is installed (ids may no longer exist). */
    fun reset() {
        shownThisCycle.clear()
        lastId = null
    }

    /**
     * Standard cumulative-weight draw. Weights are coerced to >= 1 defensively: the parser
     * already does this, but a zero total here would mean `nextInt(0)` throwing inside an
     * accessibility service, and a guilt line is never worth crashing the detector over.
     */
    private fun weightedPick(pool: List<GuiltLine>): GuiltLine {
        val total = pool.sumOf { it.weight.coerceAtLeast(1) }
        var target = random.nextInt(total)
        for (line in pool) {
            target -= line.weight.coerceAtLeast(1)
            if (target < 0) return line
        }
        return pool.last()   // unreachable while total is computed from the same list
    }
}
