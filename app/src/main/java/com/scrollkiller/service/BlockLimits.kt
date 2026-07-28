package com.scrollkiller.service

/**
 * Every number the block screen is tuned by, in one place: how many reels a day is "too many",
 * what the user is allowed to choose, and how long "5 more minutes" actually lasts.
 *
 * ## Why these are not literals at their call sites
 * The daily limit is read by the overlay, written by Settings, defaulted on [PlatformSpec] and
 * printed on a button. Four call sites for one product number is exactly how a slider ends up
 * offering a range the policy does not honour, or a button promising five minutes while the
 * timer grants ten. The BUTTON TEXT in particular is formatted from [GRACE_MINUTES] rather than
 * written as copy, so the promise the user reads cannot drift from the reprieve they get.
 *
 * Pure Kotlin, no Android imports, so the clamping is unit-testable off-device.
 */
object BlockLimits {

    /** Reels a day before the block escalates, unless the user says otherwise. */
    const val DEFAULT_DAILY_LIMIT = 100

    /**
     * The lowest limit a user may set. Not zero, and not 1: a limit below a normal *arrival* on
     * the reel surface would block someone before they had scrolled anything, which reads as a
     * broken app rather than a strict one. Twenty is a deliberate choice — small enough to be a
     * real commitment, large enough that the block is always a response to scrolling.
     */
    const val MIN_DAILY_LIMIT = 20

    /** The highest limit a user may set. Past this the block is theatre; the guilt lines carry it. */
    const val MAX_DAILY_LIMIT = 300

    /** Slider granularity. Ten keeps the choice meaningful without pretending 137 differs from 140. */
    const val LIMIT_STEP = 10

    /** How long "5 more minutes" lasts, in minutes. The number the BUTTON is formatted from. */
    const val GRACE_MINUTES = 5

    /** [GRACE_MINUTES] as millis. Derived, so the two can never disagree. */
    const val GRACE_MS = GRACE_MINUTES * 60_000L

    /**
     * How long COMPLETING A CHALLENGE buys, in minutes (D50).
     *
     * ## Why this must be larger than [GRACE_MINUTES], and is asserted to be
     * The free tap and the challenge grant their reprieve through the same mechanism, so if they
     * granted the same amount the challenge would be strictly dominated: nobody walks twenty steps
     * for what one tap gives for nothing, and the feature would ship dead. The gap IS the
     * incentive, and it is stated on the two buttons side by side — "5 more minutes" against
     * "Walk 20 steps — 15 minutes" — so the user is choosing between a small free thing and a
     * larger earned one rather than being nagged into the harder path.
     *
     * `BlockLimitsTest` asserts the inequality, so a later tuning edit cannot quietly reintroduce
     * the dead-on-arrival version.
     */
    const val CHALLENGE_GRACE_MINUTES = 15

    /** [CHALLENGE_GRACE_MINUTES] as millis. Derived, for the same reason as [GRACE_MS]. */
    const val CHALLENGE_GRACE_MS = CHALLENGE_GRACE_MINUTES * 60_000L

    /**
     * How long to wait before trying to draw the block again after its window failed to appear
     * (D52). See [BlockRetryPolicy].
     *
     * Thirty seconds is chosen against the two failure modes it sits between. Shorter and a ROM
     * that refuses `SYSTEM_ALERT_WINDOW` at runtime puts us back into the retry storm this
     * constant exists to end — the block re-inflating a full-screen layout on every reel. Longer
     * and a user who fixes the permission stands in Instagram waiting for the app to notice.
     * At thirty seconds a denial costs one attempt every half-minute, which is invisible, and a
     * recovery is picked up well within the time it takes to switch back to the app.
     */
    const val BLOCK_RETRY_COOLDOWN_MS = 30_000L

    /**
     * The nearest legal limit to [value]: snapped to [LIMIT_STEP] and held inside
     * [MIN_DAILY_LIMIT]..[MAX_DAILY_LIMIT].
     *
     * Applied on the way IN to storage and on the way OUT of it, not just at the slider. A value
     * can reach the preference from a restored backup or from a build whose range differed, and
     * an out-of-range limit is not a cosmetic problem here — it is the number that decides
     * whether someone's screen gets covered.
     *
     * RANGE FIRST, then snap — not the other way round. Snapping adds half a step before
     * dividing, so a garbage `Int.MAX_VALUE` overflowed to negative and came back as the MINIMUM
     * limit: the one wrong answer that covers someone's screen 280 reels early. Coercing first
     * cannot overflow, and snapping afterwards stays in range because both ends are themselves
     * on-step.
     */
    fun clampLimit(value: Int): Int {
        val inRange = value.coerceIn(MIN_DAILY_LIMIT, MAX_DAILY_LIMIT)
        return ((inRange + LIMIT_STEP / 2) / LIMIT_STEP) * LIMIT_STEP
    }
}
