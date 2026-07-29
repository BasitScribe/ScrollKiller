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
     * @param count today's COMBINED count across every blocking platform (D76) — see
     *   [blockingTotal]. No longer the current platform's own count: the limit is one budget for
     *   the whole doomscrolling day.
     * @param limit the user's single daily limit, from
     *   [com.scrollkiller.data.SettingsPrefs.dailyLimit].
     * @param gatingActive whether the platform the user is CURRENTLY ON is cleared to block at
     *   all. Still per-platform, and deliberately so: [count] answers "has the day's budget run
     *   out", this answers "may we cover *this* screen". Callers pass
     *   [PlatformSpec.blocksAtLimit], true only when the block is switched on for the platform
     *   AND its count may enforce (D32/D73) — so a platform with unverified surface markers can't
     *   cover the feed (D19/D24), and standing in a SHADOW app with the budget spent shows the
     *   bubble, never the block.
     * @param graceUntilMs wall-clock millis until which an EARNED reprieve suppresses the block;
     *   0 when there is none. Since D74 a completed challenge is the only thing that sets it —
     *   the free "5 more minutes" tap wrote to this same deadline and was removed. Passed IN
     *   rather than read here so this stays pure and the persistence decision (D49) lives in one
     *   place.
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
     * Today's count summed across every platform CLEARED TO BLOCK — the number the single daily
     * limit is measured against (D76).
     *
     * ## Why not simply the grand total
     * The bubble shows the grand total across everything tracked (D35), and that is right for a
     * passive counter. It is the wrong input for a limit. TikTok and Snapchat run
     * [GatingMode.SHADOW] on untoured markers, and Snapchat is a KNOWN overcount — it counts
     * Chat/Stories/Map scrolls as "snaps" (D32). Folding a number we have admitted is wrong into
     * the sum that covers someone's screen is exactly the failure D32 and D73 exist to prevent:
     * the user would be blocked out of Instagram because they scrolled their Snapchat inbox.
     *
     * So the same predicate that decides whether a platform may *trigger* the block also decides
     * whether it may *contribute* to it. One rule, both questions — a platform earns its place in
     * the budget by being trustworthy enough to enforce on.
     *
     * Unknown platforms in the map are skipped rather than assumed countable.
     */
    fun blockingTotal(perPlatform: Map<Platform, Int>): Int =
        perPlatform.entries.sumOf { (platform, count) ->
            if (PlatformRegistry.specOrNull(platform)?.blocksAtLimit == true) count else 0
        }

    /**
     * Is a reprieve still running?
     *
     * Strictly BEFORE the deadline, so the instant the grace expires the next emission blocks —
     * fifteen minutes means fifteen, and the boundary belongs to the block. A deadline in the past
     * (and the 0 that means "never granted") is simply not in grace, so no separate "has a
     * reprieve been set" flag exists to fall out of step with the timestamp.
     */
    fun inGrace(graceUntilMs: Long, nowMs: Long): Boolean = nowMs < graceUntilMs
}
