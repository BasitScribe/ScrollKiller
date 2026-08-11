package com.scrollkiller.service

/**
 * Turns a stream of per-item IDENTITY reads into discrete "advanced to the next item"
 * signals for ONE platform. The [AdvanceStrategy.IDENTITY_CHANGE] counterpart to
 * [SwipeDetector], and pure/Android-free for the same reason: this is accuracy-critical
 * logic that must be provable off-device.
 *
 * ## Why identity and not events (D34)
 * YouTube Shorts reports `scrollDeltaY=0` on every scroll — there is no delta to debounce, so
 * [SwipeDetector] can never fire. The real per-Short signal lives on
 * `TYPE_WINDOW_CONTENT_CHANGED`, but that fires **40+ times per Short** (subtitle ticks, like
 * counts re-rendering, the "Auto-dubbed" badge). Counting events would overcount ~40×.
 * What DOES change exactly once per advance is the item's identity — the channel handle
 * (`@SagarsKitchen` → `@PakWheels` → `@DSMotoTube`), extracted by [ReelIdentity]. So we count
 * the CHANGE, and the 40 repeats collapse to nothing.
 *
 * ## The three rules that are easy to get wrong
 * All fail toward UNDERcount, which is the direction we've chosen everywhere (D24/D27):
 *
 *  1. A `null` identity (unreadable frame) NEVER clears [lastIdentity]. If it did, a single
 *     momentarily-unreadable frame would make the very next read of the SAME Short look like
 *     a new one — turning idle playback into a count, which is the exact bug being prevented.
 *  2. A rejection by the [minAdvanceIntervalMs] floor ALSO doesn't store the identity. The
 *     new item is therefore still "unseen", and one of the ~40 content-changes that follow
 *     within the next second counts it once the floor has passed. Storing it on rejection
 *     would silently drop genuinely fast swipes.
 *  3. "Not provably different" is the test, NOT "equal" — see [ItemIdentity.differsFrom]. Only
 *     fields present on both sides are compared, so a title that renders a frame after the
 *     handle cannot look like an advance.
 *
 * ## ⚑ What this used to get wrong, and it was not an edge case
 * The identity was a single string, in practice always the channel handle. So two consecutive
 * Shorts by the SAME creator were `UNCHANGED` and the second never counted — and on a channel's
 * own Shorts tab, where every item shares one handle, an entire session counted **one**. The
 * `15 swipes count 15` acceptance test passed throughout, because it feeds fifteen *different*
 * handles; the repeat case was never written. Reported from real use, 2026-08-11.
 *
 * That is also why [ItemIdentity.mergedWith] is called on the UNCHANGED path rather than nothing
 * happening there: staying on one Short is when we learn the fields its first frame did not
 * carry, and it is the only chance to learn them before the next item needs something to differ
 * from.
 *
 * @param minAdvanceIntervalMs floor between two COUNTED advances (see
 *   [PlatformSpec.minAdvanceIntervalMs], whose meaning follows the strategy).
 */
class IdentityAdvanceDetector(private val minAdvanceIntervalMs: Long) {

    /**
     * What one identity read meant. An enum rather than a Boolean so the DEBUG transcript can
     * report WHICH rejection happened — during an acceptance run, "the identity never changed"
     * and "it changed but hit the floor" call for completely different fixes.
     */
    enum class Advance {
        /** Identity differed from the last counted one: a real advance. Record it. */
        COUNTED,

        /** Nothing provably per-item in the tree; treated as no information at all. */
        UNREADABLE,

        /** Same identity as the last counted one — the item is just re-rendering. */
        UNCHANGED,

        /** Identity changed, but inside [minAdvanceIntervalMs] of the last count. */
        FLOORED,
    }

    /**
     * Identity of the last item we COUNTED, enriched by every same-item read since. Null until
     * the first successful read.
     */
    private var lastIdentity: ItemIdentity? = null

    /** When we last counted, for the floor. UNSET before any count. */
    private var lastCountAtMs = UNSET

    /**
     * Feed one identity read.
     *
     * The first non-null identity of a session always counts — that is the item the user
     * LANDED on. This is why [AdvanceStrategy.IDENTITY_CHANGE] platforms must NOT also take
     * D29's separate landing-item entry credit: it is already covered here, and taking both
     * would count the landing item twice.
     *
     * @param identity the item's identity, or null when this frame yielded nothing usable.
     * @param atMs event time in millis (monotonic within a session is enough).
     */
    fun onIdentity(identity: ItemIdentity?, atMs: Long): Advance {
        if (identity == null || identity.isEmpty) return Advance.UNREADABLE   // keep what we know

        val last = lastIdentity
        if (last != null && !last.differsFrom(identity)) {
            // Same item as far as we can prove. Absorb anything this frame knew that the stored
            // identity did not — a title that rendered late is the case that matters, and without
            // this the stored identity stays handle-only and the next Short by the same creator
            // is once again indistinguishable.
            lastIdentity = last.mergedWith(identity)
            return Advance.UNCHANGED
        }

        // Floor. Deliberately checked AFTER the change test and WITHOUT storing, so the pending
        // new identity is retried on the next read rather than being swallowed.
        if (lastCountAtMs != UNSET && atMs - lastCountAtMs < minAdvanceIntervalMs) {
            return Advance.FLOORED
        }

        lastIdentity = identity
        lastCountAtMs = atMs
        return Advance.COUNTED
    }

    /**
     * Forget the session. Called when the user leaves the tracked app, so that re-entering
     * counts the newly-landed item again (the D29 behaviour, expressed through identity).
     */
    fun reset() {
        lastIdentity = null
        lastCountAtMs = UNSET
    }

    private companion object {
        /** Sentinel meaning "nothing counted yet"; avoids Long overflow on the first diff. */
        const val UNSET = Long.MIN_VALUE
    }
}
