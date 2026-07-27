package com.scrollkiller

import com.scrollkiller.service.BlockLimits
import com.scrollkiller.service.BlockPolicy
import com.scrollkiller.service.SurfaceOverlay
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards the bubble-vs-block decision — the one function that decides whether the app covers
 * someone else's screen.
 *
 * Two properties matter more than the arithmetic. The "never the feed" safety gate: the block must
 * not fire while surface gating is inactive, whatever the count says (D19/D24/D32). And the
 * reprieve: "5 more minutes" must genuinely suppress the block for exactly that long (D49).
 */
class BlockPolicyTest {

    private val limit = 100

    /** A wall-clock instant. The value is arbitrary; only the deltas against it matter. */
    private val now = 1_800_000_000_000L

    private fun overlay(
        count: Int,
        gatingActive: Boolean = true,
        graceUntilMs: Long = 0L,
        nowMs: Long = now,
    ) = BlockPolicy.overlayFor(count, limit, gatingActive, graceUntilMs, nowMs)

    @Test
    fun `below limit shows the bubble`() {
        assertEquals(SurfaceOverlay.BUBBLE, overlay(count = 99))
    }

    @Test
    fun `at or over limit with gating active shows the block`() {
        assertEquals(SurfaceOverlay.BLOCK, overlay(count = 100))
        assertEquals(SurfaceOverlay.BLOCK, overlay(count = 500))
    }

    @Test
    fun `over limit but gating inactive stays on the bubble (never the feed)`() {
        // The gate that has kept the block dormant since D19. A platform whose surface markers
        // are unverified, or whose count we have admitted is wrong, must never cover a screen —
        // no count is high enough to buy past this.
        assertEquals(SurfaceOverlay.BUBBLE, overlay(count = 500, gatingActive = false))
        assertEquals(
            SurfaceOverlay.BUBBLE,
            overlay(count = 5_000, gatingActive = false, graceUntilMs = 0L),
        )
    }

    /* --- the reprieve (D49) -------------------------------------------------------------- */

    @Test
    fun `inside the grace window the block stays down`() {
        val granted = now + BlockLimits.GRACE_MS
        assertEquals(SurfaceOverlay.BUBBLE, overlay(count = 500, graceUntilMs = granted))
        // Still down a minute in, and still down a millisecond before it lapses.
        assertEquals(
            SurfaceOverlay.BUBBLE,
            overlay(count = 500, graceUntilMs = granted, nowMs = now + 60_000L),
        )
        assertEquals(
            SurfaceOverlay.BUBBLE,
            overlay(count = 500, graceUntilMs = granted, nowMs = granted - 1),
        )
    }

    @Test
    fun `the boundary belongs to the block — five minutes means five`() {
        // At exactly the deadline the reprieve is over. Asserted because an off-by-one the other
        // way would be a promise the app quietly extends, and the next count emission after this
        // instant is what re-blocks (there is no timer — see OverlayController.onSnooze).
        val granted = now + BlockLimits.GRACE_MS
        assertEquals(SurfaceOverlay.BLOCK, overlay(count = 500, graceUntilMs = granted, nowMs = granted))
    }

    @Test
    fun `an expired grace re-blocks`() {
        val stale = now - 1_000L
        assertEquals(SurfaceOverlay.BLOCK, overlay(count = 500, graceUntilMs = stale))
    }

    @Test
    fun `a grace does not lower the limit`() {
        // Under the limit is a bubble whether or not a reprieve happens to be running. The two
        // conditions are independent, and a stale deadline must not make an under-limit count
        // behave any differently.
        assertEquals(
            SurfaceOverlay.BUBBLE,
            overlay(count = 20, graceUntilMs = now + BlockLimits.GRACE_MS),
        )
        assertEquals(SurfaceOverlay.BUBBLE, overlay(count = 20, graceUntilMs = now - 1))
    }

    @Test
    fun `a grace can never resurrect a blocked platform's gate`() {
        // Belt and braces on the ordering of the conditions: no combination of grace and count
        // turns an ineligible platform into a blocking one.
        listOf(0L, now - 1, now + BlockLimits.GRACE_MS).forEach { grace ->
            assertEquals(
                "gatingActive=false must win at grace=$grace",
                SurfaceOverlay.BUBBLE,
                overlay(count = 1_000, gatingActive = false, graceUntilMs = grace),
            )
        }
    }

    @Test
    fun `inGrace is the whole reprieve predicate`() {
        assertEquals(true, BlockPolicy.inGrace(now + 1, now))
        assertEquals(false, BlockPolicy.inGrace(now, now))
        assertEquals(false, BlockPolicy.inGrace(now - 1, now))
        // 0 is "never granted", and it must read as expired rather than needing its own flag.
        assertEquals(false, BlockPolicy.inGrace(0L, now))
    }
}
