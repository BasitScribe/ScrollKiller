package com.scrollkiller

import com.scrollkiller.stats.TimeEstimate
import org.junit.Assert.assertEquals
import org.junit.Test

/** The "~N mins" motivator on the Today tab. Pure arithmetic, so worth pinning. */
class TimeEstimateTest {

    @Test
    fun `zero scrolls is zero minutes`() {
        assertEquals(0.0, TimeEstimate.minutes(0), 0.0001)
        assertEquals("0.0", TimeEstimate.minutesLabel(0))
    }

    @Test
    fun `48 reels at 6s each is 4-point-8 minutes`() {
        assertEquals(4.8, TimeEstimate.minutes(48), 0.0001)
        assertEquals("4.8", TimeEstimate.minutesLabel(48))
    }

    @Test
    fun `label rounds to one decimal`() {
        // 25 * 6 = 150s = 2.5 min
        assertEquals("2.5", TimeEstimate.minutesLabel(25))
        // 10 * 6 = 60s = 1.0 min
        assertEquals("1.0", TimeEstimate.minutesLabel(10))
    }

    @Test
    fun `custom seconds-per-item is honored`() {
        assertEquals(2.0, TimeEstimate.minutes(count = 12, secondsPerItem = 10), 0.0001)
    }
}
