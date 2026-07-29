package com.scrollkiller.stats

import java.time.DayOfWeek
import java.time.LocalDate

/** How the Insights trend groups its days. */
enum class TrendGrouping {
    /** One bar per day. The short view — seven bars, each individually meaningful. */
    DAILY,

    /** One bar per calendar week. The long view — see [TrendBuckets.weekly] for why not 30 bars. */
    WEEKLY,
}

/**
 * One bar of the trend chart.
 *
 * @param start first day this bar covers, inclusive.
 * @param endInclusive last day it covers. Equal to [start] when grouping is [TrendGrouping.DAILY].
 * @param count total short videos across the bucket, gaps counted as zero.
 * @param isPartial the bucket contains today, so it is STILL ACCUMULATING and will grow before the
 *   period ends. The chart must distinguish these or an in-progress week reads as a collapse in
 *   usage — the same class of dishonesty as blending measured and estimated time in one series
 *   (D80). Always true of exactly one bucket: the last.
 */
data class TrendBucket(
    val start: LocalDate,
    val endInclusive: LocalDate,
    val count: Int,
    val isPartial: Boolean,
) {
    /** Days this bucket actually covers, counting only up to and including [today]. */
    fun elapsedDays(today: LocalDate): Int {
        val last = if (endInclusive.isAfter(today)) today else endInclusive
        return (last.toEpochDay() - start.toEpochDay() + 1).toInt().coerceAtLeast(0)
    }
}

/**
 * Groups daily totals into the bars the Insights chart draws (D82). Pure, clock-free, Android-free:
 * `today` is a parameter, same as [StreakCalculator].
 */
object TrendBuckets {

    /** Weeks start Monday (ISO-8601). See [weekly] for why this is fixed rather than locale-derived. */
    val WEEK_START: DayOfWeek = DayOfWeek.MONDAY

    /**
     * One bucket per day over the inclusive window [from]..[today], oldest first.
     *
     * Days with no `daily_counts` row are emitted as zero rather than skipped — a chart that omits
     * empty days would silently compress the x-axis and show seven bars spanning three weeks.
     */
    fun daily(
        totalsByDate: Map<LocalDate, Int>,
        from: LocalDate,
        today: LocalDate,
    ): List<TrendBucket> {
        if (from.isAfter(today)) return emptyList()
        val out = mutableListOf<TrendBucket>()
        var day = from
        while (!day.isAfter(today)) {
            out += TrendBucket(
                start = day,
                endInclusive = day,
                count = totalsByDate[day] ?: 0,
                isPartial = day == today,
            )
            day = day.plusDays(1)
        }
        return out
    }

    /**
     * One bucket per calendar week, oldest first, ending with the week containing [today].
     *
     * ## Why weeks and not thirty daily bars
     * Thirty bars on a phone are three or four pixels wide: individually unreadable, impossible to
     * tap, and they present day-to-day noise as though it were signal. A month view is asked a
     * different question than a week view — "is this getting better or worse", not "what happened on
     * the 14th" — and weeks are the granularity that answers it.
     *
     * ## The range snaps BACK to a week boundary, and that is deliberate
     * [weeksBack] complete weeks plus the current partial one. The window therefore starts on a
     * Monday rather than exactly N days ago, which means **only one bucket is ever partial: the
     * last**. Taking a literal trailing 30 days would clip the OLDEST week too, producing a short
     * first bar that looks like a genuine dip but is only an artifact of where the window fell.
     * Two partial buckets are two things to explain; one is a caption.
     *
     * Callers should label the range with its real dates rather than "30 days", because it covers
     * 29–35 depending on the weekday.
     *
     * ## Monday, fixed
     * ISO-8601 rather than `WeekFields.of(locale)`. A locale-derived week start makes the bucket
     * boundaries — and therefore every test — depend on the device's region, and the chart labels
     * each bar with its actual dates anyway, so nothing here reads as "the wrong week".
     */
    fun weekly(
        totalsByDate: Map<LocalDate, Int>,
        today: LocalDate,
        weeksBack: Int = DEFAULT_WEEKS_BACK,
    ): List<TrendBucket> {
        if (weeksBack < 0) return emptyList()
        val currentWeekStart = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(WEEK_START))
        val firstWeekStart = currentWeekStart.minusWeeks(weeksBack.toLong())

        val out = mutableListOf<TrendBucket>()
        var weekStart = firstWeekStart
        while (!weekStart.isAfter(currentWeekStart)) {
            val weekEnd = weekStart.plusDays(6)
            var sum = 0
            var day = weekStart
            while (!day.isAfter(weekEnd)) {
                // Days after today contribute nothing; the bucket is partial, not predictive.
                if (!day.isAfter(today)) sum += totalsByDate[day] ?: 0
                day = day.plusDays(1)
            }
            out += TrendBucket(
                start = weekStart,
                endInclusive = weekEnd,
                count = sum,
                isPartial = weekStart == currentWeekStart,
            )
            weekStart = weekStart.plusWeeks(1)
        }
        return out
    }

    /** The first day covered by [weekly] — what the caller passes to the range queries. */
    fun weeklyRangeStart(today: LocalDate, weeksBack: Int = DEFAULT_WEEKS_BACK): LocalDate =
        today.with(java.time.temporal.TemporalAdjusters.previousOrSame(WEEK_START))
            .minusWeeks(weeksBack.toLong())

    /** Complete weeks shown before the current partial one. Four ≈ a month without claiming "30 days". */
    const val DEFAULT_WEEKS_BACK = 4

    /** Days in the short view, including today. */
    const val DAILY_WINDOW_DAYS = 7
}
