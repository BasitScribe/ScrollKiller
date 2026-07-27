package com.scrollkiller

import com.scrollkiller.guilt.GuiltThresholds
import com.scrollkiller.guilt.GuiltTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The escalation curve, boundary by boundary. These numbers are the product — "the app starts
 * talking at 50 and gets savage at 150" is a design decision, not an implementation detail, so
 * an accidental off-by-one here would change what the app IS.
 */
class GuiltTierTest {

    @Test
    fun `the app says nothing below the first threshold`() {
        // Not a gap. Fifty short videos is a normal amount of scrolling, and an app that
        // comments on it has cried wolf by the time the number matters.
        listOf(0, 1, 25, 49).forEach { count ->
            assertNull("expected silence at $count", GuiltTier.forCount(count))
        }
    }

    @Test
    fun `each band maps to its tier, at both edges`() {
        val table = listOf(
            50 to GuiltTier.MILD, 69 to GuiltTier.MILD,
            70 to GuiltTier.MEDIUM, 99 to GuiltTier.MEDIUM,
            100 to GuiltTier.STRONG, 149 to GuiltTier.STRONG,
            150 to GuiltTier.EXTREME, 5_000 to GuiltTier.EXTREME,
        )
        table.forEach { (count, expected) ->
            assertEquals("count $count", expected, GuiltTier.forCount(count))
        }
    }

    @Test
    fun `a negative count is silent rather than crashing`() {
        // Not reachable through the repository (counts are monotonic within a day), but this is
        // called from a render path in an accessibility service — it must be total.
        assertNull(GuiltTier.forCount(-1))
    }

    @Test
    fun `thresholds are strictly increasing and match the tiers`() {
        // Guards a future tuning edit that changes one constant and not the others: overlapping
        // or out-of-order thresholds would make forCount's descending scan return the wrong tier
        // silently rather than failing.
        val thresholds = GuiltTier.entries.map { it.minCount }
        assertEquals(thresholds.sorted(), thresholds)
        assertEquals(thresholds.distinct(), thresholds)
        assertEquals(
            listOf(
                GuiltThresholds.MILD_AT,
                GuiltThresholds.MEDIUM_AT,
                GuiltThresholds.STRONG_AT,
                GuiltThresholds.EXTREME_AT,
            ),
            thresholds,
        )
    }

    @Test
    fun `levels are 1 to 4 in order, and cover exactly their own intensity`() {
        assertEquals(listOf(1, 2, 3, 4), GuiltTier.entries.map { it.level })
        assertEquals(1..4, GuiltTier.INTENSITY_RANGE)
        GuiltTier.entries.forEach { tier ->
            assertTrue(tier.covers(tier.level))
            assertFalse("$tier must not accept a harsher line", tier.covers(tier.level + 1))
        }
    }
}
