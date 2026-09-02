package com.scrollkiller.service

/**
 * Vertical scroll direction of a single accessibility scroll event.
 *
 * DOWN = content moved up / user swiped to the NEXT reel (a forward advance).
 * UP   = user swiped back to the previous reel.
 * SAME = settle/idle frame with no net vertical movement (noise).
 */
enum class ScrollDirection { UP, DOWN, SAME }

/**
 * Turns a noisy stream of scroll events into discrete "reel advanced" signals for
 * ONE platform. Pure and Android-free so it is unit-testable off-device — this is
 * the accuracy-critical piece behind the Phase-1 exit bar (±2 over 50 swipes).
 *
 * ## Why an activity-reset quiet-gap (not a fixed refractory)
 * A single Instagram swipe emits a burst of DOWN events over the fling's settle
 * (~640ms observed), spaced ~110ms apart. Meanwhile genuine swipes can be as
 * little as ~108ms apart — so the settle duration and real cadence OVERLAP, and no
 * fixed "count at most once per N ms" window separates them cleanly.
 *
 * Resetting [lastDownTs] on EVERY DOWN sidesteps this: while a fling keeps firing
 * events <[minAdvanceIntervalMs] apart, the gap never reopens, so the whole burst
 * — of any length — collapses to exactly one advance. Only a DOWN that follows a
 * genuine quiet gap counts. Failure mode is a mild UNDERcount on ultra-fast skips
 * (two real swipes merged), never the ~8× overcount of counting raw events.
 *
 * Forward-only by product decision: UP (re-viewing a previous reel) and SAME never
 * count.
 */
class SwipeDetector(private val minAdvanceIntervalMs: Long) {

    /** Timestamp of the last DOWN event seen (counted or not). UNSET before any. */
    private var lastDownTs = UNSET

    /**
     * Feed one scroll event. Returns true exactly when it should count as a new
     * forward reel advance.
     *
     * @param atMs event time in millis (monotonic within a session is enough).
     */
    fun onScroll(direction: ScrollDirection, atMs: Long): Boolean {
        if (direction != ScrollDirection.DOWN) return false

        // First DOWN ever, or the first after a quiet gap, is a real advance.
        val isNewAdvance = lastDownTs == UNSET || atMs - lastDownTs > minAdvanceIntervalMs

        // Reset on EVERY DOWN — this is what collapses an arbitrarily long fling burst.
        lastDownTs = atMs

        return isNewAdvance
    }

    /**
     * [AdvanceStrategy.EVENT_PULSE]: any container scroll is an advance, including
     * [ScrollDirection.SAME]. Vertical pagers (TikTok, Snapchat Spotlight) often report
     * `scrollDeltaY = 0`, which [onScroll] would drop. The quiet-gap still collapses a
     * fling; only the direction check is skipped.
     */
    fun onPulse(atMs: Long): Boolean = onScroll(ScrollDirection.DOWN, atMs)

    private companion object {
        /** Sentinel meaning "no DOWN seen yet"; avoids Long overflow on the first diff. */
        const val UNSET = Long.MIN_VALUE
    }
}
