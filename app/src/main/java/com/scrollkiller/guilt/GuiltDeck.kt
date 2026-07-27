package com.scrollkiller.guilt

import kotlin.random.Random

/**
 * A deterministic, per-install, per-day ORDERING of a pool of lines.
 *
 * ## What this used to be, and why it shrank (D42 → D47)
 * D42 made this a DECK: it narrowed each tier's pool to ~60% per day so that the set a user drew
 * from rotated, which was how "different lines on different days" was delivered from a fixed
 * pack. D47's rolling 7-day exclusion delivers that same property far more strongly — a line the
 * user has seen is excluded outright, not merely deprioritised — and the two cannot both narrow
 * the pool without starving it: 60% of a pool that has already had a week of shown lines removed
 * is how you reach the exhaustion fallback on a pack that had plenty of content.
 *
 * So the narrowing is gone and the SEED survives. The 7-day rule decides what is eligible; this
 * decides the order within it. That is the whole of the reconciliation — exclusion is the hard
 * rule, the daily seed is the tiebreak.
 *
 * ## Where the order actually matters
 * Not in the common path: [GuiltRotation] draws by weight and ignores order. It matters in
 * [GuiltSelector]'s exhaustion fallback, which sorts by staleness and takes the stalest slice —
 * where many lines tie (everything shown "today") and something has to break the tie. Doing that
 * with a per-install daily seed means two users on the same day degrade differently, and one user
 * degrades the same way all day rather than reshuffling on every draw.
 *
 * Pure Kotlin, no Android imports.
 */
object GuiltDeck {

    /**
     * [pool] in a stable order for [dayKey] and [installId].
     *
     * The pool is canonicalised (sorted by id) BEFORE shuffling, so the pack's declaration order
     * cannot leak into the result — otherwise reordering guilt_pack.json, an edit with no intent
     * behind it, would silently reshuffle every user.
     */
    fun order(pool: List<GuiltLine>, dayKey: String, installId: String): List<GuiltLine> =
        pool.sortedBy { it.id }
            .shuffled(Random(seed(dayKey, installId)))

    /**
     * 64-bit FNV-1a over `dayKey|installId`.
     *
     * Hand-rolled rather than [String.hashCode] because this seed decides what a user sees and
     * must be stable for the life of the install — leaning on a platform hash means a future ART
     * change silently reshuffles everyone. It is also 64-bit, so it fills [Random]'s seed instead
     * of leaving the high word constant.
     */
    fun seed(dayKey: String, installId: String): Long {
        var hash = -0x340d631b7bdddcdbL          // FNV offset basis
        for (char in "$dayKey|$installId") {
            hash = hash xor char.code.toLong()
            hash *= 0x100000001b3L               // FNV prime
        }
        return hash
    }
}
