package com.scrollkiller

import com.scrollkiller.service.BlockPolicy
import com.scrollkiller.service.SurfaceOverlay
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards the bubble-vs-block decision, including the "never the feed" safety gate: the
 * block must not fire while surface gating is inactive (empty markers).
 */
class BlockPolicyTest {

    private val limit = 100

    @Test
    fun `below limit shows the bubble`() {
        assertEquals(
            SurfaceOverlay.BUBBLE,
            BlockPolicy.overlayFor(count = 99, limit = limit, gatingActive = true, unlocked = false),
        )
    }

    @Test
    fun `at or over limit with gating active shows the block`() {
        assertEquals(
            SurfaceOverlay.BLOCK,
            BlockPolicy.overlayFor(count = 100, limit = limit, gatingActive = true, unlocked = false),
        )
        assertEquals(
            SurfaceOverlay.BLOCK,
            BlockPolicy.overlayFor(count = 500, limit = limit, gatingActive = true, unlocked = false),
        )
    }

    @Test
    fun `over limit but gating inactive stays on the bubble (never the feed)`() {
        assertEquals(
            SurfaceOverlay.BUBBLE,
            BlockPolicy.overlayFor(count = 500, limit = limit, gatingActive = false, unlocked = false),
        )
    }

    @Test
    fun `over limit but unlocked stays on the bubble`() {
        assertEquals(
            SurfaceOverlay.BUBBLE,
            BlockPolicy.overlayFor(count = 500, limit = limit, gatingActive = true, unlocked = true),
        )
    }
}
