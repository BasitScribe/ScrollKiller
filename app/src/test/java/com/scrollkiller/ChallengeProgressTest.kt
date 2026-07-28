package com.scrollkiller

import com.scrollkiller.challenge.ChallengeProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The challenge tracker. Small class, but it holds the two sensor edges that decide whether the
 * feature works on a real phone — a cumulative counter that starts in the thousands, and one that
 * resets to zero underneath you on reboot.
 */
class ChallengeProgressTest {

    private val target = 20

    /* --- step events (TYPE_STEP_DETECTOR) --------------------------------------------- */

    @Test
    fun `increments count up to the target`() {
        val progress = ChallengeProgress(target)
        assertEquals(0, progress.progress)
        assertFalse(progress.isComplete)

        repeat(19) { progress.onIncrement() }
        assertEquals(19, progress.progress)
        assertFalse("nineteen steps is not twenty", progress.isComplete)

        progress.onIncrement()
        assertEquals(20, progress.progress)
        assertTrue(progress.isComplete)
    }

    @Test
    fun `progress never exceeds the target`() {
        // Step events CAN arrive in a burst — a batched sensor flushes several at once — and a
        // ring drawing 140% of a circle is a bug the user sees.
        val progress = ChallengeProgress(target)
        repeat(200) { progress.onIncrement() }
        assertEquals(target, progress.progress)
        assertEquals(1f, progress.fraction, 0.0001f)
    }

    @Test
    fun `completion is sticky`() {
        val progress = ChallengeProgress(target)
        repeat(target) { progress.onIncrement() }
        assertTrue(progress.isComplete)
        // A late event after the block has already come down must not un-complete it.
        progress.onIncrement()
        assertTrue(progress.isComplete)
    }

    /* --- cumulative (TYPE_STEP_COUNTER) ----------------------------------------------- */

    @Test
    fun `the first cumulative reading is a baseline, not a score`() {
        // THE edge that makes this class necessary. TYPE_STEP_COUNTER reports steps since BOOT,
        // shared with every other app on the device — so the first reading of a challenge is a
        // number in the thousands with nothing to do with this challenge. Treating it as progress
        // would complete every challenge instantly.
        val progress = ChallengeProgress(target)
        progress.onCumulative(8_432L)
        assertEquals(0, progress.progress)
        assertFalse(progress.isComplete)
    }

    @Test
    fun `cumulative progress is the delta from the baseline`() {
        val progress = ChallengeProgress(target)
        progress.onCumulative(8_432L)
        progress.onCumulative(8_437L)
        assertEquals(5, progress.progress)
        progress.onCumulative(8_452L)
        assertEquals(target, progress.progress)
        assertTrue(progress.isComplete)
    }

    @Test
    fun `a reboot mid-challenge re-baselines instead of going negative`() {
        // TYPE_STEP_COUNTER resets on reboot, so a device restarted mid-challenge sends a total
        // SMALLER than the baseline. The naive subtraction gives negative progress and a ring
        // drawn backwards. Going backwards means the counter restarted, not that the user
        // un-walked — the same reasoning GuiltFiring applies to a count that drops within a day.
        val progress = ChallengeProgress(target)
        progress.onCumulative(8_432L)
        progress.onCumulative(8_440L)
        assertEquals(8, progress.progress)

        progress.onCumulative(3L)          // rebooted; counter restarted
        assertEquals("a reboot must not read as negative progress", 8, progress.progress)

        progress.onCumulative(9L)          // six steps since the restart
        assertEquals(6, progress.progress)
        assertTrue(progress.progress >= 0)
    }

    @Test
    fun `cumulative progress never exceeds the target either`() {
        val progress = ChallengeProgress(target)
        progress.onCumulative(1_000L)
        progress.onCumulative(9_000L)      // an enormous jump; a long walk, or a stale flush
        assertEquals(target, progress.progress)
    }

    /* --- holds (ORIENTATION_HOLD / PROXIMITY_HOLD) ------------------------------------ */

    @Test
    fun `hold seconds are absolute and clamped, like a cumulative reading`() {
        val progress = ChallengeProgress(30)
        progress.onHoldElapsed(0)
        assertEquals(0, progress.progress)
        progress.onHoldElapsed(12)
        assertEquals(12, progress.progress)
        progress.onHoldElapsed(30)
        assertTrue(progress.isComplete)
        progress.onHoldElapsed(45)          // held past the target
        assertEquals(30, progress.progress)
    }

    @Test
    fun `a broken hold sends progress back to zero`() {
        // THE anti-cheat. A pause would make a 30-second hold satisfiable as six five-second flips
        // with a peek at Instagram between each. HoldDetector reports 0 on a break and the reset
        // falls out of the absolute semantics — no separate signal needed.
        val progress = ChallengeProgress(30)
        progress.onHoldElapsed(25)
        assertEquals(25, progress.progress)
        progress.onHoldElapsed(0)
        assertEquals("a break must not bank the 25", 0, progress.progress)
    }

    @Test
    fun `completion is NOT sticky for holds, and that is deliberate`() {
        // The counting inputs are monotonic so completion sticks; onHoldElapsed breaks that on
        // purpose. Nothing downstream cares — ChallengeController latches onComplete in its own flag,
        // so the reprieve is granted exactly once and the block is already down by the time this
        // flips back. Pinned so nobody "fixes" the non-monotonicity by clamping isComplete.
        val progress = ChallengeProgress(30)
        progress.onHoldElapsed(30)
        assertTrue(progress.isComplete)
        progress.onHoldElapsed(0)
        assertFalse("a hold that breaks after completing does go back to incomplete", progress.isComplete)
    }

    @Test
    fun `a negative hold reading cannot draw the ring backwards`() {
        // Only reachable from a clock that went backwards, but fraction feeds a view drawn over
        // another app and a negative sweep is the worst place to discover one.
        val progress = ChallengeProgress(30)
        progress.onHoldElapsed(-5)
        assertEquals(0, progress.progress)
        assertEquals(0f, progress.fraction, 0.0001f)
    }

    @Test
    fun `reset clears hold progress too`() {
        val progress = ChallengeProgress(30)
        progress.onHoldElapsed(18)
        progress.reset()
        assertEquals(0, progress.progress)
        assertFalse(progress.isComplete)
    }

    /* --- reset ------------------------------------------------------------------------ */

    @Test
    fun `reset clears progress AND the baseline`() {
        // Both halves matter. Clearing progress but keeping the baseline would make the next
        // attempt's first reading score the steps taken during the abandoned one.
        val progress = ChallengeProgress(target)
        progress.onCumulative(5_000L)
        progress.onCumulative(5_010L)
        assertEquals(10, progress.progress)

        progress.reset()
        assertEquals(0, progress.progress)
        assertFalse(progress.isComplete)

        progress.onCumulative(5_010L)      // first reading of the NEW attempt
        assertEquals("the new attempt must start from zero", 0, progress.progress)
    }

    @Test
    fun `a cancelled attempt banks nothing`() {
        // A challenge you can chip away at across five separate blocks — two steps here, three
        // there — is a slow tap, not a challenge.
        val progress = ChallengeProgress(target)
        repeat(10) { progress.onIncrement() }
        progress.reset()
        assertEquals(0, progress.progress)
    }

    /* --- the ring's input ------------------------------------------------------------- */

    @Test
    fun `fraction tracks progress and stays inside zero to one`() {
        val progress = ChallengeProgress(target)
        assertEquals(0f, progress.fraction, 0.0001f)
        repeat(10) { progress.onIncrement() }
        assertEquals(0.5f, progress.fraction, 0.0001f)
        repeat(50) { progress.onIncrement() }
        assertEquals(1f, progress.fraction, 0.0001f)
    }

    @Test
    fun `a zero target cannot divide by zero`() {
        // Not reachable through the registry (a test asserts every spec's target is positive), but
        // fraction feeds a view and a crash in an overlay drawn over another app is the worst
        // place in this codebase to discover a division.
        val progress = ChallengeProgress(0)
        assertEquals(1f, progress.fraction, 0.0001f)
        assertTrue(progress.isComplete)
    }
}
