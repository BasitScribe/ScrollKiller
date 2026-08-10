package com.scrollkiller

import com.scrollkiller.service.BlockLimits
import com.scrollkiller.service.BlockPolicy
import com.scrollkiller.service.SurfaceOverlay
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The reprieve is ONE thing, spent against ONE budget (D88).
 *
 * ## The bug this exists for, in the words it was reported in
 * *"I did exercise in Instagram but in YT shorts it is again blocking."*
 *
 * Exactly right, and it was a real hole. D76 made the daily limit GLOBAL — one budget for the whole
 * day across every blocking app — but the reprieve a completed challenge buys was still keyed by
 * platform. So the sequence was: hit the global limit inside Instagram, walk twenty steps, get let
 * out of Instagram, open YouTube Shorts, and the block is waiting immediately, because the global
 * count is still over the global limit and YouTube's own grace key is zero.
 *
 * The user paid once and was charged per app. That is incoherent with D76 (one budget), with D77
 * (a completed challenge is the ONLY way past a block, so being charged twice for one block is
 * being charged for something that does not exist), and with D83, which already charges escalation
 * across the whole challenge set precisely so the price cannot be dodged by switching.
 *
 * ## What this file can and cannot test
 * `BlockPolicy` is pure and takes `graceUntilMs` as a parameter, so the ARITHMETIC below is fully
 * testable here. Where the value comes from is `SettingsPrefs`, which needs a `Context` — so the
 * per-platform-key half is pinned by the device checklist (HANDOFF Run T) and by the fact that the
 * platform parameter no longer exists on `graceUntilMs`, which makes the old bug unwriteable rather
 * than merely untested. Deleting the parameter IS the fix; this file pins the behaviour it buys.
 */
class GraceIsGlobalTest {

    private val now = 1_700_000_000_000L

    /** The block decision for one app, given the ONE global count and the ONE global grace. */
    private fun overlayFor(globalCount: Int, graceUntilMs: Long, nowMs: Long = now) =
        BlockPolicy.overlayFor(
            count = globalCount,
            limit = 100,
            gatingActive = true,
            graceUntilMs = graceUntilMs,
            nowMs = nowMs,
        )

    @Test
    fun `the exercise done in one app gets you out of the other one too`() {
        // THE regression. Both calls below are the same two arguments — that is the entire point.
        // Instagram and YouTube ask the same question of the same numbers, so they cannot disagree.
        val earned = now + BlockLimits.CHALLENGE_GRACE_MS

        // Over the global limit, reprieve just earned. Instagram lets you through...
        assertEquals(SurfaceOverlay.BUBBLE, overlayFor(globalCount = 140, graceUntilMs = earned))
        // ...and so does YouTube, one second later, on the same reprieve.
        assertEquals(
            "a reprieve earned in one app must not be re-charged in another — this is D88",
            SurfaceOverlay.BUBBLE,
            overlayFor(globalCount = 141, graceUntilMs = earned, nowMs = now + 1_000L),
        )
    }

    @Test
    fun `with no reprieve both apps block, which is the point of a global budget`() {
        // The mirror image, and just as important: going global must not have made the block
        // LEAKY. Over the budget with nothing earned, every blocking app covers.
        assertEquals(SurfaceOverlay.BLOCK, overlayFor(globalCount = 140, graceUntilMs = 0L))
        assertEquals(
            SurfaceOverlay.BLOCK,
            overlayFor(globalCount = 141, graceUntilMs = 0L, nowMs = now + 1_000L),
        )
    }

    @Test
    fun `the reprieve expires everywhere at once`() {
        // One deadline means one expiry. The old shape could have Instagram's reprieve live while
        // YouTube's had lapsed, which from the user's side is the app changing its mind about
        // whether they had earned anything.
        val earned = now + BlockLimits.CHALLENGE_GRACE_MS
        assertEquals(SurfaceOverlay.BUBBLE, overlayFor(140, earned, nowMs = earned - 1))
        assertEquals(SurfaceOverlay.BLOCK, overlayFor(140, earned, nowMs = earned))
        assertEquals(SurfaceOverlay.BLOCK, overlayFor(140, earned, nowMs = earned + 1))
    }

    @Test
    fun `one challenge buys exactly one window, not one per app`() {
        // Fifteen minutes is fifteen minutes of DAY, not fifteen minutes multiplied by however many
        // blocking apps happen to be installed. Under the old shape, a user with Instagram and
        // YouTube could complete one challenge in each and hold thirty minutes of reprieve for two
        // challenges — but could equally be blocked in the second app having done one, which is the
        // half nobody would have reported as a feature.
        val earned = now + BlockLimits.CHALLENGE_GRACE_MS
        val stillInside = now + BlockLimits.CHALLENGE_GRACE_MS - 1
        val justOutside = now + BlockLimits.CHALLENGE_GRACE_MS + 1
        assertEquals(SurfaceOverlay.BUBBLE, overlayFor(140, earned, nowMs = stillInside))
        assertEquals(SurfaceOverlay.BLOCK, overlayFor(140, earned, nowMs = justOutside))
    }

    @Test
    fun `a reprieve still cannot cover a platform that is not cleared to block`() {
        // Unchanged by D88 and worth pinning beside it: gating is the one thing that stays
        // per-platform, because it answers "may we cover THIS screen" rather than "has the day's
        // budget run out". A SHADOW app shows the bubble whatever the grace says.
        listOf(0L, now - 1, now + BlockLimits.CHALLENGE_GRACE_MS).forEach { grace ->
            assertEquals(
                "gatingActive=false must win at grace=$grace",
                SurfaceOverlay.BUBBLE,
                BlockPolicy.overlayFor(
                    count = 5_000,
                    limit = 100,
                    gatingActive = false,
                    graceUntilMs = grace,
                    nowMs = now,
                ),
            )
        }
    }
}
