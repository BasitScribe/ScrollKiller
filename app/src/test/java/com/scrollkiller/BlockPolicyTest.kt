package com.scrollkiller

import com.scrollkiller.service.BlockLimits
import com.scrollkiller.service.BlockPolicy
import com.scrollkiller.service.PlatformRegistry
import com.scrollkiller.service.SurfaceOverlay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
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

    /* --- the ONE limiter, across all blocking platforms (D76) ------------------------------ */

    @Test
    fun `the limiter fires at the shared limit, on the combined blocking count`() {
        // D76 replaced the per-platform limiter with one budget. The arithmetic is unchanged; what
        // changed is WHICH number the overlay hands in — `BlockPolicy.blockingTotal(perPlatform)`
        // against one `SettingsPrefs.dailyLimit(context)`. This pins the boundary itself.
        val limit = 100
        assertEquals(
            "must not block one short of the shared limit",
            SurfaceOverlay.BUBBLE,
            BlockPolicy.overlayFor(limit - 1, limit, true, 0L, now),
        )
        assertEquals(
            "must block AT the shared limit",
            SurfaceOverlay.BLOCK,
            BlockPolicy.overlayFor(limit, limit, true, 0L, now),
        )
    }

    @Test
    fun `counts from different blocking platforms spend ONE shared budget`() {
        // The whole point of D76, and the case the old per-platform shape got wrong: 60 reels then
        // 45 Shorts is 105 short videos and used to block at NEITHER, because each app measured
        // only itself. Built from the registry so it keeps meaning something as platforms change.
        val blocking = PlatformRegistry.enabled.filter { it.blocksAtLimit }
        assumeTrue("needs at least two blocking platforms to be meaningful", blocking.size >= 2)

        val limit = 100
        val split = blocking.associate { it.platform to limit / blocking.size + 1 }
        val total = BlockPolicy.blockingTotal(split)

        assertTrue("the split must exceed the shared limit to test anything", total >= limit)
        split.values.forEach { each ->
            assertTrue("each platform alone must stay UNDER the limit, or this proves nothing", each < limit)
        }
        assertEquals(
            "the combined total must block even though no single platform reached the limit",
            SurfaceOverlay.BLOCK,
            BlockPolicy.overlayFor(total, limit, true, 0L, now),
        )
    }

    @Test
    fun `a SHADOW platform's count never contributes to the budget`() {
        // The safety half of D76. TikTok and Snapchat are counted for display but are NOT cleared
        // to enforce, and Snapchat is a known OVERcount (it counts Chat/Stories/Map scrolls as
        // "snaps" — D32). Letting those numbers spend the budget would block someone out of
        // Instagram because they scrolled their Snapchat inbox.
        val nonBlocking = PlatformRegistry.enabled.filterNot { it.blocksAtLimit }
        assumeTrue("needs a non-blocking platform", nonBlocking.isNotEmpty())

        val huge = nonBlocking.associate { it.platform to 10_000 }
        assertEquals(
            "no SHADOW/BETA platform may add a single item to the limiter's input",
            0,
            BlockPolicy.blockingTotal(huge),
        )
    }

    @Test
    fun `blockingTotal ignores platforms it does not recognise`() {
        assertEquals(0, BlockPolicy.blockingTotal(emptyMap()))
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
