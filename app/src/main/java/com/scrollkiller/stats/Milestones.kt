package com.scrollkiller.stats

import java.time.LocalDate

/** The milestones this app can prove from stored data. Order is display order. */
enum class MilestoneId {
    /** One day, ever, finished under the limit. */
    FIRST_DAY_UNDER,

    /** Three consecutive days under. */
    STREAK_3,

    /** Seven consecutive days under. */
    STREAK_7,

    /** Fourteen consecutive days under. */
    STREAK_14,

    /** Thirty consecutive days under. */
    STREAK_30,

    /** Ten days under in total, consecutive or not. */
    TOTAL_10,

    /** Fifty days under in total. */
    TOTAL_50,
}

/**
 * One milestone's state.
 *
 * @param progress how far along, in the same unit as [target]. Capped at [target].
 * @param target what it takes to earn it.
 * @param achieved derived, never stored — see [Milestones].
 */
data class Milestone(
    val id: MilestoneId,
    val progress: Int,
    val target: Int,
) {
    val achieved: Boolean get() = progress >= target
}

/**
 * Milestones, DERIVED from history on every read (D83).
 *
 * ## Why nothing here is ever awarded and stored
 * D81 is explicit: a streak is entirely a claim about which day a count belongs to, and D14's
 * device-local day boundary is INTERIM — invariant 2 replaces it with server timezone truth in
 * Phase 3. A count recorded at 00:30 local may migrate to the previous day, joining two runs or
 * splitting one.
 *
 * So a badge PERSISTED today can become arithmetically false later, and the app would be
 * contradicting its own history on the one screen whose entire job is to be believed. Deriving
 * costs nothing — the data is already loaded for the trend — and it means a milestone is always a
 * true statement about the counts as they currently stand.
 *
 * The honest consequence, accepted: clearing data clears the milestones too. That is correct rather
 * than a bug. They are a VIEW of the history, and the user asked for the history to be gone.
 *
 * ## Why there are no challenge milestones
 * "Walked it off five times" is the obvious one to want, and it cannot be built: challenge
 * completions are not persisted anywhere. Adding a counter for it is a storage decision, and this
 * pass deliberately adds no storage. Everything below is provable from `daily_counts`, which has
 * been kept forever since D4.
 *
 * ## The tone rule
 * Warmth lives here. The block screen keeps the guilt (D33/D49); this is the counterweight, and
 * the research behind D78 is blunt that an app which only ever scolds gets uninstalled out of
 * shame. So a LOCKED milestone shows progress toward it, never a reproach — "4 of 7", not "you
 * failed to reach 7".
 *
 * Pure, clock-free, Android-free: `today` is a parameter, same as [StreakCalculator].
 */
object Milestones {

    /** Streak milestones, in ascending order of difficulty. */
    private val STREAK_TARGETS = listOf(
        MilestoneId.STREAK_3 to 3,
        MilestoneId.STREAK_7 to 7,
        MilestoneId.STREAK_14 to 14,
        MilestoneId.STREAK_30 to 30,
    )

    /** Cumulative "days under" milestones. */
    private val TOTAL_TARGETS = listOf(
        MilestoneId.TOTAL_10 to 10,
        MilestoneId.TOTAL_50 to 50,
    )

    /**
     * Every milestone with its current progress, computed over ALL stored history.
     *
     * ## The window is all-time, deliberately, and differs from the Insights trend
     * [StreakCalculator] is bounded by the window its caller passes, because a 7-day chart must not
     * report a 30-day best. A milestone is the opposite question — "have you EVER" — so this walks
     * from the first day with data. `daily_counts` holds at most a couple of rows per day and is
     * already in memory for the chart, so there is no cost to asking the wider question.
     *
     * ## Days with no row count as under
     * Same rule as [StreakCalculator], and for the same reason: a lazily-created row means zero
     * scrolls, and a day the user did not doomscroll at all is the outcome the product exists to
     * produce. But the walk STARTS at the first day that has data rather than at some arbitrary
     * epoch — otherwise every new install would instantly hold a thousand-day streak of days before
     * the app was installed, which is the one way this could be actively dishonest.
     *
     * @param totalsByDate every day with a stored count. Empty means a fresh install.
     * @param limit the user's single daily limit (D76).
     * @param today the day to measure up to.
     */
    fun of(
        totalsByDate: Map<LocalDate, Int>,
        limit: Int,
        today: LocalDate,
    ): List<Milestone> {
        val firstDay = totalsByDate.keys.minOrNull()
        if (firstDay == null || firstDay.isAfter(today)) {
            return MilestoneId.entries.map { Milestone(it, progress = 0, target = targetFor(it)) }
        }

        var bestStreak = 0
        var run = 0
        var daysUnder = 0

        var day: LocalDate = firstDay
        while (!day.isAfter(today)) {
            if ((totalsByDate[day] ?: 0) < limit) {
                run++
                daysUnder++
                if (run > bestStreak) bestStreak = run
            } else {
                run = 0
            }
            day = day.plusDays(1)
        }

        return MilestoneId.entries.map { id ->
            val target = targetFor(id)
            val raw = when (id) {
                MilestoneId.FIRST_DAY_UNDER -> daysUnder
                MilestoneId.TOTAL_10, MilestoneId.TOTAL_50 -> daysUnder
                else -> bestStreak
            }
            Milestone(id, progress = raw.coerceAtMost(target), target = target)
        }
    }

    /** How many earned so far — the "3 of 9" counter. */
    fun earnedCount(milestones: List<Milestone>): Int = milestones.count { it.achieved }

    private fun targetFor(id: MilestoneId): Int = when (id) {
        MilestoneId.FIRST_DAY_UNDER -> 1
        else -> (STREAK_TARGETS + TOTAL_TARGETS).first { it.first == id }.second
    }
}
