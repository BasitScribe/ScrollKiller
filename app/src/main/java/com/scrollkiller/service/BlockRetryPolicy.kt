package com.scrollkiller.service

/**
 * How soon the block may try to draw itself again after the window failed to materialise.
 *
 * ## Why this is a class and not an `if` in the controller (D52)
 * The bug this exists for was a retry storm: when the window could not be created, the controller
 * left its "already showing" guard un-armed and re-inflated a whole full-screen layout on EVERY
 * count emission — dozens of times a minute, each one a fresh `block_root` for the system to tear
 * down again. The fix is a cooldown, and a cooldown buried as two timestamp comparisons inside a
 * WindowManager class is a thing nobody can test and everybody trusts. Out here it is four lines
 * of pure Kotlin with the retry storm asserted against it.
 *
 * ## Why a cooldown rather than giving up
 * A refusal on these ROMs is not permanent — the user can re-grant, and some ROMs relent by
 * themselves. Never retrying would mean one AppOps denial kills blocking until the process
 * restarts, which is its own silent failure. The cooldown is the middle: retry, but at a rate that
 * costs nothing and cannot churn.
 *
 * Pure Kotlin, no Android imports, no clock of its own — every method takes `nowMs`.
 * NOT thread-safe; main thread only, like the rest of the overlay.
 */
class BlockRetryPolicy(private val cooldownMs: Long = BlockLimits.BLOCK_RETRY_COOLDOWN_MS) {

    /** When the window last failed to appear. 0 = never, so the first attempt is never held back. */
    private var lastFailureAtMs = 0L

    /**
     * May the block attempt to create its window right now?
     *
     * True when nothing has failed yet, or when the cooldown since the last failure has elapsed.
     * The caller must not inflate anything when this is false — the whole point is that a
     * suppressed attempt costs one subtraction, not a layout.
     */
    fun mayAttempt(nowMs: Long): Boolean =
        lastFailureAtMs == 0L || nowMs - lastFailureAtMs >= cooldownMs

    /** The window did not appear (an exception, or the system detached it underneath us). */
    fun recordFailure(nowMs: Long) {
        lastFailureAtMs = nowMs
    }

    /**
     * The window is verifiably up. Clears the cooldown, so a block that recovers is not still
     * being rate-limited by a denial that has since been fixed.
     */
    fun recordSuccess() {
        lastFailureAtMs = 0L
    }

    /** Are we currently inside a cooldown? For the "why did nothing happen" log line. */
    fun isCoolingDown(nowMs: Long): Boolean = !mayAttempt(nowMs)

    /** Millis until the next attempt is allowed, or 0 when one is allowed now. */
    fun remainingMs(nowMs: Long): Long =
        if (mayAttempt(nowMs)) 0L else cooldownMs - (nowMs - lastFailureAtMs)
}
