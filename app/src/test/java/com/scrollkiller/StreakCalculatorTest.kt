package com.scrollkiller

import com.scrollkiller.stats.StreakCalculator
import com.scrollkiller.stats.Streaks
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * Streak arithmetic (D81). Pure, so every case here runs off-device with a pinned "today".
 *
 * The two decisions worth guarding are the ones a reasonable person could implement the other way:
 * a day with NO row counts as under the limit, and today is judged on where it currently stands
 * rather than waiting for midnight.
 */
class StreakCalculatorTest {

    private val limit = 100
    private val today: LocalDate = LocalDate.of(2026, 7, 29)

    private fun days(vararg totals: Int): Map<LocalDate, Int> {
        // Oldest first; the last entry is today.
        val start = today.minusDays((totals.size - 1).toLong())
        return totals.withIndex().associate { (i, t) -> start.plusDays(i.toLong()) to t }
    }

    private fun streaks(totals: Map<LocalDate, Int>, from: LocalDate = today.minusDays(29)) =
        StreakCalculator.of(totals, limit, today, from)

    @Test
    fun `no data at all is a clean slate, not a streak`() {
        // Every day in the window is a missing row, so every day is under the limit. The user has
        // genuinely not been over — but "best 30" for someone who just installed the app would be
        // absurd, so the window start is what bounds it and the caller chooses that.
        val s = streaks(emptyMap(), from = today)
        assertEquals(Streaks(current = 1, best = 1), s)
    }

    @Test
    fun `consecutive under-limit days accumulate`() {
        assertEquals(4, streaks(days(10, 20, 30, 40), from = today.minusDays(3)).current)
    }

    @Test
    fun `a day at the limit BREAKS the streak, matching BlockPolicy`() {
        // The block fires at `count >= limit`, so the day you hit exactly 100 is the day you got
        // blocked. Calling it a success would make the streak disagree with the user's experience.
        val s = streaks(days(10, limit, 10), from = today.minusDays(2))
        assertEquals("today is under, so the current run is just today", 1, s.current)
        assertEquals(1, s.best)
    }

    @Test
    fun `one under the limit still counts`() {
        assertEquals(3, streaks(days(limit - 1, limit - 1, limit - 1), from = today.minusDays(2)).current)
    }

    @Test
    fun `a missing day counts as under, because zero scrolls is not a failure`() {
        // Phone off, holiday, app unused. Punishing that would invert the point of the number.
        val sparse = mapOf(
            today.minusDays(3) to 10,
            // two days absent entirely
            today to 20,
        )
        assertEquals(4, streaks(sparse, from = today.minusDays(3)).current)
    }

    @Test
    fun `today over the limit zeroes the current streak immediately`() {
        // Honest: the user blew it, and the screen should not claim otherwise until midnight.
        val s = streaks(days(10, 10, 10, limit + 50), from = today.minusDays(3))
        assertEquals(0, s.current)
        assertEquals("the three good days before it are still the best run", 3, s.best)
    }

    @Test
    fun `best survives a broken current streak`() {
        val s = streaks(days(1, 1, 1, 1, 1, 999, 1), from = today.minusDays(6))
        assertEquals(1, s.current)
        assertEquals(5, s.best)
    }

    @Test
    fun `best is the longest run anywhere in the window, not the most recent`() {
        val s = streaks(days(1, 1, 1, 1, 999, 1, 1), from = today.minusDays(6))
        assertEquals(2, s.current)
        assertEquals(4, s.best)
    }

    @Test
    fun `current never exceeds best`() {
        // An invariant of the definitions rather than of any one case: the current run is itself a
        // run, so `best` has seen it.
        listOf(
            days(1, 1, 1),
            days(999, 1, 1),
            days(1, 999, 1),
            days(999, 999, 999),
        ).forEach { totals ->
            val s = streaks(totals, from = today.minusDays(2))
            assertEquals("current > best for $totals", true, s.current <= s.best)
        }
    }

    @Test
    fun `the window bounds the answer, so a 7-day view cannot report a 30-day best`() {
        val allGood = (0..29).associate { today.minusDays(it.toLong()) to 1 }
        assertEquals(7, streaks(allGood, from = today.minusDays(6)).best)
        assertEquals(30, streaks(allGood, from = today.minusDays(29)).best)
    }

    @Test
    fun `a window starting after today is empty rather than an exception`() {
        assertEquals(Streaks.NONE, streaks(days(1, 1), from = today.plusDays(1)))
    }

    @Test
    fun `days after today are ignored`() {
        // A clock change or a restored backup can leave a row dated in the future. It must not be
        // able to extend a streak the user has not lived yet.
        val withFuture = mapOf(
            today to 1,
            today.plusDays(1) to 1,
            today.plusDays(2) to 1,
        )
        assertEquals(1, streaks(withFuture, from = today).current)
        assertEquals(1, streaks(withFuture, from = today).best)
    }
}
