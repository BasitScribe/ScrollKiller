package com.scrollkiller

import com.scrollkiller.guilt.GuiltCadence
import com.scrollkiller.guilt.GuiltFiring
import com.scrollkiller.guilt.GuiltTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WHEN a line fires. The subtle half of the cadence — the schedule itself is a lookup table,
 * whereas this is stateful and every one of its edges (baselining, pending suppression, the day
 * rollover, a data clear) is a place the app could silently start nagging wrongly.
 *
 * [feed] walks a real scrolling session one count at a time and returns the counts that fired,
 * because that is how the feature is actually experienced: a sequence, not a single call.
 */
class GuiltFiringTest {

    private val day = "2026-07-27"

    /** Time far enough past that the display gap never interferes. */
    private fun unhurried(step: Int) = 1_000L + step * (GuiltCadence.MIN_GAP_MS + 1)

    /**
     * Scroll from [from] to [to] one count at a time, unhurried, and return the counts at which
     * a line fired.
     */
    private fun feed(firing: GuiltFiring, from: Int, to: Int, dayKey: String = day): List<Int> {
        val fired = mutableListOf<Int>()
        var step = 0
        for (count in from..to) {
            step++
            if (firing.onCount(count, GuiltTier.forCount(count), dayKey, unhurried(step))) {
                fired += count
            }
        }
        return fired
    }

    /* --- baselining ------------------------------------------------------------------ */

    @Test
    fun `the first observation never fires`() {
        // Walking into Reels at 300 must not greet you with a line, or tabbing in and out would
        // be a slot machine. The baseline is what makes the cadence about SCROLLING.
        val firing = GuiltFiring()
        assertFalse(firing.onCount(300, GuiltTier.EXTREME, day, 1_000L))
    }

    @Test
    fun `re-entering a surface at an unchanged count stays silent`() {
        val firing = GuiltFiring()
        firing.onCount(300, GuiltTier.EXTREME, day, 1_000L)
        repeat(5) { i ->
            assertFalse(
                "arrival ${i + 1} fired without a single new scroll",
                firing.onCount(300, GuiltTier.EXTREME, day, 100_000L + i * 10_000L),
            )
        }
    }

    /* --- tier crossings (50 and 70) --------------------------------------------------- */

    @Test
    fun `50 and 70 fire once each, and nothing in between`() {
        val firing = GuiltFiring()
        assertEquals(listOf(50, 70), feed(firing, from = 40, to = 99))
    }

    @Test
    fun `crossing 100 and 150 announce themselves rather than waiting out an interval`() {
        // 100 is both a tier crossing and the start of the schedule; 150 is a tier crossing in
        // the middle of the every-10 band. Both must fire ON the crossing.
        val firing = GuiltFiring()
        val fired = feed(firing, from = 95, to = 155)
        assertTrue("100 did not fire", 100 in fired)
        assertTrue("150 did not fire", 150 in fired)
    }

    @Test
    fun `a de-escalation never fires`() {
        val firing = GuiltFiring()
        firing.onCount(200, GuiltTier.EXTREME, day, 1_000L)
        // Count can't fall within a day except via a clear, but the guard is on the tier LEVEL
        // rather than on inequality of enum entries, so assert the intent directly.
        assertFalse(firing.onCount(200, GuiltTier.MILD, day, 999_000L))
    }

    /* --- the repeating schedule -------------------------------------------------------- */

    @Test
    fun `every 10 scrolls from 100`() {
        val firing = GuiltFiring()
        firing.onCount(100, GuiltTier.STRONG, day, 1_000L)          // baseline at 100
        assertEquals(listOf(110, 120, 130, 140), feed(firing, from = 101, to = 149))
    }

    @Test
    fun `the cadence tightens as the count climbs, band by band`() {
        val bands = listOf(
            Triple(250, 7, listOf(257, 264, 271)),
            Triple(320, 5, listOf(325, 330, 335)),
        )
        bands.forEach { (start, interval, expected) ->
            val firing = GuiltFiring()
            firing.onCount(start, GuiltTier.forCount(start), day, 1_000L)   // baseline
            val fired = feed(firing, from = start + 1, to = expected.last())
            assertEquals("every $interval from $start", expected, fired)
        }
    }

    @Test
    fun `still every 5 at 800, because the curve plateaus (D48)`() {
        // The schedule used to fire on EVERY scroll up here, which billed the pack ~1,372 tier-4
        // lines a week for a heavy user. It plateaus at the floor instead; the app is still
        // relentless at 800 but it asks for content that can actually be written.
        val firing = GuiltFiring()
        firing.onCount(800, GuiltTier.EXTREME, day, 1_000L)          // baseline
        assertEquals(listOf(805, 810, 815, 820), feed(firing, from = 801, to = 824))
    }

    /* --- the display gap ---------------------------------------------------------------- */

    @Test
    fun `a burst cannot spam faster than the display gap`() {
        // At the top of the curve a scroll is eligible every 5, and a fast scroller covers five
        // scrolls in well under two seconds — so the gap still binds after D48 flattened the
        // schedule. It is the hard ceiling: without it each line would be overwritten before it
        // could be read and the pill would never collapse back to being a counter.
        val firing = GuiltFiring()
        var now = 1_000L
        firing.onCount(800, GuiltTier.EXTREME, day, now)              // baseline
        var fired = 0
        // 60 scrolls, one every 300ms — about as fast as a human can flick, for 18s.
        (801..860).forEach { count ->
            now += 300L
            if (firing.onCount(count, GuiltTier.EXTREME, day, now)) fired++
        }
        val elapsed = now - 1_000L
        assertTrue(
            "fired $fired times in ${elapsed}ms — faster than one per ${GuiltCadence.MIN_GAP_MS}ms",
            fired <= elapsed / GuiltCadence.MIN_GAP_MS + 1,
        )
        assertTrue("the gap suppressed everything; it should still fire sometimes", fired >= 1)
    }

    @Test
    fun `a gapped fire is PENDING, not lost`() {
        // Dropping a suppressed fire would mean the faster you scroll the fewer lines you see —
        // exactly backwards. It must go out on the first emission after the gap elapses.
        val firing = GuiltFiring()
        firing.onCount(800, GuiltTier.EXTREME, day, 1_000L)           // baseline
        assertTrue(firing.onCount(805, GuiltTier.EXTREME, day, 2_000L))
        assertFalse("too soon", firing.onCount(810, GuiltTier.EXTREME, day, 3_000L))
        assertFalse("still too soon", firing.onCount(815, GuiltTier.EXTREME, day, 4_000L))
        assertTrue(
            "the suppressed fire was dropped instead of held",
            firing.onCount(820, GuiltTier.EXTREME, day, 2_000L + GuiltCadence.MIN_GAP_MS),
        )
    }

    @Test
    fun `a tier crossing is gapped too, and still lands afterwards`() {
        // A crossing is a shown line like any other, so it obeys the gap — but it must not be
        // swallowed, which is why lastFiredTier is only advanced on an actual fire.
        val firing = GuiltFiring()
        firing.onCount(138, GuiltTier.STRONG, day, 1_000L)            // baseline
        assertTrue(firing.onCount(148, GuiltTier.STRONG, day, 2_000L))    // due, every 10
        assertFalse(firing.onCount(150, GuiltTier.EXTREME, day, 2_500L))  // crossing, gapped
        assertTrue(
            "the tier crossing was lost to the gap",
            firing.onCount(151, GuiltTier.EXTREME, day, 2_000L + GuiltCadence.MIN_GAP_MS),
        )
    }

    /* --- rollovers and clears ----------------------------------------------------------- */

    @Test
    fun `a day rollover baselines with the count`() {
        val firing = GuiltFiring()
        firing.onCount(500, GuiltTier.EXTREME, day, 1_000L)
        assertTrue(firing.onCount(505, GuiltTier.EXTREME, day, 100_000L))

        // New day. The count resets with it in the repository; the cadence must reset too, and
        // must not fire on the first observation of the new day.
        assertFalse(firing.onCount(0, null, "2026-07-28", 200_000L))
        assertEquals(listOf(50, 70), feed(firing, from = 1, to = 99, dayKey = "2026-07-28"))
    }

    @Test
    fun `clearing data mid-day re-baselines instead of firing a backlog`() {
        // Without this, the next scroll after a clear reads as "the count jumped by 500" and
        // fires immediately, at a tier the user is no longer in.
        val firing = GuiltFiring()
        firing.onCount(500, GuiltTier.EXTREME, day, 1_000L)
        assertFalse("the clear itself must not fire", firing.onCount(0, null, day, 100_000L))
        assertFalse("nor must the first scroll after it", firing.onCount(1, null, day, 200_000L))
        assertEquals(listOf(50), feed(firing, from = 2, to = 60))
    }

    @Test
    fun `dropping below 50 lets the crossing fire again later`() {
        val firing = GuiltFiring()
        assertFalse(firing.onCount(40, null, day, 1_000L))
        assertTrue(firing.onCount(50, GuiltTier.MILD, day, 100_000L))
        assertFalse(firing.onCount(0, null, day, 200_000L))            // cleared
        assertTrue(
            "re-crossing 50 must fire again",
            firing.onCount(50, GuiltTier.MILD, day, 300_000L),
        )
    }

    @Test
    fun `reset returns it to a fresh state`() {
        val firing = GuiltFiring()
        firing.onCount(500, GuiltTier.EXTREME, day, 1_000L)
        firing.reset()
        assertFalse("the first call after a reset baselines", firing.onCount(505, GuiltTier.EXTREME, day, 2_000L))
    }
}
