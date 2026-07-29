package com.scrollkiller

import com.scrollkiller.service.BlockLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The numbers the block is tuned by. Small surface, but [BlockLimits.clampLimit] is the last
 * thing standing between a nonsense preference value and a screen that covers itself at 0 reels
 * — a limit can arrive from a restored backup or from a build whose range differed.
 */
class BlockLimitsTest {

    @Test
    fun `a value in range and on step survives untouched`() {
        listOf(20, 50, 100, 210, 300).forEach {
            assertEquals("limit $it is already legal", it, BlockLimits.clampLimit(it))
        }
    }

    @Test
    fun `out of range clamps to the ends, not to zero or to the default`() {
        assertEquals(BlockLimits.MIN_DAILY_LIMIT, BlockLimits.clampLimit(0))
        assertEquals(BlockLimits.MIN_DAILY_LIMIT, BlockLimits.clampLimit(-500))
        assertEquals(BlockLimits.MIN_DAILY_LIMIT, BlockLimits.clampLimit(19))
        assertEquals(BlockLimits.MAX_DAILY_LIMIT, BlockLimits.clampLimit(301))
        // The overflow case, and the reason clampLimit coerces BEFORE it snaps: adding half a
        // step to Int.MAX_VALUE wraps negative, and the naive order returned the MINIMUM limit —
        // silently blocking someone 280 reels earlier than they asked to be.
        assertEquals(BlockLimits.MAX_DAILY_LIMIT, BlockLimits.clampLimit(Int.MAX_VALUE))
        assertEquals(BlockLimits.MIN_DAILY_LIMIT, BlockLimits.clampLimit(Int.MIN_VALUE))
    }

    @Test
    fun `off-step values snap to the nearest stop`() {
        assertEquals(100, BlockLimits.clampLimit(97))
        assertEquals(100, BlockLimits.clampLimit(104))
        assertEquals(110, BlockLimits.clampLimit(105))   // halfway rounds up
        assertEquals(90, BlockLimits.clampLimit(94))
    }

    @Test
    fun `clamping is idempotent`() {
        // The value is clamped on the way into storage AND on the way out, so a second pass must
        // never move it again — otherwise a limit would drift every time Settings was opened.
        (-50..350 step 7).forEach { raw ->
            val once = BlockLimits.clampLimit(raw)
            assertEquals("clamping $raw twice moved it", once, BlockLimits.clampLimit(once))
        }
    }

    @Test
    fun `every clamped value is a legal slider stop`() {
        (-50..350 step 3).forEach { raw ->
            val limit = BlockLimits.clampLimit(raw)
            assertTrue("$raw clamped to $limit, out of range", limit in BlockLimits.MIN_DAILY_LIMIT..BlockLimits.MAX_DAILY_LIMIT)
            assertEquals("$raw clamped to $limit, off step", 0, limit % BlockLimits.LIMIT_STEP)
        }
    }

    @Test
    fun `the default limit is itself a legal limit`() {
        // It is what an untouched preference falls back to, so if it were out of range or off
        // step the very first read would silently move it.
        assertEquals(BlockLimits.DEFAULT_DAILY_LIMIT, BlockLimits.clampLimit(BlockLimits.DEFAULT_DAILY_LIMIT))
    }

    @Test
    fun `the range is well formed and the slider can express it`() {
        assertTrue(BlockLimits.MIN_DAILY_LIMIT < BlockLimits.MAX_DAILY_LIMIT)
        assertEquals(0, BlockLimits.MIN_DAILY_LIMIT % BlockLimits.LIMIT_STEP)
        assertEquals(0, BlockLimits.MAX_DAILY_LIMIT % BlockLimits.LIMIT_STEP)
        assertEquals(
            "the range must divide evenly by the step, or the slider's top stop is not the max",
            0,
            (BlockLimits.MAX_DAILY_LIMIT - BlockLimits.MIN_DAILY_LIMIT) % BlockLimits.LIMIT_STEP,
        )
    }

    @Test
    fun `the grace duration derives from the minutes on the button`() {
        // The button's text is formatted from CHALLENGE_GRACE_MINUTES and the reprieve is granted
        // from CHALLENGE_GRACE_MS. If these ever stopped agreeing, the app would promise one thing
        // and do another — the exact failure this derivation exists to make impossible.
        assertEquals(BlockLimits.CHALLENGE_GRACE_MINUTES * 60_000L, BlockLimits.CHALLENGE_GRACE_MS)
        assertTrue(
            "a zero-length reprieve is not a reprieve",
            BlockLimits.CHALLENGE_GRACE_MINUTES > 0,
        )
    }

    @Test
    fun `the earned reprieve is the only one, and it stays bounded (D77)`() {
        // D50 asserted an inequality here — challenge grace had to exceed the free tap's five
        // minutes, or nobody would walk twenty steps for what one tap gave for nothing. D77 deleted
        // the free tap for good (strict mode), so the thing that could dominate the challenge no
        // longer exists and the inequality has nothing left to compare against.
        //
        // What replaces it is the property that still matters: a reprieve exists, it is reached
        // only by finishing something, and it is bounded. An unbounded or accidentally-zero grace
        // would mean the block either never returns or never lets go.
        assertTrue("the reprieve must be real", BlockLimits.CHALLENGE_GRACE_MS > 0)
        assertTrue(
            "a reprieve longer than an hour is a disabled block, not a reprieve",
            BlockLimits.CHALLENGE_GRACE_MS <= 60 * 60_000L,
        )
    }
}
