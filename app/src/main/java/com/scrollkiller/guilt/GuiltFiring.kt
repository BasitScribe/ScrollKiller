package com.scrollkiller.guilt

/**
 * Decides WHEN a guilt line fires. The stateful half of [GuiltCadence].
 *
 * Fed every count emission; answers one question — "show a new line now?" — from three inputs:
 * the tier the count is in, how many scrolls since the last line, and how long since the last
 * line was actually put on screen.
 *
 * ## The three ways a line fires
 *  1. **A tier crossing.** Any escalation fires immediately. This is what makes 50 and 70 fire
 *     once each even though [GuiltCadence.SCHEDULE] has no repeating interval down there, and it
 *     is why crossing 100 and 150 announce themselves rather than waiting out an interval.
 *  2. **The schedule.** From 100 up, every [GuiltCadence.intervalFor] scrolls since the last fire.
 *  3. Nothing else. In particular, not time alone: a user who puts the phone down does not get
 *     nagged, because the cadence is measured in SCROLLS. Time only ever *suppresses*.
 *
 * ## Baselining, and why entering a surface is silent
 * The first observation of a day (or of a process) records the count and fires nothing. Without
 * that, walking into Reels at 300 would read `300 - 0 >= 7` and fire instantly — and again every
 * time you tabbed back in. Because the baseline persists across surface exits, re-entering at an
 * unchanged count can never fire: the cadence is about the day's scrolling, not about arrivals.
 *
 * ## Suppression is PENDING, not dropped
 * When [GuiltCadence.MIN_GAP_MS] blocks a fire, no state is consumed — the interval stays due and
 * the tier stays un-fired, so the line goes out on the first emission after the gap elapses
 * rather than being lost. Dropping it instead would mean the faster you scroll the fewer lines
 * you see, which is exactly backwards.
 *
 * Pure Kotlin, no Android imports, no clock of its own — [onCount] takes `nowMs`. NOT
 * thread-safe; main thread only, like everything else in this package.
 */
class GuiltFiring {

    /** The day the counters below belong to. Null before the first observation. */
    private var dayKey: String? = null

    /** Count at the last line we actually SHOWED. The cadence measures from here. */
    private var lastFireCount = 0

    /** `nowMs` of the last line we actually showed. 0 = never, so the first fire is never gapped. */
    private var lastFireAtMs = 0L

    /** Tier of the last line we showed, so an escalation is detectable. */
    private var lastFiredTier: GuiltTier? = null

    /**
     * Should a new line be shown for [count] right now?
     *
     * Returns true at most once per emission, and never twice for the same count.
     *
     * @param tier the tier [count] falls in, or null below the first threshold. Passed in rather
     *   than recomputed so there is exactly one place ([GuiltTier.forCount]) that maps counts to
     *   tiers, and a caller cannot fire against a different tier than it then draws from.
     * @param dayKey today, device-local. A change baselines: the day's cadence starts over with
     *   the count, per the daily rollover rule.
     * @param nowMs a MONOTONIC clock (the caller passes `SystemClock.elapsedRealtime`). Not wall
     *   time: the user changing the device clock — or a timezone shift — must not be able to
     *   unblock or permanently block the display gap.
     */
    fun onCount(count: Int, tier: GuiltTier?, dayKey: String, nowMs: Long): Boolean {
        // First observation, or a day rollover. Baseline and stay quiet — see the class doc.
        if (this.dayKey != dayKey) {
            baseline(count, tier, dayKey)
            return false
        }

        // The count went BACKWARDS, which within a day means only one thing: the user cleared
        // their data. Re-baseline rather than treating the drop as progress, or the next
        // increment would read as hundreds of scrolls at once and fire immediately.
        if (count < lastFireCount) {
            baseline(count, tier, dayKey)
            return false
        }

        if (tier == null) {
            // Below the first threshold. Keep tracking the count so crossing 50 later is a
            // crossing and not a backlog, and forget the fired tier so the crossing registers.
            lastFireCount = count
            lastFiredTier = null
            return false
        }

        val escalated = tier.level > (lastFiredTier?.level ?: 0)
        val interval = GuiltCadence.intervalFor(count)
        val due = interval != null && count - lastFireCount >= interval
        if (!escalated && !due) return false

        // Gap-blocked: consume NOTHING, so this fire is pending rather than lost.
        if (lastFireAtMs != 0L && nowMs - lastFireAtMs < GuiltCadence.MIN_GAP_MS) return false

        lastFireCount = count
        lastFiredTier = tier
        lastFireAtMs = nowMs
        return true
    }

    /** Forget everything. New pack, new locale, or a test. */
    fun reset() {
        dayKey = null
        lastFireCount = 0
        lastFireAtMs = 0L
        lastFiredTier = null
    }

    /**
     * Start counting from here without firing. [lastFireAtMs] is cleared too, so the first real
     * fire of a new day is never held back by a gap measured against yesterday.
     */
    private fun baseline(count: Int, tier: GuiltTier?, dayKey: String) {
        this.dayKey = dayKey
        lastFireCount = count
        lastFiredTier = tier
        lastFireAtMs = 0L
    }
}
