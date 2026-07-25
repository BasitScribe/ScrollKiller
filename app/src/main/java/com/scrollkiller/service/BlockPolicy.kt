package com.scrollkiller.service

/** Which on-surface overlay should be showing right now. */
enum class SurfaceOverlay { BUBBLE, BLOCK }

/**
 * Pure decision for what to show while the user is on a tracked platform's doom
 * surface. No Android imports so it is unit-testable — this is the gate that decides
 * whether the passive bubble escalates to the full-screen block.
 */
object BlockPolicy {

    /**
     * @param count today's count for the platform.
     * @param limit the platform's daily limit.
     * @param gatingActive whether this platform is cleared to block at all. Callers pass
     *   [PlatformSpec.blocksAtLimit], which is true only when the block is switched on for
     *   the platform AND its count is trusted ([Maturity.STABLE]) — so a platform with
     *   unverified surface markers can't cover the feed (D19/D24), and one with a known-wrong
     *   count can't lock the screen on a bad number (D32).
     * @param unlocked whether the user has bypassed the block for this surface visit.
     */
    fun overlayFor(count: Int, limit: Int, gatingActive: Boolean, unlocked: Boolean): SurfaceOverlay =
        if (gatingActive && !unlocked && count >= limit) SurfaceOverlay.BLOCK else SurfaceOverlay.BUBBLE
}
