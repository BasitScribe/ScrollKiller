package com.scrollkiller

import com.scrollkiller.service.BlockLimits
import com.scrollkiller.service.BlockPolicy
import com.scrollkiller.service.PlatformRegistry
import com.scrollkiller.service.SurfaceOverlay
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards the bubble-vs-block decision — the one function that decides whether the app covers
 * someone else's screen.
 *
 * Two properties matter more than the arithmetic. The "never the feed" safety gate: the block must
 * not fire while surface gating is inactive, whatever the count says (D19/D24/D32). And the
 * reprieve: a completed challenge must genuinely suppress the block for exactly that long (D49,
 * and since D74 it is the only reprieve there is).
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

    /* --- the limiter, per platform (D73) --------------------------------------------------- */

    @Test
    fun `every blocking platform's limiter fires at its own limit, on its own count`() {
        // Written when YouTube became the second platform allowed to block (D73). The limiter is
        // one shared function, so the risk in adding a platform was never the arithmetic — it was
        // whether the OVERLAY hands it the right pair. OverlayController.render reads
        // `summary.countFor(platform)` against `SettingsPrefs.dailyLimit(context, platform)`, and
        // both are per-platform; this pins the consequence, which is that one platform's counts can
        // never spend another's allowance.
        //
        // The arrangement below is the one that would catch the mistake: two platforms with
        // DIFFERENT limits, each just under and just over its own.
        PlatformRegistry.enabled.filter { it.blocksAtLimit }.forEach { spec ->
            val own = spec.dailyLimit
            assertEquals(
                "${spec.platform} must not block one short of its own limit ($own)",
                SurfaceOverlay.BUBBLE,
                BlockPolicy.overlayFor(own - 1, own, spec.blocksAtLimit, 0L, now),
            )
            assertEquals(
                "${spec.platform} must block AT its own limit ($own)",
                SurfaceOverlay.BLOCK,
                BlockPolicy.overlayFor(own, own, spec.blocksAtLimit, 0L, now),
            )
        }
    }

    @Test
    fun `a non-blocking platform's count never raises a block, however far past the limit`() {
        // The other half: TikTok and Snapchat count and display, and no number they produce may
        // reach the screen. Driven off the registry rather than a hardcoded list so promoting one
        // of them without meaning to fails here.
        PlatformRegistry.enabled.filterNot { it.blocksAtLimit }.forEach { spec ->
            assertEquals(
                "${spec.platform} may not block at any count",
                SurfaceOverlay.BUBBLE,
                BlockPolicy.overlayFor(10_000, spec.dailyLimit, spec.blocksAtLimit, 0L, now),
            )
        }
    }

    /* --- the reprieve (D49) -------------------------------------------------------------- */

    @Test
    fun `inside the grace window the block stays down`() {
        val granted = now + BlockLimits.CHALLENGE_GRACE_MS
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
    fun `the boundary belongs to the block — fifteen minutes means fifteen`() {
        // At exactly the deadline the reprieve is over. Asserted because an off-by-one the other
        // way would be a promise the app quietly extends, and the next count emission after this
        // instant is what re-blocks (there is no timer — see OverlayController.onChallengeComplete).
        val granted = now + BlockLimits.CHALLENGE_GRACE_MS
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
            overlay(count = 20, graceUntilMs = now + BlockLimits.CHALLENGE_GRACE_MS),
        )
        assertEquals(SurfaceOverlay.BUBBLE, overlay(count = 20, graceUntilMs = now - 1))
    }

    @Test
    fun `a grace can never resurrect a blocked platform's gate`() {
        // Belt and braces on the ordering of the conditions: no combination of grace and count
        // turns an ineligible platform into a blocking one.
        listOf(0L, now - 1, now + BlockLimits.CHALLENGE_GRACE_MS).forEach { grace ->
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
