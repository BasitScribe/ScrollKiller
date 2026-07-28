package com.scrollkiller

import com.scrollkiller.service.BlockLimits
import com.scrollkiller.service.BlockRetryPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cooldown that turns a refused window from a retry storm into a no-op (D52).
 *
 * The bug it exists for: when the block's window could not be created, the controller left its
 * "already showing" guard un-armed and re-inflated a full-screen layout on EVERY count emission.
 * The headline test here is [`a refusal cannot become a rebuild loop`] — it is the assertion that
 * the churn is gone.
 */
class BlockRetryPolicyTest {

    private val t0 = 1_800_000_000_000L
    private val cooldown = BlockLimits.BLOCK_RETRY_COOLDOWN_MS

    @Test
    fun `the first attempt is never held back`() {
        // Nothing has failed, so nothing may delay the very first block of the day.
        assertTrue(BlockRetryPolicy().mayAttempt(t0))
        assertTrue(BlockRetryPolicy().mayAttempt(0L))
    }

    @Test
    fun `a failure suppresses attempts until the cooldown elapses`() {
        val policy = BlockRetryPolicy()
        policy.recordFailure(t0)

        assertFalse("immediately after", policy.mayAttempt(t0))
        assertFalse("one second later", policy.mayAttempt(t0 + 1_000))
        assertFalse("a millisecond short", policy.mayAttempt(t0 + cooldown - 1))
        assertTrue("exactly at the cooldown", policy.mayAttempt(t0 + cooldown))
        assertTrue("well past it", policy.mayAttempt(t0 + cooldown * 3))
    }

    @Test
    fun `a refusal cannot become a rebuild loop`() {
        // THE regression test. Simulate what the capture showed: the count emitting several times
        // a second while the window keeps being refused. Before D52 every one of these inflated a
        // fresh block_root; now all but a handful are suppressed before anything is built.
        val policy = BlockRetryPolicy()
        var attempts = 0
        var now = t0
        // Two minutes of emissions at 4/second — a deliberately brutal rate.
        repeat(480) {
            if (policy.mayAttempt(now)) {
                attempts++
                policy.recordFailure(now)   // it keeps failing; the ROM is still refusing
            }
            now += 250L
        }
        // The first emission, then one per 30s cooldown at t+30s, +60s, +90s. The run ends at
        // t+119.75s, just short of the fifth. FOUR attempts, not four hundred and eighty.
        assertEquals("a refusal must cost one attempt per cooldown, not one per emission", 4, attempts)
        assertTrue("and it must still be trying — a refusal is not permanent", attempts > 1)
    }

    @Test
    fun `success clears the cooldown so a recovery is not rate-limited`() {
        // A ROM that relents, or a user who re-grants: the very next emission must be allowed to
        // put the block up, not sit out the remainder of a cooldown from a problem already fixed.
        val policy = BlockRetryPolicy()
        policy.recordFailure(t0)
        assertFalse(policy.mayAttempt(t0 + 1_000))

        policy.recordSuccess()
        assertTrue("a verified window must clear the cooldown", policy.mayAttempt(t0 + 1_000))
    }

    @Test
    fun `remaining time counts down and hits zero`() {
        val policy = BlockRetryPolicy()
        assertEquals("nothing has failed", 0L, policy.remainingMs(t0))

        policy.recordFailure(t0)
        assertEquals(cooldown, policy.remainingMs(t0))
        assertEquals(cooldown - 10_000, policy.remainingMs(t0 + 10_000))
        assertEquals(0L, policy.remainingMs(t0 + cooldown))
        assertEquals("never negative", 0L, policy.remainingMs(t0 + cooldown * 2))
    }

    @Test
    fun `isCoolingDown is the inverse of mayAttempt`() {
        val policy = BlockRetryPolicy()
        assertFalse(policy.isCoolingDown(t0))
        policy.recordFailure(t0)
        assertTrue(policy.isCoolingDown(t0 + 1))
        assertFalse(policy.isCoolingDown(t0 + cooldown))
    }

    @Test
    fun `the shipped cooldown is long enough to stop churn and short enough to recover`() {
        // Both bounds are product decisions, so both are pinned. Too short and the retry storm
        // comes back; too long and a user who fixes the permission stands there waiting.
        assertTrue("under 5s would still be a storm", BlockLimits.BLOCK_RETRY_COOLDOWN_MS >= 5_000L)
        assertTrue("over 2min is a user waiting", BlockLimits.BLOCK_RETRY_COOLDOWN_MS <= 120_000L)
    }
}
