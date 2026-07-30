package com.scrollkiller

import com.scrollkiller.stats.TrendBuckets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Trend bucketing (D82). The properties that matter are the honesty ones: gaps become zero bars
 * rather than vanishing, exactly one bucket is ever partial, and a partial bucket never borrows
 * counts from days that have not happened.
 */
class TrendBucketsTest {

    // A Wednesday, so the current week is genuinely partial in every weekly case below.
    private val today: LocalDate = LocalDate.of(2026, 7, 29)

    private fun totals(vararg pairs: Pair<LocalDate, Int>) = pairs.toMap()

    /* --- daily --------------------------------------------------------------------------- */

    @Test
    fun `daily emits one bucket per day, oldest first`() {
        val from = today.minusDays(6)
        val bars = TrendBuckets.daily(emptyMap(), from, today)

        assertEquals(7, bars.size)
        assertEquals(from, bars.first().start)
        assertEquals(today, bars.last().start)
    }

    @Test
    fun `a day with no row becomes a ZERO bar, not a missing one`() {
        // Skipping empty days would silently compress the axis — seven bars spanning three weeks,
        // with no indication that anything was left out.
        val bars = TrendBuckets.daily(
            totals(today to 5, today.minusDays(6) to 3),
            today.minusDays(6),
            today,
        )
        assertEquals(7, bars.size)
        assertEquals(listOf(3, 0, 0, 0, 0, 0, 5), bars.map { it.count })
    }

    @Test
    fun `only today is partial in the daily view`() {
        val bars = TrendBuckets.daily(emptyMap(), today.minusDays(6), today)
        assertEquals(1, bars.count { it.isPartial })
        assertTrue(bars.last().isPartial)
    }

    @Test
    fun `an inverted window is empty rather than an exception`() {
        assertTrue(TrendBuckets.daily(emptyMap(), today.plusDays(1), today).isEmpty())
    }

    /* --- weekly -------------------------------------------------------------------------- */

    @Test
    fun `weekly emits the four complete weeks plus the current one`() {
        val bars = TrendBuckets.weekly(emptyMap(), today)
        assertEquals(TrendBuckets.DEFAULT_WEEKS_BACK + 1, bars.size)
    }

    @Test
    fun `every weekly bucket starts on a Monday`() {
        TrendBuckets.weekly(emptyMap(), today).forEach {
            assertEquals(DayOfWeek.MONDAY, it.start.dayOfWeek)
        }
    }

    @Test
    fun `exactly one bucket is partial, and it is the last`() {
        // THE property the whole snap-back-to-a-week-boundary design exists for. A literal trailing
        // 30 days would clip the OLDEST week too, producing a short first bar that reads as a
        // genuine dip but is only an artifact of where the window happened to fall.
        val bars = TrendBuckets.weekly(emptyMap(), today)
        assertEquals(1, bars.count { it.isPartial })
        assertTrue(bars.last().isPartial)
        assertFalse(bars.first().isPartial)
    }

    @Test
    fun `the partial bucket counts only days that have happened`() {
        // Wednesday: Mon+Tue+Wed are real, Thu-Sun are the future. A bucket that summed the whole
        // calendar week would be reading rows that cannot exist — and if a clock change ever
        // created one, it would silently inflate the current week.
        val monday = today.with(DayOfWeek.MONDAY)
        val bars = TrendBuckets.weekly(
            totals(
                monday to 10,
                monday.plusDays(1) to 20,
                today to 5,
                today.plusDays(1) to 999, // tomorrow — must be ignored
                monday.plusDays(6) to 999, // Sunday — must be ignored
            ),
            today,
        )
        assertEquals(35, bars.last().count)
    }

    @Test
    fun `a complete past week sums all seven of its days`() {
        val lastWeekMonday = today.with(DayOfWeek.MONDAY).minusWeeks(1)
        val week = (0..6).associate { lastWeekMonday.plusDays(it.toLong()) to 1 }
        val bars = TrendBuckets.weekly(week, today)

        val target = bars.single { it.start == lastWeekMonday }
        assertEquals(7, target.count)
        assertFalse(target.isPartial)
    }

    @Test
    fun `weeklyRangeStart matches the first bucket, so queries and chart agree`() {
        // If these ever diverged the chart would show a week the range queries never fetched, and
        // the per-app totals beneath it would silently cover a different window than the bars.
        val bars = TrendBuckets.weekly(emptyMap(), today)
        assertEquals(bars.first().start, TrendBuckets.weeklyRangeStart(today))
    }

    @Test
    fun `elapsedDays never counts the future`() {
        val bars = TrendBuckets.weekly(emptyMap(), today)
        val current = bars.last()
        // Wednesday -> Mon, Tue, Wed.
        assertEquals(3, current.elapsedDays(today))
        // A completed week is its full seven.
        assertEquals(7, bars.first().elapsedDays(today))
    }

    @Test
    fun `bucketing on a Monday still yields exactly one partial bucket of one day`() {
        // The boundary case: today IS the week start, so the current bucket holds a single day.
        val monday = LocalDate.of(2026, 7, 27)
        val bars = TrendBuckets.weekly(emptyMap(), monday)
        assertEquals(1, bars.count { it.isPartial })
        assertEquals(1, bars.last().elapsedDays(monday))
    }

    @Test
    fun `bucketing on a Sunday makes the current bucket a full seven days`() {
        val sunday = LocalDate.of(2026, 7, 26)
        val bars = TrendBuckets.weekly(emptyMap(), sunday)
        assertTrue("still in progress until the day ends", bars.last().isPartial)
        assertEquals(7, bars.last().elapsedDays(sunday))
    }

    @Test
    fun `weekly totals never exceed the sum of their days`() {
        val monday = today.with(DayOfWeek.MONDAY).minusWeeks(2)
        val data = (0..20).associate { monday.plusDays(it.toLong()) to 3 }
        val bars = TrendBuckets.weekly(data, today)
        val charted = bars.sumOf { it.count }
        val available = data.filterKeys { !it.isAfter(today) }.values.sum()
        assertTrue("charted=$charted available=$available", charted <= available)
    }
}
