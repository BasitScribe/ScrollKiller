package com.scrollkiller.service

import com.scrollkiller.guilt.GuiltCadence

/**
 * WHAT the bubble's animation does, as pure data. [BubbleAnimator] is the thin Android half that
 * applies it (the same split as `ShakeDetector` beside `AccelShakeSource`, and for the same
 * reason: every rule below is unit-testable off-device, where a phone can only show you that it
 * "feels wrong").
 *
 * ## The one thing that makes animation dangerous HERE
 * This runs inside somebody else's doomscroll, on a window that stays attached for the whole life
 * of the AccessibilityService. Two failure modes follow from that, and they are the reason this
 * file exists rather than a handful of `animate()` calls sprinkled through [OverlayController]:
 *
 * 1. **Motion costs reading time, and reading time is the thing D83 just bought.** D83 raised
 *    [GuiltCadence.DISPLAY_MS] from 4s to 6.5s for exactly one reason: lines were collapsing back
 *    to the count mid-sentence, so an unread line was never really shown and the pack, the tiers
 *    and the whole cadence curve were paying for nothing. A 400ms flourish on the way in is 400ms
 *    of that purchase handed back. So the motion budget is **capped as a fraction of the display
 *    window** and [MOTION_BUDGET_DIVISOR] is enforced by a test — if someone later raises
 *    [REVEAL_MS] to something cinematic, the build fails instead of the feature silently
 *    regressing to the bug D83 fixed.
 *
 * 2. **A leaked animator is a permanently broken bubble.** A pill left at alpha 0.4, or scaled to
 *    0.9 forever, is not a glitch that clears on the next screen — nothing recreates this window.
 *    Hence [BubbleAnimator.settle], and hence the rule that **every property this file touches has
 *    a named rest value** ([REST_ALPHA], [REST_SCALE], [REST_TRANSLATION]) that can be restored
 *    without knowing what was running.
 *
 * ## Only draw-time properties. Never layout, never window params.
 * Alpha, scale and translation are composited by the render thread and change nothing about the
 * view's measured size or the window's. Animating a width, a margin, or anything on
 * `WindowManager.LayoutParams` would relayout on every frame — the surface churn D30 exists to
 * prevent, on a per-frame schedule instead of a per-hysteresis-cycle one. **That is the invariant
 * of this file**: the transforms below are the complete permitted set, and `AnimationSpec` cannot
 * express anything else even if a caller wanted it to.
 */
object BubbleMotion {

    /**
     * How a just-fired guilt line arrives on the pill.
     *
     * Four of them because the user asked for the lines to show up in different ways, and because
     * a nag that always enters identically stops being noticed — which is the same absorption
     * argument `ChallengeEscalation` makes about a static challenge, one surface over. What it is
     * NOT is decoration chosen at random per frame: the style is a deterministic function of the
     * line and the tier ([revealFor]), so the same line at the same intensity always enters the
     * same way and a test can pin it.
     *
     * @param fromAlpha opacity at the start of the reveal. Never 0 for the assertive styles — a
     *   line that begins fully invisible spends the first frames of its budget unreadable.
     * @param fromScale scale at the start. 1 means no scaling at all.
     * @param fromTranslationDp where it starts, in dp, relative to its resting position.
     *   Negative Y is above, positive X is to the trailing side.
     */
    enum class Reveal(
        val fromAlpha: Float,
        val fromScale: Float,
        val fromTranslationXDp: Float,
        val fromTranslationYDp: Float,
    ) {

        /** Straight fade. The quietest arrival, for the quietest tier. */
        FADE(fromAlpha = 0.2f, fromScale = 1f, fromTranslationXDp = 0f, fromTranslationYDp = 0f),

        /** Drifts up into place. Reads as the mascot speaking rather than the app interrupting. */
        RISE(fromAlpha = 0.2f, fromScale = 1f, fromTranslationXDp = 0f, fromTranslationYDp = 6f),

        /** Comes in slightly small and settles. The assertive one — it demands the glance. */
        POP(fromAlpha = 0.4f, fromScale = 0.88f, fromTranslationXDp = 0f, fromTranslationYDp = 0f),

        /** Slides in from the trailing side. The one that most reads as "another one already". */
        SWEEP(fromAlpha = 0.3f, fromScale = 1f, fromTranslationXDp = 10f, fromTranslationYDp = 0f),
    }

    /**
     * Which reveal [seed] gets at intensity [tierLevel] (1–4, `GuiltTier.level`).
     *
     * ## Two inputs, doing two different jobs
     * **The tier picks the PALETTE.** At tier 1 the app has only just started speaking and the
     * motion is calm; by tier 4 it is allowed to be pushy. This is the same escalation shape the
     * content already has, applied to how the content arrives — and it means the motion carries
     * information rather than just being pleasant, because a line that pops in is telling you
     * something about your count before you have read a word of it.
     *
     * **The seed picks WITHIN the palette**, so consecutive lines at the same tier do not all
     * enter the same way. It is the line's own text, hashed — deterministic, so this is a pure
     * function a test can pin, and stable, so a given line always has its own entrance.
     *
     * FNV-1a over the seed for the same reason `GuiltDeck` used it: a few lines of arithmetic with
     * no dependency, good enough spread for choosing between three options, and identical on every
     * device, which `String.hashCode` is contractually guaranteed to be but `Random` is not.
     *
     * @param tierLevel clamped, not validated — a caller with no tier at all (below the first
     *   threshold, where the app is silent) still gets a valid answer rather than an exception on
     *   a render path.
     */
    fun revealFor(seed: String, tierLevel: Int): Reveal {
        val palette = paletteFor(tierLevel)
        if (palette.size == 1) return palette[0]
        var hash = FNV_OFFSET
        for (ch in seed) {
            hash = hash xor (ch.code.toLong() and 0xFF)
            hash *= FNV_PRIME
        }
        // ushr before rem: the multiply overflows into the sign bit constantly, and a negative
        // index is the classic hashCode-based-selection crash. The unsigned shift makes the value
        // non-negative BEFORE the modulo, rather than trusting abs() (which has its own
        // MIN_VALUE hole).
        val index = ((hash ushr 1) % palette.size).toInt()
        return palette[index]
    }

    /**
     * The styles allowed at [tierLevel]. Widening, never replacing: every tier keeps the calmer
     * options and only gains the pushier ones, so the escalation reads as "it can now also do
     * this" rather than as four unrelated behaviours.
     */
    private fun paletteFor(tierLevel: Int): List<Reveal> = when {
        tierLevel <= 1 -> listOf(Reveal.FADE)
        tierLevel == 2 -> listOf(Reveal.FADE, Reveal.RISE)
        tierLevel == 3 -> listOf(Reveal.FADE, Reveal.RISE, Reveal.POP)
        else -> listOf(Reveal.RISE, Reveal.POP, Reveal.SWEEP)
    }

    /**
     * The total time a reveal is allowed to take, in ms — the animation plus the settle.
     *
     * Exposed as a derived value rather than as a second constant so it cannot drift from the two
     * it is the sum of. `BubbleMotionTest` asserts this against [GuiltCadence.DISPLAY_MS]; see
     * [MOTION_BUDGET_DIVISOR].
     */
    val revealBudgetMs: Long get() = REVEAL_MS + SETTLE_MS

    /**
     * How long the mascot's state change takes IN TOTAL — out and back in.
     *
     * The swap is two halves because one ImageView cannot cross-fade with itself: the old art
     * shrinks and fades, the drawable is exchanged at the trough where nothing is legible anyway,
     * and the new art comes back. Splitting it evenly is what makes the exchange invisible.
     */
    val mascotSwapMs: Long get() = MASCOT_OUT_MS + MASCOT_IN_MS

    /**
     * How long the pill's accent colour takes to travel between two [com.scrollkiller.brain.BrainState]
     * accents — mint → orange at 50, orange → red at 150.
     *
     * ## This closes a hole D86 left, and the hole is instructive
     * D86 animated the mascot and left `background.setTint(...)` as a bare assignment, so the pill's
     * colour SNAPPED from mint to orange on one frame while the character was still mid-dip. The
     * result is a transition that looks unfinished in a way that is hard to name if you are just
     * watching it: the eye catches the instant colour change, decides the state has already
     * changed, and then the mascot arrives late. Animating one property of a composite and not the
     * others is worse than animating none of them, because the mismatch reads as a bug rather than
     * as a missing feature.
     *
     * DERIVED from [mascotSwapMs] rather than given its own constant, so the colour and the
     * character are guaranteed to be describing the same event. Two independent durations here
     * would drift the first time either is tuned, and the drift would be exactly the artefact this
     * exists to remove.
     */
    val tintCrossfadeMs: Long get() = mascotSwapMs

    const val FNV_OFFSET = -0x340d631b7bdddcdbL   // 14695981039346656037 as a signed Long
    const val FNV_PRIME = 0x100000001b3L

    /**
     * The reveal's motion, in ms. Deliberately short. A guilt line is not a splash screen, and
     * anything long enough to be admired is long enough to be in the way — see the class doc on
     * why milliseconds here are milliseconds taken from D83.
     */
    const val REVEAL_MS = 180L

    /** The overshoot settling back after [REVEAL_MS]. Part of the budget, not extra. */
    const val SETTLE_MS = 100L

    /**
     * The reveal budget may not exceed `DISPLAY_MS / this`. TWENTY — so at the shipped 6.5s
     * display, motion may spend at most 325ms and currently spends 280.
     *
     * The number is not the point; the TEST is. D83's lesson was that display time is content
     * budget, and the way that lesson gets un-learned is one plausible-looking constant bump at a
     * time. Tying the ceiling to `DISPLAY_MS` also means a future retune of the display carries
     * the motion ceiling with it automatically, exactly as `MIN_GAP_MS` already follows it.
     */
    const val MOTION_BUDGET_DIVISOR = 20

    /**
     * How far the mascot tips as it swaps, in degrees.
     *
     * ## Why a rotation at all, when a dip alone already worked
     * A dip-and-return is a *replacement*: the old art leaves, the new art arrives, and the eye
     * reads two events. Adding a small rotation through the trough makes it read as ONE object
     * turning — the character reacting rather than being exchanged. That difference is most of what
     * separates a transition that looks finished from one that looks like a resource swap, and it
     * is the cheapest possible version of it (a rotation is part of the same draw-time matrix the
     * scale already uses — no extra pass, no extra view, no layout).
     *
     * SMALL, and the number is doing work. The mascot is ~40dp inside somebody else's video: past
     * roughly ten degrees the tilt stops reading as a reaction and starts reading as the overlay
     * being broken or askew, which is the one impression this surface can least afford. It tips one
     * way going out and unwinds to level coming back, so the resting state is always square.
     */
    const val MASCOT_TILT_DEG = 8f

    /** Mascot state change, fading out. Half the swap. */
    const val MASCOT_OUT_MS = 140L

    /** Mascot state change, the new art coming back. The longer half — arrival wants more room
     *  than departure, and this fires at most twice a day (50 and 150). */
    const val MASCOT_IN_MS = 200L

    /** How small the mascot gets at the trough of a state change. Not zero: a mascot that
     *  vanishes completely reads as a glitch in an overlay, where there is no page transition to
     *  explain it. */
    const val MASCOT_DIP_SCALE = 0.72f

    /** The breakdown panel's fade-in when the pill is tapped open. The panel's SIZE is not
     *  animated — that is a window relayout, and one relayout is fine where sixty are not. */
    const val PANEL_MS = 160L

    /** How far the panel's content starts above its resting place, in dp. Small: the window has
     *  already resized to its final height, so this is the content catching up, not the box
     *  growing. */
    const val PANEL_RISE_DP = 8f

    /** The tap acknowledgement on the pill itself — a quick press-in, so a tap that lands on a
     *  bubble with an empty breakdown still feels like it was received. */
    const val TAP_MS = 90L
    const val TAP_SCALE = 0.94f

    /** Rest values. Every animated property must be restorable to one of these without knowing
     *  what was mid-flight — see the class doc's second failure mode. */
    const val REST_ALPHA = 1f
    const val REST_SCALE = 1f
    const val REST_TRANSLATION = 0f

    /** Level. The mascot tilts through a swap and must always come back square — a pill left
     *  permanently at eight degrees is the "is this app broken?" impression, not a flourish. */
    const val REST_ROTATION = 0f
}
