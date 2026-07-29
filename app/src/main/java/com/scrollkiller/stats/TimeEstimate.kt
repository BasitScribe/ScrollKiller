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

    /**
     * MEASURED seconds as a compact label — "48m" under an hour, "3.1h" above (D82).
     *
     * Takes SECONDS, not a count, because this renders the session-derived figure from
     * `daily_minutes` (D80) rather than a flat count estimate. The two must never be formatted by
     * the same call: one is measured and one is inferred, and passing a count here would silently
     * present a guess in the slot reserved for a measurement.
     *
     * Locale-independent arithmetic, same as [minutesLabel].
     */
    fun hoursLabel(seconds: Long): String {
        if (seconds <= 0) return "0m"
        val totalMinutes = seconds / 60
        if (totalMinutes < 60) return "${totalMinutes}m"
        val tenths = Math.round(totalMinutes * 10.0 / 60.0)
        return "${tenths / 10}.${tenths % 10}h"
    }
}
