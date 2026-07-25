package com.scrollkiller.brain

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.scrollkiller.R

/**
 * The ONE place mascot art is named. Every surface that draws the mascot — the Compose
 * Home hero, the overlay bubble's compound drawable, the block screen's ImageView —
 * resolves its drawable here, so swapping art is a change to `art/mascot/` plus a re-run
 * of `tools/mascot_import.py`, never a hunt through render code.
 *
 * ## Why this is separate from [BrainState]
 * [BrainState] is the count→state machine and is deliberately free of Android imports so
 * it stays unit-testable (see its doc). Art resolution needs `R`, so it lives here instead
 * of being hung off the enum. The split is the reason there is still exactly one mapping:
 * BrainState owns *which* state you're in, MascotArt owns *what that state looks like*.
 *
 * ## Two size families, and why the bubble has its own
 * [hero] is a 120dp master used by Home and the block screen. [bubble] is a separate 40dp
 * master for the overlay. The bubble must not runtime-downscale a 480px xxxhdpi hero bitmap
 * to 120px — that overlay lives inside someone's doomscroll session and cannot afford the
 * resize (D30's cheap-bubble constraint). Both families are pre-scaled into
 * `drawable-{m,h,xh,xxh,xxxh}dpi`, so the platform picks the exact-size bitmap by density
 * and no scaling happens at draw time.
 *
 * ## The art is NOT square — bound it by HEIGHT (D37)
 * [HERO_DP] and [BUBBLE_DP] are the art's HEIGHT, not the side of a square. The masters are
 * trimmed to one shared crop of the character's own bounding box (so the states cannot differ
 * in apparent scale), and that box is taller than it is wide — currently 33x40dp and 98x120dp.
 * Every consumer therefore fixes the HEIGHT and lets the width follow (`Modifier.height`,
 * `layout_height` + `adjustViewBounds`). Giving one of these to a square box still renders
 * correctly — ImageView and Compose `Image` both default to a fit — it just reserves empty
 * columns either side of the character, which is the exact defect D37 set out to remove.
 *
 * ## [GUARDIAN]
 * The confident "stop" pose, and the one mascot that is NOT count-derived: it means "the
 * block is up", which is a [BlockPolicy] outcome rather than a point on the fried scale.
 * Keeping it out of [BrainState] is what stops `forCount` from ever being able to return it.
 * Hero family only — the bubble has no blocked state.
 */
object MascotArt {

    /** 120dp mascot for the Home hero and the block screen. */
    @DrawableRes
    fun hero(state: BrainState): Int = when (state) {
        BrainState.HEALTHY -> R.drawable.mascot_healthy
        BrainState.CRACKING -> R.drawable.mascot_cracking
        BrainState.FRIED -> R.drawable.mascot_fried
    }

    /** 40dp mascot for the overlay bubble. Pre-scaled; never derived from [hero]. */
    @DrawableRes
    fun bubble(state: BrainState): Int = when (state) {
        BrainState.HEALTHY -> R.drawable.mascot_healthy_bubble
        BrainState.CRACKING -> R.drawable.mascot_cracking_bubble
        BrainState.FRIED -> R.drawable.mascot_fried_bubble
    }

    /** Screen-reader description for the mascot in [state]; it conveys the count's severity. */
    @StringRes
    fun contentDescription(state: BrainState): Int = when (state) {
        BrainState.HEALTHY -> R.string.mascot_cd_healthy
        BrainState.CRACKING -> R.string.mascot_cd_cracking
        BrainState.FRIED -> R.string.mascot_cd_fried
    }

    /** Block-screen-only pose. See the class doc for why it isn't a [BrainState]. */
    @DrawableRes
    val GUARDIAN: Int = R.drawable.mascot_guardian

    /** HEIGHT of the [hero] family's art. Not a square — see the class doc. */
    const val HERO_DP = 120

    /** HEIGHT of the [bubble] family's art; the bubble's ImageView is bounded to this. */
    const val BUBBLE_DP = 40
}
