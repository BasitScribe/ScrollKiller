package com.scrollkiller.stats

/**
 * Turns a short-video count into a rough "time spent" estimate. Pure and Android-free
 * so it's unit-testable and reused by any surface. The number is intentionally a coarse
 * motivator ("~4.8 mins on Reels"), not a precise measurement — we only detect advances,
 * not dwell time.
 */
object TimeEstimate {

    /** Assumed average seconds spent per short video before swiping on. */
    const val AVG_SECONDS_PER_ITEM = 6

    /** Estimated minutes for [count] advances at [secondsPerItem] each. */
    fun minutes(count: Int, secondsPerItem: Int = AVG_SECONDS_PER_ITEM): Double =
        count.toDouble() * secondsPerItem / 60.0

    /**
     * Human string like "4.8" (minutes, one decimal) — the caller wraps it, e.g.
     * "~%s mins". Uses plain arithmetic + rounding so it's Locale-independent.
     */
    fun minutesLabel(count: Int, secondsPerItem: Int = AVG_SECONDS_PER_ITEM): String {
        val tenths = Math.round(minutes(count, secondsPerItem) * 10.0)
        return "${tenths / 10}.${tenths % 10}"
    }
}
