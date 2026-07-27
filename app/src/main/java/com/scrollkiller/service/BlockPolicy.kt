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
     * @param limit the platform's daily limit — the USER's, from
     *   [com.scrollkiller.data.SettingsPrefs.dailyLimit], falling back to the spec's default.
     * @param gatingActive whether this platform is cleared to block at all. Callers pass
     *   [PlatformSpec.blocksAtLimit], which is true only when the block is switched on for
     *   the platform AND its count is trusted ([Maturity.STABLE]) — so a platform with
     *   unverified surface markers can't cover the feed (D19/D24), and one with a known-wrong
     *   count can't lock the screen on a bad number (D32).
     * @param graceUntilMs wall-clock millis until which "5 more minutes" suppresses the block;
     *   0 when there is no reprieve. Passed IN rather than read here so this stays pure and the
     *   persistence decision (D49) lives in one place.
     * @param nowMs wall clock, matching [graceUntilMs].
     */
    fun overlayFor(
        count: Int,
        limit: Int,
        gatingActive: Boolean,
        graceUntilMs: Long,
        nowMs: Long,
    ): SurfaceOverlay =
        if (gatingActive && count >= limit && !inGrace(graceUntilMs, nowMs)) {
            SurfaceOverlay.BLOCK
        } else {
            SurfaceOverlay.BUBBLE
        }

    /**
     * Is a reprieve still running?
     *
     * Strictly BEFORE the deadline, so the instant the grace expires the next emission blocks —
     * "5 more minutes" means five, and the boundary belongs to the block. A deadline in the past
     * (and the 0 that means "never granted") is simply not in grace, so no separate "has a
     * reprieve been set" flag exists to fall out of step with the timestamp.
     */
    fun inGrace(graceUntilMs: Long, nowMs: Long): Boolean = nowMs < graceUntilMs
}
