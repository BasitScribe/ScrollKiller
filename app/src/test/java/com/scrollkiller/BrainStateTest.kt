package com.scrollkiller

import com.scrollkiller.brain.BrainState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards the brain-state thresholds so a future tweak to the constants can't silently
 * shift the arc. Boundary-focused: the exact edges are what matter.
 */
class BrainStateTest {

    @Test
    fun `below cracking threshold is healthy`() {
        assertEquals(BrainState.HEALTHY, BrainState.forCount(0))
        assertEquals(BrainState.HEALTHY, BrainState.forCount(49))
    }

    @Test
    fun `cracking threshold is inclusive`() {
        assertEquals(BrainState.CRACKING, BrainState.forCount(50))
        assertEquals(BrainState.CRACKING, BrainState.forCount(149))
    }

    @Test
    fun `fried threshold is inclusive`() {
        assertEquals(BrainState.FRIED, BrainState.forCount(150))
        assertEquals(BrainState.FRIED, BrainState.forCount(10_000))
    }

    @Test
    fun `thresholds match documented values`() {
        assertEquals(50, BrainState.CRACKING_AT)
        assertEquals(150, BrainState.FRIED_AT)
    }
}
