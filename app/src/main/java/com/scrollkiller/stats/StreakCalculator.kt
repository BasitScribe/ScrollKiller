package com.scrollkiller.stats

import java.time.LocalDate

/**
 * How many days in a row the user stayed under their limit.
 *
 * @param current days up to and including today. 0 when today is already over.
 * @param best the longest run anywhere in the window examined.
 */
data class Streaks(val current: Int, val best: Int) {
    companion object {
        val NONE = Streaks(current = 0, best = 0)
    }
}

/**
 * Streaks over the daily totals, as a pure function (D81).
 *
 * ## ⚠ PROVISIONAL — these numbers sit on an interim day boundary (D14 / invariant 2)
 * A streak is entirely a statement about which day a count belongs to, and that question does not
 * have its final answer yet. D14 records the device-local `LocalDate` as an INTERIM boundary;
 * invariant 2 says the real one is the user's timezone computed SERVER-side, arriving in Phase 3.
 *
 * So a "best streak" shown today can change when that lands — a count recorded at 00:30 local may
 * migrate to the previous day, joining two runs or splitting one. That is not a bug to fix here; it
 * is the known cost of shipping streaks before the boundary is settled. What it means in practice:
 *  - do NOT persist a computed streak as a durable achievement (Pass C's milestones must derive
 *    from the same source, not cache a number that will silently become wrong);
 *  - when Phase 3 lands, this function does not change — only the dates handed to it do, which is
 *    exactly why the dates are a parameter and there is no clock in here.
 *
 * Android-free and clock-free for that reason: `today` is passed in, so a test can pin it and the
 * Phase-3 migration is a change of caller, not of logic.
 */
object StreakCalculator {

    /**
     * Streaks over the inclusive date window [from]..[today].
     *
     * ## What counts as a day "under"
     * `total < limit`, matching [com.scrollkiller.service.BlockPolicy] exactly — the block fires at
     * `count >= limit`, so "under" means "was not blocked". Using `<=` here would call the day you
     * got blocked a success.
     *
     * ## A day with no row counts as under, and that is deliberate
     * [totalsByDate] only contains days that have a `daily_counts` row, which is created lazily on
     * the first advance. A missing day therefore means zero scrolls — the phone was off, the app
     * unused, the user on holiday — and zero is emphatically under the limit. Treating a gap as a
     * BREAK would punish someone for a day they did not doomscroll at all, which inverts the entire
     * point of the number.
     *
     * ## Today is in progress, and is judged on where it stands
     * If today is already over the limit the current streak is 0 — that is honest, the user blew it
     * and the app should not pretend otherwise until midnight. If today is still under, it counts,
     * and it may of course be lost later in the day. A streak that only updated at midnight would be
     * stale for up to 24 hours on the one screen whose job is to be current.
     *
     * @param totalsByDate day → total across every platform. Days absent are treated as 0.
     * @param limit the user's single daily limit (D76).
     * @param today the day to end the window on; the caller supplies it (see the class doc).
     * @param from the earliest day to examine. [best] is the longest run WITHIN this window, so a
     *   caller asking for 30 days can never report a 40-day best.
     */
    fun of(
        totalsByDate: Map<LocalDate, Int>,
        limit: Int,
        today: LocalDate,
        from: LocalDate,
    ): Streaks {
        if (from.isAfter(today)) return Streaks.NONE

        var best = 0
        var run = 0
        var current = 0

        var day = from
        while (!day.isAfter(today)) {
            val under = (totalsByDate[day] ?: 0) < limit
            if (under) {
                run++
                if (run > best) best = run
            } else {
                run = 0
            }
            // The run standing when the loop reaches today IS the current streak: it is by
            // construction the number of consecutive under-limit days ending at today, and it is
            // already 0 if today itself is over.
            if (day == today) current = run
            day = day.plusDays(1)
        }

        return Streaks(current = current, best = best)
    }
}
