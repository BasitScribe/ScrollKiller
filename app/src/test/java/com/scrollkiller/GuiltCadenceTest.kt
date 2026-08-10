package com.scrollkiller

import com.scrollkiller.guilt.GuiltCadence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The nagging curve. Like [GuiltTierTest], these numbers ARE the product — "every 10 scrolls at
 * 100, every 5 from 320 up" is a design decision, so an off-by-one in a band edge changes what
 * the app is rather than how it is built.
 */
class GuiltCadenceTest {

    @Test
    fun `below the first schedule row there is no repeating cadence`() {
        // 50 and 70 fire on their TIER CROSSING and then go quiet. A repeating interval down
        // there would nag someone at 55 reels, which is the opposite of the whole design.
        listOf(0, 49, 50, 70, 99).forEach { count ->
            assertNull("expected no interval at $count", GuiltCadence.intervalFor(count))
        }
    }

    @Test
    fun `each band maps to its interval, at both edges`() {
        val table = listOf(
            100 to 10, 249 to 10,
            250 to 7, 319 to 7,
            320 to 5, 400 to 5, 500 to 5, 800 to 5, 5_000 to 5,
        )
        table.forEach { (count, expected) ->
            assertEquals("count $count", expected, GuiltCadence.intervalFor(count))
        }
    }

    @Test
    fun `the curve plateaus at the floor and never tightens past it (D48)`() {
        // The content bill, expressed as a cadence invariant. Every scroll above 320 costs the
        // pack a line, and the old 400/500/600/800 rows billed for content nobody can write —
        // 1,372 tier-4 lines for a 800/day user against a target of 150. A future row below the
        // floor would quietly reintroduce that, so it is asserted rather than trusted.
        (0..2_000 step 7).forEach { count ->
            val interval = GuiltCadence.intervalFor(count) ?: return@forEach
            assertTrue(
                "count $count fires every $interval scrolls, below the floor",
                interval >= GuiltCadence.FLOOR_SCROLLS,
            )
        }
        assertEquals(GuiltCadence.FLOOR_SCROLLS, GuiltCadence.SCHEDULE.last().everyScrolls)
        assertEquals(GuiltCadence.FLOOR_SCROLLS, GuiltCadence.intervalFor(100_000))
    }

    @Test
    fun `the schedule is a well-formed table`() {
        // Guards a future tuning edit: thresholds out of order would make intervalFor's
        // descending scan silently pick the wrong row, and a non-decreasing interval would mean
        // the app nags LESS as the count climbs.
        val steps = GuiltCadence.SCHEDULE
        assertEquals("declare the table ascending", steps.sortedBy { it.fromCount }, steps)
        assertEquals(steps.map { it.fromCount }.distinct(), steps.map { it.fromCount })
        steps.zipWithNext { lower, higher ->
            assertTrue(
                "interval must tighten as the count climbs: $lower then $higher",
                higher.everyScrolls < lower.everyScrolls,
            )
        }
        assertTrue("an interval below 1 would fire more than once per scroll",
            steps.all { it.everyScrolls >= 1 })
    }

    @Test
    fun `the display gap always outlasts the line it is gapping`() {
        // The invariant the gap exists for: a line gets its full readable life AND the bubble
        // becomes a counter again before the next one. Structural (MIN_GAP is derived from
        // DISPLAY_MS), but asserted so a future edit that inlines a literal is caught.
        assertTrue(GuiltCadence.MIN_GAP_MS > GuiltCadence.DISPLAY_MS)
        assertEquals(
            GuiltCadence.DISPLAY_MS + GuiltCadence.COUNT_VISIBLE_MS,
            GuiltCadence.MIN_GAP_MS,
        )
    }

    @Test
    fun `a line stays up long enough to actually be read`() {
        // Five days of real use found the original 4s collapsed the pill mid-sentence, which is
        // the feature failing rather than a taste complaint: an unread line was never shown, and
        // the pack, the tiers and the no-repeat rotation all exist to serve a line someone reads.
        //
        // The floor is deliberately below the shipped 6.5s — this pins the LESSON, not the tuning.
        // A future edit chasing frequency may lower the display, but not back under the point
        // where the pack's longer lines stop being finishable (D83).
        assertTrue(
            "DISPLAY_MS=${GuiltCadence.DISPLAY_MS} is too short to finish a long line",
            GuiltCadence.DISPLAY_MS >= 6_000L,
        )
    }
}
