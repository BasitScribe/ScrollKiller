package com.scrollkiller.guilt

/**
 * Which guilt lines the user has already seen, and when. The in-memory half of the rolling
 * 7-day no-repeat rule (D47).
 *
 * ## Why in memory at all
 * [GuiltSelector] runs on the main thread inside an AccessibilityService's render path and must
 * answer "which lines are excluded?" synchronously. Room cannot be asked that on the main thread.
 * So the table is read ONCE at process start into this map ([seed]) and written through
 * afterwards ([onRecord]); the map is the read model, Room is the durable one. The table is
 * bounded by the pack's line count, so "read it all" is a few hundred rows at worst.
 *
 * Between process start and [seed] landing, [shownSince] returns nothing and the window is
 * briefly not enforced. That is a deliberate, bounded hole: the alternative is blocking a render
 * on a database read, and the cost of losing it is at most a repeat or two in the first moments
 * after a cold start. [seed] merges rather than overwrites so anything recorded during that
 * window survives the load landing late.
 *
 * Wall-clock millis throughout, because the values outlive the process and must stay comparable
 * across reboots — the opposite requirement to [GuiltFiring]'s display gap, which needs a
 * monotonic clock precisely so a clock change cannot jam it. Two clocks, two reasons; see
 * [GuiltNow].
 *
 * Pure Kotlin, no Android imports. NOT thread-safe; main thread only, like the rest of the
 * package (the [seed] callback posts back to Main).
 */
class GuiltHistory {

    private val shownAt = HashMap<String, Long>()

    /**
     * Called on every [record], so the caller can persist it. Set by [GuiltLines] once the
     * database is reachable; null in tests and before priming, which keeps this class pure.
     */
    var onRecord: ((lineId: String, wallMs: Long) -> Unit)? = null

    /**
     * Merge persisted history into the map, keeping the LATER timestamp per id.
     *
     * Merging rather than replacing matters: this arrives asynchronously, so a line may already
     * have been shown and recorded in the gap since process start. Overwriting would move that
     * line's timestamp backwards to whatever disk said, letting it come round again early.
     */
    fun seed(entries: Map<String, Long>) {
        entries.forEach { (id, at) ->
            val existing = shownAt[id]
            if (existing == null || at > existing) shownAt[id] = at
        }
    }

    /** Note that [lineId] was just shown, and persist it if a sink is attached. */
    fun record(lineId: String, wallMs: Long) {
        shownAt[lineId] = wallMs
        onRecord?.invoke(lineId, wallMs)
    }

    /** Ids shown at or after [sinceMs] — the exclusion set for the window. */
    fun shownSince(sinceMs: Long): Set<String> =
        shownAt.filterValues { it >= sinceMs }.keys

    /**
     * When [lineId] was last shown, or [Long.MIN_VALUE] if never.
     *
     * MIN_VALUE rather than null so it sorts first in the least-recently-shown fallback: a line
     * the user has never seen is the stalest thing there is, and is exactly what that fallback
     * should reach for first.
     */
    fun lastShownAt(lineId: String): Long = shownAt[lineId] ?: Long.MIN_VALUE

    /** Forget everything (Settings → Clear data). Does not touch the database; caller does. */
    fun clear() {
        shownAt.clear()
    }

    /** How many lines are remembered. Diagnostics and tests. */
    val size: Int get() = shownAt.size
}
