package com.scrollkiller.service

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView

/**
 * The thin Android half of [BubbleMotion] — it owns no rules, only the `ViewPropertyAnimator`
 * calls that carry them out. One instance per [BubbleView], created and used on the
 * AccessibilityService's main thread only.
 *
 * ## Why this is a class and not four `view.animate()` calls at the call sites
 * Because of [settle]. Every animation started here is registered against the view it touches, and
 * [settle] cancels the lot and snaps every property back to its named rest value. That matters far
 * more in an overlay than in an Activity: nothing recreates this window, so a pill abandoned at
 * alpha 0.4 by a cancelled animator stays at alpha 0.4 until the service dies. Scattered
 * `animate()` calls have no such off switch, and the one place that needs it — the nudge collapsing
 * while its own reveal is still in flight — is the *common* case at a high count, not an edge one.
 *
 * ## What it deliberately cannot do
 * There is no method here that animates a size, a margin, a layout parameter, or anything on the
 * window. Only alpha, scale and translation, which are composited without a relayout. See
 * [BubbleMotion]'s class doc: per-frame layout on a WindowManager window is D30's surface churn
 * arriving sixty times a second instead of once per hysteresis cycle.
 *
 * ## Animations are optional, and the app must be correct without them
 * Every method here is a no-op-safe embellishment applied AFTER the view already holds its final
 * content. A device with animations disabled at the system level, a view detached mid-flight, or a
 * cancelled animator all land on the same place: the correct final state, reached instantly. That
 * ordering — set the truth, then animate towards it — is what keeps motion from ever being able to
 * show the wrong number.
 */
class BubbleAnimator(private val density: Float) {

    /** Interpolator for arrivals: fast then easing in to rest. Shared, stateless, allocated once. */
    private val decelerate = DecelerateInterpolator()

    /** A gentle overshoot for the settle, so [BubbleMotion.Reveal.POP] lands rather than stops. */
    private val overshoot = OvershootInterpolator(OVERSHOOT_TENSION)

    /**
     * Reveal [target] using [reveal] — the guilt line arriving on the pill.
     *
     * Called immediately AFTER the text has been set, never instead of setting it: if the animator
     * is cancelled on its first frame the line is already correct and simply appears without
     * ceremony.
     */
    fun revealLine(target: View, reveal: BubbleMotion.Reveal) {
        target.animate().cancel()
        target.alpha = reveal.fromAlpha
        target.scaleX = reveal.fromScale
        target.scaleY = reveal.fromScale
        target.translationX = dp(reveal.fromTranslationXDp)
        target.translationY = dp(reveal.fromTranslationYDp)
        // Pivot at the leading edge vertically centred, so a scaled reveal grows out of where the
        // mascot is rather than out of the middle of a line whose width changes with every count.
        target.pivotX = 0f
        target.pivotY = target.height / 2f
        target.animate()
            .alpha(BubbleMotion.REST_ALPHA)
            .scaleX(BubbleMotion.REST_SCALE)
            .scaleY(BubbleMotion.REST_SCALE)
            .translationX(BubbleMotion.REST_TRANSLATION)
            .translationY(BubbleMotion.REST_TRANSLATION)
            .setDuration(BubbleMotion.REVEAL_MS)
            .setInterpolator(if (reveal == BubbleMotion.Reveal.POP) overshoot else decelerate)
            .withEndAction { settle(target) }
            .start()
    }

    /**
     * Swap [mascot]'s art to [artRes] through a dip, and invoke nothing — the exchange happens at
     * the trough, inside this method.
     *
     * ## The exchange has to happen at the trough, and that is the whole trick
     * One ImageView cannot cross-fade with itself, and stacking a second one to do it properly
     * would add a view to a window that draws over other apps for a transition that fires **at most
     * twice a day** (the brain state changes at 50 and at 150 — see `BrainState`). So the art is
     * exchanged at the point where it is smallest and faintest, which is the point where nobody can
     * see which drawable is on screen. The result is indistinguishable from a cross-fade and costs
     * no extra view.
     *
     * The dip does not go to zero ([BubbleMotion.MASCOT_DIP_SCALE]): a mascot that disappears
     * completely, inside somebody else's app, reads as the overlay breaking rather than as the
     * character reacting.
     */
    fun swapMascot(mascot: ImageView, artRes: Int) {
        mascot.animate().cancel()
        mascot.pivotX = mascot.width / 2f
        mascot.pivotY = mascot.height / 2f
        mascot.animate()
            .alpha(MASCOT_DIP_ALPHA)
            .scaleX(BubbleMotion.MASCOT_DIP_SCALE)
            .scaleY(BubbleMotion.MASCOT_DIP_SCALE)
            // Tips one way going out and unwinds to level coming back, so the character reads as
            // ONE thing turning rather than as two drawables being exchanged. Rotation rides the
            // same draw-time matrix the scale already uses, so it costs nothing extra.
            .rotation(BubbleMotion.MASCOT_TILT_DEG)
            .setDuration(BubbleMotion.MASCOT_OUT_MS)
            .setInterpolator(decelerate)
            .withEndAction {
                // The swap. Everything either side of this line exists to make this line invisible.
                mascot.setImageResource(artRes)
                mascot.animate()
                    .alpha(BubbleMotion.REST_ALPHA)
                    .scaleX(BubbleMotion.REST_SCALE)
                    .scaleY(BubbleMotion.REST_SCALE)
                    .rotation(BubbleMotion.REST_ROTATION)
                    .setDuration(BubbleMotion.MASCOT_IN_MS)
                    .setInterpolator(overshoot)
                    .withEndAction { settle(mascot) }
                    .start()
            }
            .start()
    }

    /**
     * Travel the pill's background tint from [fromArgb] to [toArgb] across the mascot swap.
     *
     * ## Why this is a `ValueAnimator` and not a `ViewPropertyAnimator`
     * There is no view property for "the tint of my background drawable", so this is the one place
     * that has to drive a value itself. It is still draw-time only — `setTint` on a mutated drawable
     * invalidates, it does not measure or lay out — so the rule the rest of this class lives by is
     * intact. `ArgbEvaluator` is used rather than lerping the channels by hand because it does the
     * per-channel interpolation correctly, and a hand-rolled version of it is the kind of code that
     * looks right and produces a muddy grey through the middle of every transition.
     *
     * ## The failure it fixes
     * The tint used to be a bare assignment while the mascot animated, so the colour changed on one
     * frame and the character arrived a third of a second later. The eye catches the instant colour
     * change, concludes the state has already flipped, and then watches the mascot turn up late —
     * which reads as a glitch rather than as a missing animation. **Animating one part of a
     * composite and not the rest is worse than animating none of it.**
     *
     * Returns the animator so the caller can cancel it: it outlives a `ViewPropertyAnimator` on the
     * same view and is not covered by [settle], which only knows about view properties.
     */
    fun crossfadeTint(target: View, fromArgb: Int, toArgb: Int): ValueAnimator =
        ValueAnimator.ofObject(ArgbEvaluator(), fromArgb, toArgb).apply {
            duration = BubbleMotion.tintCrossfadeMs
            interpolator = decelerate
            addUpdateListener { anim ->
                // mutate() has already been applied by the caller, so tinting this instance cannot
                // reach the shared drawable constant every other bubble would draw from.
                target.background?.setTint(anim.animatedValue as Int)
            }
        }

    /**
     * Fade [panel]'s content in as it opens, or out as it closes.
     *
     * ⚑ **The caller flips visibility; this only animates what is already laid out.** The panel
     * going `VISIBLE` re-measures the window — that is D37's deliberate single resize, and it has
     * happened before the first frame here. Animating the panel's HEIGHT instead would turn that
     * one resize into one per frame, which is precisely the churn D30 forbids. So the box arrives
     * at full size instantly and its contents catch up, which is also why the rise is only a few dp:
     * a large travel would advertise that the box did not move with it.
     *
     * Closing is not animated at all. A collapse that fades out has to keep the window at its
     * expanded size for the duration, so the pill would appear to hang before shrinking — worse
     * than an instant close, and the user has already decided they are done with it.
     */
    fun openPanel(panel: View) {
        panel.animate().cancel()
        panel.alpha = 0f
        panel.translationY = -dp(BubbleMotion.PANEL_RISE_DP)
        panel.animate()
            .alpha(BubbleMotion.REST_ALPHA)
            .translationY(BubbleMotion.REST_TRANSLATION)
            .setDuration(BubbleMotion.PANEL_MS)
            .setInterpolator(decelerate)
            .withEndAction { settle(panel) }
            .start()
    }

    /**
     * A quick press-in on [target], acknowledging a tap.
     *
     * Worth its ninety milliseconds for one specific case: [OverlayController] refuses to expand
     * into an empty breakdown, so early in the day a tap on the pill correctly does nothing at all.
     * Without this, "nothing to show yet" and "the overlay is dead" look identical, and the second
     * is a thing this app has actually been accused of by its own permission bugs.
     *
     * ⚑ **This is the one method that may be pointed at the bubble's ROOT view, and therefore the
     * one that must never touch alpha.** The root's opacity is [OverlayController.setBubbleShown]'s
     * hide-without-churn mechanism (D30) — a hidden bubble sits at alpha 0 with a live surface — so
     * an end action that "restored" alpha to 1 would flash the whole overlay back over somebody's
     * video every time a stale tap resolved. Hence [settleScale] rather than [settle]: it asserts
     * only the two properties this method actually moved.
     */
    fun acknowledgeTap(target: View) {
        target.animate().cancel()
        target.pivotX = target.width / 2f
        target.pivotY = target.height / 2f
        target.animate()
            .scaleX(BubbleMotion.TAP_SCALE)
            .scaleY(BubbleMotion.TAP_SCALE)
            .setDuration(BubbleMotion.TAP_MS)
            .withEndAction {
                target.animate()
                    .scaleX(BubbleMotion.REST_SCALE)
                    .scaleY(BubbleMotion.REST_SCALE)
                    .setDuration(BubbleMotion.TAP_MS)
                    .withEndAction { settleScale(target) }
                    .start()
            }
            .start()
    }

    /**
     * Cancel and restore SCALE only, leaving alpha and translation exactly as they are.
     *
     * The narrow counterpart to [settle], for the one caller that animates a view whose opacity
     * belongs to somebody else. See [acknowledgeTap].
     */
    fun settleScale(view: View) {
        view.animate().cancel()
        view.scaleX = BubbleMotion.REST_SCALE
        view.scaleY = BubbleMotion.REST_SCALE
    }

    /**
     * Cancel anything running on [views] and snap every animated property back to rest.
     *
     * **This is the method the rest of the file exists to make possible.** Call it whenever the
     * bubble's state changes out from under an animation — a nudge collapsing, the surface being
     * left, the block coming up, the bubble being switched off — and on teardown. It is
     * unconditional and idempotent by construction: it does not ask what was animating, it asserts
     * what the values must now be, so there is no in-flight combination it can fail to clean up.
     *
     * `alpha` is deliberately restored to [BubbleMotion.REST_ALPHA] and not to "whatever it was":
     * the one legitimate non-rest alpha on this tree is the ROOT's, which
     * [OverlayController.setBubbleShown] owns for the hide-without-churn trick — and the root is
     * never passed here. Every view this class touches is a child whose only correct opacity is 1.
     */
    fun settle(vararg views: View) {
        for (view in views) {
            view.animate().cancel()
            view.alpha = BubbleMotion.REST_ALPHA
            view.scaleX = BubbleMotion.REST_SCALE
            view.scaleY = BubbleMotion.REST_SCALE
            view.translationX = BubbleMotion.REST_TRANSLATION
            view.translationY = BubbleMotion.REST_TRANSLATION
            view.rotation = BubbleMotion.REST_ROTATION
        }
    }

    private fun dp(value: Float): Float = value * density

    private companion object {
        /** How faint the mascot gets at the trough. Paired with `MASCOT_DIP_SCALE`; not zero, for
         *  the reason given on [BubbleAnimator.swapMascot]. */
        const val MASCOT_DIP_ALPHA = 0.35f

        /** Overshoot strength. Well below the default 2.0: this is a settle, not a bounce, and a
         *  pronounced bounce over somebody's video is the kind of cute that gets an app uninstalled
         *  (D9's rule, applied to motion rather than to copy). */
        const val OVERSHOOT_TENSION = 1.1f
    }
}
