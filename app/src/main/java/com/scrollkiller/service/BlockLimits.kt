package com.scrollkiller.service

/**
 * Every number the block screen is tuned by, in one place: how many reels a day is "too many",
 * what the user is allowed to choose, and how long a completed challenge buys.
 *
 * ## Why these are not literals at their call sites
 * The daily limit is read by the overlay, written by Settings, defaulted on [PlatformSpec] and
 * printed on a button. Four call sites for one product number is exactly how a slider ends up
 * offering a range the policy does not honour, or a button promising fifteen minutes while the
 * deadline grants ten. The BUTTON TEXT is formatted from [CHALLENGE_GRACE_MINUTES] rather than
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

    /**
     * How long COMPLETING A CHALLENGE buys, in minutes (D50).
     *
     * ## This is now the ONLY reprieve (D74)
     * There used to be a second one: a free "5 more minutes" button, granting `GRACE_MINUTES`
     * through this same mechanism. It is gone by owner decision — the constants, the button, the
     * string and the handler, not merely the UI — so a reprieve can now be EARNED or not had.
     *
     * The removed pair carried an asserted inequality (challenge grace had to exceed the free tap,
     * or nobody would walk twenty steps for what one tap gave for nothing). That assertion is gone
     * with the thing it constrained; what replaced it is simpler and stronger, because there is no
     * longer a cheaper competing path for this number to be dominated by.
     *
     * **Removing it did not touch invariant 6.** A snooze was never an exit — it kept the user in
     * the app with the block deferred. Exit and Back are what invariant 6 names, both are untouched
     * on all three panels, and neither has ever depended on this constant.
     */
    const val CHALLENGE_GRACE_MINUTES = 15

    /**
     * [CHALLENGE_GRACE_MINUTES] as millis. DERIVED, never written twice: the sentence on the button
     * is formatted from the minutes and the reprieve is granted from the millis, so the promise the
     * user reads and the time they actually get are the same number by construction.
     */
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
     * How long the block window is given to actually attach before the attempt is called a failure.
     *
     * Attachment cannot be read synchronously — `mAttachInfo` is set in
     * `ViewRootImpl.performTraversals()`, a Choreographer frame after `addView` returns — so a
     * deadline is the only honest way to distinguish "still coming" from "never coming". See
     * [BlockScreenController.ShowResult.PENDING_ATTACH].
     *
     * 250ms is roughly fifteen frames at 60Hz: far beyond the one or two a healthy attach needs
     * even on a loaded main thread, and short enough that a genuine refusal is reported while the
     * user is still on the reel that triggered it. It is deliberately NOT tuned to be tight — a
     * false "refused" verdict is the expensive mistake here, and this project has now made it
     * three times.
     */
    const val ATTACH_DEADLINE_MS = 250L

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
