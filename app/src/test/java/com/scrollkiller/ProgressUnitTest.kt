package com.scrollkiller

import com.scrollkiller.challenge.ProgressUnit
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The ring's centre label. Trivial arithmetic, but it is what the user reads mid-challenge and it
 * lives here rather than in the view precisely so it can be pinned without a device.
 */
class ProgressUnitTest {

    @Test
    fun `a count reads as progress over target`() {
        assertEquals("0 / 20", ProgressUnit.COUNT.ringLabel(0, 20))
        assertEquals("7 / 20", ProgressUnit.COUNT.ringLabel(7, 20))
        assertEquals("20 / 20", ProgressUnit.COUNT.ringLabel(20, 20))
    }

    @Test
    fun `seconds count DOWN, because remaining is the only question mid-hold`() {
        assertEquals("30s", ProgressUnit.SECONDS.ringLabel(0, 30))
        assertEquals("18s", ProgressUnit.SECONDS.ringLabel(12, 30))
        assertEquals("1s", ProgressUnit.SECONDS.ringLabel(29, 30))
    }

    @Test
    fun `a completed hold reads zero, never a negative`() {
        // ChallengeProgress clamps, so this should not arrive — but the label is drawn over another
        // app and "-3s" would be a visible bug in the one moment the user is paying attention.
        assertEquals("0s", ProgressUnit.SECONDS.ringLabel(30, 30))
        assertEquals("0s", ProgressUnit.SECONDS.ringLabel(33, 30))
    }
}
